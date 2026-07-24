#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-07-22
source "$(dirname "$0")/../lib/common.sh"

# Stops the cluster while preserving all configuration and data volumes, so
# the next startup resumes the same cluster. Idempotent, and must work even
# when .env is absent or broken: without .env the compose file cannot be
# interpolated, so teardown falls back to the fixed compose project name.

if [[ -f "$HARNESS_DIR/.env" ]]; then
  load_environment_file
  compose --profile verification down --remove-orphans
else
  compose_teardown down --remove-orphans
fi
