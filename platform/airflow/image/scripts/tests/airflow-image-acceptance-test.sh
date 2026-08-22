#!/usr/bin/env bash
# Execute and time the complete P1-4.1-S2 local image acceptance sequence.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BUILD_DIR="${IMAGE_DIR}/scripts/build"
EVIDENCE_DIR="${IMAGE_DIR}/artifacts/evidence"
RUN_ID="${STRATUS_AIRFLOW_IMAGE_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
EVIDENCE_FILE="${EVIDENCE_DIR}/airflow-image-acceptance-${RUN_ID}.log"
START_NS="$(date +%s%N)"

mkdir -p "${EVIDENCE_DIR}"
exec > >(tee -a "${EVIDENCE_FILE}") 2>&1

log() {
  printf 'timestamp=%s component=airflow-image-acceptance level=%s event=%s run_id=%s %s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2" "${RUN_ID}" "${3:-}"
}

run_phase() {
  local phase="$1"
  local command="$2"
  local phase_start_ns
  phase_start_ns="$(date +%s%N)"
  log INFO phase_started "phase=${phase}"
  "${command}"
  log INFO phase_completed \
    "phase=${phase} duration_ms=$(( ($(date +%s%N) - phase_start_ns) / 1000000 ))"
}

log INFO acceptance_started "evidence=${EVIDENCE_FILE}"
run_phase resolve "${BUILD_DIR}/airflow-image-resolve-artifacts.sh"
run_phase build "${BUILD_DIR}/airflow-image-build.sh"
run_phase smoke "${SCRIPT_DIR}/airflow-image-smoke-test.sh"
run_phase scan "${SCRIPT_DIR}/airflow-image-vulnerability-scan-test.sh"
log INFO acceptance_completed \
  "duration_ms=$(( ($(date +%s%N) - START_NS) / 1000000 )) evidence=${EVIDENCE_FILE}"
