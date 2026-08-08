#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-08
source "$(dirname "$0")/../lib/spark-compose-common.sh"

# Runs the live Spark conformance suite (stratus-spark-tests) against this
# running cluster, the deployed Polaris catalog, and the deployed Ceph RGW
# gateways. Arguments pass through to Maven. Every invocation writes a
# timestamped transcript with explicit run boundaries to this harness's logs/.
#
# The suite submits real statements to the standalone master from inside the
# cluster, so it needs no workstation truststore or hosts-file entry: the
# harness containers already carry both lab CAs.

load_environment

export STRATUS_SPARK_INTEGRATION=true

cd "$REPO_DIR"
test_log_dir="$HARNESS_DIR/logs"
mkdir -p "$test_log_dir"
run_started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
run_timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
run_id="spark-conformance-tests-$run_timestamp"
test_log="$test_log_dir/$run_id.log"
log "Writing the complete Spark conformance transcript to $test_log"

if [[ "$#" -eq 0 ]]; then
  set -- test -Pspark-integration-tests -pl :stratus-spark-tests -am
fi

set +e
{
  printf 'RUN startedAtUtc=%s runId=%s catalog=%s storage=%s\n' \
    "$run_started_at" "$run_id" "$POLARIS_ENDPOINT" "$CEPH_RGW_ENDPOINT"
  ./mvnw "$@"
} 2>&1 | tee "$test_log"
maven_status="${PIPESTATUS[0]}"
set -e
printf 'RUN completedAtUtc=%s runId=%s exitCode=%s\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$run_id" "$maven_status" | tee -a "$test_log"
exit "$maven_status"
