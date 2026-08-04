#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-04
source "$(dirname "$0")/../lib/openbao-compose-common.sh"

# Brings the disposable developer secret store up. Idempotent: .env is
# generated from the template once with a per-machine root token, which is
# also written to private/root-token (owner-only) for consumer scripts.
# Dev mode is in-memory: secrets vanish on restart and are restored by
# re-running the Ceph service-identity provisioning step.

rand_hex() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }

if [[ ! -f "$HARNESS_DIR/.env" ]]; then
  sed \
    -e "s|^OPENBAO_ROOT_TOKEN=.*|OPENBAO_ROOT_TOKEN=stratus-dev-$(rand_hex 16)|" \
    "$HARNESS_DIR/.env.template" >"$HARNESS_DIR/.env"
  chmod 600 "$HARNESS_DIR/.env"
  harden_windows_acl "$HARNESS_DIR/.env"
  log "Generated $HARNESS_DIR/.env with a per-machine dev root token"
fi

load_environment
mkdir -p "$HARNESS_DIR/private" "$HARNESS_DIR/logs" "$HARNESS_DIR/evidence"
printf '%s' "$OPENBAO_ROOT_TOKEN" >"$HARNESS_DIR/private/root-token"
chmod 600 "$HARNESS_DIR/private/root-token"
harden_windows_acl "$HARNESS_DIR/private" "$HARNESS_DIR/private/root-token"

# Validate interpolation before touching container state so a broken .env
# fails here with a compose diagnostic rather than mid-startup.
compose config --quiet
compose up --detach --remove-orphans --wait

log "OpenBao (dev mode, in-memory) listening on ${OPENBAO_BIND_ADDRESS:-127.0.0.1}:${OPENBAO_PORT:-8200}"
log "Check it with: bash scripts/verify/openbao-compose-verify-endpoint.sh"
compose ps
