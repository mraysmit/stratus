#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-07-22
source "$(dirname "$0")/../lib/common.sh"

# Destroys the cluster including every configuration and data volume; the next
# startup bootstraps a brand-new Ceph cluster. Interactive confirmation is the
# default because this is the harness's only data-destroying entry point;
# --force/-y exists for the self-test and scripted teardown. Like shutdown,
# reset must work when .env is absent, via the fixed compose project name.

if [[ "${1:-}" != "--force" && "${1:-}" != "-y" ]]; then
  printf 'This permanently deletes the Compose cluster containers and ALL cluster configuration and data volumes.\n'
  read -r -p 'Type yes to continue: ' answer
  [[ "$answer" == yes ]] || fail "Reset cancelled"
fi
if [[ -f "$HARNESS_DIR/.env" ]]; then
  load_environment_file
  compose --profile verification down --volumes --remove-orphans
else
  compose_teardown down --volumes --remove-orphans
fi
log "Removed the disposable Compose cluster containers, network, configuration volume, and data volume."
