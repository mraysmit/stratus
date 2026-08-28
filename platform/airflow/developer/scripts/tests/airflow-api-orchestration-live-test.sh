#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-24
# Purpose: prove Airflow's REST API task-state contract and a fail-closed promotion.

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
readonly AIRFLOW_HARNESS_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
readonly REPOSITORY_DIR="$(cd "$AIRFLOW_HARNESS_DIR/../../.." && pwd)"
readonly CEPH_DIR="$REPOSITORY_DIR/platform/ceph/compose-cluster"
readonly OPENBAO_DIR="$REPOSITORY_DIR/platform/openbao/compose-service"
readonly POLARIS_DIR="$REPOSITORY_DIR/platform/polaris/compose-service"
readonly SPARK_DIR="$REPOSITORY_DIR/platform/spark/compose-cluster"
readonly CATALOG_STATE_CLASS="dev.stratus.jobs.spark.CatalogTableStateJob"
readonly POSITIVE_DAG_ID="stratus_api_contract_probe"
readonly BLOCKED_DAG_ID="stratus_bronze_to_silver"

mkdir -p "$AIRFLOW_HARNESS_DIR/evidence"
suite_run_id="airflow-api-orchestration-$(date -u +%Y%m%dT%H%M%SZ)"
suite_token="$(printf '%s' "$suite_run_id" | tr '[:upper:]-' '[:lower:]_')"
source_table="stratus.bronze.airflow_pipeline_probe_${suite_token}_blocked"
target_table="stratus.silver.airflow_pipeline_probe_${suite_token}_blocked"
source_batch="$suite_run_id-blocked-source"
source_quality_run_id="$suite_run_id-blocked-no-evidence"
blocked_pipeline_run_id="$suite_run_id-blocked-promotion"
positive_run_id="$suite_run_id-api-contract"
blocked_run_id="$suite_run_id-quality-block"
evidence_file="$AIRFLOW_HARNESS_DIR/evidence/${suite_run_id}.log"
started_ms="$(date +%s%3N)"

ceph_attempted=false
openbao_attempted=false
polaris_attempted=false
spark_attempted=false
airflow_attempted=false

export STRATUS_RUN_ID="$suite_run_id"
export STRATUS_LOG_LEVEL="${STRATUS_LOG_LEVEL:-INFO}"
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

configure_airflow() {
  verify_protected_connections

  local attempt listing complete
  for attempt in $(seq 1 30); do
    listing="$(compose exec -T airflow-scheduler airflow dags list 2>/dev/null || true)"
    complete=true
    for dag_id in stratus_landing_to_bronze stratus_bronze_to_silver \
        stratus_silver_to_gold stratus_table_maintenance stratus_api_contract_probe; do
      if ! grep -Fq "$dag_id" <<<"$listing"; then
        complete=false
      fi
    done
    $complete && return 0
    sleep 2
  done
  fail_local "Airflow did not register all five required Stratus DAGs within 60 seconds"
}

verify_blocked_target_absent() {
  compose exec -T airflow-scheduler java \
    -Djavax.net.ssl.trustStore=/opt/stratus/certs/stratus-truststore.jks \
    -cp '/opt/stratus/jobs/stratus-spark-jobs.jar:/opt/stratus/runtime/stratus-iceberg-aws-runtime.jar:/opt/spark/jars/*' \
    "$CATALOG_STATE_CLASS" \
    --table "$target_table" \
    --expectedState absent \
    --catalogProperties /opt/stratus/spark-conf/spark-defaults.conf \
    --runId "$suite_run_id-blocked-catalog-verifier"
}

airflow_attempted=true
phase airflow_startup bash "$AIRFLOW_HARNESS_DIR/scripts/lifecycle/airflow-compose-startup.sh"
phase airflow_configuration configure_airflow

positive_conf="{\"probe_run_id\":\"$positive_run_id\"}"
blocked_conf="{\"bronze_table\":\"$source_table\",\"silver_table\":\"$target_table\",\"source_batch\":\"$source_batch\",\"quality_run_id\":\"$source_quality_run_id\",\"pipeline_run_id\":\"$blocked_pipeline_run_id\"}"
positive_conf_base64="$(printf '%s' "$positive_conf" | base64 | tr -d '\r\n')"
blocked_conf_base64="$(printf '%s' "$blocked_conf" | base64 | tr -d '\r\n')"
positive_tasks_base64="$(printf '%s' '{"complete_api_contract_probe":"success"}' | base64 | tr -d '\r\n')"
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
  -DSTRATUS_AIRFLOW_POSITIVE_DAG_ID="$POSITIVE_DAG_ID" \
  -DSTRATUS_AIRFLOW_POSITIVE_RUN_ID="$positive_run_id" \
  -DSTRATUS_AIRFLOW_POSITIVE_EXPECTED_RUN_STATE=success \
  -DSTRATUS_AIRFLOW_POSITIVE_CONF_BASE64="$positive_conf_base64" \
  -DSTRATUS_AIRFLOW_POSITIVE_TASK_STATES_BASE64="$positive_tasks_base64" \
  -DSTRATUS_AIRFLOW_BLOCKED_DAG_ID="$BLOCKED_DAG_ID" \
  -DSTRATUS_AIRFLOW_BLOCKED_RUN_ID="$blocked_run_id" \
  -DSTRATUS_AIRFLOW_BLOCKED_EXPECTED_RUN_STATE=failed \
  -DSTRATUS_AIRFLOW_BLOCKED_CONF_BASE64="$blocked_conf_base64" \
  -DSTRATUS_AIRFLOW_BLOCKED_TASK_STATES_BASE64="$blocked_tasks_base64"

phase blocked_no_write_verification verify_blocked_target_absent

grep -Fq "event=airflow_orchestration_verification_completed status=SUCCESS" "$evidence_file" \
  || fail_local "The Java API verification completion marker is absent"
grep -Fq "CATALOG TABLE STATE VERIFIED" "$evidence_file" \
  || fail_local "The blocked-promotion catalog-state marker is absent"

load_environment_file
for secret_name in AIRFLOW_DB_PASSWORD AIRFLOW_FERNET_KEY AIRFLOW_JWT_SECRET \
    AIRFLOW_API_SECRET_KEY AIRFLOW_SPARK_RGW_ACCESS_KEY AIRFLOW_SPARK_RGW_SECRET_KEY \
    AIRFLOW_LANDING_RGW_ACCESS_KEY AIRFLOW_LANDING_RGW_SECRET_KEY; do
  secret_value="${!secret_name:-}"
  [[ -z "$secret_value" ]] || ! grep -Fq -- "$secret_value" "$evidence_file" \
    || fail_local "Secret-redaction check failed for $secret_name"
done

log_local "event=airflow_api_orchestration_suite_completed suiteRunId=$suite_run_id status=SUCCESS positiveRunId=$positive_run_id blockedRunId=$blocked_run_id elapsedMs=$(( $(date +%s%3N) - started_ms )) evidence=$evidence_file"
