#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
if [[ "${1:-}" != "--force" && "${1:-}" != "-y" ]]; then
  printf 'This permanently deletes the local Ceph containers and ALL cluster configuration and data volumes.\n'
  read -r -p 'Type yes to continue: ' answer
  [[ "$answer" == yes ]] || fail "Reset cancelled"
fi
if [[ -f "$HARNESS_DIR/.env" ]]; then
  load_environment_file
  compose --profile verification down --volumes --remove-orphans
else
  compose_teardown down --volumes --remove-orphans
fi
log "Removed the disposable local Ceph containers, network, configuration volume, and data volume."
