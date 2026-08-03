#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-03
source "$(dirname "$0")/../lib/polaris-compose-common.sh"

# Brings the Polaris developer service up. Idempotent: .env is generated from
# the template once with a per-machine disposable bootstrap credential and
# then left alone. Requires the Ceph harness to be running (ADR-P1-003); it
# is never started transitively from here.
#
# Scaffold status: the first two start/verify/stop validation cycles belong
# to P1-2.2-D1 and have not yet been recorded.

rand_hex() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }

if [[ ! -f "$HARNESS_DIR/.env" ]]; then
  sed \
    -e "s|^POLARIS_BOOTSTRAP_CREDENTIALS=POLARIS,stratus-root,.*|POLARIS_BOOTSTRAP_CREDENTIALS=POLARIS,stratus-root,$(rand_hex 20)|" \
    "$HARNESS_DIR/.env.template" >"$HARNESS_DIR/.env"
  chmod 600 "$HARNESS_DIR/.env"
  harden_windows_acl "$HARNESS_DIR/.env"
  log "Generated $HARNESS_DIR/.env with a per-machine disposable bootstrap credential"
fi

require_ceph_harness_network
load_environment
mkdir -p "$HARNESS_DIR/evidence" "$HARNESS_DIR/logs"

# Validate interpolation before touching container state so a broken .env
# fails here with a compose diagnostic rather than mid-startup.
compose config --quiet
compose up --detach --remove-orphans

log "Polaris starting from $POLARIS_IMAGE on ${POLARIS_BIND_ADDRESS:-127.0.0.1}:${POLARIS_PORT:-8181}"
log "Check liveness with: bash scripts/verify/polaris-compose-verify-endpoint.sh"
compose ps
