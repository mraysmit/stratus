#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-07-22
source "$(dirname "$0")/../lib/common.sh"

# Read-only liveness check of the bucket contract over the live S3 endpoint.
# Safe to run repeatedly, including during failure drills while daemons are
# deliberately down. Listing output is discarded: the assertion is that the
# authenticated, TLS-verified request succeeds, not what the bucket contains.

load_environment
for bucket in stratus-landing stratus-bronze stratus-silver stratus-gold stratus-platform; do
  compose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt lsf "cephrgw:${bucket}/" >/dev/null
  log "PASS bucket=$bucket"
done
