#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-16

script_dir="$(cd "$(dirname "$0")" && pwd)"
source "$script_dir/../lib/spark-compose-maven-common.sh"
source "$script_dir/../lib/spark-compose-focused-test-artifacts.sh"

[[ "$#" -eq 0 ]] || fail "This command accepts no arguments"
focused_test_prepare_artifacts
