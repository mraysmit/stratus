#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-29
# Purpose: prove accepted and naturally blocked silver-to-gold behavior through the real scheduler.
#
# The focused harness creates both silver boundary fixtures in one Spark application. The blocked
# fixture contains a duplicate customer key, so the production DAG's own uniqueness rule records
# the failure. Airflow is exercised through its public API, catalog assertions run without Spark,
# and one final Spark application removes the exact quality rows and generated tables. The hard
# application budget is therefore six: prepare 1, product DAGs 4, verification 0, cleanup 1.
source "$(dirname "$0")/../lib/airflow-spark-common.sh"

readonly DAG_ID="stratus_silver_to_gold"
readonly FIXTURE_CLASS="dev.stratus.jobs.spark.AirflowSilverToGoldFixtureJob"
readonly CATALOG_VERIFIER_CLASS="dev.stratus.jobs.spark.CatalogSilverToGoldVerifierJob"
readonly EXPECTED_ROWS="3"
readonly EXPECTED_COUNTRIES="GB,NL,US"
readonly EXPECTED_SPARK_APPLICATIONS="6"
readonly SPARK_EVENT_LOG_DIRECTORY="/opt/airflow/logs/spark-events"
readonly REPOSITORY_DIR="$REPO_DIR"

mkdir -p "$HARNESS_DIR/evidence"
suite_run_id="airflow-silver-to-gold-$(date -u +%Y%m%dT%H%M%SZ)"
suite_token="$(printf '%s' "$suite_run_id" | tr '[:upper:]-' '[:lower:]_')"
evidence_file="$HARNESS_DIR/evidence/${suite_run_id}.log"
started_ms="$(date +%s%3N)"
accepted_source_table="stratus.silver.airflow_pipeline_probe_${suite_token}_accepted"
accepted_target_table="stratus.gold.airflow_pipeline_probe_${suite_token}_accepted"
blocked_source_table="stratus.silver.airflow_pipeline_probe_${suite_token}_blocked"
blocked_target_table="stratus.gold.airflow_pipeline_probe_${suite_token}_blocked"
accepted_quality_run_id="$suite_run_id-accepted-silver-quality"
accepted_pipeline_run_id="$suite_run_id-accepted-gold"
blocked_quality_run_id="$suite_run_id-blocked-silver-quality"
blocked_pipeline_run_id="$suite_run_id-blocked-gold"
accepted_airflow_run_id="$suite_run_id-accepted-dag"
blocked_airflow_run_id="$suite_run_id-blocked-dag"
cleanup_run_ids="$accepted_quality_run_id,$accepted_pipeline_run_id,$blocked_quality_run_id,$blocked_pipeline_run_id"
airflow_started=false
airflow_owned=false
fixture_prepared=false
spark_applications_before=0
export STRATUS_RUN_ID="$suite_run_id"
export STRATUS_LOG_LEVEL="${STRATUS_LOG_LEVEL:-INFO}"
export STRATUS_SILVER_TO_GOLD_RETRIES=0
exec > >(tee "$evidence_file") 2>&1

phase_complete() {
  local phase="$1" phase_started_ms="$2"
  log "event=airflow_silver_to_gold_phase_completed suiteRunId=$suite_run_id phase=$phase status=SUCCESS elapsedMs=$(( $(date +%s%3N) - phase_started_ms ))"
}

assert_not_logged() {
  local value="$1" label="$2"
  [[ -z "$value" ]] && return 0
  ! grep -Fq -- "$value" "$evidence_file" || fail "Secret-redaction check failed for $label"
}

run_repository_maven() {
  if [[ -n "${MSYSTEM:-}" ]]; then
    (cd "$REPOSITORY_DIR" && MSYS_NO_PATHCONV=1 cmd.exe /d /c mvnw.cmd "$@")
  elif [[ -n "${WSL_DISTRO_NAME:-}" ]]; then
    (cd "$REPOSITORY_DIR" && ./mvnw "$@")
  elif [[ "${OS:-}" == "Windows_NT" ]]; then
    (cd "$REPOSITORY_DIR" && ./mvnw.cmd "$@")
  else
    (cd "$REPOSITORY_DIR" && ./mvnw "$@")
  fi
}

run_spark_job() {
  local java_class="$1" application_name="$2"
  shift 2
  compose exec -T airflow-scheduler mkdir -p "$SPARK_EVENT_LOG_DIRECTORY"
  compose exec -T airflow-scheduler spark-submit \
    --master spark://spark-master.stratus.local:7077 \
    --name "$application_name" \
    --class "$java_class" \
    --conf spark.driver.host=airflow-scheduler.stratus.local \
    --conf spark.driver.bindAddress=0.0.0.0 \
    --conf spark.driver.extraClassPath=/opt/stratus/runtime/stratus-iceberg-aws-runtime.jar:/opt/stratus/runtime/hadoop-aws.jar:/opt/stratus/runtime/aws-sdk-bundle.jar:/opt/stratus/runtime/analyticsaccelerator-s3.jar:/opt/stratus/runtime/log4j-slf4j-impl.jar \
    --conf spark.eventLog.dir=file://$SPARK_EVENT_LOG_DIRECTORY \
    --conf spark.cores.max=2 \
    --conf spark.executor.cores=1 \
    /opt/stratus/jobs/stratus-spark-jobs.jar "$@"
}

spark_application_count() {
  curl --silent --show-error --fail --max-time 20 http://127.0.0.1:8090/json/ \
    | awk '/"id" : "app-/{count++} END{print count+0}'
}

run_fixture() {
  local mode="$1"
  run_spark_job "$FIXTURE_CLASS" \
    "stratus-airflow-silver-to-gold-fixture-$mode-$suite_run_id" \
    --mode "$mode" \
    --acceptedSourceTable "$accepted_source_table" \
    --acceptedTargetTable "$accepted_target_table" \
    --blockedSourceTable "$blocked_source_table" \
    --blockedTargetTable "$blocked_target_table" \
    --cleanupRunIds "$cleanup_run_ids" \
    --runId "$suite_run_id-fixture-$mode"
}

cleanup() {
  local original_exit_code="$?" final_exit_code
  final_exit_code="$original_exit_code"
  set +e
  if $airflow_started && $fixture_prepared; then
    if ! run_fixture cleanup >/dev/null 2>&1; then
      log "event=airflow_silver_to_gold_cleanup_failed suiteRunId=$suite_run_id resource=fixtures"
      final_exit_code=1
    fi
  fi
  if $airflow_owned; then
    if ! bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-shutdown.sh"; then
      final_exit_code=1
    fi
  fi
  exit "$final_exit_code"
}
trap cleanup EXIT

run_scheduler_scenarios() {
  local accepted_conf blocked_conf scenarios_json scenarios_base64
  accepted_conf="{\"silver_table\":\"$accepted_source_table\",\"gold_table\":\"$accepted_target_table\",\"quality_run_id\":\"$accepted_quality_run_id\",\"pipeline_run_id\":\"$accepted_pipeline_run_id\"}"
  blocked_conf="{\"silver_table\":\"$blocked_source_table\",\"gold_table\":\"$blocked_target_table\",\"quality_run_id\":\"$blocked_quality_run_id\",\"pipeline_run_id\":\"$blocked_pipeline_run_id\"}"
  scenarios_json="[{\"name\":\"accepted-silver-to-gold\",\"dagId\":\"$DAG_ID\",\"runId\":\"$accepted_airflow_run_id\",\"expectedRunState\":\"success\",\"configuration\":$accepted_conf,\"expectedTaskStates\":{\"run_silver_quality_for_gold\":\"success\",\"evaluate_silver_promotion\":\"success\",\"run_gold_materialisation\":\"success\",\"run_gold_quality\":\"success\"},\"expectedSparkApplications\":3},{\"name\":\"blocked-silver-to-gold\",\"dagId\":\"$DAG_ID\",\"runId\":\"$blocked_airflow_run_id\",\"expectedRunState\":\"failed\",\"configuration\":$blocked_conf,\"expectedTaskStates\":{\"run_silver_quality_for_gold\":\"success\",\"evaluate_silver_promotion\":\"failed\",\"run_gold_materialisation\":\"upstream_failed\",\"run_gold_quality\":\"upstream_failed\"},\"expectedSparkApplications\":1}]"
  scenarios_base64="$(printf '%s' "$scenarios_json" | base64 | tr -d '\r\n')"

  run_repository_maven -o test \
    -Porchestration-integration-tests -pl :stratus-orchestration-verifier -am \
    -DSTRATUS_AIRFLOW_BASE_URL=http://127.0.0.1:8088 \
    -DSTRATUS_AIRFLOW_ALLOW_HTTP=true \
    -DSTRATUS_AIRFLOW_ANONYMOUS_ADMIN=true \
    -DSTRATUS_AIRFLOW_USERNAME=anonymous \
    -DSTRATUS_AIRFLOW_PASSWORD=unused-development-value \
    -DSTRATUS_AIRFLOW_POLL_INTERVAL_MS=2000 \
    -DSTRATUS_AIRFLOW_RUN_TIMEOUT_MS=900000 \
    -DSTRATUS_AIRFLOW_SCENARIOS_BASE64="$scenarios_base64"
}

run_catalog_verifier() {
  compose exec -T airflow-scheduler java \
    -Djavax.net.ssl.trustStore=/opt/stratus/certs/stratus-truststore.jks \
    -cp '/opt/stratus/jobs/stratus-spark-jobs.jar:/opt/stratus/runtime/stratus-iceberg-aws-runtime.jar:/opt/spark/jars/*' \
    "$CATALOG_VERIFIER_CLASS" \
    --acceptedSourceTable "$accepted_source_table" \
    --acceptedTargetTable "$accepted_target_table" \
    --acceptedQualityRunId "$accepted_quality_run_id" \
    --acceptedPipelineRunId "$accepted_pipeline_run_id" \
    --blockedSourceTable "$blocked_source_table" \
    --blockedTargetTable "$blocked_target_table" \
    --blockedQualityRunId "$blocked_quality_run_id" \
    --expectedRows "$EXPECTED_ROWS" \
    --expectedCountries "$EXPECTED_COUNTRIES" \
    --catalogProperties /opt/stratus/spark-conf/spark-defaults.conf \
    --runId "$suite_run_id-catalog-verifier"
}

log "event=airflow_silver_to_gold_suite_started suiteRunId=$suite_run_id dagId=$DAG_ID expectedSparkApplications=$EXPECTED_SPARK_APPLICATIONS logLevel=$STRATUS_LOG_LEVEL"
require_spark_cluster
spark_applications_before="$(spark_application_count)"

phase_started_ms="$(date +%s%3N)"
if suite_owns_airflow; then
  bash "$HARNESS_DIR/scripts/tests/airflow-compose-verify-health.sh"
  log "event=airflow_silver_to_gold_suite_airflow_reused suiteRunId=$suite_run_id"
else
  bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-startup.sh"
  airflow_owned=true
fi
airflow_started=true
phase_complete airflow_startup "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
verify_protected_connections
phase_complete protected_connections "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
run_fixture prepare
fixture_prepared=true
grep -Fq "AIRFLOW SILVER TO GOLD FIXTURES READY" "$evidence_file" \
  || fail "The silver boundary fixture marker is absent"
phase_complete boundary_fixture_prepare "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
run_scheduler_scenarios
grep -Fq "event=airflow_orchestration_verification_completed status=SUCCESS" "$evidence_file" \
  || fail "The scheduler scenario completion marker is absent"
phase_complete scheduler_execution "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
run_catalog_verifier
grep -Fq "CATALOG SILVER TO GOLD VERIFIED" "$evidence_file" \
  || fail "The accepted direct-catalog verification marker is absent"
grep -Fq "CATALOG SILVER TO GOLD BLOCK VERIFIED" "$evidence_file" \
  || fail "The blocked direct-catalog verification marker is absent"
phase_complete direct_catalog_verification "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
run_fixture cleanup
fixture_prepared=false
grep -Fq "AIRFLOW SILVER TO GOLD FIXTURES CLEANUP COMPLETE" "$evidence_file" \
  || fail "The exact fixture cleanup marker is absent"
phase_complete exact_cleanup "$phase_started_ms"

spark_applications_after="$(spark_application_count)"
observed_spark_applications="$((spark_applications_after - spark_applications_before))"
log "event=airflow_silver_to_gold_application_budget suiteRunId=$suite_run_id expectedSparkApplications=$EXPECTED_SPARK_APPLICATIONS observedSparkApplications=$observed_spark_applications baselineApplications=$spark_applications_before finalApplications=$spark_applications_after"
if [[ "$observed_spark_applications" -ne "$EXPECTED_SPARK_APPLICATIONS" ]]; then
  fail "Expected $EXPECTED_SPARK_APPLICATIONS Spark applications but observed $observed_spark_applications"
fi

load_environment_file
assert_not_logged "$AIRFLOW_DB_PASSWORD" "Airflow database password"
assert_not_logged "$AIRFLOW_FERNET_KEY" "Airflow Fernet key"
assert_not_logged "$AIRFLOW_JWT_SECRET" "Airflow JWT secret"
assert_not_logged "$AIRFLOW_API_SECRET_KEY" "Airflow API secret"
assert_not_logged "$AIRFLOW_SPARK_RGW_ACCESS_KEY" "Spark RGW access key"
assert_not_logged "$AIRFLOW_SPARK_RGW_SECRET_KEY" "Spark RGW secret key"
assert_not_logged "$AIRFLOW_LANDING_RGW_ACCESS_KEY" "Airflow landing RGW access key"
assert_not_logged "$AIRFLOW_LANDING_RGW_SECRET_KEY" "Airflow landing RGW secret key"

log "event=airflow_silver_to_gold_suite_completed suiteRunId=$suite_run_id status=SUCCESS expectedSparkApplications=$EXPECTED_SPARK_APPLICATIONS elapsedMs=$(( $(date +%s%3N) - started_ms )) evidence=$evidence_file"
