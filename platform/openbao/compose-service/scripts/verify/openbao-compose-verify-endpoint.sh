#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-04
source "$(dirname "$0")/../lib/openbao-compose-common.sh"

# Liveness check only: the store answers on the loopback port and the root
# token the harness published is on disk. That is what a caller needs before
# anything else can be attempted.
#
# It deliberately does NOT write, read back, or delete a probe secret. The KV
# round trip is product behavior and belongs to
# SecretStoreConformanceTest.writesReadsAndDeletesASecretRoundTrip, which
# asserts more than this script did — it requires the deleted secret to answer
# 404 afterwards, where the script fired the delete and ignored the result
# (code style rules 10.1). Run verify/openbao-compose-run-secrets-tests.sh.

load_environment
# shellcheck disable=SC1091
set -a; source "$HARNESS_DIR/connection.env"; set +a
token_file="$HARNESS_DIR/$OPENBAO_TOKEN_FILE"
[[ -f "$token_file" ]] || fail "Missing $token_file; run lifecycle/openbao-compose-startup.sh"

health="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 10 \
  "$OPENBAO_ENDPOINT/v1/sys/health" || true)"
[[ "$health" == 200 ]] || fail "OpenBao is not healthy on $OPENBAO_ENDPOINT (got '${health:-no response}'); start it with lifecycle/openbao-compose-startup.sh"
log "PASS openbao-health endpoint=$OPENBAO_ENDPOINT httpStatus=$health"
log "Secret storage behavior is proven by SecretStoreConformanceTest in the live suite"
