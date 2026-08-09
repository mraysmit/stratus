#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-03
source "$(dirname "$0")/../lib/polaris-compose-common.sh"

# Destroys the disposable Polaris state — containers and the polaris-data
# volume — for a fresh catalog on the next startup. .env, evidence, and logs
# are preserved. Prompts unless forced; validates nothing else is targeted.

usage() {
  cat <<'EOF'
Usage: polaris-compose-reset.sh [--force]

Destroys the Polaris containers and the catalog data volume. Prompts for
confirmation unless forced. .env, evidence, and logs are preserved.
EOF
}

# An unrecognised argument is rejected rather than ignored: --help on a
# data-destroying script must not open a destroy prompt.
force=false
case "${1:-}" in
  "") ;;
  --force) force=true ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; fail "Unknown argument: $1" ;;
esac

if [[ "$force" != true ]]; then
  printf 'Destroy the stratus-polaris-local containers and catalog data volume? [y/N] '
  read -r answer
  [[ "$answer" == y || "$answer" == Y ]] || fail "Reset cancelled; nothing was removed"
fi

compose_teardown down --volumes --remove-orphans
log "Polaris harness reset; disposable catalog state destroyed"
