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

# Checked here as well as at startup, because the jar can disappear after the
# cluster is up: any `mvnw clean` on the jobs module removes it, and the
# container mounts it from the workstation. Without this the suite still runs
# and every job answers "Failed to load class dev.stratus.jobs.spark....",
# which arrives as a dozen unrelated-looking test failures instead of one line
# saying the jar is missing. Observed 2026-08-12.
require_jobs_jar

export STRATUS_SPARK_INTEGRATION=true
# The suite and the jobs it submits both read this. A transcript recorded at
# INFO alone says a pipeline passed without saying what it did, so the default
# here is DEBUG, matching the catalog and secrets runners.
export STRATUS_LOG_LEVEL="${STRATUS_LOG_LEVEL:-DEBUG}"

cd "$REPO_DIR"
test_log_dir="$HARNESS_DIR/logs"
mkdir -p "$test_log_dir"
run_started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
run_started_epoch_ms="$(date -u +%s%3N)"
run_timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
run_id="spark-conformance-tests-$run_timestamp"
export STRATUS_RUN_ID="$run_id"
test_log="$test_log_dir/$run_id.log"
log "Writing the complete Spark conformance transcript to $test_log"

git_revision="$(git rev-parse --short=12 HEAD)"
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  git_state="dirty"
else
  git_state="clean"
fi

if [[ "$#" -eq 0 ]]; then
  # verify lets the upstream isolated-AWS module finish its package phase
  # before this module's tests start; its classes are produced by shading.
  set -- verify -Pspark-integration-tests -pl :stratus-spark-tests -am
fi

set +e
{
  printf 'RUN startedAtUtc=%s runId=%s revision=%s worktree=%s logLevel=%s catalog=%s storage=%s\n' \
    "$run_started_at" "$run_id" "$git_revision" "$git_state" "$STRATUS_LOG_LEVEL" \
    "$POLARIS_ENDPOINT" "$CEPH_RGW_ENDPOINT"
  # A variable exported inside Git Bash is not reliably added to the Windows
  # environment inherited by cmd.exe. Pass the same opt-in as a Maven user
  # property so Surefire receives it on Windows and WSL as well as on Linux.
  repository_maven -Dstratus.spark.integration=true -Dstratus.run.id="$run_id" "$@"
} 2>&1 | tee "$test_log"
maven_status="${PIPESTATUS[0]}"
set -e
run_completed_epoch_ms="$(date -u +%s%3N)"
run_duration_ms="$((run_completed_epoch_ms - run_started_epoch_ms))"
printf 'RUN completedAtUtc=%s runId=%s exitCode=%s durationMs=%s\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$run_id" "$maven_status" "$run_duration_ms" \
  | tee -a "$test_log"
printf 'RUN transcript runId=%s bytes=%s\n' "$run_id" "$(wc -c < "$test_log")" | tee -a "$test_log"
exit "$maven_status"
