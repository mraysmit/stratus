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

scheme="https"
if [[ "${POLARIS_ALLOW_HTTP:-false}" == true ]]; then
  scheme="http"
fi
endpoint="$scheme://127.0.0.1:${POLARIS_PORT:-8181}/api/catalog/v1/config"

# Polaris cold-start takes tens of seconds (slower still while the Ceph
# cluster shares the host), so poll with a bounded deadline instead of
# requiring callers to guess a sleep. Overridable for slower machines.
deadline_seconds="${POLARIS_STARTUP_DEADLINE_SECONDS:-90}"
elapsed=0
status_code=""
while (( elapsed < deadline_seconds )); do
  status_code="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --cacert "$CEPH_HARNESS_CA_FILE" \
    --max-time 10 "$endpoint" || true)"
  case "$status_code" in
    200|401|403)
      log "PASS polaris-endpoint listening endpoint=$endpoint httpStatus=$status_code waitedSeconds=$elapsed"
      exit 0
      ;;
  esac
  sleep 3
  elapsed=$((elapsed + 3))
done
fail "Polaris API did not answer on $endpoint within ${deadline_seconds}s (last result '${status_code:-no response}'); inspect logs with: docker compose --project-name stratus-polaris-local logs polaris"
