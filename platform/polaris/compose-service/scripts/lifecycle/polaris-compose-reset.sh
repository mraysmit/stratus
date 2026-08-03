#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-03
source "$(dirname "$0")/../lib/polaris-compose-common.sh"

# Destroys the disposable Polaris state — containers and the polaris-data
# volume — for a fresh catalog on the next startup. .env, evidence, and logs
# are preserved. Prompts unless forced; validates nothing else is targeted.

force=false
[[ "${1:-}" == "--force" ]] && force=true
if [[ "$force" != true ]]; then
  printf 'Destroy the stratus-polaris-local containers and catalog data volume? [y/N] '
  read -r answer
  [[ "$answer" == y || "$answer" == Y ]] || fail "Reset cancelled; nothing was removed"
fi

compose_teardown down --volumes --remove-orphans
log "Polaris harness reset; disposable catalog state destroyed"
