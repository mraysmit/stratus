#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-04
source "$(dirname "$0")/../lib/openbao-compose-common.sh"

# Idempotent shutdown that works even when .env is absent. Dev mode is
# in-memory, so stopping the container discards all stored secrets; .env and
# private/root-token are preserved for the next startup.

compose_teardown down --remove-orphans
log "OpenBao harness stopped; dev-mode secrets are discarded by design"
