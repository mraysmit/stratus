#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-03
source "$(dirname "$0")/../lib/ceph-compose-common.sh"

# Comprehensive one-shot cluster validation: runs the documented verification
# sequence (bootstrap-buckets through run-live-tests) in order and writes a
# timestamped transcript with explicit start/end records and per-step results
# to logs/. Every delegated step is itself idempotent, so the whole run is.
#
# By default the cluster must already be running. With --full the run brings
# the cluster up first and shuts it down after a fully successful validation;
# after a failed step the cluster is always left running for diagnosis.

SCRIPTS_DIR="$(cd "$(dirname "$0")/.." && pwd)"

full=false
for argument in "$@"; do
  case "$argument" in
    --full) full=true ;;
    *) fail "Unknown argument '$argument'. Usage: ceph-compose-validate-cluster.sh [--full]" ;;
  esac
done

run_timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
run_id="ceph-validate-cluster-$run_timestamp"
mkdir -p "$HARNESS_DIR/logs"
transcript="$HARNESS_DIR/logs/validate-cluster-$run_timestamp.txt"

passed_steps=()

step() {
  local name="$1" script="$2" started ended
  started="$(date +%s)"
  log "STEP START name=$name"
  if bash "$SCRIPTS_DIR/$script"; then
    ended="$(date +%s)"
    log "STEP PASS name=$name elapsedSeconds=$((ended - started))"
    passed_steps+=("$name")
  else
    ended="$(date +%s)"
    log "STEP FAIL name=$name elapsedSeconds=$((ended - started))"
    log "The cluster is left running for diagnosis; shut it down with lifecycle/ceph-compose-shutdown.sh"
    log "RESULT FAIL runId=$run_id failedStep=$name completedSteps=${#passed_steps[@]}"
    exit 1
  fi
}

validate() {
  log "RUN startedAtUtc=$(date -u +%Y-%m-%dT%H:%M:%SZ) runId=$run_id full=$full"
  log "Writing the validation transcript to $transcript"
  if [[ "$full" == true ]]; then
    step startup lifecycle/ceph-compose-startup.sh
  else
    load_environment
    [[ "$(compose ps --services --status running)" == *rgw-proxy* ]] \
      || fail "The cluster is not running. Start it with lifecycle/ceph-compose-startup.sh or rerun with --full"
  fi
  step bootstrap-buckets verify/ceph-compose-bootstrap-buckets.sh
  step provision-service-identities verify/ceph-compose-provision-service-identities.sh
  step verify-buckets verify/ceph-compose-verify-buckets.sh
  step verify-storage verify/ceph-compose-verify-storage.sh
  step verify-security verify/ceph-compose-verify-security.sh
  step verify-dashboard verify/ceph-compose-verify-dashboard.sh
  step verify-dataset verify/ceph-compose-verify-dataset.sh
  step run-live-tests verify/ceph-compose-run-live-tests.sh
  if [[ "$full" == true ]]; then
    step shutdown lifecycle/ceph-compose-shutdown.sh
  fi
  log "RESULT PASS runId=$run_id completedAtUtc=$(date -u +%Y-%m-%dT%H:%M:%SZ) steps=${#passed_steps[@]} (${passed_steps[*]})"
}

# Preserve the validation exit code through tee so the transcript always holds
# the complete run, including the RESULT record, and failures still fail.
set +e
validate 2>&1 | tee "$transcript"
validation_status="${PIPESTATUS[0]}"
set -e
exit "$validation_status"
