#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-04
source "$(dirname "$0")/../lib/openbao-compose-common.sh"

# Liveness and round-trip check: the store answers on the loopback port,
# accepts an authenticated KV v2 write, returns the same value on read, and
# deletes it. Probe values are disposable and never logged.

load_environment
# shellcheck disable=SC1091
set -a; source "$HARNESS_DIR/connection.env"; set +a
token_file="$HARNESS_DIR/$OPENBAO_TOKEN_FILE"
[[ -f "$token_file" ]] || fail "Missing $token_file; run lifecycle/openbao-compose-startup.sh"
token="$(cat "$token_file")"

health="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 10 \
  "$OPENBAO_ENDPOINT/v1/sys/health" || true)"
[[ "$health" == 200 ]] || fail "OpenBao is not healthy on $OPENBAO_ENDPOINT (got '${health:-no response}'); start it with lifecycle/openbao-compose-startup.sh"
log "PASS openbao-health endpoint=$OPENBAO_ENDPOINT httpStatus=$health"

probe_path="$OPENBAO_KV_MOUNT/data/stratus/verify/endpoint-probe"
probe_value="probe-$(date -u +%Y%m%dT%H%M%SZ)"
write_status="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 10 \
  -H "X-Vault-Token: $token" -H 'Content-Type: application/json' \
  -X POST "$OPENBAO_ENDPOINT/v1/$probe_path" \
  -d "{\"data\":{\"probe\":\"$probe_value\"}}")"
[[ "$write_status" == 200 || "$write_status" == 204 ]] \
  || fail "KV write was refused (HTTP $write_status)"
read_back="$(curl --silent --max-time 10 -H "X-Vault-Token: $token" \
  "$OPENBAO_ENDPOINT/v1/$probe_path" | sed -nE 's/.*"probe" *: *"([^"]+)".*/\1/p')"
[[ "$read_back" == "$probe_value" ]] \
  || fail "KV read did not return the written probe value"
curl --silent --output /dev/null --max-time 10 -H "X-Vault-Token: $token" \
  -X DELETE "$OPENBAO_ENDPOINT/v1/$OPENBAO_KV_MOUNT/metadata/stratus/verify/endpoint-probe" || true
log "PASS openbao-kv-round-trip mount=$OPENBAO_KV_MOUNT"
