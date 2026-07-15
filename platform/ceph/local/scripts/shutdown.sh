#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
if [[ -f "$HARNESS_DIR/.env" ]]; then
  load_environment_file
  compose --profile verification down --remove-orphans
else
  compose_teardown down --remove-orphans
fi
