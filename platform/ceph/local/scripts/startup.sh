#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
if [[ ! -f "$HARNESS_DIR/.env" ]]; then
  rand_hex() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }
  sed \
    -e "s|^CEPH_RGW_ACCESS_KEY=.*|CEPH_RGW_ACCESS_KEY=stratus-local-$(rand_hex 6)|" \
    -e "s|^CEPH_RGW_SECRET_KEY=.*|CEPH_RGW_SECRET_KEY=$(rand_hex 20)|" \
    -e "s|^CEPH_DENIED_ACCESS_KEY=.*|CEPH_DENIED_ACCESS_KEY=stratus-denied-$(rand_hex 6)|" \
    -e "s|^CEPH_DENIED_SECRET_KEY=.*|CEPH_DENIED_SECRET_KEY=$(rand_hex 20)|" \
    "$HARNESS_DIR/.env.template" >"$HARNESS_DIR/.env"
  chmod 600 "$HARNESS_DIR/.env"
  log "Generated $HARNESS_DIR/.env with per-machine disposable credentials"
fi
# Idempotent: generates on first run, renews when a certificate nears expiry.
"$(dirname "$0")/generate-lab-certificates.sh"
load_environment
require_free_harness_subnet
mkdir -p "$HARNESS_DIR/evidence"
compose config --quiet
compose up --detach --remove-orphans --wait
compose ps
