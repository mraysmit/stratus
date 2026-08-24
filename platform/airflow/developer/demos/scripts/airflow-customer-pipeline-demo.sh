#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-24
# Purpose: demonstrate customer data progressing through Airflow-controlled bronze, silver, and gold stages.

readonly DEMO_ID="customer-pipeline"
readonly DEMO_TITLE="Customer landing-to-gold pipeline"
readonly DEMO_ROLE="Airflow detects work, coordinates Spark, enforces promotion evidence, and records the run"
readonly ESTIMATED_DURATION="12-15 minutes"
readonly DATA_PLANE_REQUIRED="true"
readonly HARNESS_SCRIPT="airflow-silver-to-gold-live-test.sh"
readonly EVIDENCE_PREFIX="airflow-silver-to-gold"
readonly EXPECTED_RESULT_FILE="$(cd "$(dirname "$0")/../expected-results" && pwd)/customer-pipeline.md"
readonly -a required_markers=(
  "AIRFLOW SILVER TO GOLD VERIFIED"
  "AIRFLOW SILVER TO GOLD BLOCK VERIFIED"
  "event=airflow_silver_to_gold_suite_completed"
)

# The accepted harness remains the executable source of truth; this entry point adds the demo
# narrative, dependency lifecycle, marker summary, optional UI inspection, and one-command cleanup.
source "$(dirname "$0")/airflow-demo-common.sh"
run_airflow_demo "$@"
