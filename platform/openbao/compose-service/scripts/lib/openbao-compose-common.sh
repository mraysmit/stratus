#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-04

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPO_DIR="$(cd "$HARNESS_DIR/../../.." && pwd)"

# All harness status output carries an ISO-8601 UTC timestamp.
log_timestamp() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }
log() { printf '%s %s\n' "$(log_timestamp)" "$*"; }
fail() { printf '%s ERROR: %s\n' "$(log_timestamp)" "$*" >&2; exit 1; }

load_environment_file() {
  [[ -f "$HARNESS_DIR/.env" ]] || fail "Create $HARNESS_DIR/.env from .env.template (lifecycle/openbao-compose-startup.sh does this)"
  set -a
  # shellcheck disable=SC1091
  source "$HARNESS_DIR/.env"
  set +a
}

load_environment() {
  load_environment_file
  : "${OPENBAO_IMAGE:?OPENBAO_IMAGE is required}"
  [[ "$OPENBAO_IMAGE" != *latest* ]] || fail "OPENBAO_IMAGE must be a pinned release, never latest"
}

compose_runtime() {
  local implementation="${COMPOSE_IMPLEMENTATION:-auto}"
  if [[ "$implementation" == docker ]] || { [[ "$implementation" == auto ]] && command -v docker >/dev/null 2>&1; }; then
    printf 'docker'
  elif [[ "$implementation" == podman ]] || [[ "$implementation" == auto ]]; then
    command -v podman >/dev/null 2>&1 || fail "Neither Docker Compose nor Podman is available"
    printf 'podman'
  else
    fail "COMPOSE_IMPLEMENTATION must be auto, docker, or podman"
  fi
}

compose() {
  local runtime project_dir env_file compose_file
  runtime="$(compose_runtime)"
  project_dir="$HARNESS_DIR"
  env_file="$HARNESS_DIR/.env"
  compose_file="$HARNESS_DIR/compose.yaml"
  if [[ -n "${MSYSTEM:-}" ]] && command -v cygpath >/dev/null 2>&1; then
    project_dir="$(cygpath -w "$project_dir")"
    env_file="$(cygpath -w "$env_file")"
    compose_file="$(cygpath -w "$compose_file")"
    MSYS_NO_PATHCONV=1 "$runtime" compose --project-directory "$project_dir" --env-file "$env_file" -f "$compose_file" "$@"
  else
    "$runtime" compose --project-directory "$project_dir" --env-file "$env_file" -f "$compose_file" "$@"
  fi
}

# Tears down by compose project name alone, so it works even when .env is
# missing and the compose file's required variables cannot be interpolated.
compose_teardown() {
  if [[ -n "${MSYSTEM:-}" ]]; then
    MSYS_NO_PATHCONV=1 "$(compose_runtime)" compose --project-name stratus-openbao-local "$@"
  else
    "$(compose_runtime)" compose --project-name stratus-openbao-local "$@"
  fi
}

# chmod cannot strip inherited NTFS ACLs, so under Git Bash on Windows the
# secret files additionally get an owner-only icacls grant. Best-effort: ACL
# hardening must never abort the harness on an exotic filesystem.
harden_windows_acl() {
  local target grant account
  [[ -n "${MSYSTEM:-}" ]] && command -v icacls.exe >/dev/null 2>&1 || return 0
  account="${USERDOMAIN:-}${USERDOMAIN:+\\}${USERNAME:-$(whoami)}"
  for target in "$@"; do
    [[ -e "$target" ]] || continue
    grant="${account}:F"
    [[ -d "$target" ]] && grant="${account}:(OI)(CI)F"
    MSYS_NO_PATHCONV=1 icacls.exe "$(cygpath -w "$target")" \
      /inheritance:r /grant:r "$grant" >/dev/null 2>&1 || true
  done
}
