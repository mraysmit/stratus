#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-23
# Purpose: prove both accepted and fail-closed bronze-to-silver Airflow outcomes.
source "$(dirname "$0")/../lib/airflow-spark-common.sh"

readonly LANDING_CONNECTION_ID="stratus_landing"
readonly LANDING_BUCKET_VARIABLE="stratus_landing_bucket"
readonly LANDING_BUCKET="stratus-landing"
readonly LANDING_DAG_ID="stratus_landing_to_bronze"
readonly DAG_ID="stratus_bronze_to_silver"
readonly VERIFIER_CLASS="dev.stratus.jobs.spark.AirflowBronzeToSilverVerifierJob"
readonly CATALOG_STATE_CLASS="dev.stratus.jobs.spark.CatalogTableStateJob"
readonly FIXTURE_SCRIPT="/opt/stratus/airflow-tests/airflow-pipeline-s3-fixture.py"
readonly EXPECTED_ROWS="3"
readonly SPARK_EVENT_LOG_DIRECTORY="/opt/airflow/logs/spark-events"

mkdir -p "$HARNESS_DIR/evidence"
suite_run_id="airflow-bronze-to-silver-$(date -u +%Y%m%dT%H%M%SZ)"
suite_token="$(printf '%s' "$suite_run_id" | tr '[:upper:]-' '[:lower:]_')"
evidence_file="$HARNESS_DIR/evidence/${suite_run_id}.log"
started_ms="$(date +%s%3N)"
logical_epoch="$(date +%s)"
airflow_started=false
airflow_owned=false
fixture_staged=false
current_landing_key=""
current_source_table=""
current_target_table=""
current_source_batch=""
current_source_quality_run_id=""
current_pipeline_run_id=""
current_expected_outcome="blocked"
verification_attempted=false
export STRATUS_RUN_ID="$suite_run_id"
export STRATUS_LOG_LEVEL="${STRATUS_LOG_LEVEL:-INFO}"
export STRATUS_BRONZE_TO_SILVER_RETRIES=0
exec > >(tee "$evidence_file") 2>&1

phase_complete() {
  local phase="$1" phase_started_ms="$2" scenario="${3:-suite}"
  log "event=airflow_bronze_to_silver_phase_completed suiteRunId=$suite_run_id scenario=$scenario phase=$phase status=SUCCESS elapsedMs=$(( $(date +%s%3N) - phase_started_ms ))"
}

assert_not_logged() {
  local value="$1" label="$2"
  [[ -z "$value" ]] && return 0
  ! grep -Fq -- "$value" "$evidence_file" \
    || fail "Secret-redaction check failed for $label"
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
  local expected_outcome="$1" verifier_run_id="$2"
  run_spark_job "$VERIFIER_CLASS" \
    --sourceTable "$current_source_table" \
    --targetTable "$current_target_table" \
    --sourceBatch "$current_source_batch" \
    --sourceQualityRunId "$current_source_quality_run_id" \
    --pipelineRunId "$current_pipeline_run_id" \
    --expectedRows "$EXPECTED_ROWS" \
    --expectedOutcome "$expected_outcome" \
    --runId "$verifier_run_id" \
    --cleanup true
}

cleanup() {
  local exit_code="$?"
  set +e
  if $airflow_started && [[ "$current_expected_outcome" == "accepted" ]] \
      && [[ -n "$current_source_table" ]] && ! $verification_attempted; then
    run_verifier "$current_expected_outcome" "$suite_run_id-emergency-cleanup" >/dev/null 2>&1
  fi
  if $airflow_started && $fixture_staged; then
    compose exec -T airflow-scheduler python "$FIXTURE_SCRIPT" delete \
      --bucket "$LANDING_BUCKET" --key "$current_landing_key" >/dev/null 2>&1
  fi
  if $airflow_owned; then
    bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-shutdown.sh"
  fi
  exit "$exit_code"
}

verify_catalog_table_absent() {
  compose exec -T airflow-scheduler java \
    -Djavax.net.ssl.trustStore=/opt/stratus/certs/stratus-truststore.jks \
    -cp '/opt/stratus/jobs/stratus-spark-jobs.jar:/opt/stratus/runtime/stratus-iceberg-aws-runtime.jar:/opt/spark/jars/*' \
    "$CATALOG_STATE_CLASS" \
    --table "$current_target_table" \
    --expectedState absent \
    --catalogProperties /opt/stratus/spark-conf/spark-defaults.conf \
    --runId "$suite_run_id-blocked-catalog-verifier"
}
trap cleanup EXIT

configure_protected_connections() {
  verify_protected_connections
}

stage_bronze() {
  local scenario="$1" logical_offset="$2"
  local phase_started_ms
  phase_started_ms="$(date +%s%3N)"
  current_landing_key="verification/$suite_run_id/$scenario/customers.csv"
  compose exec -T airflow-scheduler python "$FIXTURE_SCRIPT" put \
    --bucket "$LANDING_BUCKET" --key "$current_landing_key"
  fixture_staged=true
  local dag_conf
  dag_conf="{\"landing_bucket\":\"$LANDING_BUCKET\",\"landing_object_key\":\"$current_landing_key\",\"bronze_table\":\"$current_source_table\",\"pipeline_run_id\":\"$current_source_quality_run_id\"}"
  compose exec -T airflow-scheduler airflow dags test "$LANDING_DAG_ID" \
    "$(logical_date "$logical_offset")" --conf "$dag_conf"
  phase_complete "bronze_seed_and_quality" "$phase_started_ms" "$scenario"
}

delete_fixture() {
  local scenario="$1" phase_started_ms
  phase_started_ms="$(date +%s%3N)"
  compose exec -T airflow-scheduler python "$FIXTURE_SCRIPT" delete \
    --bucket "$LANDING_BUCKET" --key "$current_landing_key"
  fixture_staged=false
  phase_complete "landing_cleanup" "$phase_started_ms" "$scenario"
}

log "event=airflow_bronze_to_silver_suite_started suiteRunId=$suite_run_id dagId=$DAG_ID logLevel=$STRATUS_LOG_LEVEL"
require_spark_cluster

phase_started_ms="$(date +%s%3N)"
if suite_owns_airflow; then
  bash "$HARNESS_DIR/scripts/tests/airflow-compose-verify-health.sh"
  log "event=airflow_bronze_to_silver_suite_airflow_reused suiteRunId=$suite_run_id"
else
  bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-startup.sh"
  airflow_owned=true
fi
airflow_started=true
phase_complete "airflow_startup" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
configure_protected_connections
phase_complete "protected_connections" "$phase_started_ms"

# Accepted path: the landing DAG records passing bronze evidence, TransformJob consumes that exact
# run, and the independent verifier inspects both the conformed table and silver quality history.
current_source_table="stratus.bronze.airflow_pipeline_probe_${suite_token}_accepted"
current_target_table="stratus.silver.airflow_pipeline_probe_${suite_token}_accepted"
current_source_batch="$suite_run_id-accepted-source"
current_source_quality_run_id="$current_source_batch"
current_pipeline_run_id="$suite_run_id-accepted-promotion"
current_expected_outcome="accepted"
verification_attempted=false
stage_bronze "accepted" 0

phase_started_ms="$(date +%s%3N)"
accepted_conf="{\"bronze_table\":\"$current_source_table\",\"silver_table\":\"$current_target_table\",\"source_batch\":\"$current_source_batch\",\"quality_run_id\":\"$current_source_quality_run_id\",\"pipeline_run_id\":\"$current_pipeline_run_id\"}"
compose exec -T airflow-scheduler airflow dags test "$DAG_ID" \
  "$(logical_date 1)" --conf "$accepted_conf"
phase_complete "accepted_dag_execution" "$phase_started_ms" "accepted"

phase_started_ms="$(date +%s%3N)"
verification_attempted=true
run_verifier accepted "$suite_run_id-accepted-verifier"
grep -Fq "AIRFLOW BRONZE TO SILVER VERIFIED" "$evidence_file" \
  || fail "The accepted bronze-to-silver verification marker is absent"
phase_complete "accepted_output_verification_and_cleanup" "$phase_started_ms" "accepted"
delete_fixture "accepted"
current_source_table=""

# Blocked path: an unknown evidence run must fail closed before any Spark writer starts. This
# isolates the V2 Airflow boundary without repeating ingestion and quality work already covered by
# the accepted path and the suite-scoped Spark integration tests.
current_source_table="stratus.bronze.airflow_pipeline_probe_${suite_token}_missing"
current_target_table="stratus.silver.airflow_pipeline_probe_${suite_token}_blocked"
current_source_batch="$suite_run_id-blocked-missing-source"
current_source_quality_run_id="$suite_run_id-blocked-no-evidence"
current_pipeline_run_id="$suite_run_id-blocked-promotion"
current_expected_outcome="blocked"
verification_attempted=false

phase_started_ms="$(date +%s%3N)"
blocked_conf="{\"bronze_table\":\"$current_source_table\",\"silver_table\":\"$current_target_table\",\"source_batch\":\"$current_source_batch\",\"quality_run_id\":\"$current_source_quality_run_id\",\"pipeline_run_id\":\"$current_pipeline_run_id\"}"
if compose exec -T airflow-scheduler airflow dags test "$DAG_ID" \
    "$(logical_date 3)" --conf "$blocked_conf"; then
  fail "The bronze-to-silver DAG succeeded despite a blocking quality result"
else
  blocked_exit_code="$?"
  log "event=airflow_bronze_to_silver_expected_failure suiteRunId=$suite_run_id scenario=blocked exitCode=$blocked_exit_code elapsedMs=$(( $(date +%s%3N) - phase_started_ms ))"
fi

phase_started_ms="$(date +%s%3N)"
verification_attempted=true
verify_catalog_table_absent
grep -Fq "CATALOG TABLE STATE VERIFIED" "$evidence_file" \
  || fail "The blocked bronze-to-silver catalog verification marker is absent"
phase_complete "blocked_no_write_verification" "$phase_started_ms" "blocked"
current_source_table=""

load_environment_file
assert_not_logged "$AIRFLOW_DB_PASSWORD" "Airflow database password"
assert_not_logged "$AIRFLOW_FERNET_KEY" "Airflow Fernet key"
assert_not_logged "$AIRFLOW_JWT_SECRET" "Airflow JWT secret"
assert_not_logged "$AIRFLOW_API_SECRET_KEY" "Airflow API secret"
assert_not_logged "$AIRFLOW_SPARK_RGW_ACCESS_KEY" "Spark RGW access key"
assert_not_logged "$AIRFLOW_SPARK_RGW_SECRET_KEY" "Spark RGW secret key"
assert_not_logged "$AIRFLOW_LANDING_RGW_ACCESS_KEY" "Airflow landing RGW access key"
assert_not_logged "$AIRFLOW_LANDING_RGW_SECRET_KEY" "Airflow landing RGW secret key"

log "event=airflow_bronze_to_silver_suite_completed suiteRunId=$suite_run_id status=SUCCESS elapsedMs=$(( $(date +%s%3N) - started_ms )) evidence=$evidence_file"
