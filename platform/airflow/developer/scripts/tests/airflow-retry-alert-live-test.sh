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
export AIRFLOW_COMPOSE_OVERLAY="$PROBE_OVERLAY"

suite_run_id="airflow-retry-alert-$(date -u +%Y%m%dT%H%M%SZ)"
transient_correlation="$suite_run_id-transient"
permanent_correlation="$suite_run_id-permanent"
logical_epoch="$(date +%s)"
started_ms="$(date +%s%3N)"
airflow_started=false

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

logical_date() {
  local offset_seconds="$1"
  date -u -d "@$(( logical_epoch + offset_seconds ))" +%Y-%m-%dT%H:%M:%S+00:00
}

cleanup() {
  local exit_code="$?"
  set +e
  if $airflow_started; then
    bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-shutdown.sh"
  fi
  exit "$exit_code"
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
bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-startup.sh"
airflow_started=true
wait_for_probe_dag
phase_complete "airflow_startup_and_probe_registration" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
transient_conf="{\"mode\":\"transient\",\"correlation_id\":\"$transient_correlation\"}"
compose exec -T airflow-scheduler airflow dags test "$DAG_ID" \
  "$(logical_date 0)" --conf "$transient_conf"
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
permanent_conf="{\"mode\":\"permanent\",\"correlation_id\":\"$permanent_correlation\"}"
set +e
compose exec -T airflow-scheduler airflow dags test "$DAG_ID" \
  "$(logical_date 1)" --conf "$permanent_conf"
permanent_status="$?"
set -e
[[ "$permanent_status" -ne 0 ]] || fail "The permanent probe unexpectedly succeeded"
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
