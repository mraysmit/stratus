#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-22
# Purpose: prove the first P1-4.3 pipeline DAG through Airflow's own parser and registry.
source "$(dirname "$0")/../lib/airflow-compose-common.sh"

SUITE_RUN_ID="airflow-pipeline-parse-$(date -u +%Y%m%dT%H%M%SZ)"
EXPECTED_DAG_ID="stratus_landing_to_bronze"
SUITE_STARTED_MS="$(date +%s%3N)"

mkdir -p "$HARNESS_DIR/evidence"
evidence_file="$HARNESS_DIR/evidence/${SUITE_RUN_ID}.log"
exec > >(tee "$evidence_file") 2>&1

elapsed_ms() {
  local started_ms="$1"
  printf '%s' "$(( $(date +%s%3N) - started_ms ))"
}

shutdown_airflow() {
  local exit_code=$?
  trap - EXIT
  bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-shutdown.sh" || true
  exit "$exit_code"
}
trap shutdown_airflow EXIT

log "PIPELINE DAG PARSE suiteRunId=$SUITE_RUN_ID phase=startup status=STARTED"
phase_started_ms="$(date +%s%3N)"
bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-startup.sh"
bash "$HARNESS_DIR/scripts/tests/airflow-compose-verify-health.sh"
log "PIPELINE DAG PARSE suiteRunId=$SUITE_RUN_ID phase=startup status=SUCCESS elapsedMs=$(elapsed_ms "$phase_started_ms")"

load_environment
phase_started_ms="$(date +%s%3N)"
import_errors_json="$(compose exec -T airflow-scheduler \
  airflow dags list-import-errors --output json | tail -n 1 | tr -d '\r')"
printf '%s\n' "$import_errors_json"
[[ "$import_errors_json" == "[]" ]] \
  || fail "Airflow reported DAG import errors: $import_errors_json"

dag_list="$(compose exec -T airflow-scheduler airflow dags list --output json)"
printf '%s\n' "$dag_list"
grep -Fq "\"dag_id\": \"$EXPECTED_DAG_ID\"" <<<"$dag_list" \
  || grep -Fq "\"dag_id\":\"$EXPECTED_DAG_ID\"" <<<"$dag_list" \
  || fail "Airflow did not register $EXPECTED_DAG_ID"
log "PIPELINE DAG PARSE suiteRunId=$SUITE_RUN_ID phase=parse status=SUCCESS dagId=$EXPECTED_DAG_ID elapsedMs=$(elapsed_ms "$phase_started_ms")"

log "PIPELINE DAG PARSE suiteRunId=$SUITE_RUN_ID status=SUCCESS elapsedMs=$(elapsed_ms "$SUITE_STARTED_MS") evidence=$evidence_file"
