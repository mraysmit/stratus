#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-16

# Build-only helpers shared by the Spark harness. Keep these independent of
# provider connection files so focused-test artifacts can be prepared while
# the complete Stratus environment is stopped.
HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPO_DIR="$(cd "$HARNESS_DIR/../../.." && pwd)"

log_timestamp() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }
log() { printf '%s %s\n' "$(log_timestamp)" "$*"; }
fail() { printf '%s ERROR: %s\n' "$(log_timestamp)" "$*" >&2; exit 1; }

# A Windows checkout may deliberately use CRLF for the wrapper scripts. Git
# Bash cannot execute that mvnw shebang, while cmd.exe can execute mvnw.cmd.
# Keep every harness entry point on the repository wrapper without requiring
# developers to rewrite tracked files locally.
repository_maven() {
  if [[ -n "${MSYSTEM:-}" ]]; then
    # Git Bash otherwise rewrites cmd.exe's /d and /c switches as paths.
    MSYS_NO_PATHCONV=1 cmd.exe /d /c mvnw.cmd "$@"
  elif [[ -n "${WSL_DISTRO_NAME:-}" ]]; then
    cmd.exe /d /c mvnw.cmd "$@"
  else
    ./mvnw "$@"
  fi
}
