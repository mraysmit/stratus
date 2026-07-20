#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
load_environment
for bucket in stratus-landing stratus-bronze stratus-silver stratus-gold stratus-platform; do
  compose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt lsf "cephrgw:${bucket}/" >/dev/null
  log "PASS bucket=$bucket"
done
