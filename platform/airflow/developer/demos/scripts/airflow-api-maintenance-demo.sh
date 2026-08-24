#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-24
# Purpose: demonstrate API-driven Airflow maintenance and independent data-plane verification.

readonly DEMO_ID="api-maintenance"
readonly DEMO_TITLE="REST API-driven Iceberg maintenance"
readonly DEMO_ROLE="Airflow authenticates a caller, schedules policy-controlled maintenance, and reports bounded task state and timing"
readonly ESTIMATED_DURATION="8-10 minutes"
readonly DATA_PLANE_REQUIRED="false"
readonly HARNESS_SCRIPT="airflow-api-orchestration-live-test.sh"
readonly EVIDENCE_PREFIX="airflow-api-orchestration"
readonly EXPECTED_RESULT_FILE="$(cd "$(dirname "$0")/../expected-results" && pwd)/api-maintenance.md"
readonly -a required_markers=(
  "event=airflow_orchestration_verification_completed status=SUCCESS"
  "AIRFLOW TABLE MAINTENANCE RUN VERIFIED"
  "AIRFLOW BRONZE TO SILVER BLOCK VERIFIED"
  "event=airflow_api_orchestration_suite_completed"
)

# This accepted harness owns its full provider lifecycle because API authentication, scheduling,
# side-effect verification, and cleanup are one indivisible control-plane proof.
source "$(dirname "$0")/airflow-demo-common.sh"
run_airflow_demo "$@"
