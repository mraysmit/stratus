#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-22
source "$(dirname "$0")/../lib/airflow-spark-common.sh"

readonly LANDING_CONNECTION_ID="stratus_landing"
readonly LANDING_BUCKET_VARIABLE="stratus_landing_bucket"
readonly LANDING_BUCKET="stratus-landing"
readonly DAG_ID="stratus_landing_to_bronze"
readonly VERIFIER_CLASS="dev.stratus.jobs.spark.AirflowPipelineVerifierJob"
readonly FIXTURE_SCRIPT="/opt/stratus/airflow-tests/airflow-pipeline-s3-fixture.py"
readonly EXPECTED_ROWS="3"

mkdir -p "$HARNESS_DIR/evidence"
suite_run_id="airflow-pipeline-$(date -u +%Y%m%dT%H%M%SZ)"
run_token="$(printf '%s' "$suite_run_id" | tr '[:upper:]-' '[:lower:]_')"
landing_key="verification/$suite_run_id/customers.csv"
target_table="stratus.bronze.airflow_pipeline_probe_$run_token"
pipeline_run_id="$suite_run_id"
verifier_run_id="$suite_run_id-verifier"
airflow_run_id="$suite_run_id-dag"
evidence_file="$HARNESS_DIR/evidence/${suite_run_id}.log"
started_ms="$(date +%s%3N)"
airflow_started=false
airflow_owned=false
fixture_staged=false
verification_attempted=false
export STRATUS_RUN_ID="$suite_run_id"
export STRATUS_LOG_LEVEL="${STRATUS_LOG_LEVEL:-INFO}"
exec > >(tee "$evidence_file") 2>&1

phase_complete() {
  local phase="$1" phase_started_ms="$2"
  log "event=airflow_pipeline_phase_completed suiteRunId=$suite_run_id phase=$phase status=SUCCESS elapsedMs=$(( $(date +%s%3N) - phase_started_ms ))"
}

assert_not_logged() {
  local value="$1" label="$2"
  [[ -z "$value" ]] && return 0
  ! grep -Fq -- "$value" "$evidence_file" \
    || fail "Secret-redaction check failed for $label"
}

run_verifier() {
  compose exec -T airflow-scheduler mkdir -p /opt/airflow/logs/spark-events
  compose exec -T airflow-scheduler spark-submit \
    --master spark://spark-master.stratus.local:7077 \
    --class "$VERIFIER_CLASS" \
    --conf spark.driver.host=airflow-scheduler.stratus.local \
    --conf spark.driver.bindAddress=0.0.0.0 \
    --conf spark.driver.extraClassPath=/opt/stratus/runtime/stratus-iceberg-aws-runtime.jar:/opt/stratus/runtime/hadoop-aws.jar:/opt/stratus/runtime/aws-sdk-bundle.jar:/opt/stratus/runtime/analyticsaccelerator-s3.jar:/opt/stratus/runtime/log4j-slf4j-impl.jar \
    --conf spark.eventLog.dir=file:///opt/airflow/logs/spark-events \
    --conf spark.cores.max=2 \
    --conf spark.executor.cores=1 \
    /opt/stratus/jobs/stratus-spark-jobs.jar \
    --targetTable "$target_table" \
    --batchId "$pipeline_run_id" \
    --pipelineRunId "$pipeline_run_id" \
    --expectedRows "$EXPECTED_ROWS" \
    --runId "$verifier_run_id" \
    --cleanup true
}

run_repository_maven() {
  if [[ -n "${MSYSTEM:-}" ]]; then
    (cd "$REPO_DIR" && MSYS_NO_PATHCONV=1 cmd.exe /d /c mvnw.cmd "$@")
  elif [[ -n "${WSL_DISTRO_NAME:-}" ]]; then
    (cd "$REPO_DIR" && ./mvnw "$@")
  elif [[ "${OS:-}" == "Windows_NT" ]]; then
    (cd "$REPO_DIR" && ./mvnw.cmd "$@")
  else
    (cd "$REPO_DIR" && ./mvnw "$@")
  fi
}

run_scheduler_scenario() {
  local dag_conf scenarios_json scenarios_base64
  dag_conf="{\"landing_bucket\":\"$LANDING_BUCKET\",\"landing_object_key\":\"$landing_key\",\"bronze_table\":\"$target_table\",\"pipeline_run_id\":\"$pipeline_run_id\"}"
  scenarios_json="[{\"name\":\"accepted-landing-to-bronze\",\"dagId\":\"$DAG_ID\",\"runId\":\"$airflow_run_id\",\"expectedRunState\":\"success\",\"configuration\":$dag_conf,\"expectedTaskStates\":{\"wait_for_source_file\":\"success\",\"run_ingestion\":\"success\",\"run_bronze_quality\":\"success\"},\"expectedSparkApplications\":2}]"
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

cleanup() {
  local exit_code="$?"
  set +e
  if $airflow_started && ! $verification_attempted; then
    run_verifier >/dev/null 2>&1
  fi
  if $airflow_started && $fixture_staged; then
    compose exec -T airflow-scheduler python "$FIXTURE_SCRIPT" delete \
      --bucket "$LANDING_BUCKET" --key "$landing_key" >/dev/null 2>&1
  fi
  if $airflow_owned; then
    bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-shutdown.sh"
  fi
  exit "$exit_code"
}
trap cleanup EXIT

log "event=airflow_pipeline_suite_started suiteRunId=$suite_run_id dagId=$DAG_ID targetTable=$target_table logLevel=$STRATUS_LOG_LEVEL"
require_spark_cluster

phase_started_ms="$(date +%s%3N)"
if suite_owns_airflow; then
  bash "$HARNESS_DIR/scripts/tests/airflow-compose-verify-health.sh"
  log "event=airflow_pipeline_suite_airflow_reused suiteRunId=$suite_run_id"
else
  bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-startup.sh"
  airflow_owned=true
fi
airflow_started=true
phase_complete "airflow_startup" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
verify_protected_connections
phase_complete "protected_connections" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
compose exec -T airflow-scheduler python "$FIXTURE_SCRIPT" put \
  --bucket "$LANDING_BUCKET" --key "$landing_key"
fixture_staged=true
phase_complete "isolated_input" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
run_scheduler_scenario
grep -Fq "event=airflow_orchestration_verification_completed status=SUCCESS" "$evidence_file" \
  || fail "The landing scheduler scenario completion marker is absent"
phase_complete "dag_execution" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
verification_attempted=true
run_verifier
grep -Fq "AIRFLOW PIPELINE VERIFIED" "$evidence_file" \
  || fail "The independent pipeline verification marker is absent"
grep -Fq "AIRFLOW PIPELINE CLEANUP COMPLETE" "$evidence_file" \
  || fail "The pipeline cleanup marker is absent"
phase_complete "output_verification_and_cleanup" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
compose exec -T airflow-scheduler python "$FIXTURE_SCRIPT" delete \
  --bucket "$LANDING_BUCKET" --key "$landing_key"
fixture_staged=false
phase_complete "landing_cleanup" "$phase_started_ms"

load_environment_file
assert_not_logged "$AIRFLOW_DB_PASSWORD" "Airflow database password"
assert_not_logged "$AIRFLOW_FERNET_KEY" "Airflow Fernet key"
assert_not_logged "$AIRFLOW_JWT_SECRET" "Airflow JWT secret"
assert_not_logged "$AIRFLOW_API_SECRET_KEY" "Airflow API secret"
assert_not_logged "$AIRFLOW_SPARK_RGW_ACCESS_KEY" "Spark RGW access key"
assert_not_logged "$AIRFLOW_SPARK_RGW_SECRET_KEY" "Spark RGW secret key"
assert_not_logged "$AIRFLOW_LANDING_RGW_ACCESS_KEY" "Airflow landing RGW access key"
assert_not_logged "$AIRFLOW_LANDING_RGW_SECRET_KEY" "Airflow landing RGW secret key"

log "event=airflow_pipeline_suite_completed suiteRunId=$suite_run_id status=SUCCESS elapsedMs=$(( $(date +%s%3N) - started_ms )) evidence=$evidence_file"
