#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-24
# Purpose: prove Airflow's REST API, a successful maintenance run, and a fail-closed promotion.

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
readonly AIRFLOW_HARNESS_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
readonly REPOSITORY_DIR="$(cd "$AIRFLOW_HARNESS_DIR/../../.." && pwd)"
readonly CEPH_DIR="$REPOSITORY_DIR/platform/ceph/compose-cluster"
readonly OPENBAO_DIR="$REPOSITORY_DIR/platform/openbao/compose-service"
readonly POLARIS_DIR="$REPOSITORY_DIR/platform/polaris/compose-service"
readonly SPARK_DIR="$REPOSITORY_DIR/platform/spark/compose-cluster"
readonly LANDING_BUCKET="stratus-landing"
readonly FIXTURE_SCRIPT="/opt/stratus/airflow-tests/airflow-pipeline-s3-fixture.py"
readonly INGESTION_CLASS="dev.stratus.jobs.spark.IngestionJob"
readonly QUALITY_CLASS="dev.stratus.jobs.spark.QualityCheckJob"
readonly MAINTENANCE_VERIFIER_CLASS="dev.stratus.jobs.spark.AirflowTableMaintenanceVerifierJob"
readonly BRONZE_VERIFIER_CLASS="dev.stratus.jobs.spark.AirflowBronzeToSilverVerifierJob"
readonly SPARK_EVENT_LOG_DIRECTORY="/opt/airflow/logs/spark-events"

mkdir -p "$AIRFLOW_HARNESS_DIR/evidence"
suite_run_id="airflow-api-orchestration-$(date -u +%Y%m%dT%H%M%SZ)"
suite_token="$(printf '%s' "$suite_run_id" | tr '[:upper:]-' '[:lower:]_')"
maintenance_table="stratus.bronze.airflow_maintenance_probe_${suite_token}"
source_table="stratus.bronze.airflow_pipeline_probe_${suite_token}_blocked"
target_table="stratus.silver.airflow_pipeline_probe_${suite_token}_blocked"
source_batch="$suite_run_id-blocked-source"
source_quality_run_id="$source_batch"
blocked_pipeline_run_id="$suite_run_id-blocked-promotion"
positive_run_id="$suite_run_id-maintenance"
blocked_run_id="$suite_run_id-quality-block"
landing_key="verification/$suite_run_id/customers.csv"
evidence_file="$AIRFLOW_HARNESS_DIR/evidence/${suite_run_id}.log"
started_ms="$(date +%s%3N)"

ceph_attempted=false
openbao_attempted=false
polaris_attempted=false
spark_attempted=false
airflow_attempted=false
maintenance_seeded=false
bronze_seeded=false
fixture_staged=false

export STRATUS_RUN_ID="$suite_run_id"
export STRATUS_LOG_LEVEL="${STRATUS_LOG_LEVEL:-DEBUG}"
export STRATUS_BRONZE_TO_SILVER_RETRIES=0
exec > >(tee "$evidence_file") 2>&1

timestamp() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }
log_local() { printf '%s %s\n' "$(timestamp)" "$*"; }
fail_local() { log_local "ERROR: $*" >&2; exit 1; }

phase() {
  local name="$1"
  shift
  local phase_started_ms
  phase_started_ms="$(date +%s%3N)"
  log_local "event=airflow_api_orchestration_phase_started suiteRunId=$suite_run_id phase=$name"
  "$@"
  log_local "event=airflow_api_orchestration_phase_completed suiteRunId=$suite_run_id phase=$name status=SUCCESS elapsedMs=$(( $(date +%s%3N) - phase_started_ms ))"
}

run_repository_maven() {
  if [[ -n "${MSYSTEM:-}" ]]; then
    (cd "$REPOSITORY_DIR" && MSYS_NO_PATHCONV=1 cmd.exe /d /c mvnw.cmd "$@")
  elif [[ -n "${WSL_DISTRO_NAME:-}" ]]; then
    (cd "$REPOSITORY_DIR" && cmd.exe /d /c mvnw.cmd "$@")
  else
    (cd "$REPOSITORY_DIR" && ./mvnw "$@")
  fi
}

shutdown_checked_harnesses() {
  local exit_code="$?"
  local cleanup_failed=false
  local remaining remaining_count
  set +e
  log_local "event=airflow_api_orchestration_cleanup_started suiteRunId=$suite_run_id originalExitCode=$exit_code"

  shutdown_harness() {
    local name="$1"
    local script="$2"
    if bash "$script"; then
      return 0
    fi
    log_local "WARN: $name shutdown failed; retrying the checked lifecycle script once"
    if ! bash "$script"; then
      log_local "ERROR: $name shutdown failed after retry"
      cleanup_failed=true
    fi
  }

  if $airflow_attempted && [[ -f "$AIRFLOW_HARNESS_DIR/.env" ]]; then
    if $bronze_seeded; then
      run_bronze_verifier >/dev/null 2>&1
    fi
    if $maintenance_seeded; then
      run_spark_job "$MAINTENANCE_VERIFIER_CLASS" \
        --mode cleanup --targetTable "$maintenance_table" \
        --runId "$suite_run_id-emergency-maintenance-cleanup" >/dev/null 2>&1
    fi
    if $fixture_staged; then
      compose exec -T airflow-scheduler python "$FIXTURE_SCRIPT" delete \
        --bucket "$LANDING_BUCKET" --key "$landing_key" >/dev/null 2>&1
    fi
  fi

  if $airflow_attempted; then
    shutdown_harness airflow \
      "$AIRFLOW_HARNESS_DIR/scripts/lifecycle/airflow-compose-shutdown.sh"
  fi
  if $spark_attempted; then
    shutdown_harness spark "$SPARK_DIR/scripts/lifecycle/spark-compose-shutdown.sh"
  fi
  if $polaris_attempted; then
    shutdown_harness polaris "$POLARIS_DIR/scripts/lifecycle/polaris-compose-shutdown.sh"
  fi
  if $openbao_attempted; then
    shutdown_harness openbao "$OPENBAO_DIR/scripts/lifecycle/openbao-compose-shutdown.sh"
  fi
  if $ceph_attempted; then
    shutdown_harness ceph "$CEPH_DIR/scripts/lifecycle/ceph-compose-shutdown.sh"
  fi

  remaining="$(docker ps --format '{{.Names}}' \
    --filter 'name=stratus-airflow' --filter 'name=stratus-spark' \
    --filter 'name=stratus-polaris' --filter 'name=stratus-openbao' \
    --filter 'name=stratus-ceph')"
  remaining_count=0
  if [[ -n "$remaining" ]]; then
    remaining_count="$(printf '%s\n' "$remaining" | wc -l | tr -d ' ')"
    log_local "ERROR: Stratus containers remain after checked shutdown: ${remaining//$'\n'/,}"
    [[ "$exit_code" -ne 0 ]] || exit_code=1
  fi
  if $cleanup_failed && [[ "$exit_code" -eq 0 ]]; then
    exit_code=1
  fi
  log_local "event=airflow_api_orchestration_cleanup_completed suiteRunId=$suite_run_id remainingStratusContainers=$remaining_count"
  exit "$exit_code"
}
trap shutdown_checked_harnesses EXIT

ceph_attempted=true
phase ceph_startup bash "$CEPH_DIR/scripts/lifecycle/ceph-compose-startup.sh"
phase ceph_buckets bash "$CEPH_DIR/scripts/verify/ceph-compose-bootstrap-buckets.sh"

openbao_attempted=true
phase openbao_startup bash "$OPENBAO_DIR/scripts/lifecycle/openbao-compose-startup.sh"
phase service_identities bash "$CEPH_DIR/scripts/verify/ceph-compose-provision-service-identities.sh"

polaris_attempted=true
phase polaris_startup bash "$POLARIS_DIR/scripts/lifecycle/polaris-compose-startup.sh"
phase polaris_catalog bash "$POLARIS_DIR/scripts/verify/polaris-compose-bootstrap-catalog.sh"

spark_attempted=true
phase spark_startup bash "$SPARK_DIR/scripts/lifecycle/spark-compose-startup.sh"
phase spark_principal bash "$SPARK_DIR/scripts/verify/spark-compose-bootstrap-principal.sh"

# Provider connection files now exist, so load the Airflow/Spark composition helpers only here.
# shellcheck disable=SC1091
source "$AIRFLOW_HARNESS_DIR/scripts/lib/airflow-spark-common.sh"
require_spark_cluster

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

run_bronze_verifier() {
  run_spark_job "$BRONZE_VERIFIER_CLASS" \
    --sourceTable "$source_table" \
    --targetTable "$target_table" \
    --sourceBatch "$source_batch" \
    --sourceQualityRunId "$source_quality_run_id" \
    --pipelineRunId "$blocked_pipeline_run_id" \
    --expectedRows 3 \
    --expectedOutcome blocked \
    --runId "$suite_run_id-blocked-side-effect-verifier" \
    --cleanup true
}

configure_airflow() {
  compose exec -T airflow-scheduler airflow connections delete spark_default >/dev/null 2>&1 || true
  compose exec -T airflow-scheduler airflow connections add spark_default \
    --conn-type spark --conn-host spark://spark-master.stratus.local --conn-port 7077 >/dev/null

  local attempt listing complete
  for attempt in $(seq 1 30); do
    listing="$(compose exec -T airflow-scheduler airflow dags list 2>/dev/null || true)"
    complete=true
    for dag_id in stratus_landing_to_bronze stratus_bronze_to_silver \
        stratus_silver_to_gold stratus_table_maintenance; do
      if ! grep -Fq "$dag_id" <<<"$listing"; then
        complete=false
      fi
    done
    $complete && return 0
    sleep 2
  done
  fail_local "Airflow did not register all four Stratus DAGs within 60 seconds"
}

airflow_attempted=true
phase airflow_startup bash "$AIRFLOW_HARNESS_DIR/scripts/lifecycle/airflow-compose-startup.sh"
phase airflow_configuration configure_airflow

phase maintenance_fixture_seed run_spark_job "$MAINTENANCE_VERIFIER_CLASS" \
  --mode seed --targetTable "$maintenance_table" --runId "$suite_run_id-maintenance-seed"
maintenance_seeded=true

phase landing_fixture_stage compose exec -T airflow-scheduler python "$FIXTURE_SCRIPT" put \
  --bucket "$LANDING_BUCKET" --key "$landing_key"
fixture_staged=true

phase bronze_ingestion run_spark_job "$INGESTION_CLASS" \
  --sourceFile "s3a://$LANDING_BUCKET/$landing_key" \
  --targetTable "$source_table" \
  --sourceSystem airflow-api-verifier \
  --batchId "$source_batch" \
  --runId "$suite_run_id-bronze-ingestion"
bronze_seeded=true

blocking_checks_base64="$(printf '%s' '[{"name":"requires_four_rows","type":"row_count_min","severity":"blocking","minRows":4}]' | base64 | tr -d '\r\n')"
phase blocking_quality_result run_spark_job "$QUALITY_CLASS" \
  --targetTable "$source_table" \
  --runId "$source_quality_run_id" \
  --pipelineRunId "$source_quality_run_id" \
  --checksBase64 "$blocking_checks_base64"

positive_conf="{\"target_table\":\"$maintenance_table\",\"policy\":\"development-run-v1\",\"run_id\":\"$positive_run_id\"}"
blocked_conf="{\"bronze_table\":\"$source_table\",\"silver_table\":\"$target_table\",\"source_batch\":\"$source_batch\",\"quality_run_id\":\"$source_quality_run_id\",\"pipeline_run_id\":\"$blocked_pipeline_run_id\"}"
positive_conf_base64="$(printf '%s' "$positive_conf" | base64 | tr -d '\r\n')"
blocked_conf_base64="$(printf '%s' "$blocked_conf" | base64 | tr -d '\r\n')"
positive_tasks_base64="$(printf '%s' '{"apply_table_maintenance_policy":"success"}' | base64 | tr -d '\r\n')"
blocked_tasks_base64="$(printf '%s' '{"evaluate_bronze_promotion":"failed","run_silver_transform":"upstream_failed","run_silver_quality":"upstream_failed"}' | base64 | tr -d '\r\n')"

phase java_api_verification run_repository_maven -o test \
  -Porchestration-integration-tests -pl :stratus-orchestration-verifier -am \
  -DSTRATUS_AIRFLOW_BASE_URL=http://127.0.0.1:8088 \
  -DSTRATUS_AIRFLOW_ALLOW_HTTP=true \
  -DSTRATUS_AIRFLOW_ANONYMOUS_ADMIN=true \
  -DSTRATUS_AIRFLOW_USERNAME=anonymous \
  -DSTRATUS_AIRFLOW_PASSWORD=unused-development-value \
  -DSTRATUS_AIRFLOW_POLL_INTERVAL_MS=2000 \
  -DSTRATUS_AIRFLOW_RUN_TIMEOUT_MS=900000 \
  -DSTRATUS_AIRFLOW_POSITIVE_DAG_ID=stratus_table_maintenance \
  -DSTRATUS_AIRFLOW_POSITIVE_RUN_ID="$positive_run_id" \
  -DSTRATUS_AIRFLOW_POSITIVE_EXPECTED_RUN_STATE=success \
  -DSTRATUS_AIRFLOW_POSITIVE_CONF_BASE64="$positive_conf_base64" \
  -DSTRATUS_AIRFLOW_POSITIVE_TASK_STATES_BASE64="$positive_tasks_base64" \
  -DSTRATUS_AIRFLOW_BLOCKED_DAG_ID=stratus_bronze_to_silver \
  -DSTRATUS_AIRFLOW_BLOCKED_RUN_ID="$blocked_run_id" \
  -DSTRATUS_AIRFLOW_BLOCKED_EXPECTED_RUN_STATE=failed \
  -DSTRATUS_AIRFLOW_BLOCKED_CONF_BASE64="$blocked_conf_base64" \
  -DSTRATUS_AIRFLOW_BLOCKED_TASK_STATES_BASE64="$blocked_tasks_base64"

phase maintenance_side_effect_verification run_spark_job "$MAINTENANCE_VERIFIER_CLASS" \
  --mode verify-run --targetTable "$maintenance_table" \
  --expectedRows 3 --expectedFiles 1 --runId "$suite_run_id-maintenance-side-effect-verifier"

phase blocked_side_effect_verification run_bronze_verifier
bronze_seeded=false

phase maintenance_cleanup run_spark_job "$MAINTENANCE_VERIFIER_CLASS" \
  --mode cleanup --targetTable "$maintenance_table" --runId "$suite_run_id-maintenance-cleanup"
maintenance_seeded=false

phase landing_cleanup compose exec -T airflow-scheduler python "$FIXTURE_SCRIPT" delete \
  --bucket "$LANDING_BUCKET" --key "$landing_key"
fixture_staged=false

grep -Fq "event=airflow_orchestration_verification_completed status=SUCCESS" "$evidence_file" \
  || fail_local "The Java API verification completion marker is absent"
grep -Fq "AIRFLOW TABLE MAINTENANCE RUN VERIFIED" "$evidence_file" \
  || fail_local "The independent maintenance side-effect marker is absent"
grep -Fq "AIRFLOW BRONZE TO SILVER BLOCK VERIFIED" "$evidence_file" \
  || fail_local "The independent blocked-promotion marker is absent"

load_environment_file
for secret_name in AIRFLOW_DB_PASSWORD AIRFLOW_FERNET_KEY AIRFLOW_JWT_SECRET \
    AIRFLOW_API_SECRET_KEY AIRFLOW_SPARK_RGW_ACCESS_KEY AIRFLOW_SPARK_RGW_SECRET_KEY \
    AIRFLOW_LANDING_RGW_ACCESS_KEY AIRFLOW_LANDING_RGW_SECRET_KEY; do
  secret_value="${!secret_name:-}"
  [[ -z "$secret_value" ]] || ! grep -Fq -- "$secret_value" "$evidence_file" \
    || fail_local "Secret-redaction check failed for $secret_name"
done

log_local "event=airflow_api_orchestration_suite_completed suiteRunId=$suite_run_id status=SUCCESS positiveRunId=$positive_run_id blockedRunId=$blocked_run_id elapsedMs=$(( $(date +%s%3N) - started_ms )) evidence=$evidence_file"
