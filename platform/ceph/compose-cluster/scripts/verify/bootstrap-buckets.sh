#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-07-22
source "$(dirname "$0")/../lib/common.sh"

# Creates the five platform buckets plus the denied-owner bucket that the
# security negative tests probe against. Buckets are created through the
# S3 API from inside the Compose network, so this also exercises the TLS
# and credential path a real client uses. Safe to re-run: rclone mkdir
# succeeds when the bucket already exists.

load_environment
: "${CEPH_RGW_DENIED_BUCKET:?CEPH_RGW_DENIED_BUCKET is required; update .env from .env.template}"
: "${CEPH_DENIED_UID:?CEPH_DENIED_UID is required; update .env from .env.template}"
for bucket in stratus-landing stratus-bronze stratus-silver stratus-gold stratus-platform; do
  compose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt mkdir "cephrgw:${bucket}"
  log "READY bucket=$bucket"
done
# The denied bucket is owned by a separate RGW identity so verify-security can
# prove the primary verifier cannot cross an identity boundary.
compose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt mkdir "deniedowner:${CEPH_RGW_DENIED_BUCKET}"
log "READY isolated-policy-bucket=$CEPH_RGW_DENIED_BUCKET owner=$CEPH_DENIED_UID"
