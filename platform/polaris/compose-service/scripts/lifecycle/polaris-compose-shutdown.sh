#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-03
source "$(dirname "$0")/../lib/polaris-compose-common.sh"

# Idempotent shutdown that works even when .env is absent. Removes this
# project's containers while preserving the catalog data volume, .env, and
# evidence. The Ceph harness network is external and is never removed here.

compose_teardown down --remove-orphans
log "Polaris harness stopped; the polaris-data volume is preserved"
