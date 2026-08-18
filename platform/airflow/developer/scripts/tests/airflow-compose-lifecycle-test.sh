#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-18
source "$(dirname "$0")/../lib/airflow-compose-common.sh"

mkdir -p "$HARNESS_DIR/evidence"
evidence_file="$HARNESS_DIR/evidence/airflow-lifecycle-$(date -u +%Y%m%dT%H%M%SZ).log"
exec > >(tee "$evidence_file") 2>&1

for cycle in 1 2; do
  log "LIFECYCLE cycle=$cycle phase=start"
  bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-startup.sh"
  bash "$HARNESS_DIR/scripts/tests/airflow-compose-verify-health.sh"
  log "LIFECYCLE cycle=$cycle phase=stop"
  bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-shutdown.sh"
  log "LIFECYCLE cycle=$cycle outcome=passed"
done

log "Two Airflow developer lifecycle cycles passed; evidence=$evidence_file"
