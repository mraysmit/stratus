#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-04
source "$(dirname "$0")/../lib/openbao-compose-common.sh"

# Runs the live secret-store conformance suite (stratus-secrets-verifier)
# against this OpenBao service, supplying the environment and the live
# opt-in switch. Arguments pass through to Maven. Every invocation writes a
# timestamped transcript with explicit run boundaries to this harness's
# logs/.

load_environment
# shellcheck disable=SC1091
set -a; source "$HARNESS_DIR/connection.env"; set +a
token_file="$HARNESS_DIR/$OPENBAO_TOKEN_FILE"
[[ -f "$token_file" ]] || fail "Missing $token_file; run lifecycle/openbao-compose-startup.sh"

export STRATUS_SECRETS_INTEGRATION=true
export OPENBAO_TOKEN="$(cat "$token_file")"
export OPENBAO_ALLOW_HTTP=true
# DEBUG by default so transcripts prove both operational log levels.
export STRATUS_LOG_LEVEL="${STRATUS_LOG_LEVEL:-DEBUG}"

cd "$REPO_DIR"
test_log_dir="$HARNESS_DIR/logs"
mkdir -p "$test_log_dir"
run_timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
run_started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
run_id="secrets-conformance-tests-$run_timestamp"
test_log="$test_log_dir/$run_id.log"
log "Writing the complete secrets conformance transcript to $test_log"
if [[ "$#" -eq 0 ]]; then
  set -- test -Psecrets-integration-tests -pl :stratus-secrets-verifier -am
fi

set +e
{
  printf 'RUN startedAtUtc=%s runId=%s endpoint=%s\n' \
    "$run_started_at" "$run_id" "$OPENBAO_ENDPOINT"
  ./mvnw "$@"
} 2>&1 | tee "$test_log"
maven_status="${PIPESTATUS[0]}"
set -e
printf 'RUN completedAtUtc=%s runId=%s exitCode=%s\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$run_id" "$maven_status" | tee -a "$test_log"
exit "$maven_status"
