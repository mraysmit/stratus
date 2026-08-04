#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-07-22
source "$(dirname "$0")/../lib/ceph-compose-common.sh"

# Brings the cluster up, creating it on first run. Idempotent: .env is
# generated from the template once with per-machine disposable credentials
# and then left alone, and certificates regenerate only when absent or near
# expiry. Every generated secret is local to this disposable harness.

rand_hex() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }

env_key_exists() {
  local key="$1"
  awk -F= -v key="$key" '$1 == key { found=1 } END { exit !found }' "$HARNESS_DIR/.env"
}

env_key_has_value() {
  local key="$1"
  awk -F= -v key="$key" '
    $1 == key { found=1; value=substr($0, index($0, "=") + 1) }
    END { exit !(found && length(value) > 0) }
  ' "$HARNESS_DIR/.env"
}

env_value() {
  local key="$1"
  awk -F= -v key="$key" '
    $1 == key { found=1; value=substr($0, index($0, "=") + 1) }
    END { if (found) print value }
  ' "$HARNESS_DIR/.env"
}

credential_bundle_state() {
  local key present=0 populated=0 total="$#"
  for key in "$@"; do
    env_key_exists "$key" && present=$((present + 1))
    env_key_has_value "$key" && populated=$((populated + 1))
  done
  if (( present == 0 )); then
    printf 'missing'
  elif (( present == total && populated == total )); then
    printf 'complete'
  else
    printf 'partial'
  fi
}

if [[ ! -f "$HARNESS_DIR/.env" ]]; then
  sed \
    -e "s|^CEPH_RGW_ACCESS_KEY=.*|CEPH_RGW_ACCESS_KEY=stratus-local-$(rand_hex 6)|" \
    -e "s|^CEPH_RGW_SECRET_KEY=.*|CEPH_RGW_SECRET_KEY=$(rand_hex 20)|" \
    -e "s|^CEPH_DENIED_ACCESS_KEY=.*|CEPH_DENIED_ACCESS_KEY=stratus-denied-$(rand_hex 6)|" \
    -e "s|^CEPH_DENIED_SECRET_KEY=.*|CEPH_DENIED_SECRET_KEY=$(rand_hex 20)|" \
    -e "s|^CEPH_DASHBOARD_PASSWORD=.*|CEPH_DASHBOARD_PASSWORD=$(rand_hex 20)|" \
    -e "s|^CEPH_ADMIN_OPS_ACCESS_KEY=.*|CEPH_ADMIN_OPS_ACCESS_KEY=stratus-adminops-$(rand_hex 6)|" \
    -e "s|^CEPH_ADMIN_OPS_SECRET_KEY=.*|CEPH_ADMIN_OPS_SECRET_KEY=$(rand_hex 20)|" \
    "$HARNESS_DIR/.env.template" >"$HARNESS_DIR/.env"
  chmod 600 "$HARNESS_DIR/.env"
  harden_windows_acl "$HARNESS_DIR/.env"
  log "Generated $HARNESS_DIR/.env with per-machine disposable credentials"
fi

# Migrate complete historical configurations without replacing credentials.
# A partial bundle is rejected: inventing the missing half of a credential can
# leave .env disagreeing with an identity that already exists inside Ceph.
case "$(credential_bundle_state CEPH_DASHBOARD_USER CEPH_DASHBOARD_PASSWORD)" in
  missing)
    {
      echo ''
      echo '# Ceph Dashboard (management console) sign-in, added by startup.'
      echo 'CEPH_DASHBOARD_USER=stratus-dashboard'
      echo "CEPH_DASHBOARD_PASSWORD=$(rand_hex 20)"
    } >>"$HARNESS_DIR/.env"
    log "Added generated dashboard credentials to $HARNESS_DIR/.env"
    ;;
  partial)
    fail "CEPH_DASHBOARD_USER and CEPH_DASHBOARD_PASSWORD must either both be populated or both be absent"
    ;;
esac

case "$(credential_bundle_state CEPH_ADMIN_OPS_UID CEPH_ADMIN_OPS_ACCESS_KEY CEPH_ADMIN_OPS_SECRET_KEY)" in
  missing)
    {
      echo ''
      echo '# Scoped RGW Admin Operations API reader, added by startup.'
      echo 'CEPH_ADMIN_OPS_UID=stratus-admin-ops-reader'
      echo "CEPH_ADMIN_OPS_ACCESS_KEY=stratus-adminops-$(rand_hex 6)"
      echo "CEPH_ADMIN_OPS_SECRET_KEY=$(rand_hex 20)"
    } >>"$HARNESS_DIR/.env"
    log "Added a generated Admin Operations API reader to $HARNESS_DIR/.env"
    ;;
  partial)
    fail "CEPH_ADMIN_OPS_UID, CEPH_ADMIN_OPS_ACCESS_KEY, and CEPH_ADMIN_OPS_SECRET_KEY must all be populated or all be absent"
    ;;
esac

# Platform service identities (svc-polaris and successors) are NOT generated
# here: verify/ceph-compose-provision-service-identities.sh provisions them
# from the declarative service-identities.conf after the cluster is up.

# Backfilled separately from the credentials above: this is a non-secret URL and
# an .env generated before the dashboard REST conformance test existed will not have it.
if ! env_key_exists CEPH_DASHBOARD_ENDPOINT; then
  dashboard_port="$(env_value CEPH_DASHBOARD_PORT)"
  dashboard_port="${dashboard_port:-8444}"
  [[ "$dashboard_port" =~ ^[0-9]+$ ]] || fail "CEPH_DASHBOARD_PORT must be numeric"
  {
    echo ''
    echo '# Dashboard REST API URL, added by startup.'
    echo "CEPH_DASHBOARD_ENDPOINT=https://object-store.stratus.local:${dashboard_port}"
  } >>"$HARNESS_DIR/.env"
  log "Added CEPH_DASHBOARD_ENDPOINT to $HARNESS_DIR/.env"
elif ! env_key_has_value CEPH_DASHBOARD_ENDPOINT; then
  fail "CEPH_DASHBOARD_ENDPOINT must not be empty"
fi
chmod 600 "$HARNESS_DIR/.env"
harden_windows_acl "$HARNESS_DIR/.env"
# Idempotent: generates on first run, renews when a certificate nears expiry.
"$(dirname "$0")/../lib/ceph-compose-generate-certificates.sh"
load_environment
require_free_harness_subnet
mkdir -p "$HARNESS_DIR/evidence"
# Validate interpolation before touching container state so a broken .env
# fails here with a compose diagnostic rather than mid-startup.
compose config --quiet
compose up --detach --remove-orphans --wait

# The Dashboard's RGW pages and its /api/rgw REST endpoints need an RGW identity
# of their own. This runs here rather than in ceph/configure.sh because the
# command requires a running RGW daemon, and configure.sh completes before the
# RGW services start. It is idempotent. A failure is reported but does not abort
# startup: the cluster and the S3 endpoint are fully usable without it, and only
# the dashboard's RGW views depend on it.
if compose exec -T mon1 ceph dashboard set-rgw-credentials >/dev/null 2>&1; then
  log "Configured the Dashboard RGW credentials"
else
  log "WARNING: could not configure Dashboard RGW credentials; /api/rgw endpoints will be unavailable"
fi

compose ps
