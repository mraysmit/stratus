#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-03
source "$(dirname "$0")/../lib/polaris-compose-common.sh"

# Liveness smoke check only: proves the Polaris API is listening on the
# loopback port. It is NOT the catalog conformance; catalog behavior is proven
# by the live verification suite planned under verification/catalog. An
# unauthenticated 401/403 still proves liveness, so both count as listening.

load_environment
[[ "$(compose ps --services --status running)" == *polaris* ]] \
  || fail "The polaris service is not running. Start it with lifecycle/polaris-compose-startup.sh"

endpoint="$(polaris_api_base)/catalog/v1/config"

# The bounded wait itself lives in the common library, because the catalog
# bootstrap needs exactly the same readiness gate before its first request.
wait_for_polaris_api
log "PASS polaris-endpoint listening endpoint=$endpoint httpStatus=$POLARIS_API_STATUS waitedSeconds=$POLARIS_API_WAITED_SECONDS"
