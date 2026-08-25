#!/usr/bin/env bash
# Resolve and verify every non-container input for the Stratus Airflow image.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LOCK_FILE="${IMAGE_DIR}/artifact-lock.properties"
ARTIFACT_DIR="${IMAGE_DIR}/artifacts"
WHEELHOUSE_DIR="${ARTIFACT_DIR}/wheelhouse"
STAGING_DIR=""
START_NS="$(date +%s%N)"

log() {
  printf 'timestamp=%s component=airflow-image-resolver level=%s event=%s %s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2" "${3:-}"
}

elapsed_ms() {
  printf '%s' "$(( ($(date +%s%N) - $1) / 1000000 ))"
}

cleanup() {
  if [[ -n "${STAGING_DIR}" && -d "${STAGING_DIR}" ]]; then
    rm -rf -- "${STAGING_DIR}"
  fi
}

trap cleanup EXIT

property() {
  local key="$1"
  local value
  value="$(awk -F= -v key="${key}" '$1 == key {sub(/^[^=]*=/, ""); sub(/\r$/, ""); print; exit}' "${LOCK_FILE}")"
  if [[ -z "${value}" ]]; then
    log ERROR missing_lock_property "key=${key}"
    return 1
  fi
  printf '%s' "${value}"
}

for command in awk curl docker sha256sum; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    log ERROR missing_command "command=${command}"
    exit 1
  fi
done

AIRFLOW_IMAGE="$(property airflow.image)"
AIRFLOW_DIGEST="$(property airflow.image.digest)"
CONSTRAINTS_NAME="$(property constraints.name)"
CONSTRAINTS_URL="$(property constraints.url)"
CONSTRAINTS_SHA256="$(property constraints.sha256)"
mkdir -p "${ARTIFACT_DIR}"
log INFO resolution_started "airflow_image=${AIRFLOW_IMAGE}@${AIRFLOW_DIGEST}"

step_ns="$(date +%s%N)"
if [[ -f "${ARTIFACT_DIR}/${CONSTRAINTS_NAME}" ]] && (
  cd "${ARTIFACT_DIR}"
  printf '%s  %s\n' "${CONSTRAINTS_SHA256}" "${CONSTRAINTS_NAME}" | sha256sum --check --status
); then
  log INFO artifact_reused "name=${CONSTRAINTS_NAME}"
else
  curl --proto '=https' --tlsv1.2 --fail --location --retry 3 \
    --output "${ARTIFACT_DIR}/${CONSTRAINTS_NAME}" "${CONSTRAINTS_URL}"
fi
(
  cd "${ARTIFACT_DIR}"
  printf '%s  %s\n' "${CONSTRAINTS_SHA256}" "${CONSTRAINTS_NAME}" | sha256sum --check
)
for required in \
  'apache-airflow-providers-amazon==9.34.0' \
  'apache-airflow-providers-apache-spark==6.3.1' \
  'aiohttp==3.14.3' \
  'boto3==1.43.56'; do
  if ! grep -Fx "${required}" "${ARTIFACT_DIR}/${CONSTRAINTS_NAME}" >/dev/null; then
    log ERROR incompatible_constraint "requirement=${required}"
    exit 1
  fi
done
log INFO constraints_verified "duration_ms=$(elapsed_ms "${step_ns}") sha256=${CONSTRAINTS_SHA256}"

step_ns="$(date +%s%N)"
STAGING_DIR="$(mktemp -d "${ARTIFACT_DIR}/wheelhouse.next.XXXXXX")"
STAGING_NAME="$(basename "${STAGING_DIR}")"
MSYS_NO_PATHCONV=1 docker run --rm --user 0:0 --entrypoint /bin/bash \
  --env STRATUS_WHEELHOUSE_PATH="/workspace/artifacts/${STAGING_NAME}" \
  --volume "${IMAGE_DIR}:/workspace" \
  "${AIRFLOW_IMAGE}@${AIRFLOW_DIGEST}" \
  -ec 'python -m pip download --disable-pip-version-check --no-deps --no-build-isolation --timeout 60 --retries 5 --require-hashes --dest "${STRATUS_WHEELHOUSE_PATH}" --requirement /workspace/requirements.lock'
(
  cd "${STAGING_DIR}"
  find . -maxdepth 1 -type f ! -name resolved-artifacts.sha256 -print0 \
    | sort -z \
    | xargs -0 sha256sum > resolved-artifacts.sha256
  sha256sum --check resolved-artifacts.sha256
)
PREVIOUS_DIR="${ARTIFACT_DIR}/wheelhouse.previous"
rm -rf -- "${PREVIOUS_DIR}"
if [[ -d "${WHEELHOUSE_DIR}" ]]; then
  mv "${WHEELHOUSE_DIR}" "${PREVIOUS_DIR}"
fi
mv "${STAGING_DIR}" "${WHEELHOUSE_DIR}"
STAGING_DIR=""
rm -rf -- "${PREVIOUS_DIR}"
log INFO python_artifacts_verified "duration_ms=$(elapsed_ms "${step_ns}") count=$(find "${WHEELHOUSE_DIR}" -maxdepth 1 -type f ! -name resolved-artifacts.sha256 | wc -l | tr -d ' ')"
log INFO resolution_completed "duration_ms=$(elapsed_ms "${START_NS}") artifact_dir=${ARTIFACT_DIR}"
