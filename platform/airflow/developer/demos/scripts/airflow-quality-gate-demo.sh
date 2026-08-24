#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-24
# Purpose: demonstrate an accepted promotion and a blocking quality result that prevents a write.

readonly DEMO_ID="quality-gate"
readonly DEMO_TITLE="Fail-closed bronze-to-silver quality gate"
readonly DEMO_ROLE="Airflow exposes the promotion decision and stops downstream work when governed evidence fails"
readonly ESTIMATED_DURATION="10-12 minutes"
readonly DATA_PLANE_REQUIRED="true"
readonly HARNESS_SCRIPT="airflow-bronze-to-silver-live-test.sh"
readonly EVIDENCE_PREFIX="airflow-bronze-to-silver"
readonly EXPECTED_RESULT_FILE="$(cd "$(dirname "$0")/../expected-results" && pwd)/quality-gate.md"
readonly -a required_markers=(
  "AIRFLOW BRONZE TO SILVER VERIFIED"
  "AIRFLOW BRONZE TO SILVER BLOCK VERIFIED"
  "event=airflow_bronze_to_silver_suite_completed"
)

source "$(dirname "$0")/airflow-demo-common.sh"
run_airflow_demo "$@"
