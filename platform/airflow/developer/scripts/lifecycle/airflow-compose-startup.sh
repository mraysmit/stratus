#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-18
source "$(dirname "$0")/../lib/airflow-compose-common.sh"

rand_hex() { openssl rand -hex "$1"; }
rand_fernet_key() { openssl rand 32 | base64 | tr '+/' '-_' | tr -d '\r\n'; }

if [[ ! -f "$HARNESS_DIR/.env" ]]; then
  db_password="$(rand_hex 24)"
  fernet_key="$(rand_fernet_key)"
  jwt_secret="$(rand_hex 32)"
  api_secret="$(rand_hex 32)"
  sed \
    -e "s|^AIRFLOW_DB_PASSWORD=.*|AIRFLOW_DB_PASSWORD=$db_password|" \
    -e "s|^AIRFLOW_FERNET_KEY=.*|AIRFLOW_FERNET_KEY=$fernet_key|" \
    -e "s|^AIRFLOW_JWT_SECRET=.*|AIRFLOW_JWT_SECRET=$jwt_secret|" \
    -e "s|^AIRFLOW_API_SECRET_KEY=.*|AIRFLOW_API_SECRET_KEY=$api_secret|" \
    "$HARNESS_DIR/.env.template" >"$HARNESS_DIR/.env"
  chmod 600 "$HARNESS_DIR/.env"
  harden_windows_acl "$HARNESS_DIR/.env"
  log "Generated developer-only Airflow secrets in $HARNESS_DIR/.env"
fi

load_environment
mkdir -p "$HARNESS_DIR/logs" "$HARNESS_DIR/evidence"

"$(compose_runtime)" image inspect "$AIRFLOW_IMAGE" >/dev/null 2>&1 \
  || fail "Missing $AIRFLOW_IMAGE. Build it with: bash platform/airflow/image/scripts/build/airflow-image-resolve-artifacts.sh && bash platform/airflow/image/scripts/build/airflow-image-build.sh"
"$(compose_runtime)" image inspect "$POSTGRES_IMAGE" >/dev/null 2>&1 \
  || fail "Missing $POSTGRES_IMAGE. Pull it with: $(compose_runtime) pull $POSTGRES_IMAGE"

compose config --quiet
compose up --detach --wait --wait-timeout "${AIRFLOW_STARTUP_DEADLINE_SECONDS:-180}" postgres

migration_log="$HARNESS_DIR/logs/airflow-db-migrate-$(date -u +%Y%m%dT%H%M%SZ).log"
log "Running idempotent Airflow metadata migration"
compose run --rm airflow-init bash -c 'airflow db migrate && airflow db check' \
  2>&1 | tee "$migration_log"

compose up --detach --remove-orphans airflow-api-server airflow-dag-processor \
  airflow-scheduler airflow-triggerer
bash "$HARNESS_DIR/scripts/tests/airflow-compose-verify-health.sh"
log "Airflow developer deployment is healthy; migration output: $migration_log"
