#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-24
# Purpose: prove real Airflow transient retry recovery and one terminal failure alert.
source "$(dirname "$0")/../lib/airflow-compose-common.sh"

readonly DAG_ID="stratus_retry_alert_probe"
readonly TASK_ID="exercise_retry_contract"
readonly PROBE_OVERLAY="$HARNESS_DIR/scripts/tests/compose.retry-alert.yaml"
readonly TERMINAL_DETAIL="controlled_detail_must_not_enter_alert"
readonly RUN_STATE_DEADLINE_SECONDS=90
if ! suite_owns_airflow; then
  export AIRFLOW_COMPOSE_OVERLAY="$PROBE_OVERLAY"
fi

suite_run_id="airflow-retry-alert-$(date -u +%Y%m%dT%H%M%SZ)"
transient_correlation="$suite_run_id-transient"
permanent_correlation="$suite_run_id-permanent"
transient_run_id="$suite_run_id-transient"
permanent_run_id="$suite_run_id-permanent"
started_ms="$(date +%s%3N)"
airflow_started=false
airflow_owned=false

mkdir -p "$HARNESS_DIR/evidence"
evidence_file="$HARNESS_DIR/evidence/${suite_run_id}.log"
exec > >(tee "$evidence_file") 2>&1

phase_complete() {
  local phase="$1" phase_started_ms="$2"
  log "event=airflow_retry_alert_phase_completed suiteRunId=$suite_run_id phase=$phase status=SUCCESS elapsedMs=$(( $(date +%s%3N) - phase_started_ms ))"
}

assert_not_logged() {
  local value="$1" label="$2"
  [[ -z "$value" ]] && return 0
  ! grep -Fq -- "$value" "$evidence_file" || fail "Secret-redaction check failed for $label"
}

cleanup() {
  local exit_code="$?"
  set +e
  if $airflow_owned; then
    bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-shutdown.sh"
  fi
  exit "$exit_code"
}

wait_for_terminal_run() {
  local run_id="$1" expected_state="$2"
  local deadline=$(( SECONDS + RUN_STATE_DEADLINE_SECONDS )) success_runs failed_runs
  while (( SECONDS < deadline )); do
    success_runs="$(compose exec -T airflow-scheduler airflow dags list-runs "$DAG_ID" \
      --state success --output json 2>&1 || true)"
    failed_runs="$(compose exec -T airflow-scheduler airflow dags list-runs "$DAG_ID" \
      --state failed --output json 2>&1 || true)"
    if [[ "$expected_state" == "success" ]] && grep -Fq "$run_id" <<<"$success_runs"; then
      log "RETRY ALERT PROBE RUN COMPLETED dagId=$DAG_ID runId=$run_id state=success"
      return 0
    fi
    if [[ "$expected_state" == "failed" ]] && grep -Fq "$run_id" <<<"$failed_runs"; then
      log "RETRY ALERT PROBE RUN COMPLETED dagId=$DAG_ID runId=$run_id state=failed"
      return 0
    fi
    ! grep -Fq "$run_id" <<<"$success_runs" \
      || fail "Retry Alert probe run succeeded unexpectedly: $run_id"
    ! grep -Fq "$run_id" <<<"$failed_runs" \
      || fail "Retry Alert probe run failed unexpectedly: $run_id"
    sleep 1
  done
  fail "Retry Alert probe run did not reach $expected_state within ${RUN_STATE_DEADLINE_SECONDS}s: $run_id"
}

capture_run_logs() {
  local run_id="$1"
  compose exec -T airflow-scheduler sh -c \
    'find /opt/airflow/logs -type f -path "*run_id=$1*" -exec cat {} +' _ "$run_id"
}

trigger_probe() {
  local run_id="$1" correlation_id="$2" mode="$3" expected_state="$4" conf
  conf="{\"mode\":\"$mode\",\"correlation_id\":\"$correlation_id\"}"
  compose exec -T airflow-scheduler airflow dags trigger "$DAG_ID" \
    --run-id "$run_id" --conf "$conf" --output json
  wait_for_terminal_run "$run_id" "$expected_state"
  capture_run_logs "$run_id"
}
trap cleanup EXIT

wait_for_probe_dag() {
  local dag_list attempt
  for attempt in $(seq 1 30); do
    dag_list="$(compose exec -T airflow-scheduler airflow dags list --output json 2>&1 || true)"
    if grep -Fq "\"dag_id\": \"$DAG_ID\"" <<<"$dag_list" \
        || grep -Fq "\"dag_id\":\"$DAG_ID\"" <<<"$dag_list"; then
      log "RETRY ALERT PROBE REGISTERED dagId=$DAG_ID attempts=$attempt"
      return 0
    fi
    sleep 1
  done
  fail "Airflow did not register the test-only retry/alert probe DAG"
}

log "event=airflow_retry_alert_suite_started suiteRunId=$suite_run_id dagId=$DAG_ID taskId=$TASK_ID"

phase_started_ms="$(date +%s%3N)"
if suite_owns_airflow; then
  bash "$HARNESS_DIR/scripts/tests/airflow-compose-verify-health.sh"
  log "event=airflow_retry_alert_suite_airflow_reused suiteRunId=$suite_run_id"
else
  bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-startup.sh"
  airflow_owned=true
fi
airflow_started=true
wait_for_probe_dag
phase_complete "airflow_startup_and_probe_registration" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
trigger_probe "$transient_run_id" "$transient_correlation" transient success
grep -Fq "correlationId=$transient_correlation mode=transient tryNumber=1" "$evidence_file" \
  || fail "The transient first attempt was not observed"
grep -Fq "correlationId=$transient_correlation mode=transient tryNumber=2" "$evidence_file" \
  || fail "The transient retry attempt was not observed"
grep -Fq "event=airflow_retry_alert_probe_succeeded correlationId=$transient_correlation" \
  "$evidence_file" || fail "The transient retry did not recover"
retry_count="$(grep -Fc "event=airflow_task_retry dag_id=$DAG_ID" "$evidence_file" || true)"
[[ "$retry_count" -eq 1 ]] || fail "Expected one retry callback after recovery, got $retry_count"
numeric_retry_count="$(grep -F "event=airflow_task_retry dag_id=$DAG_ID" "$evidence_file" \
  | grep -Ec "duration_ms=[0-9]+" || true)"
[[ "$numeric_retry_count" -eq 1 ]] \
  || fail "The recovered retry callback did not include numeric elapsed time"
! grep -Fq "event=airflow_task_failed dag_id=$DAG_ID" "$evidence_file" \
  || fail "A recovered transient failure emitted a terminal alert"
phase_complete "transient_retry_recovery" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
trigger_probe "$permanent_run_id" "$permanent_correlation" permanent failed
grep -Fq "correlationId=$permanent_correlation mode=permanent tryNumber=1" "$evidence_file" \
  || fail "The permanent first attempt was not observed"
grep -Fq "correlationId=$permanent_correlation mode=permanent tryNumber=2" "$evidence_file" \
  || fail "The permanent final attempt was not observed"
retry_count="$(grep -Fc "event=airflow_task_retry dag_id=$DAG_ID" "$evidence_file" || true)"
[[ "$retry_count" -eq 2 ]] || fail "Expected one retry callback per DAG run, got $retry_count total"
numeric_retry_count="$(grep -F "event=airflow_task_retry dag_id=$DAG_ID" "$evidence_file" \
  | grep -Ec "duration_ms=[0-9]+" || true)"
[[ "$numeric_retry_count" -eq 2 ]] \
  || fail "A retry callback omitted numeric elapsed time"
alert_count="$(grep -Fc "event=airflow_task_failed dag_id=$DAG_ID" "$evidence_file" || true)"
[[ "$alert_count" -eq 1 ]] || fail "Expected exactly one terminal alert, got $alert_count"
alert_line="$(grep -F "event=airflow_task_failed dag_id=$DAG_ID" "$evidence_file")"
grep -Fq "task_id=$TASK_ID" <<<"$alert_line" || fail "Terminal alert omitted task_id"
grep -Fq "try_number=2" <<<"$alert_line" || fail "Terminal alert omitted the final attempt"
grep -Eq "duration_ms=[0-9]+" <<<"$alert_line" \
  || fail "Terminal alert omitted numeric task duration"
grep -Fq "exception_class=AirflowException" <<<"$alert_line" \
  || fail "Terminal alert omitted the safe exception class"
! grep -Fq "$TERMINAL_DETAIL" <<<"$alert_line" \
  || fail "Terminal alert copied arbitrary exception detail"
phase_complete "permanent_failure_alert" "$phase_started_ms"

load_environment_file
assert_not_logged "$AIRFLOW_DB_PASSWORD" "Airflow database password"
assert_not_logged "$AIRFLOW_FERNET_KEY" "Airflow Fernet key"
assert_not_logged "$AIRFLOW_JWT_SECRET" "Airflow JWT secret"
assert_not_logged "$AIRFLOW_API_SECRET_KEY" "Airflow API secret"

log "event=airflow_retry_alert_suite_completed suiteRunId=$suite_run_id status=SUCCESS elapsedMs=$(( $(date +%s%3N) - started_ms )) evidence=$evidence_file"
