#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-08
source "$(dirname "$0")/../lib/spark-compose-common.sh"

# Stops the Spark developer cluster. Teardown must work when .env is missing
# or unusable, so it never loads or validates the environment file; it tears
# down by compose project name alone. Enforced by HarnessShutdownBehaviorTest.

usage() {
  cat <<'EOF'
Usage: spark-compose-shutdown.sh [--volumes]

  --volumes  Also remove the event-log and scratch volumes.
EOF
}

remove_volumes=false
case "${1:-}" in
  "") ;;
  --volumes) remove_volumes=true ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; fail "Unknown argument: $1" ;;
esac

if [[ "$remove_volumes" == true ]]; then
  compose_teardown down --remove-orphans --volumes
  log "Spark harness stopped; event-log and scratch volumes removed"
else
  compose_teardown down --remove-orphans
  log "Spark harness stopped; event-log and scratch volumes are preserved"
fi
