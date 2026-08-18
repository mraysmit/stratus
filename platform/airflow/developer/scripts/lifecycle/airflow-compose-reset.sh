#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-18
source "$(dirname "$0")/../lib/airflow-compose-common.sh"

usage() {
  printf '%s\n' 'Usage: airflow-compose-reset.sh [--force]' \
    '' 'Destroys the disposable PostgreSQL and Airflow log volumes.'
}

force=false
case "${1:-}" in
  "") ;;
  --force) force=true ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; fail "Unknown argument: $1" ;;
esac

if [[ "$force" != true ]]; then
  printf 'Destroy the stratus-airflow-local containers and data volumes? [y/N] '
  read -r answer
  [[ "$answer" == y || "$answer" == Y ]] || fail "Reset cancelled; nothing was removed"
fi

compose_teardown down --volumes --remove-orphans
log "Airflow developer deployment reset; disposable metadata and logs destroyed"
