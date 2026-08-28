#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-24
# Purpose: execute every Airflow development test as one timed, cleanup-safe gate suite.

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
readonly AIRFLOW_HARNESS_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
readonly REPOSITORY_DIR="$(cd "$AIRFLOW_HARNESS_DIR/../../.." && pwd)"
readonly IMAGE_TEST_DIR="$REPOSITORY_DIR/platform/airflow/image/scripts/tests"
readonly CEPH_DIR="$REPOSITORY_DIR/platform/ceph/compose-cluster"
readonly OPENBAO_DIR="$REPOSITORY_DIR/platform/openbao/compose-service"
readonly POLARIS_DIR="$REPOSITORY_DIR/platform/polaris/compose-service"
readonly SPARK_DIR="$REPOSITORY_DIR/platform/spark/compose-cluster"
readonly ACCEPTANCE_OVERLAY="$AIRFLOW_HARNESS_DIR/scripts/tests/compose.acceptance-suite.yaml"

readonly SUITE_RUN_ID="airflow-development-acceptance-$(date -u +%Y%m%dT%H%M%SZ)"
readonly EVIDENCE_DIR="$AIRFLOW_HARNESS_DIR/evidence"
readonly EVIDENCE_FILE="$EVIDENCE_DIR/${SUITE_RUN_ID}.log"
readonly SUITE_STARTED_MS="$(date +%s%3N)"
services_stopped=false

# The canonical runner owns one Airflow lifecycle. Every nested harness sees the same provider,
# metadata, scheduler and DAG-processor state and remains responsible only for its own fixtures.
export STRATUS_AIRFLOW_SUITE_SCOPED=true
export STRATUS_DISABLE_DAG_SCHEDULES=true
export STRATUS_BRONZE_TO_SILVER_RETRIES=0
export STRATUS_SILVER_TO_GOLD_RETRIES=0
export STRATUS_DEADLINE_PROBE_DAG_ID="stratus_deadline_alert_probe_${SUITE_RUN_ID#airflow-development-acceptance-}_$$"

mkdir -p "$EVIDENCE_DIR"
exec > >(tee "$EVIDENCE_FILE") 2>&1

timestamp() {
  date -u +%Y-%m-%dT%H:%M:%S.%3NZ
}

log_suite() {
  printf '%s event=%s suiteRunId=%s %s\n' "$(timestamp)" "$1" "$SUITE_RUN_ID" "${2:-}"
}

run_phase() {
  local name="$1"
  shift
  local phase_started_ms
  phase_started_ms="$(date +%s%3N)"
  log_suite phase_started "phase=$name"
  "$@"
  log_suite phase_completed \
    "phase=$name status=SUCCESS durationMs=$(( $(date +%s%3N) - phase_started_ms ))"
}

run_repository_maven() {
  if [[ -n "${MSYSTEM:-}" ]]; then
    local native_temp java_tool_options
    native_temp="$(MSYS_NO_PATHCONV=1 cmd.exe /d /c "echo %TEMP%" \
      | tr -d '\r' | tail -n 1)"
    [[ -n "$native_temp" ]] || {
      log_suite maven_environment_failed "reason=native_temp_not_found"
      return 1
    }
    java_tool_options="${JAVA_TOOL_OPTIONS:-}"
    java_tool_options="${java_tool_options:+$java_tool_options }-Djava.io.tmpdir=$native_temp"
    (cd "$REPOSITORY_DIR" && MSYS_NO_PATHCONV=1 \
      TEMP="$native_temp" TMP="$native_temp" JAVA_TOOL_OPTIONS="$java_tool_options" \
      cmd.exe /d /c mvnw.cmd "$@")
  elif [[ -n "${WSL_DISTRO_NAME:-}" ]]; then
    (cd "$REPOSITORY_DIR" && cmd.exe /d /c mvnw.cmd "$@")
  else
    (cd "$REPOSITORY_DIR" && ./mvnw "$@")
  fi
}

shutdown_harness() {
  local name="$1"
  local script="$2"
  if bash "$script"; then
    return 0
  fi
  log_suite cleanup_retry "provider=$name"
  bash "$script"
}

stop_shared_services() {
  local stop_failed=false
  shutdown_harness airflow \
    "$AIRFLOW_HARNESS_DIR/scripts/lifecycle/airflow-compose-shutdown.sh" || stop_failed=true
  shutdown_harness spark \
    "$SPARK_DIR/scripts/lifecycle/spark-compose-shutdown.sh" || stop_failed=true
  shutdown_harness polaris \
    "$POLARIS_DIR/scripts/lifecycle/polaris-compose-shutdown.sh" || stop_failed=true
  shutdown_harness openbao \
    "$OPENBAO_DIR/scripts/lifecycle/openbao-compose-shutdown.sh" || stop_failed=true
  shutdown_harness ceph \
    "$CEPH_DIR/scripts/lifecycle/ceph-compose-shutdown.sh" || stop_failed=true
  if $stop_failed; then
    return 1
  fi
  services_stopped=true
}

cleanup() {
  local original_exit_code="$?"
  local final_exit_code="$original_exit_code"
  local cleanup_failed=false
  local remaining
  local remaining_count=0
  set +e

  log_suite cleanup_started "originalExitCode=$original_exit_code"
  if ! $services_stopped; then
    stop_shared_services || cleanup_failed=true
  fi

  remaining="$(docker ps --format '{{.Names}}' \
    --filter 'name=stratus-airflow' --filter 'name=stratus-spark' \
    --filter 'name=stratus-polaris' --filter 'name=stratus-openbao' \
    --filter 'name=stratus-ceph')"
  if [[ -n "$remaining" ]]; then
    remaining_count="$(printf '%s\n' "$remaining" | wc -l | tr -d ' ')"
    log_suite cleanup_failed \
      "remainingStratusContainers=$remaining_count containers=${remaining//$'\n'/,}"
    final_exit_code=1
  fi
  if $cleanup_failed; then
    final_exit_code=1
  fi

  log_suite cleanup_completed "remainingStratusContainers=$remaining_count"
  if [[ "$final_exit_code" -eq 0 ]]; then
    log_suite development_acceptance_completed \
      "status=SUCCESS durationMs=$(( $(date +%s%3N) - SUITE_STARTED_MS )) evidence=$EVIDENCE_FILE"
  else
    log_suite development_acceptance_failed \
      "status=FAILURE durationMs=$(( $(date +%s%3N) - SUITE_STARTED_MS )) evidence=$EVIDENCE_FILE"
  fi
  exit "$final_exit_code"
}
trap cleanup EXIT

log_suite development_acceptance_started "evidence=$EVIDENCE_FILE"

run_phase offline_reactor_before_live run_repository_maven -o verify
run_phase image_acceptance bash "$IMAGE_TEST_DIR/airflow-image-acceptance-test.sh"

run_phase ceph_startup bash "$CEPH_DIR/scripts/lifecycle/ceph-compose-startup.sh"
run_phase ceph_buckets bash "$CEPH_DIR/scripts/verify/ceph-compose-bootstrap-buckets.sh"
run_phase openbao_startup bash "$OPENBAO_DIR/scripts/lifecycle/openbao-compose-startup.sh"
run_phase service_identities bash "$CEPH_DIR/scripts/verify/ceph-compose-provision-service-identities.sh"
run_phase polaris_startup bash "$POLARIS_DIR/scripts/lifecycle/polaris-compose-startup.sh"
run_phase polaris_catalog bash "$POLARIS_DIR/scripts/verify/polaris-compose-bootstrap-catalog.sh"
run_phase spark_startup bash "$SPARK_DIR/scripts/lifecycle/spark-compose-startup.sh"
run_phase spark_principal bash "$SPARK_DIR/scripts/verify/spark-compose-bootstrap-principal.sh"

# Load the accepted Spark/Airflow mount and credential contract only after the provider-owned
# connection files exist. The additional overlay makes both control-plane probes available in the
# same Airflow deployment as the platform DAGs.
# shellcheck disable=SC1091
source "$AIRFLOW_HARNESS_DIR/scripts/lib/airflow-spark-common.sh"
require_spark_cluster
append_airflow_compose_overlay "$ACCEPTANCE_OVERLAY"
run_phase airflow_startup bash "$AIRFLOW_HARNESS_DIR/scripts/lifecycle/airflow-compose-startup.sh"

run_phase dag_parse_and_registry bash "$SCRIPT_DIR/airflow-pipeline-dag-parse-test.sh"
run_phase retry_and_terminal_alert bash "$SCRIPT_DIR/airflow-retry-alert-live-test.sh"
run_phase deadline_alert bash "$SCRIPT_DIR/airflow-deadline-alert-live-test.sh"

run_phase spark_submission bash "$SCRIPT_DIR/airflow-spark-submission-test.sh"
run_phase landing_to_bronze bash "$SCRIPT_DIR/airflow-landing-to-bronze-live-test.sh"
run_phase bronze_to_silver bash "$SCRIPT_DIR/airflow-bronze-to-silver-live-test.sh"
run_phase silver_to_gold bash "$SCRIPT_DIR/airflow-silver-to-gold-live-test.sh"
run_phase table_maintenance bash "$SCRIPT_DIR/airflow-table-maintenance-live-test.sh"
run_phase public_api_orchestration bash "$SCRIPT_DIR/airflow-api-orchestration-live-test.sh"
run_phase shared_service_shutdown stop_shared_services
run_phase offline_reactor_after_live run_repository_maven -o verify
