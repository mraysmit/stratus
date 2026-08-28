#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-23
# Purpose: prove accepted materialisation and fail-closed silver-to-gold Airflow outcomes.
source "$(dirname "$0")/../lib/airflow-spark-common.sh"

readonly LANDING_CONNECTION_ID="stratus_landing"
readonly LANDING_BUCKET_VARIABLE="stratus_landing_bucket"
readonly LANDING_BUCKET="stratus-landing"
readonly LANDING_DAG_ID="stratus_landing_to_bronze"
readonly BRONZE_TO_SILVER_DAG_ID="stratus_bronze_to_silver"
readonly DAG_ID="stratus_silver_to_gold"
readonly QUALITY_CLASS="dev.stratus.jobs.spark.QualityCheckJob"
readonly VERIFIER_CLASS="dev.stratus.jobs.spark.AirflowSilverToGoldVerifierJob"
readonly FIXTURE_SCRIPT="/opt/stratus/airflow-tests/airflow-pipeline-s3-fixture.py"
readonly EXPECTED_ROWS="3"
readonly EXPECTED_GROUPS="3"
readonly SPARK_EVENT_LOG_DIRECTORY="/opt/airflow/logs/spark-events"

mkdir -p "$HARNESS_DIR/evidence"
suite_run_id="airflow-silver-to-gold-$(date -u +%Y%m%dT%H%M%SZ)"
suite_token="$(printf '%s' "$suite_run_id" | tr '[:upper:]-' '[:lower:]_')"
evidence_file="$HARNESS_DIR/evidence/${suite_run_id}.log"
started_ms="$(date +%s%3N)"
logical_epoch="$(date +%s)"
airflow_started=false
fixture_staged=false
verification_attempted=false
current_expected_outcome="blocked"
current_landing_key=""
current_bronze_table=""
current_source_table=""
current_target_table=""
current_source_batch=""
current_bronze_quality_run_id=""
current_silver_pipeline_run_id=""
current_source_quality_run_id=""
current_pipeline_run_id=""
export STRATUS_RUN_ID="$suite_run_id"
export STRATUS_LOG_LEVEL="${STRATUS_LOG_LEVEL:-INFO}"
export STRATUS_BRONZE_TO_SILVER_RETRIES=0
export STRATUS_SILVER_TO_GOLD_RETRIES=0
exec > >(tee "$evidence_file") 2>&1

phase_complete() {
  local phase="$1" phase_started_ms="$2" scenario="${3:-suite}"
  log "event=airflow_silver_to_gold_phase_completed suiteRunId=$suite_run_id scenario=$scenario phase=$phase status=SUCCESS elapsedMs=$(( $(date +%s%3N) - phase_started_ms ))"
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

cleanup_run_ids() {
  printf '%s,%s,%s,%s' "$current_bronze_quality_run_id" \
    "$current_silver_pipeline_run_id" "$current_source_quality_run_id" \
    "$current_pipeline_run_id"
}

run_verifier() {
  local expected_outcome="$1" verifier_run_id="$2"
  run_spark_job "$VERIFIER_CLASS" \
    --bronzeTable "$current_bronze_table" \
    --sourceTable "$current_source_table" \
    --targetTable "$current_target_table" \
    --sourceQualityRunId "$current_source_quality_run_id" \
    --pipelineRunId "$current_pipeline_run_id" \
    --cleanupRunIds "$(cleanup_run_ids)" \
    --expectedSourceRows "$EXPECTED_ROWS" \
    --expectedGroups "$EXPECTED_GROUPS" \
    --expectedTotal "$EXPECTED_ROWS" \
    --expectedOutcome "$expected_outcome" \
    --runId "$verifier_run_id" \
    --cleanup true
}

cleanup() {
  local exit_code="$?"
  set +e
  if $airflow_started && [[ -n "$current_bronze_table" ]] && ! $verification_attempted; then
    run_verifier "$current_expected_outcome" "$suite_run_id-emergency-cleanup" >/dev/null 2>&1
  fi
  if $airflow_started && $fixture_staged; then
    compose exec -T airflow-scheduler python "$FIXTURE_SCRIPT" delete \
      --bucket "$LANDING_BUCKET" --key "$current_landing_key" >/dev/null 2>&1
  fi
  if $airflow_started; then
    bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-shutdown.sh"
  fi
  exit "$exit_code"
}
trap cleanup EXIT

configure_protected_connections() {
  verify_protected_connections
}

stage_silver() {
  local scenario="$1" logical_offset="$2" phase_started_ms dag_conf
  phase_started_ms="$(date +%s%3N)"
  current_landing_key="verification/$suite_run_id/$scenario/customers.csv"
  compose exec -T airflow-scheduler python "$FIXTURE_SCRIPT" put \
    --bucket "$LANDING_BUCKET" --key "$current_landing_key"
  fixture_staged=true
  dag_conf="{\"landing_bucket\":\"$LANDING_BUCKET\",\"landing_object_key\":\"$current_landing_key\",\"bronze_table\":\"$current_bronze_table\",\"pipeline_run_id\":\"$current_bronze_quality_run_id\"}"
  compose exec -T airflow-scheduler airflow dags test "$LANDING_DAG_ID" \
    "$(logical_date "$logical_offset")" --conf "$dag_conf"
  dag_conf="{\"bronze_table\":\"$current_bronze_table\",\"silver_table\":\"$current_source_table\",\"source_batch\":\"$current_source_batch\",\"quality_run_id\":\"$current_bronze_quality_run_id\",\"pipeline_run_id\":\"$current_silver_pipeline_run_id\"}"
  compose exec -T airflow-scheduler airflow dags test "$BRONZE_TO_SILVER_DAG_ID" \
    "$(logical_date "$((logical_offset + 1))")" --conf "$dag_conf"
  phase_complete "silver_seed_and_quality" "$phase_started_ms" "$scenario"
}

delete_fixture() {
  local scenario="$1" phase_started_ms
  phase_started_ms="$(date +%s%3N)"
  compose exec -T airflow-scheduler python "$FIXTURE_SCRIPT" delete \
    --bucket "$LANDING_BUCKET" --key "$current_landing_key"
  fixture_staged=false
  phase_complete "landing_cleanup" "$phase_started_ms" "$scenario"
}

configure_scenario() {
  local scenario="$1"
  current_bronze_table="stratus.bronze.airflow_pipeline_probe_${suite_token}_${scenario}"
  current_source_table="stratus.silver.airflow_pipeline_probe_${suite_token}_${scenario}"
  current_target_table="stratus.gold.airflow_pipeline_probe_${suite_token}_${scenario}"
  current_source_batch="$suite_run_id-$scenario-bronze"
  current_bronze_quality_run_id="$current_source_batch"
  current_silver_pipeline_run_id="$suite_run_id-$scenario-silver"
  current_source_quality_run_id="$suite_run_id-$scenario-gold-quality"
  current_pipeline_run_id="$suite_run_id-$scenario-gold"
  current_expected_outcome="$scenario"
  verification_attempted=false
}

log "event=airflow_silver_to_gold_suite_started suiteRunId=$suite_run_id dagId=$DAG_ID logLevel=$STRATUS_LOG_LEVEL"
require_spark_cluster

phase_started_ms="$(date +%s%3N)"
bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-startup.sh"
airflow_started=true
phase_complete "airflow_startup" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
configure_protected_connections
phase_complete "protected_connections" "$phase_started_ms"

configure_scenario accepted
stage_silver accepted 0
phase_started_ms="$(date +%s%3N)"
accepted_conf="{\"silver_table\":\"$current_source_table\",\"gold_table\":\"$current_target_table\",\"quality_run_id\":\"$current_source_quality_run_id\",\"pipeline_run_id\":\"$current_pipeline_run_id\"}"
compose exec -T airflow-scheduler airflow dags test "$DAG_ID" \
  "$(logical_date 2)" --conf "$accepted_conf"
phase_complete "accepted_dag_execution" "$phase_started_ms" accepted

phase_started_ms="$(date +%s%3N)"
verification_attempted=true
run_verifier accepted "$suite_run_id-accepted-verifier"
grep -Fq "AIRFLOW SILVER TO GOLD VERIFIED" "$evidence_file" \
  || fail "The accepted silver-to-gold verification marker is absent"
phase_complete "accepted_output_verification_and_cleanup" "$phase_started_ms" accepted
delete_fixture accepted
current_bronze_table=""

configure_scenario blocked
stage_silver blocked 3
phase_started_ms="$(date +%s%3N)"
blocking_checks_base64="$(printf '%s' '[{"name":"requires_four_silver_rows","type":"row_count_min","severity":"blocking","minRows":4}]' | base64 | tr -d '\r\n')"
run_spark_job "$QUALITY_CLASS" \
  --targetTable "$current_source_table" \
  --runId "$current_source_quality_run_id" \
  --pipelineRunId "$current_pipeline_run_id" \
  --checksBase64 "$blocking_checks_base64"
phase_complete "record_blocking_quality" "$phase_started_ms" blocked

phase_started_ms="$(date +%s%3N)"
blocked_conf="{\"silver_table\":\"$current_source_table\",\"gold_table\":\"$current_target_table\",\"quality_run_id\":\"$current_source_quality_run_id\",\"pipeline_run_id\":\"$current_pipeline_run_id\"}"
if compose exec -T airflow-scheduler airflow dags test "$DAG_ID" \
    "$(logical_date 5)" --conf "$blocked_conf"; then
  fail "The silver-to-gold DAG succeeded despite a blocking quality result"
else
  blocked_exit_code="$?"
  log "event=airflow_silver_to_gold_expected_failure suiteRunId=$suite_run_id scenario=blocked exitCode=$blocked_exit_code elapsedMs=$(( $(date +%s%3N) - phase_started_ms ))"
fi

phase_started_ms="$(date +%s%3N)"
verification_attempted=true
run_verifier blocked "$suite_run_id-blocked-verifier"
grep -Fq "AIRFLOW SILVER TO GOLD BLOCK VERIFIED" "$evidence_file" \
  || fail "The blocked silver-to-gold verification marker is absent"
phase_complete "blocked_no_write_verification_and_cleanup" "$phase_started_ms" blocked
delete_fixture blocked
current_bronze_table=""

load_environment_file
assert_not_logged "$AIRFLOW_DB_PASSWORD" "Airflow database password"
assert_not_logged "$AIRFLOW_FERNET_KEY" "Airflow Fernet key"
assert_not_logged "$AIRFLOW_JWT_SECRET" "Airflow JWT secret"
assert_not_logged "$AIRFLOW_API_SECRET_KEY" "Airflow API secret"
assert_not_logged "$AIRFLOW_SPARK_RGW_ACCESS_KEY" "Spark RGW access key"
assert_not_logged "$AIRFLOW_SPARK_RGW_SECRET_KEY" "Spark RGW secret key"
assert_not_logged "$AIRFLOW_LANDING_RGW_ACCESS_KEY" "Airflow landing RGW access key"
assert_not_logged "$AIRFLOW_LANDING_RGW_SECRET_KEY" "Airflow landing RGW secret key"

log "event=airflow_silver_to_gold_suite_completed suiteRunId=$suite_run_id status=SUCCESS elapsedMs=$(( $(date +%s%3N) - started_ms )) evidence=$evidence_file"
