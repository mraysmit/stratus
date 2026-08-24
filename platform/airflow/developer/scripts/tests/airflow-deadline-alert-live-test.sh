#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-24
# Purpose: prove one on-time DAG run and one real Airflow Deadline Alert callback.
source "$(dirname "$0")/../lib/airflow-compose-common.sh"

readonly DAG_ID_PREFIX="stratus_deadline_alert_probe"
readonly TASK_ID="exercise_deadline_contract"
readonly DEADLINE_NAME="stratus-development-dag-deadline"
readonly PROBE_OVERLAY="$HARNESS_DIR/scripts/tests/compose.deadline-alert.yaml"
readonly RUN_STATE_DEADLINE_SECONDS=90
readonly SCHEDULER_HEARTBEAT_SECONDS=2
export AIRFLOW_COMPOSE_OVERLAY="$PROBE_OVERLAY"

suite_run_id="airflow-deadline-alert-$(date -u +%Y%m%dT%H%M%SZ)"
DAG_ID="${DAG_ID_PREFIX}_${suite_run_id#airflow-deadline-alert-}_$$"
export STRATUS_DEADLINE_PROBE_DAG_ID="$DAG_ID"
on_time_run_id="$suite_run_id-on-time"
missed_run_id="$suite_run_id-missed"
on_time_correlation="$on_time_run_id-correlation"
missed_correlation="$missed_run_id-correlation"
started_ms="$(date +%s%3N)"
airflow_started=false
current_run_id=""

mkdir -p "$HARNESS_DIR/evidence"
evidence_file="$HARNESS_DIR/evidence/${suite_run_id}.log"
exec > >(tee "$evidence_file") 2>&1

phase_complete() {
  local phase="$1" phase_started_ms="$2"
  log "event=airflow_deadline_alert_phase_completed suiteRunId=$suite_run_id phase=$phase status=SUCCESS elapsedMs=$(( $(date +%s%3N) - phase_started_ms ))"
}

assert_not_logged() {
  local value="$1" label="$2"
  [[ -z "$value" ]] && return 0
  ! grep -Fq -- "$value" "$evidence_file" || fail "Secret-redaction check failed for $label"
}

capture_failure_diagnostics() {
  log "DEADLINE ALERT FAILURE DIAGNOSTICS runId=${current_run_id:-unavailable}"
  compose ps || true
  compose logs --no-color airflow-scheduler airflow-triggerer airflow-api-server \
    airflow-dag-processor || true
  if [[ -n "$current_run_id" ]]; then
    compose exec -T airflow-scheduler sh -c \
      "find /opt/airflow/logs -type f -path '*run_id=$current_run_id*' -exec tail -n 200 {} +" || true
  fi
}

cleanup() {
  local exit_code="$?"
  set +e
  if $airflow_started; then
    if [[ "$exit_code" -ne 0 ]]; then
      capture_failure_diagnostics
    fi
    # The per-run DAG ID avoids Airflow 3.3.1 reusing a DeadlineAlert reference whose
    # metadata row belongs to an older serialized DAG version. Delete only this test-owned
    # identity so repeated proofs remain isolated without resetting the development database.
    compose exec -T airflow-scheduler airflow dags delete "$DAG_ID" -y >/dev/null 2>&1 || true
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
      log "DEADLINE ALERT PROBE REGISTERED dagId=$DAG_ID attempts=$attempt"
      return 0
    fi
    sleep 1
  done
  fail "Airflow did not register the test-only Deadline Alert probe DAG"
}

wait_for_run_state() {
  local run_id="$1" deadline=$(( SECONDS + RUN_STATE_DEADLINE_SECONDS )) runs
  while (( SECONDS < deadline )); do
    runs="$(compose exec -T airflow-scheduler airflow dags list-runs "$DAG_ID" \
      --state success --output json 2>&1 || true)"
    if grep -Fq "$run_id" <<<"$runs"; then
      log "DEADLINE ALERT PROBE RUN COMPLETED dagId=$DAG_ID runId=$run_id state=success"
      return 0
    fi
    runs="$(compose exec -T airflow-scheduler airflow dags list-runs "$DAG_ID" \
      --state failed --output json 2>&1 || true)"
    ! grep -Fq "$run_id" <<<"$runs" || fail "Deadline Alert probe run failed: $run_id"
    sleep 1
  done
  fail "Deadline Alert probe run did not complete within ${RUN_STATE_DEADLINE_SECONDS}s: $run_id"
}

trigger_probe() {
  local run_id="$1" correlation_id="$2" mode="$3" sleep_seconds="$4" conf
  conf="{\"correlation_id\":\"$correlation_id\",\"mode\":\"$mode\",\"sleep_seconds\":$sleep_seconds}"
  current_run_id="$run_id"
  compose exec -T airflow-scheduler airflow dags trigger "$DAG_ID" \
    --run-id "$run_id" --conf "$conf" --output json
  wait_for_run_state "$run_id"
}

log "event=airflow_deadline_alert_suite_started suiteRunId=$suite_run_id dagId=$DAG_ID taskId=$TASK_ID schedulerHeartbeatSeconds=$SCHEDULER_HEARTBEAT_SECONDS"

phase_started_ms="$(date +%s%3N)"
bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-startup.sh"
airflow_started=true
wait_for_probe_dag
phase_complete "airflow_startup_and_probe_registration" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
trigger_probe "$on_time_run_id" "$on_time_correlation" on-time 1
phase_complete "on_time_completion" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
trigger_probe "$missed_run_id" "$missed_correlation" missed 35
phase_complete "missed_deadline_completion" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
triggerer_logs="$(compose logs --no-color airflow-triggerer 2>&1)"
printf '%s\n' "$triggerer_logs"
! grep -F "event=airflow_deadline_missed" <<<"$triggerer_logs" \
  | grep -Fq "run_id=$on_time_run_id" \
  || fail "The on-time run emitted a Deadline Alert"
deadline_alert_count="$(grep -F "event=airflow_deadline_missed" <<<"$triggerer_logs" \
  | grep -Fc "run_id=$missed_run_id" || true)"
[[ "$deadline_alert_count" -eq 1 ]] \
  || fail "Expected exactly one missed-deadline callback, got $deadline_alert_count"
deadline_alert_line="$(grep -F "event=airflow_deadline_missed" <<<"$triggerer_logs" \
  | grep -F "run_id=$missed_run_id")"
grep -Fq "correlation_id=$missed_correlation" <<<"$deadline_alert_line" \
  || fail "Deadline Alert omitted its correlation ID"
grep -Fq "deadline_name=$DEADLINE_NAME" <<<"$deadline_alert_line" \
  || fail "Deadline Alert omitted its stable name"
grep -Fq "expected_interval_ms=12000" <<<"$deadline_alert_line" \
  || fail "Deadline Alert omitted its expected interval"
grep -Eq "observed_elapsed_ms=[0-9]+" <<<"$deadline_alert_line" \
  || fail "Deadline Alert omitted numeric observed elapsed time"
grep -Eq "breach_ms=[0-9]+" <<<"$deadline_alert_line" \
  || fail "Deadline Alert omitted numeric breach time"
phase_complete "deadline_callback_observability" "$phase_started_ms"

load_environment_file
assert_not_logged "$AIRFLOW_DB_PASSWORD" "Airflow database password"
assert_not_logged "$AIRFLOW_FERNET_KEY" "Airflow Fernet key"
assert_not_logged "$AIRFLOW_JWT_SECRET" "Airflow JWT secret"
assert_not_logged "$AIRFLOW_API_SECRET_KEY" "Airflow API secret"

log "event=airflow_deadline_alert_suite_completed suiteRunId=$suite_run_id status=SUCCESS elapsedMs=$(( $(date +%s%3N) - started_ms )) evidence=$evidence_file"
