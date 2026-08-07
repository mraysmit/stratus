#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-07-29
source "$(dirname "$0")/../lib/ceph-compose-common.sh"

# Rotates the two RGW S3 key pairs, Dashboard password, disposable CA, and
# endpoint certificate without deleting buckets, objects, or Ceph volumes.
# New RGW keys overlap with the old keys until all consumers pass live checks;
# only then are the old keys revoked and proved unusable.

usage() {
  cat <<'EOF'
Usage: ceph-compose-rotate-secrets.sh [--preflight | --force | --repair-keys]

  --preflight    Validate that the live cluster is ready for rotation; change nothing.
  --force        Rotate without the interactive "rotate" confirmation.
  --repair-keys  Reconcile RGW with .env after an interrupted or rolled-back
                 rotation: reattach each .env key pair to its identity and remove
                 any other key left on those identities. Rotates nothing else.

With no option the script displays the impact and requires the word "rotate".
The rotation preserves all Ceph data but replaces the local CA, so browsers and
desktop clients must remove the old "Stratus Disposable Compose CA" trust entry
and import certs/stratus-ca.crt after completion.

If a rotation is killed outright, the lock directory it holds is reclaimed
automatically on the next run once its owning process is confirmed gone.
EOF
}

mode=interactive
case "${1:-}" in
  "") ;;
  --preflight) mode=preflight ;;
  --force) mode=force ;;
  --repair-keys) mode=repair-keys ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; fail "Unknown argument: $1" ;;
esac
[[ "$#" -le 1 ]] || { usage >&2; fail "Only one option may be supplied"; }

load_environment
: "${CEPH_DEMO_UID:?CEPH_DEMO_UID is required}"
: "${CEPH_DENIED_UID:?CEPH_DENIED_UID is required}"
: "${CEPH_DENIED_ACCESS_KEY:?CEPH_DENIED_ACCESS_KEY is required}"
: "${CEPH_DENIED_SECRET_KEY:?CEPH_DENIED_SECRET_KEY is required}"
: "${CEPH_DASHBOARD_USER:?CEPH_DASHBOARD_USER is required}"
: "${CEPH_DASHBOARD_PASSWORD:?CEPH_DASHBOARD_PASSWORD is required}"
: "${CEPH_RGW_PROBE_BUCKET:?CEPH_RGW_PROBE_BUCKET is required}"
: "${CEPH_RGW_DENIED_BUCKET:?CEPH_RGW_DENIED_BUCKET is required}"

require_single_env_entry() {
  local name="$1" count
  count="$(grep -Ec "^${name}=" "$HARNESS_DIR/.env" || true)"
  [[ "$count" -eq 1 ]] \
    || fail "$HARNESS_DIR/.env must contain exactly one $name entry"
}
for required_entry in CEPH_RGW_ACCESS_KEY CEPH_RGW_SECRET_KEY \
    CEPH_DENIED_ACCESS_KEY CEPH_DENIED_SECRET_KEY CEPH_DASHBOARD_PASSWORD; do
  require_single_env_entry "$required_entry"
done

require_running_service() {
  local service="$1"
  compose ps --services --status running | grep -Fxq "$service" \
    || fail "Service '$service' must be running before secret rotation"
}

rgw_key_exists() {
  local uid="$1" access_key="$2"
  {
    printf '%s\n' "$uid"
    printf '%s\n' "$access_key"
  } | compose exec -T mon1 bash -c '
    set -euo pipefail
    IFS= read -r uid
    IFS= read -r access_key
    radosgw-admin user info --uid "$uid" --format json |
      jq -e --arg access_key "$access_key" \
        ".keys | any(.access_key == \$access_key)" >/dev/null
  '
}

# Every s3-type access key currently attached to an identity, one per line.
rgw_access_keys() {
  local uid="$1"
  printf '%s\n' "$uid" | compose exec -T mon1 bash -c '
    set -euo pipefail
    IFS= read -r uid
    radosgw-admin user info --uid "$uid" --format json |
      jq -r ".keys[].access_key"
  ' | tr -d '\r'
}

set_rgw_key() {
  local operation="$1" uid="$2" access_key="$3" secret_key="${4:-}"
  {
    printf '%s\n' "$uid"
    printf '%s\n' "$access_key"
    printf '%s\n' "$secret_key"
  } | compose exec -T mon1 bash -c '
    set -euo pipefail
    IFS= read -r uid
    IFS= read -r access_key
    IFS= read -r secret_key
    case "$1" in
      create)
        radosgw-admin key create --uid "$uid" --key-type s3 \
          --access-key "$access_key" --secret-key "$secret_key" >/dev/null
        ;;
      remove)
        radosgw-admin key rm --uid "$uid" --key-type s3 \
          --access-key "$access_key" >/dev/null
        ;;
      *) exit 64 ;;
    esac
  ' _ "$operation"
}

# Makes RGW agree with .env for one identity: attaches the .env key pair if it
# is missing, then removes every other key on that identity. A key left behind
# by a rolled-back rotation is an un-revoked credential, so removing it is the
# point of the repair, not a side effect.
repair_identity_keys() {
  local uid="$1" access_key="$2" secret_key="$3" existing changed=false
  if rgw_key_exists "$uid" "$access_key"; then
    log "REPAIR: the .env key for $uid is already attached"
  else
    log "REPAIR: attaching the .env key to $uid"
    set_rgw_key create "$uid" "$access_key" "$secret_key"
    rgw_key_exists "$uid" "$access_key" \
      || fail "Failed to attach the .env key to $uid"
    changed=true
  fi
  while IFS= read -r existing; do
    [[ -n "$existing" && "$existing" != "$access_key" ]] || continue
    log "REPAIR: removing key '$existing' from $uid — not the key held in .env"
    set_rgw_key remove "$uid" "$existing"
    changed=true
  done < <(rgw_access_keys "$uid")
  [[ "$changed" == true ]] || log "REPAIR: $uid already matched .env; nothing changed"
}

repair_keys() {
  # Repair writes these .env values into RGW, so a blank one would attach an
  # empty credential rather than fail. The rotation path never reads them
  # before generating replacements, which is why the guard lives here.
  : "${CEPH_RGW_ACCESS_KEY:?CEPH_RGW_ACCESS_KEY must be set in .env to repair keys}"
  : "${CEPH_RGW_SECRET_KEY:?CEPH_RGW_SECRET_KEY must be set in .env to repair keys}"
  log "Reconciling RGW keys with $HARNESS_DIR/.env; no credential is rotated"
  repair_identity_keys "$CEPH_DEMO_UID" "$CEPH_RGW_ACCESS_KEY" "$CEPH_RGW_SECRET_KEY"
  repair_identity_keys "$CEPH_DENIED_UID" "$CEPH_DENIED_ACCESS_KEY" "$CEPH_DENIED_SECRET_KEY"
  log "REPAIR PASS: RGW now holds exactly the two key pairs recorded in .env"
}

preflight_cluster() {
  local service
  for service in mon1 rgw1 rgw2 rgw-proxy s3client; do
    require_running_service "$service"
  done
  compose exec -T mon1 ceph health --format json |
    grep -Eq '"status"[[:space:]]*:[[:space:]]*"HEALTH_OK"' \
    || fail "Ceph must report HEALTH_OK before secret rotation"
}

preflight() {
  preflight_cluster
  rgw_key_exists "$CEPH_DEMO_UID" "$CEPH_RGW_ACCESS_KEY" \
    || fail "The primary access key in .env is not attached to $CEPH_DEMO_UID; run --repair-keys to reconcile RGW with .env"
  rgw_key_exists "$CEPH_DENIED_UID" "$CEPH_DENIED_ACCESS_KEY" \
    || fail "The denied-owner access key in .env is not attached to $CEPH_DENIED_UID; run --repair-keys to reconcile RGW with .env"
  compose exec -T mon1 ceph dashboard ac-user-show "$CEPH_DASHBOARD_USER" >/dev/null \
    || fail "Dashboard user '$CEPH_DASHBOARD_USER' does not exist"
  compose exec -T rgw-proxy nginx -t >/dev/null
  log "PREFLIGHT PASS: cluster health, RGW identities, Dashboard user, and TLS proxy are ready"
}

if [[ "$mode" == repair-keys ]]; then
  preflight_cluster
  repair_keys
  exit 0
fi

preflight
[[ "$mode" != preflight ]] || exit 0

if [[ "$mode" == interactive ]]; then
  cat <<'EOF'
This preserves Ceph data but rotates every local client credential and replaces
the disposable TLS CA. Existing browser/Postman trust and saved S3 credentials
will stop working. Type rotate to continue.
EOF
  read -r -p '> ' answer
  [[ "$answer" == rotate ]] || fail "Secret rotation cancelled"
fi

old_primary_access="$CEPH_RGW_ACCESS_KEY"
old_primary_secret="$CEPH_RGW_SECRET_KEY"
old_denied_access="$CEPH_DENIED_ACCESS_KEY"
old_denied_secret="$CEPH_DENIED_SECRET_KEY"
old_dashboard_password="$CEPH_DASHBOARD_PASSWORD"

rand_hex() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }
new_primary_access="stratus-local-$(rand_hex 6)"
new_primary_secret="$(rand_hex 20)"
new_denied_access="stratus-denied-$(rand_hex 6)"
new_denied_secret="$(rand_hex 20)"
new_dashboard_password="$(rand_hex 20)"

rotation_root="$HARNESS_DIR/.rotation"
mkdir -p "$rotation_root"
chmod 0700 "$rotation_root"
harden_windows_acl "$rotation_root"
lock_dir="$rotation_root/rotation.lock"
stage="$(mktemp -d "$rotation_root/rotate.XXXXXX")"
stage_relative="${stage#"$HARNESS_DIR/"}"
new_root="$stage/new"
old_root="$stage/old"
mkdir -p "$new_root" "$old_root"
chmod 0700 "$stage" "$new_root" "$old_root"
harden_windows_acl "$stage"

new_primary_added=false
new_denied_added=false
dashboard_changed=false
files_swapped=false
revocation_started=false
rotation_complete=false
lock_acquired=false

set_dashboard_password() {
  local password="$1"
  printf '%s' "$password" | compose exec -T mon1 bash -c '
    set -euo pipefail
    password_file=$(mktemp)
    trap "rm -f \"$password_file\"" EXIT
    cat >"$password_file"
    chmod 0600 "$password_file"
    ceph dashboard ac-user-set-password "$1" -i "$password_file" >/dev/null
  ' _ "$CEPH_DASHBOARD_USER"
}

write_rotated_environment() {
  local line
  while IFS= read -r line || [[ -n "$line" ]]; do
    case "$line" in
      CEPH_RGW_ACCESS_KEY=*) printf 'CEPH_RGW_ACCESS_KEY=%s\n' "$new_primary_access" ;;
      CEPH_RGW_SECRET_KEY=*) printf 'CEPH_RGW_SECRET_KEY=%s\n' "$new_primary_secret" ;;
      CEPH_DENIED_ACCESS_KEY=*) printf 'CEPH_DENIED_ACCESS_KEY=%s\n' "$new_denied_access" ;;
      CEPH_DENIED_SECRET_KEY=*) printf 'CEPH_DENIED_SECRET_KEY=%s\n' "$new_denied_secret" ;;
      CEPH_DASHBOARD_PASSWORD=*) printf 'CEPH_DASHBOARD_PASSWORD=%s\n' "$new_dashboard_password" ;;
      *) printf '%s\n' "$line" ;;
    esac
  done <"$HARNESS_DIR/.env" >"$new_root/.env"
  chmod 0600 "$new_root/.env"
  harden_windows_acl "$new_root/.env"
}

swap_live_files() {
  [[ -f "$new_root/.env" ]] || fail "Staged environment file is missing"
  [[ -d "$new_root/certs" ]] || fail "Staged certificate directory is missing"
  [[ -d "$new_root/private" ]] || fail "Staged private-key directory is missing"
  # Mark the swap before its first move. restore_live_files handles a partial
  # swap, so any failure from this point is safely recoverable.
  files_swapped=true
  mv "$HARNESS_DIR/.env" "$old_root/.env"
  mv "$HARNESS_DIR/certs" "$old_root/certs"
  mv "$HARNESS_DIR/private" "$old_root/private"
  mv "$new_root/.env" "$HARNESS_DIR/.env"
  mv "$new_root/certs" "$HARNESS_DIR/certs"
  mv "$new_root/private" "$HARNESS_DIR/private"
  # Values sourced from the old .env are exported. Compose gives those values
  # precedence over --env-file, so switch the process environment at cutover.
  export CEPH_RGW_ACCESS_KEY="$new_primary_access"
  export CEPH_RGW_SECRET_KEY="$new_primary_secret"
  export CEPH_DENIED_ACCESS_KEY="$new_denied_access"
  export CEPH_DENIED_SECRET_KEY="$new_denied_secret"
  export CEPH_DASHBOARD_PASSWORD="$new_dashboard_password"
  harden_windows_acl "$HARNESS_DIR/.env" "$HARNESS_DIR/private"
}

restore_live_files() {
  [[ "$files_swapped" == true ]] || return 0
  if [[ -f "$old_root/.env" ]]; then
    rm -f "$HARNESS_DIR/.env"
    mv "$old_root/.env" "$HARNESS_DIR/.env"
  fi
  if [[ -d "$old_root/certs" ]]; then
    rm -rf "$HARNESS_DIR/certs"
    mv "$old_root/certs" "$HARNESS_DIR/certs"
  fi
  if [[ -d "$old_root/private" ]]; then
    rm -rf "$HARNESS_DIR/private"
    mv "$old_root/private" "$HARNESS_DIR/private"
  fi
  export CEPH_RGW_ACCESS_KEY="$old_primary_access"
  export CEPH_RGW_SECRET_KEY="$old_primary_secret"
  export CEPH_DENIED_ACCESS_KEY="$old_denied_access"
  export CEPH_DENIED_SECRET_KEY="$old_denied_secret"
  export CEPH_DASHBOARD_PASSWORD="$old_dashboard_password"
  harden_windows_acl "$HARNESS_DIR/.env" "$HARNESS_DIR/private"
  files_swapped=false
}

recreate_consumers() {
  compose up --detach --no-deps --force-recreate --wait rgw-proxy s3client
}

verify_denied_owner_key() {
  compose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt \
    lsf "deniedowner:${CEPH_RGW_DENIED_BUCKET}/" >/dev/null
}

assert_removed_key_rejected() {
  local access_key="$1" secret_key="$2" bucket="$3"
  export RCLONE_CONFIG_ROTATIONOLD_TYPE=s3
  export RCLONE_CONFIG_ROTATIONOLD_PROVIDER=Ceph
  export RCLONE_CONFIG_ROTATIONOLD_ACCESS_KEY_ID="$access_key"
  export RCLONE_CONFIG_ROTATIONOLD_SECRET_ACCESS_KEY="$secret_key"
  export RCLONE_CONFIG_ROTATIONOLD_ENDPOINT="$CEPH_RGW_ENDPOINT"
  export RCLONE_CONFIG_ROTATIONOLD_FORCE_PATH_STYLE="${S3_PATH_STYLE_ACCESS:-true}"
  set +e
  compose run --rm --no-deps -T --entrypoint rclone \
    -e RCLONE_CONFIG_ROTATIONOLD_TYPE \
    -e RCLONE_CONFIG_ROTATIONOLD_PROVIDER \
    -e RCLONE_CONFIG_ROTATIONOLD_ACCESS_KEY_ID \
    -e RCLONE_CONFIG_ROTATIONOLD_SECRET_ACCESS_KEY \
    -e RCLONE_CONFIG_ROTATIONOLD_ENDPOINT \
    -e RCLONE_CONFIG_ROTATIONOLD_FORCE_PATH_STYLE \
    s3client --ca-cert /certs/stratus-ca.crt lsf "rotationold:${bucket}/" \
    >"$stage/old-key-check.log" 2>&1
  local status=$?
  set -e
  unset RCLONE_CONFIG_ROTATIONOLD_TYPE RCLONE_CONFIG_ROTATIONOLD_PROVIDER \
    RCLONE_CONFIG_ROTATIONOLD_ACCESS_KEY_ID \
    RCLONE_CONFIG_ROTATIONOLD_SECRET_ACCESS_KEY \
    RCLONE_CONFIG_ROTATIONOLD_ENDPOINT \
    RCLONE_CONFIG_ROTATIONOLD_FORCE_PATH_STYLE
  [[ "$status" -ne 0 ]] || return 1
  grep -Eqi 'AccessDenied|InvalidAccessKeyId|InvalidAccessKey|SignatureDoesNotMatch|status code: 403|HTTP (status )?403' \
    "$stage/old-key-check.log"
}

assert_old_dashboard_password_rejected() {
  {
    printf '%s\n%s\n' "$CEPH_DASHBOARD_USER" "$old_dashboard_password"
    cat "$HARNESS_DIR/certs/stratus-ca.crt"
  } | compose exec -T mon1 bash -c '
    set -euo pipefail
    IFS= read -r user
    IFS= read -r pass
    work=$(mktemp -d)
    trap "rm -rf \"$work\"" EXIT
    cat >"$work/ca.crt"
    jq -n --arg u "$user" --arg p "$pass" \
      "{username: \$u, password: \$p}" >"$work/auth.json"
    code=$(curl -sS --cacert "$work/ca.crt" -o "$work/response.json" \
      -w "%{http_code}" -H "Accept: application/vnd.ceph.api.v1.0+json" \
      -H "Content-Type: application/json" -X POST --data @"$work/auth.json" \
      https://object-store.stratus.local:8444/api/auth || printf 000)
    token=$(jq -r ".token // empty" "$work/response.json" 2>/dev/null || true)
    case "$code" in
      400|401|403) [ -z "$token" ] ;;
      *) exit 1 ;;
    esac
  '
}

assert_old_ca_rejected() {
  cat "$old_root/certs/stratus-ca.crt" | compose exec -T mon1 bash -c '
    set -euo pipefail
    ca=$(mktemp)
    trap "rm -f \"$ca\"" EXIT
    cat >"$ca"
    # Any HTTP response proves that TLS trusted the old CA. Deliberately omit
    # curl -f so an application-level 4xx/5xx cannot masquerade as TLS failure.
    if curl -sS --cacert "$ca" \
        https://object-store.stratus.local:8443/ >/dev/null 2>&1; then
      exit 1
    else
      status=$?
      # curl 60 is specifically peer-certificate verification failure.
      [ "$status" -eq 60 ]
    fi
  '
}

cleanup_stage() {
  case "$stage" in
    "$HARNESS_DIR"/.rotation/rotate.*) rm -rf "$stage" ;;
    *) log "Refusing to remove unexpected rotation stage path: $stage" ;;
  esac
  if [[ "$lock_acquired" == true ]]; then
    rm -f "$lock_dir/owner.pid"
    rmdir "$lock_dir" 2>/dev/null \
      || log "Unable to remove rotation lock directory: $lock_dir"
  fi
}

on_exit() {
  local status=$?
  trap - EXIT
  if [[ "$status" -ne 0 && "$rotation_complete" != true && "$revocation_started" != true ]]; then
    log "Rotation failed before revocation; attempting rollback"
    set +e
    if [[ "$files_swapped" == true ]]; then
      restore_live_files
      recreate_consumers
    fi
    if [[ "$dashboard_changed" == true ]]; then
      set_dashboard_password "$old_dashboard_password"
    fi
    if [[ "$new_primary_added" == true ]]; then
      set_rgw_key remove "$CEPH_DEMO_UID" "$new_primary_access"
    fi
    if [[ "$new_denied_added" == true ]]; then
      set_rgw_key remove "$CEPH_DENIED_UID" "$new_denied_access"
    fi
    set -e
  elif [[ "$status" -ne 0 && "$revocation_started" == true ]]; then
    log "Rotation failed after revocation began; the new credentials remain active and compromised credentials will not be restored"
  fi
  cleanup_stage
  exit "$status"
}
trap on_exit EXIT

# The lock is released by the EXIT trap, which SIGKILL does not run, so a
# killed rotation used to block every later run until an operator removed the
# directory by hand. The owning PID is recorded inside it; when that process is
# gone the lock is stale and this run reclaims it, along with the stage
# directories the dead run left behind.
reclaim_stale_lock() {
  local owner_pid
  owner_pid="$(cat "$lock_dir/owner.pid" 2>/dev/null || true)"
  if [[ "$owner_pid" =~ ^[0-9]+$ ]] && kill -0 "$owner_pid" 2>/dev/null; then
    return 1
  fi
  if [[ -z "$owner_pid" ]]; then
    log "Rotation lock records no owning process; treating it as stale"
  else
    log "Rotation lock is owned by process $owner_pid, which is no longer running; reclaiming it"
  fi
  local orphan
  for orphan in "$rotation_root"/rotate.*; do
    [[ -d "$orphan" && "$orphan" != "$stage" ]] || continue
    log "Removing orphaned rotation stage directory: ${orphan#"$HARNESS_DIR/"}"
    rm -rf "$orphan"
  done
  rm -f "$lock_dir/owner.pid"
  rmdir "$lock_dir" 2>/dev/null || return 1
  return 0
}

if ! mkdir "$lock_dir" 2>/dev/null; then
  if ! reclaim_stale_lock || ! mkdir "$lock_dir" 2>/dev/null; then
    fail "Another secret rotation appears to be active: $lock_dir"
  fi
fi
lock_acquired=true
printf '%s\n' "$$" >"$lock_dir/owner.pid"

write_rotated_environment
STRATUS_CERTIFICATE_ROOT="$stage_relative/new" \
STRATUS_FORCE_CA_ROTATION=true \
  "$HARNESS_DIR/scripts/lib/ceph-compose-generate-certificates.sh"

set_rgw_key create "$CEPH_DEMO_UID" "$new_primary_access" "$new_primary_secret"
new_primary_added=true
set_rgw_key create "$CEPH_DENIED_UID" "$new_denied_access" "$new_denied_secret"
new_denied_added=true
set_dashboard_password "$new_dashboard_password"
dashboard_changed=true

swap_live_files
recreate_consumers

"$HARNESS_DIR/scripts/verify/ceph-compose-verify-buckets.sh"
verify_denied_owner_key
"$HARNESS_DIR/scripts/verify/ceph-compose-verify-dashboard.sh"
assert_old_ca_rejected
log "CUTOVER PASS: new RGW keys, Dashboard password, and TLS chain are live"

# From this point rollback must never reactivate compromised credentials.
revocation_started=true
revocation_failed=false
if ! set_rgw_key remove "$CEPH_DEMO_UID" "$old_primary_access"; then
  log "Old primary RGW key removal reported an error"
  revocation_failed=true
fi
if ! set_rgw_key remove "$CEPH_DENIED_UID" "$old_denied_access"; then
  log "Old denied-owner RGW key removal reported an error"
  revocation_failed=true
fi
if ! assert_removed_key_rejected "$old_primary_access" "$old_primary_secret" \
    "$CEPH_RGW_PROBE_BUCKET"; then
  log "Old primary RGW key rejection could not be proved"
  revocation_failed=true
fi
if ! assert_removed_key_rejected "$old_denied_access" "$old_denied_secret" \
    "$CEPH_RGW_DENIED_BUCKET"; then
  log "Old denied-owner RGW key rejection could not be proved"
  revocation_failed=true
fi
if ! assert_old_dashboard_password_rejected; then
  log "Old Dashboard password rejection could not be proved"
  revocation_failed=true
fi
[[ "$revocation_failed" == false ]] \
  || fail "One or more old authentication paths could not be conclusively revoked"

rotation_complete=true
log "ROTATION PASS: old RGW keys and Dashboard password are rejected; Ceph data was preserved"
log "ACTION REQUIRED: remove the old 'Stratus Disposable Compose CA' trust entry and import $HARNESS_DIR/certs/stratus-ca.crt where needed"
