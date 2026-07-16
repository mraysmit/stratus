#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
load_environment
: "${CEPH_RGW_DENIED_BUCKET:?CEPH_RGW_DENIED_BUCKET is required; update .env from .env.template}"
: "${CEPH_DENIED_UID:?CEPH_DENIED_UID is required; update .env from .env.template}"
for bucket in stratus-landing stratus-bronze stratus-silver stratus-gold stratus-platform; do
  compose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt mkdir "cephrgw:${bucket}"
  log "READY bucket=$bucket"
done
compose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt mkdir "deniedowner:${CEPH_RGW_DENIED_BUCKET}"
log "READY isolated-policy-bucket=$CEPH_RGW_DENIED_BUCKET owner=$CEPH_DENIED_UID"
