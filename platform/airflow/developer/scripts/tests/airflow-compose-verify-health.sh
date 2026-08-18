#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-18
source "$(dirname "$0")/../lib/airflow-compose-common.sh"
load_environment

endpoint="http://${AIRFLOW_BIND_ADDRESS:-127.0.0.1}:${AIRFLOW_API_PORT:-8088}/api/v2/monitor/health"
deadline=$((SECONDS + ${AIRFLOW_STARTUP_DEADLINE_SECONDS:-180}))
health=''
while (( SECONDS < deadline )); do
  health="$(curl --silent --show-error --max-time 10 "$endpoint" 2>/dev/null || true)"
  if HEALTH_JSON="$health" python -c 'import json, os, sys; data=json.loads(os.environ["HEALTH_JSON"]); expected=("metadatabase", "scheduler", "triggerer", "dag_processor"); sys.exit(0 if all(data.get(name, {}).get("status") == "healthy" for name in expected) else 1)' 2>/dev/null; then
    break
  fi
  sleep 3
done

HEALTH_JSON="$health" python -c 'import json, os; data=json.loads(os.environ["HEALTH_JSON"]); expected=("metadatabase", "scheduler", "triggerer", "dag_processor"); unhealthy={name:data.get(name) for name in expected if data.get(name, {}).get("status") != "healthy"}; assert not unhealthy, f"Unhealthy Airflow components: {unhealthy}"'

compose exec -T airflow-api-server airflow db check
compose exec -T airflow-scheduler airflow jobs check --job-type SchedulerJob --local
executor="$(compose exec -T airflow-scheduler airflow config get-value core executor | tr -d '\r')"
[[ "$executor" == LocalExecutor ]] || fail "Expected LocalExecutor, observed '$executor'"
postgres_version="$(compose exec -T postgres psql -U airflow -d airflow -Atc 'SHOW server_version;' | tr -d '\r')"
[[ "$postgres_version" == 17.10* ]] || fail "Expected PostgreSQL 17.10, observed '$postgres_version'"

log "READY airflow=3.3.1 executor=$executor postgres=$postgres_version endpoint=$endpoint"
compose ps
