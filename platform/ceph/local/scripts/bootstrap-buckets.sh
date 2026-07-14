#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_environment
for bucket in stratus-landing stratus-bronze stratus-silver stratus-gold stratus-platform; do
  compose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt mkdir "cephrgw:${bucket}"
  printf 'READY bucket=%s\n' "$bucket"
done
compose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt mkdir "deniedowner:${CEPH_RGW_DENIED_BUCKET}"
printf 'READY isolated-policy-bucket=%s owner=%s\n' "$CEPH_RGW_DENIED_BUCKET" "$CEPH_DENIED_UID"
