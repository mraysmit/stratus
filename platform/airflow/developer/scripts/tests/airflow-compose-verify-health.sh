#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-18
source "$(dirname "$0")/../lib/airflow-compose-common.sh"
load_environment

health_components_healthy() {
  local payload="$1"
  local compact
  local component
  compact="$(tr -d '[:space:]' <<<"$payload")"
  for component in metadatabase scheduler triggerer dag_processor; do
    [[ "$compact" == *"\"${component}\":{\"status\":\"healthy\""* ]] || return 1
  done
}

endpoint="http://${AIRFLOW_BIND_ADDRESS:-127.0.0.1}:${AIRFLOW_API_PORT:-8088}/api/v2/monitor/health"
deadline=$((SECONDS + ${AIRFLOW_STARTUP_DEADLINE_SECONDS:-180}))
health=''
while (( SECONDS < deadline )); do
  health="$(curl --silent --show-error --max-time 10 "$endpoint" 2>/dev/null || true)"
  if health_components_healthy "$health"; then
    break
  fi
  sleep 3
done

health_components_healthy "$health" \
  || fail "Unhealthy Airflow components: $health"

compose exec -T airflow-api-server airflow db check
compose exec -T airflow-scheduler airflow jobs check --job-type SchedulerJob --local
# Airflow emits CLI deprecation warnings to stdout before the requested value.
# Select the final line so observability output cannot corrupt the value assertion.
executor="$(compose exec -T airflow-scheduler airflow config get-value core executor \
  | tail -n 1 | tr -d '\r')"
[[ "$executor" == LocalExecutor ]] || fail "Expected LocalExecutor, observed '$executor'"
postgres_version="$(compose exec -T postgres psql -U airflow -d airflow -Atc 'SHOW server_version;' | tr -d '\r')"
[[ "$postgres_version" == 17.10* ]] || fail "Expected PostgreSQL 17.10, observed '$postgres_version'"

log "READY airflow=3.3.1 executor=$executor postgres=$postgres_version endpoint=$endpoint"
compose ps
