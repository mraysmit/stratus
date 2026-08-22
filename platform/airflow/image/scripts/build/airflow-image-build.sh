#!/usr/bin/env bash
# Assemble the Stratus Airflow image from already-verified local artifacts.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
IMAGE_TAG="${STRATUS_AIRFLOW_IMAGE:-stratus/airflow:dev}"
START_NS="$(date +%s%N)"

log() {
  printf 'timestamp=%s component=airflow-image-build level=%s event=%s %s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2" "${3:-}"
}

for required in \
  "${IMAGE_DIR}/requirements.lock" \
  "${IMAGE_DIR}/artifact-lock.properties" \
  "${IMAGE_DIR}/artifacts/wheelhouse/resolved-artifacts.sha256"; do
  if [[ ! -f "${required}" ]]; then
    log ERROR missing_artifact "path=${required} hint=run-airflow-image-resolve-artifacts"
    exit 1
  fi
done

(
  cd "${IMAGE_DIR}/artifacts/wheelhouse"
  sha256sum --check resolved-artifacts.sha256
)
WHEELHOUSE_BYTES="$(du -sb "${IMAGE_DIR}/artifacts/wheelhouse" | awk '{print $1}')"
log INFO build_started "image=${IMAGE_TAG} context=${IMAGE_DIR} wheelhouse_bytes=${WHEELHOUSE_BYTES}"
docker build --pull=false --tag "${IMAGE_TAG}" "${IMAGE_DIR}"
IMAGE_ID="$(docker image inspect "${IMAGE_TAG}" --format '{{.Id}}')"
printf '%s' "${IMAGE_ID}" > "${IMAGE_DIR}/artifacts/development-image-id.txt"
log INFO build_completed "image=${IMAGE_TAG} image_id=${IMAGE_ID} duration_ms=$(( ($(date +%s%N) - START_NS) / 1000000 ))"
