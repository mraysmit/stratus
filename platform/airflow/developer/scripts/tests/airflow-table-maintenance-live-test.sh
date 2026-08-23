#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-23
# Purpose: prove metadata-policy maintenance skip and run outcomes through Airflow.
source "$(dirname "$0")/../lib/airflow-spark-common.sh"

readonly DAG_ID="stratus_table_maintenance"
readonly VERIFIER_CLASS="dev.stratus.jobs.spark.AirflowTableMaintenanceVerifierJob"
readonly SKIP_POLICY="development-skip-v1"
readonly RUN_POLICY="development-run-v1"
readonly EXPECTED_ROWS="3"
readonly EXPECTED_SEEDED_FILES="3"
readonly EXPECTED_COMPACTED_FILES="1"
readonly SPARK_EVENT_LOG_DIRECTORY="/opt/airflow/logs/spark-events"

mkdir -p "$HARNESS_DIR/evidence"
suite_run_id="airflow-table-maintenance-$(date -u +%Y%m%dT%H%M%SZ)"
suite_token="$(printf '%s' "$suite_run_id" | tr '[:upper:]-' '[:lower:]_')"
target_table="stratus.bronze.airflow_maintenance_probe_${suite_token}"
evidence_file="$HARNESS_DIR/evidence/${suite_run_id}.log"
started_ms="$(date +%s%3N)"
logical_epoch="$(date +%s)"
airflow_started=false
fixture_seeded=false
export STRATUS_RUN_ID="$suite_run_id"
export STRATUS_LOG_LEVEL="${STRATUS_LOG_LEVEL:-DEBUG}"
exec > >(tee "$evidence_file") 2>&1

phase_complete() {
  local phase="$1" phase_started_ms="$2"
  log "event=airflow_table_maintenance_phase_completed suiteRunId=$suite_run_id phase=$phase status=SUCCESS elapsedMs=$(( $(date +%s%3N) - phase_started_ms ))"
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

run_spark_job() {
  local java_class="$1"
  shift
  compose exec -T airflow-scheduler mkdir -p "$SPARK_EVENT_LOG_DIRECTORY"
  compose exec -T airflow-scheduler spark-submit \
    --master spark://spark-master.stratus.local:7077 \
    --class "$java_class" \
    --conf spark.driver.host=airflow-scheduler.stratus.local \
    --conf spark.driver.bindAddress=0.0.0.0 \
    --conf spark.driver.extraClassPath=/opt/stratus/runtime/stratus-iceberg-aws-runtime.jar:/opt/stratus/runtime/hadoop-aws.jar:/opt/stratus/runtime/aws-sdk-bundle.jar:/opt/stratus/runtime/analyticsaccelerator-s3.jar:/opt/stratus/runtime/log4j-slf4j-impl.jar \
    --conf spark.eventLog.dir=file://$SPARK_EVENT_LOG_DIRECTORY \
    --conf spark.cores.max=2 \
    --conf spark.executor.cores=1 \
    /opt/stratus/jobs/stratus-spark-jobs.jar "$@"
}

run_verifier() {
  local mode="$1"
  shift
  run_spark_job "$VERIFIER_CLASS" \
    --mode "$mode" \
    --targetTable "$target_table" \
    --runId "$suite_run_id-$mode" "$@"
}

cleanup() {
  local exit_code="$?"
  set +e
  if $airflow_started && $fixture_seeded; then
    run_verifier cleanup >/dev/null 2>&1
  fi
  if $airflow_started; then
    bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-shutdown.sh"
  fi
  exit "$exit_code"
}
trap cleanup EXIT

configure_protected_connection() {
  compose exec -T airflow-scheduler airflow connections delete spark_default >/dev/null 2>&1 || true
  compose exec -T airflow-scheduler airflow connections add spark_default \
    --conn-type spark --conn-host spark://spark-master.stratus.local --conn-port 7077 >/dev/null
}

log "event=airflow_table_maintenance_suite_started suiteRunId=$suite_run_id dagId=$DAG_ID table=$target_table logLevel=$STRATUS_LOG_LEVEL"
require_spark_cluster

phase_started_ms="$(date +%s%3N)"
bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-startup.sh"
airflow_started=true
phase_complete "airflow_startup" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
configure_protected_connection
phase_complete "protected_connection" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
run_verifier seed
fixture_seeded=true
grep -Fq "AIRFLOW TABLE MAINTENANCE FIXTURE READY" "$evidence_file" \
  || fail "The maintenance fixture marker is absent"
phase_complete "seed_three_small_files" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
skip_conf="{\"target_table\":\"$target_table\",\"policy\":\"$SKIP_POLICY\",\"run_id\":\"$suite_run_id-skip\"}"
compose exec -T airflow-scheduler airflow dags test "$DAG_ID" \
  "$(logical_date 0)" --conf "$skip_conf"
grep -Fq "TABLE MAINTENANCE action=SKIP" "$evidence_file" \
  || fail "The policy did not record an explicit maintenance skip"
phase_complete "skip_policy_execution" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
run_verifier verify-skip \
  --expectedRows "$EXPECTED_ROWS" --expectedFiles "$EXPECTED_SEEDED_FILES"
grep -Fq "AIRFLOW TABLE MAINTENANCE SKIP VERIFIED" "$evidence_file" \
  || fail "The independent maintenance skip marker is absent"
phase_complete "skip_verification" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
run_conf="{\"target_table\":\"$target_table\",\"policy\":\"$RUN_POLICY\",\"run_id\":\"$suite_run_id-run\"}"
compose exec -T airflow-scheduler airflow dags test "$DAG_ID" \
  "$(logical_date 1)" --conf "$run_conf"
grep -Fq "TABLE MAINTENANCE action=RUN" "$evidence_file" \
  || fail "The policy did not record an explicit maintenance run"
phase_complete "run_policy_execution" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
run_verifier verify-run \
  --expectedRows "$EXPECTED_ROWS" --expectedFiles "$EXPECTED_COMPACTED_FILES"
grep -Fq "AIRFLOW TABLE MAINTENANCE RUN VERIFIED" "$evidence_file" \
  || fail "The independent maintenance run marker is absent"
phase_complete "run_verification" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
run_verifier cleanup
fixture_seeded=false
grep -Fq "AIRFLOW TABLE MAINTENANCE CLEANUP COMPLETE" "$evidence_file" \
  || fail "The exact maintenance cleanup marker is absent"
phase_complete "exact_cleanup" "$phase_started_ms"

load_environment_file
assert_not_logged "$AIRFLOW_DB_PASSWORD" "Airflow database password"
assert_not_logged "$AIRFLOW_FERNET_KEY" "Airflow Fernet key"
assert_not_logged "$AIRFLOW_JWT_SECRET" "Airflow JWT secret"
assert_not_logged "$AIRFLOW_API_SECRET_KEY" "Airflow API secret"
assert_not_logged "$AIRFLOW_SPARK_RGW_ACCESS_KEY" "Spark RGW access key"
assert_not_logged "$AIRFLOW_SPARK_RGW_SECRET_KEY" "Spark RGW secret key"
assert_not_logged "$AIRFLOW_LANDING_RGW_ACCESS_KEY" "Airflow landing RGW access key"
assert_not_logged "$AIRFLOW_LANDING_RGW_SECRET_KEY" "Airflow landing RGW secret key"

log "event=airflow_table_maintenance_suite_completed suiteRunId=$suite_run_id status=SUCCESS elapsedMs=$(( $(date +%s%3N) - started_ms )) evidence=$evidence_file"
