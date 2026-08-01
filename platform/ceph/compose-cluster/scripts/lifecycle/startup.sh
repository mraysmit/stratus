#!/usr/bin/env bash
set -euo pipefail

# Compatibility entry point retained for existing harness automation.
exec "$(dirname "$0")/ceph-compose-startup.sh" "$@"
