#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-18
source "$(dirname "$0")/../lib/airflow-compose-common.sh"

# Works even when .env is absent or damaged; PostgreSQL data and logs persist.
compose_teardown down --remove-orphans
log "Airflow developer deployment stopped; PostgreSQL and log volumes are preserved"
