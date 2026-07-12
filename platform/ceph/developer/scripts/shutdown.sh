#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
if [[ -f "$HARNESS_DIR/.env" ]]; then load_environment; fi
compose down --remove-orphans
