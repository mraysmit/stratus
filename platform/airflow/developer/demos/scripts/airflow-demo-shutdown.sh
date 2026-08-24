#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-24
# Purpose: stop an Airflow demo through every checked Stratus lifecycle script and verify cleanup.

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
readonly AIRFLOW_HARNESS_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
readonly REPOSITORY_DIR="$(cd "$AIRFLOW_HARNESS_DIR/../../.." && pwd)"
readonly CEPH_DIR="$REPOSITORY_DIR/platform/ceph/compose-cluster"
readonly OPENBAO_DIR="$REPOSITORY_DIR/platform/openbao/compose-service"
readonly POLARIS_DIR="$REPOSITORY_DIR/platform/polaris/compose-service"
readonly SPARK_DIR="$REPOSITORY_DIR/platform/spark/compose-cluster"

shutdown_failed=false

shutdown_checked() {
  local name="$1" script="$2"
  if bash "$script"; then
    return 0
  fi
  printf 'WARN: %s shutdown failed; retrying once\n' "$name" >&2
  bash "$script" || shutdown_failed=true
}

shutdown_checked airflow "$AIRFLOW_HARNESS_DIR/scripts/lifecycle/airflow-compose-shutdown.sh"
shutdown_checked spark "$SPARK_DIR/scripts/lifecycle/spark-compose-shutdown.sh"
shutdown_checked polaris "$POLARIS_DIR/scripts/lifecycle/polaris-compose-shutdown.sh"
shutdown_checked openbao "$OPENBAO_DIR/scripts/lifecycle/openbao-compose-shutdown.sh"
shutdown_checked ceph "$CEPH_DIR/scripts/lifecycle/ceph-compose-shutdown.sh"

remaining="$(docker ps --format '{{.Names}}' \
  --filter 'name=stratus-airflow' --filter 'name=stratus-spark' \
  --filter 'name=stratus-polaris' --filter 'name=stratus-openbao' \
  --filter 'name=stratus-ceph')"
remaining_count=0
if [[ -n "$remaining" ]]; then
  remaining_count="$(printf '%s\n' "$remaining" | wc -l | tr -d ' ')"
  printf 'ERROR: Stratus demo containers remain: %s\n' "${remaining//$'\n'/,}" >&2
fi

printf '%s event=airflow_demo_shutdown_completed remainingStratusContainers=%s status=%s\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)" "$remaining_count" \
  "$({ $shutdown_failed || [[ "$remaining_count" -ne 0 ]]; } && printf FAILURE || printf SUCCESS)"

if $shutdown_failed || [[ "$remaining_count" -ne 0 ]]; then
  exit 1
fi
