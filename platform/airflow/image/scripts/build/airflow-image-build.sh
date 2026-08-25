#!/usr/bin/env bash
#
# Stratus Airflow development image assembly
#
# Purpose:
#   Assemble the local Stratus Airflow image from digest-pinned OCI stages and an
#   already-resolved Python wheelhouse. This script performs no Python dependency
#   resolution: it verifies the cache before Docker can copy it into an image layer.
#
# Inputs:
#   - platform/airflow/image/{artifact-lock.properties,requirements.lock};
#   - artifacts/wheelhouse plus its resolved-artifacts.sha256 manifest;
#   - the digest-pinned Airflow and Spark source images, locally cached or reachable
#     through Docker; and
#   - optional STRATUS_AIRFLOW_IMAGE, defaulting to stratus/airflow:dev.
#   There are no positional arguments. Run the artifact resolver first whenever the
#   lock changes or the wheelhouse is absent.
#
# Outputs:
#   - the local OCI image named by STRATUS_AIRFLOW_IMAGE;
#   - artifacts/development-image-id.txt containing the resulting immutable image ID;
#   - structured timestamped build events on standard output.
#
# Failure and recovery:
#   Hash failure, a missing locked artifact, an unexpected wheelhouse file, or a pip
#   selection mismatch stops execution before docker build. Docker updates the target
#   tag only after successful assembly. Cache replacement and recovery belong to the
#   resolver; this script mounts the image definition read-only during preflight.
#
# Usage:
#   bash platform/airflow/image/scripts/build/airflow-image-build.sh
#   STRATUS_AIRFLOW_IMAGE=example/airflow:test bash \
#     platform/airflow/image/scripts/build/airflow-image-build.sh
#
# Maintenance:
#   Keep the hash check, offline pip report, exact file-set comparison and digest-based
#   source image lookup aligned. Never weaken them to make a stale cache build. Update
#   AirflowArtifactBaselineTest and AirflowWheelhouseBehaviorTest with this contract.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
IMAGE_TAG="${STRATUS_AIRFLOW_IMAGE:-stratus/airflow:dev}"
START_NS="$(date +%s%N)"
AIRFLOW_BASE_IMAGE="$(awk -F= '$1 == "airflow.image" {sub(/^[^=]*=/, ""); sub(/\r$/, ""); print; exit}' "${IMAGE_DIR}/artifact-lock.properties")"
AIRFLOW_BASE_DIGEST="$(awk -F= '$1 == "airflow.image.digest" {sub(/^[^=]*=/, ""); sub(/\r$/, ""); print; exit}' "${IMAGE_DIR}/artifact-lock.properties")"

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
if [[ -z "${AIRFLOW_BASE_IMAGE}" || -z "${AIRFLOW_BASE_DIGEST}" ]]; then
  log ERROR missing_base_image_lock "path=${IMAGE_DIR}/artifact-lock.properties"
  exit 1
fi
MSYS_NO_PATHCONV=1 docker run --rm --user 0:0 --entrypoint /bin/bash \
  --volume "${IMAGE_DIR}:/workspace:ro" \
  "${AIRFLOW_BASE_IMAGE}@${AIRFLOW_BASE_DIGEST}" \
  -ec '
    report=/tmp/stratus-wheelhouse-pip-report.json
    selected=/tmp/stratus-wheelhouse-selected-artifacts.txt
    python -m pip install --dry-run --ignore-installed --disable-pip-version-check \
      --no-build-isolation --no-index --find-links=/workspace/artifacts/wheelhouse \
      --no-deps --require-hashes --requirement=/workspace/requirements.lock \
      --report "${report}" >/dev/null
    source /workspace/scripts/lib/airflow-wheelhouse-integrity.sh
    pip_report_selected_artifacts "${report}" "${selected}"
    verify_wheelhouse_exact_file_set "${selected}" /workspace/artifacts/wheelhouse
  '
WHEELHOUSE_BYTES="$(du -sb "${IMAGE_DIR}/artifacts/wheelhouse" | awk '{print $1}')"
log INFO wheelhouse_lock_verified "wheelhouse_bytes=${WHEELHOUSE_BYTES}"
log INFO build_started "image=${IMAGE_TAG} context=${IMAGE_DIR} wheelhouse_bytes=${WHEELHOUSE_BYTES}"
docker build --pull=false --tag "${IMAGE_TAG}" "${IMAGE_DIR}"
IMAGE_ID="$(docker image inspect "${IMAGE_TAG}" --format '{{.Id}}')"
printf '%s' "${IMAGE_ID}" > "${IMAGE_DIR}/artifacts/development-image-id.txt"
log INFO build_completed "image=${IMAGE_TAG} image_id=${IMAGE_ID} duration_ms=$(( ($(date +%s%N) - START_NS) / 1000000 ))"
