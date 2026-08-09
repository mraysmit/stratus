#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-07-22
source "$(dirname "$0")/../lib/ceph-compose-common.sh"

# Destroys the cluster including every configuration and data volume; the next
# startup bootstraps a brand-new Ceph cluster. Interactive confirmation is the
# default because this is the harness's only data-destroying entry point;
# --force/-y exists for the self-test and scripted teardown. Like shutdown,
# reset must work when .env is absent, via the fixed compose project name.

usage() {
  cat <<'EOF'
Usage: ceph-compose-reset.sh [--force|-y]

Destroys the Compose cluster containers and ALL cluster configuration and data
volumes. Prompts for confirmation unless forced.
EOF
}

# An unrecognised argument is rejected rather than ignored. Falling through to
# the prompt means a mistyped --force reads the operator's next keystroke as
# the confirmation, and --help on a data-destroying script would otherwise
# open a destroy prompt.
force=false
case "${1:-}" in
  "") ;;
  --force|-y) force=true ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; fail "Unknown argument: $1" ;;
esac

if [[ "$force" != true ]]; then
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
