#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-24
# Purpose: provide one checked lifecycle, evidence, inspection, and cleanup contract for Airflow demos.

readonly DEMO_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly DEMO_ROOT="$(cd "$DEMO_SCRIPT_DIR/.." && pwd)"
readonly AIRFLOW_HARNESS_DIR="$(cd "$DEMO_ROOT/.." && pwd)"
readonly REPOSITORY_DIR="$(cd "$AIRFLOW_HARNESS_DIR/../../.." && pwd)"
readonly AIRFLOW_TEST_DIR="$AIRFLOW_HARNESS_DIR/scripts/tests"
readonly CEPH_DIR="$REPOSITORY_DIR/platform/ceph/compose-cluster"
readonly OPENBAO_DIR="$REPOSITORY_DIR/platform/openbao/compose-service"
readonly POLARIS_DIR="$REPOSITORY_DIR/platform/polaris/compose-service"
readonly SPARK_DIR="$REPOSITORY_DIR/platform/spark/compose-cluster"

keep_running=false
preserve_environment=false
ceph_attempted=false
openbao_attempted=false
polaris_attempted=false
spark_attempted=false
airflow_attempted=false
demo_run_id=""
demo_started_ms=""
demo_evidence_file=""

timestamp() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }

log_demo() {
  printf '%s event=%s demoRunId=%s %s\n' \
    "$(timestamp)" "$1" "${demo_run_id:-not-started}" "${2:-}"
}

demo_usage() {
  cat <<USAGE
Usage: $(basename "$0") [--keep-running]

$DEMO_TITLE

By default the demo verifies its observable outcomes and shuts down every service
it started. Pass --keep-running to restart Airflow after verification and retain
the environment for UI inspection at http://127.0.0.1:8088.
USAGE
}

parse_demo_arguments() {
  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --keep-running)
        keep_running=true
        ;;
      --help|-h)
        demo_usage
        exit 0
        ;;
      *)
        printf 'Unknown argument: %s\n' "$1" >&2
        demo_usage >&2
        exit 64
        ;;
    esac
    shift
  done
}

run_phase() {
  local name="$1"
  shift
  local phase_started_ms
  phase_started_ms="$(date +%s%3N)"
  log_demo airflow_demo_phase_started "phase=$name"
  "$@"
  log_demo airflow_demo_phase_completed \
    "phase=$name status=SUCCESS elapsedMs=$(( $(date +%s%3N) - phase_started_ms ))"
}

shutdown_harness() {
  local name="$1" script="$2"
  if bash "$script"; then
    return 0
  fi
  log_demo airflow_demo_cleanup_retry "provider=$name"
  bash "$script"
}

remaining_stratus_containers() {
  docker ps --format '{{.Names}}' \
    --filter 'name=stratus-airflow' --filter 'name=stratus-spark' \
    --filter 'name=stratus-polaris' --filter 'name=stratus-openbao' \
    --filter 'name=stratus-ceph'
}

cleanup() {
  local original_exit_code="$?" final_exit_code remaining remaining_count
  local cleanup_failed=false
  set +e

  if $preserve_environment; then
    log_demo airflow_demo_environment_preserved \
      "ui=http://127.0.0.1:8088 shutdown=$DEMO_SCRIPT_DIR/airflow-demo-shutdown.sh"
    return "$original_exit_code"
  fi

  log_demo airflow_demo_cleanup_started "originalExitCode=$original_exit_code"
  if $airflow_attempted; then
    shutdown_harness airflow \
      "$AIRFLOW_HARNESS_DIR/scripts/lifecycle/airflow-compose-shutdown.sh" \
      || cleanup_failed=true
  fi
  if $spark_attempted; then
    shutdown_harness spark \
      "$SPARK_DIR/scripts/lifecycle/spark-compose-shutdown.sh" || cleanup_failed=true
  fi
  if $polaris_attempted; then
    shutdown_harness polaris \
      "$POLARIS_DIR/scripts/lifecycle/polaris-compose-shutdown.sh" || cleanup_failed=true
  fi
  if $openbao_attempted; then
    shutdown_harness openbao \
      "$OPENBAO_DIR/scripts/lifecycle/openbao-compose-shutdown.sh" || cleanup_failed=true
  fi
  if $ceph_attempted; then
    shutdown_harness ceph \
      "$CEPH_DIR/scripts/lifecycle/ceph-compose-shutdown.sh" || cleanup_failed=true
  fi

  remaining="$(remaining_stratus_containers)"
  remaining_count=0
  if [[ -n "$remaining" ]]; then
    remaining_count="$(printf '%s\n' "$remaining" | wc -l | tr -d ' ')"
    log_demo airflow_demo_cleanup_error \
      "remainingStratusContainers=$remaining_count containers=${remaining//$'\n'/,}"
  fi

  final_exit_code="$original_exit_code"
  if $cleanup_failed || [[ "$remaining_count" -ne 0 ]]; then
    final_exit_code=1
  fi
  log_demo airflow_demo_cleanup_completed \
    "remainingStratusContainers=$remaining_count status=$([[ "$final_exit_code" -eq 0 ]] && printf SUCCESS || printf FAILURE)"
  exit "$final_exit_code"
}

start_data_plane() {
  ceph_attempted=true
  run_phase ceph_startup bash "$CEPH_DIR/scripts/lifecycle/ceph-compose-startup.sh"
  run_phase ceph_buckets bash "$CEPH_DIR/scripts/verify/ceph-compose-bootstrap-buckets.sh"

  openbao_attempted=true
  run_phase openbao_startup bash "$OPENBAO_DIR/scripts/lifecycle/openbao-compose-startup.sh"
  run_phase service_identities \
    bash "$CEPH_DIR/scripts/verify/ceph-compose-provision-service-identities.sh"

  polaris_attempted=true
  run_phase polaris_startup bash "$POLARIS_DIR/scripts/lifecycle/polaris-compose-startup.sh"
  run_phase polaris_catalog bash "$POLARIS_DIR/scripts/verify/polaris-compose-bootstrap-catalog.sh"

  spark_attempted=true
  run_phase spark_startup bash "$SPARK_DIR/scripts/lifecycle/spark-compose-startup.sh"
  run_phase spark_principal bash "$SPARK_DIR/scripts/verify/spark-compose-bootstrap-principal.sh"
}

latest_harness_evidence() {
  local candidates
  shopt -s nullglob
  candidates=("$AIRFLOW_HARNESS_DIR/evidence/${EVIDENCE_PREFIX}-"*.log)
  shopt -u nullglob
  [[ "${#candidates[@]}" -gt 0 ]] || return 1
  printf '%s\n' "${candidates[@]}" | sort | tail -n 1
}

verify_demo_markers() {
  local evidence_file="$1" marker
  for marker in "${required_markers[@]}"; do
    grep -Fq -- "$marker" "$evidence_file" \
      || { log_demo airflow_demo_marker_missing "marker=$marker evidence=$evidence_file"; return 1; }
    log_demo airflow_demo_marker_verified "marker=$marker"
  done
}

print_demo_summary() {
  local evidence_file="$1"
  printf '\n%s\n' "============================================================"
  printf 'AIRFLOW DEMO COMPLETE: %s\n' "$DEMO_TITLE"
  printf 'Role demonstrated: %s\n' "$DEMO_ROLE"
  printf 'Harness evidence: %s\n' "$evidence_file"
  printf 'Expected result: %s\n' "$EXPECTED_RESULT_FILE"
  printf 'Correlation fields: suiteRunId and elapsedMs are retained in the evidence.\n'
  printf '%s\n\n' "============================================================"
}

run_airflow_demo() {
  local harness_evidence
  parse_demo_arguments "$@"

  demo_run_id="airflow-demo-${DEMO_ID}-$(date -u +%Y%m%dT%H%M%SZ)"
  demo_started_ms="$(date +%s%3N)"
  mkdir -p "$DEMO_ROOT/evidence"
  demo_evidence_file="$DEMO_ROOT/evidence/${demo_run_id}.log"
  exec > >(tee "$demo_evidence_file") 2>&1
  trap cleanup EXIT

  log_demo airflow_demo_started \
    "title=$DEMO_ID estimatedDuration=$ESTIMATED_DURATION keepRunning=$keep_running"

  if [[ "$DATA_PLANE_REQUIRED" == "true" ]]; then
    start_data_plane
  fi

  run_phase accepted_live_harness bash "$AIRFLOW_TEST_DIR/$HARNESS_SCRIPT"
  harness_evidence="$(latest_harness_evidence)" \
    || { log_demo airflow_demo_evidence_missing "prefix=$EVIDENCE_PREFIX"; return 1; }
  run_phase observable_outcome_verification verify_demo_markers "$harness_evidence"
  print_demo_summary "$harness_evidence"

  log_demo airflow_demo_completed \
    "status=SUCCESS elapsedMs=$(( $(date +%s%3N) - demo_started_ms )) expectedResult=$EXPECTED_RESULT_FILE harnessEvidence=$harness_evidence"

  if $keep_running; then
    airflow_attempted=true
    run_phase airflow_ui_restart \
      bash "$AIRFLOW_HARNESS_DIR/scripts/lifecycle/airflow-compose-startup.sh"
    preserve_environment=true
    printf 'Airflow UI: http://127.0.0.1:8088\n'
    printf 'Shutdown: bash %s/airflow-demo-shutdown.sh\n' "$DEMO_SCRIPT_DIR"
  fi
}
