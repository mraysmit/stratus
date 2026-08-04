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

identities=()
declare -A display_names grants_of
while IFS='|' read -r raw_uid raw_display raw_grants; do
  uid="$(trim "$raw_uid")"
  [[ -z "$uid" || "$uid" == \#* ]] && continue
  display="$(trim "$raw_display")"
  grants="$(trim "$raw_grants")"
  [[ -n "$display" && -n "$grants" ]] || fail "Malformed line in service-identities.conf for '$uid': expected <uid> | <display name> | <bucket>:rw ..."
  [[ "$uid" =~ ^[a-z0-9-]+$ ]] || fail "Identity '$uid' must be lowercase kebab-case"
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

# One merged policy per bucket: put-bucket-policy replaces the whole policy,
# so every identity granted on a bucket must appear in a single document.
declare -A bucket_grantees
for uid in "${identities[@]}"; do
  for grant in ${grants_of[$uid]}; do
    bucket="${grant%%:*}"
    permission="${grant##*:}"
    [[ "$permission" == rw ]] || fail "Unsupported permission '$permission' for $uid on $bucket; only rw is defined"
    bucket_grantees["$bucket"]="${bucket_grantees[$bucket]:-} $uid"
  done
done

for bucket in "${!bucket_grantees[@]}"; do
  statements=""
  for uid in ${bucket_grantees[$bucket]}; do
    sid="Stratus$(printf '%s' "$uid" | sed 's/-//g')Access"
    statement=$(printf '{"Sid":"%s","Effect":"Allow","Principal":{"AWS":["arn:aws:iam:::user/%s"]},"Action":["s3:ListBucket","s3:GetObject","s3:PutObject","s3:DeleteObject","s3:AbortMultipartUpload","s3:ListBucketMultipartUploads","s3:ListMultipartUploadParts"],"Resource":["arn:aws:s3:::%s","arn:aws:s3:::%s/*"]}' \
      "$sid" "$uid" "$bucket" "$bucket")
    statements="${statements:+$statements,}$statement"
  done
  compose run --rm -T s3admin s3api put-bucket-policy \
    --endpoint-url "$CEPH_RGW_ENDPOINT" --bucket "$bucket" \
    --policy "{\"Version\":\"2012-10-17\",\"Statement\":[$statements]}" >/dev/null
  log "POLICY bucket=$bucket grantees=$(trim "${bucket_grantees[$bucket]}")"
done

# Probes: each identity must succeed on its first granted bucket and fail
# closed on the denied bucket, which never carries a service grant.
for uid in "${identities[@]}"; do
  env_base="CEPH_$(printf '%s' "$uid" | tr 'a-z-' 'A-Z_')"
  first_grant="${grants_of[$uid]%% *}"
  probe_bucket="${first_grant%%:*}"
  probe="policy-probe/${uid}-$(date -u +%Y%m%dT%H%M%SZ)"
  rclone_env=(
    -e RCLONE_CONFIG_PROBE_TYPE=s3
    -e RCLONE_CONFIG_PROBE_PROVIDER=Ceph
    -e "RCLONE_CONFIG_PROBE_ACCESS_KEY_ID=$(env_value_of "${env_base}_ACCESS_KEY")"
    -e "RCLONE_CONFIG_PROBE_SECRET_ACCESS_KEY=$(env_value_of "${env_base}_SECRET_KEY")"
    -e "RCLONE_CONFIG_PROBE_ENDPOINT=$CEPH_RGW_ENDPOINT"
    -e RCLONE_CONFIG_PROBE_FORCE_PATH_STYLE=true
  )
  compose exec -T "${rclone_env[@]}" s3client rclone --ca-cert /certs/stratus-ca.crt touch "probe:${probe_bucket}/${probe}"
  compose exec -T "${rclone_env[@]}" s3client rclone --ca-cert /certs/stratus-ca.crt deletefile "probe:${probe_bucket}/${probe}"
  log "PASS service-identity-access uid=$uid bucket=$probe_bucket"
  if compose exec -T "${rclone_env[@]}" s3client rclone --ca-cert /certs/stratus-ca.crt lsf "probe:${CEPH_RGW_DENIED_BUCKET}" >/dev/null 2>&1; then
    fail "$uid must not be able to list $CEPH_RGW_DENIED_BUCKET"
  fi
  log "PASS service-identity-denied uid=$uid bucket=$CEPH_RGW_DENIED_BUCKET (failed closed as required)"
done

log "Service identity provisioning complete: identities=${#identities[@]} buckets=${#bucket_grantees[@]}"
