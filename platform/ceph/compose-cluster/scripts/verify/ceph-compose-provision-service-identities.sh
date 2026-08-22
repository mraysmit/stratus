#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-04
source "$(dirname "$0")/../lib/ceph-compose-common.sh"

# Provisions the platform service identities declared in
# service-identities.conf: generates disposable credentials into the ignored
# .env when absent, creates each RGW user, applies one merged bucket policy
# per bucket covering every declared grant, and probes that each identity
# works where granted and fails closed on the denied bucket. Idempotent:
# existing credentials, users, and grants converge without change.
#
# DEVELOPER HARNESS ONLY: in production, service identities and secrets are
# provisioned through the approved identity and secret-management process
# (ceph_storage.md production track, P1-1.4-P1), never from a flat file.

load_environment
conf="$HARNESS_DIR/service-identities.conf"
[[ -f "$conf" ]] || fail "Missing $conf"
: "${CEPH_RGW_DENIED_BUCKET:?CEPH_RGW_DENIED_BUCKET is required; update .env from .env.template}"

rand_hex() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }
trim() { printf '%s' "$1" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'; }
env_value_of() { awk -F= -v key="$1" '$1==key{print substr($0,index($0,"=")+1)}' "$HARNESS_DIR/.env"; }
permission_actions() {
  local permission="$1"
  case "$permission" in
    ro) printf '"s3:ListBucket","s3:GetObject"' ;;
    rw) printf '"s3:ListBucket","s3:GetObject","s3:PutObject","s3:DeleteObject","s3:AbortMultipartUpload","s3:ListBucketMultipartUploads","s3:ListMultipartUploadParts"' ;;
    *) fail "Unsupported permission '$permission'; expected ro or rw" ;;
  esac
}

identities=()
declare -A display_names grants_of
while IFS='|' read -r raw_uid raw_display raw_grants; do
  uid="$(trim "$raw_uid")"
  [[ -z "$uid" || "$uid" == \#* ]] && continue
  display="$(trim "$raw_display")"
  grants="$(trim "$raw_grants")"
  [[ -n "$display" && -n "$grants" ]] || fail "Malformed line in service-identities.conf for '$uid': expected <uid> | <display name> | <bucket>:<ro|rw> ..."
  [[ "$uid" =~ ^[a-z0-9-]+$ ]] || fail "Identity '$uid' must be lowercase kebab-case"
  for grant in $grants; do
    permission_actions "${grant##*:}" >/dev/null
  done
  identities+=("$uid")
  display_names["$uid"]="$display"
  grants_of["$uid"]="$grants"
done < "$conf"
[[ ${#identities[@]} -gt 0 ]] || fail "service-identities.conf declares no identities"

# Credentials: generate-once into .env, then reuse. A partial pair is
# rejected rather than silently repaired, matching the startup migration
# rule for the harness-core identities.
for uid in "${identities[@]}"; do
  env_base="CEPH_$(printf '%s' "$uid" | tr 'a-z-' 'A-Z_')"
  access="$(env_value_of "${env_base}_ACCESS_KEY")"
  secret="$(env_value_of "${env_base}_SECRET_KEY")"
  if [[ -z "$access" && -z "$secret" ]]; then
    {
      echo ''
      echo "# Service identity '$uid', added by ceph-compose-provision-service-identities."
      echo "${env_base}_ACCESS_KEY=${uid}-$(rand_hex 6)"
      echo "${env_base}_SECRET_KEY=$(rand_hex 20)"
    } >>"$HARNESS_DIR/.env"
    log "Added generated credentials for $uid to .env"
  elif [[ -z "$access" || -z "$secret" ]]; then
    fail "${env_base}_ACCESS_KEY and ${env_base}_SECRET_KEY must both be populated or both be absent"
  fi
done
chmod 600 "$HARNESS_DIR/.env"
harden_windows_acl "$HARNESS_DIR/.env"

# RGW users, created idempotently inside the cluster.
for uid in "${identities[@]}"; do
  env_base="CEPH_$(printf '%s' "$uid" | tr 'a-z-' 'A-Z_')"
  if ! compose exec -T mon1 radosgw-admin user info --uid "$uid" >/dev/null 2>&1; then
    compose exec -T mon1 radosgw-admin user create \
      --uid "$uid" \
      --display-name "${display_names[$uid]}" \
      --access-key "$(env_value_of "${env_base}_ACCESS_KEY")" \
      --secret-key "$(env_value_of "${env_base}_SECRET_KEY")" >/dev/null
    log "READY rgw-user=$uid (created)"
  else
    log "READY rgw-user=$uid (already exists)"
  fi
done

# IAM roles for credential vending: each identity gets a same-named RGW role
# only its own user may assume (the catalog vends subscoped credentials by
# AssumeRole via RGW STS). The role's permission policy carries exactly the
# identity's declared bucket grants; role-policy put overwrites, so
# re-applying converges.
for uid in "${identities[@]}"; do
  if ! compose exec -T mon1 radosgw-admin role get --role-name "$uid" >/dev/null 2>&1; then
    compose exec -T mon1 radosgw-admin role create --role-name "$uid" \
      --assume-role-policy-doc "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"arn:aws:iam:::user/$uid\"]},\"Action\":[\"sts:AssumeRole\"]}]}" >/dev/null
    log "READY rgw-role=$uid (created)"
  else
    log "READY rgw-role=$uid (already exists)"
  fi
  role_statements=""
  for grant in ${grants_of[$uid]}; do
    bucket="${grant%%:*}"
    permission="${grant##*:}"
    actions="$(permission_actions "$permission")"
    statement=$(printf '{"Effect":"Allow","Action":[%s],"Resource":["arn:aws:s3:::%s","arn:aws:s3:::%s/*"]}' \
      "$actions" "$bucket" "$bucket")
    role_statements="${role_statements:+$role_statements,}$statement"
  done
  compose exec -T mon1 radosgw-admin role-policy put --role-name "$uid" \
    --policy-name "$uid-access" \
    --policy-doc "{\"Version\":\"2012-10-17\",\"Statement\":[$role_statements]}" >/dev/null
  log "POLICY role=$uid buckets=$(printf '%s' "${grants_of[$uid]}" | tr ' ' ',')"
done

# One merged policy per bucket: put-bucket-policy replaces the whole policy,
# so every identity granted on a bucket must appear in a single document.
declare -A bucket_grants
for uid in "${identities[@]}"; do
  for grant in ${grants_of[$uid]}; do
    bucket="${grant%%:*}"
    permission="${grant##*:}"
    permission_actions "$permission" >/dev/null
    bucket_grants["$bucket"]="${bucket_grants[$bucket]:-} $uid:$permission"
  done
done

for bucket in "${!bucket_grants[@]}"; do
  statements=""
  grantees=""
  for identity_grant in ${bucket_grants[$bucket]}; do
    uid="${identity_grant%%:*}"
    permission="${identity_grant##*:}"
    actions="$(permission_actions "$permission")"
    sid="Stratus$(printf '%s' "$uid" | sed 's/-//g')Access"
    statement=$(printf '{"Sid":"%s","Effect":"Allow","Principal":{"AWS":["arn:aws:iam:::user/%s"]},"Action":[%s],"Resource":["arn:aws:s3:::%s","arn:aws:s3:::%s/*"]}' \
      "$sid" "$uid" "$actions" "$bucket" "$bucket")
    statements="${statements:+$statements,}$statement"
    grantees="${grantees:+$grantees,}$uid:$permission"
  done
  compose run --rm -T s3admin s3api put-bucket-policy \
    --endpoint-url "$CEPH_RGW_ENDPOINT" --bucket "$bucket" \
    --policy "{\"Version\":\"2012-10-17\",\"Statement\":[$statements]}" >/dev/null
  log "POLICY bucket=$bucket grantees=$grantees"
done

# Publish each identity's key pair to the developer secret store so
# consumers pull instead of operators copying (ADR-P1-004). Publishing is
# explicitly skipped when the OpenBao harness is not running: Ceph-only
# workflows stay standalone, and absent secrets surface at the consumer
# with a fail-fast remediation.
OPENBAO_HARNESS_DIR="$REPO_DIR/platform/openbao/compose-service"
if [[ -f "$OPENBAO_HARNESS_DIR/connection.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$OPENBAO_HARNESS_DIR/connection.env"
  set +a
  openbao_token_file="$OPENBAO_HARNESS_DIR/$OPENBAO_TOKEN_FILE"
  if [[ -f "$openbao_token_file" ]] \
      && curl --silent --output /dev/null --max-time 3 "$OPENBAO_ENDPOINT/v1/sys/health" 2>/dev/null; then
    openbao_token="$(cat "$openbao_token_file")"
    for uid in "${identities[@]}"; do
      env_base="CEPH_$(printf '%s' "$uid" | tr 'a-z-' 'A-Z_')"
      publish_status="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 10 \
        -H "X-Vault-Token: $openbao_token" -H 'Content-Type: application/json' \
        -X POST "$OPENBAO_ENDPOINT/v1/$OPENBAO_KV_MOUNT/data/$OPENBAO_SERVICE_IDENTITY_PATH/$uid" \
        -d "{\"data\":{\"access_key\":\"$(env_value_of "${env_base}_ACCESS_KEY")\",\"secret_key\":\"$(env_value_of "${env_base}_SECRET_KEY")\"}}")"
      [[ "$publish_status" == 200 || "$publish_status" == 204 ]] \
        || fail "Publishing $uid to OpenBao failed (HTTP $publish_status)"
      log "PUBLISH openbao path=$OPENBAO_SERVICE_IDENTITY_PATH/$uid"
    done
  else
    log "SKIP openbao-publish (OpenBao harness not running; consumers will fail fast with the remediation at read time)"
  fi
fi

# Probes: each identity must read its first granted bucket. Read/write identities
# additionally prove write/delete; read-only identities prove write denial.
# Every identity also fails closed on the denied bucket.
for uid in "${identities[@]}"; do
  env_base="CEPH_$(printf '%s' "$uid" | tr 'a-z-' 'A-Z_')"
  first_grant="${grants_of[$uid]%% *}"
  probe_bucket="${first_grant%%:*}"
  probe_permission="${first_grant##*:}"
  probe="policy-probe/${uid}-$(date -u +%Y%m%dT%H%M%SZ)"
  rclone_env=(
    -e RCLONE_CONFIG_PROBE_TYPE=s3
    -e RCLONE_CONFIG_PROBE_PROVIDER=Ceph
    -e "RCLONE_CONFIG_PROBE_ACCESS_KEY_ID=$(env_value_of "${env_base}_ACCESS_KEY")"
    -e "RCLONE_CONFIG_PROBE_SECRET_ACCESS_KEY=$(env_value_of "${env_base}_SECRET_KEY")"
    -e "RCLONE_CONFIG_PROBE_ENDPOINT=$CEPH_RGW_ENDPOINT"
    -e RCLONE_CONFIG_PROBE_FORCE_PATH_STYLE=true
  )
  compose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt touch "cephrgw:${probe_bucket}/${probe}"
  compose exec -T "${rclone_env[@]}" s3client rclone --ca-cert /certs/stratus-ca.crt cat "probe:${probe_bucket}/${probe}" >/dev/null
  log "PASS service-identity-read uid=$uid bucket=$probe_bucket permission=$probe_permission"
  compose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt deletefile "cephrgw:${probe_bucket}/${probe}"
  if [[ "$probe_permission" == rw ]]; then
    compose exec -T "${rclone_env[@]}" s3client rclone --ca-cert /certs/stratus-ca.crt touch "probe:${probe_bucket}/${probe}"
    compose exec -T "${rclone_env[@]}" s3client rclone --ca-cert /certs/stratus-ca.crt deletefile "probe:${probe_bucket}/${probe}"
    log "PASS service-identity-write uid=$uid bucket=$probe_bucket"
  elif compose exec -T "${rclone_env[@]}" s3client rclone --ca-cert /certs/stratus-ca.crt touch "probe:${probe_bucket}/${probe}" >/dev/null 2>&1; then
    compose exec -T "${rclone_env[@]}" s3client rclone --ca-cert /certs/stratus-ca.crt deletefile "probe:${probe_bucket}/${probe}" >/dev/null 2>&1 || true
    fail "$uid must not be able to write to read-only bucket $probe_bucket"
  else
    log "PASS service-identity-write-denied uid=$uid bucket=$probe_bucket (failed closed as required)"
  fi
  if compose exec -T "${rclone_env[@]}" s3client rclone --ca-cert /certs/stratus-ca.crt lsf "probe:${CEPH_RGW_DENIED_BUCKET}" >/dev/null 2>&1; then
    fail "$uid must not be able to list $CEPH_RGW_DENIED_BUCKET"
  fi
  log "PASS service-identity-denied uid=$uid bucket=$CEPH_RGW_DENIED_BUCKET (failed closed as required)"
done

log "Service identity provisioning complete: identities=${#identities[@]} buckets=${#bucket_grants[@]}"
