#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-18

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPO_DIR="$(cd "$HARNESS_DIR/../../.." && pwd)"
AIRFLOW_COMPOSE_PROJECT="stratus-airflow-local"

log_timestamp() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }
log() { printf '%s %s\n' "$(log_timestamp)" "$*"; }
fail() { printf '%s ERROR: %s\n' "$(log_timestamp)" "$*" >&2; exit 1; }

load_environment_file() {
  [[ -f "$HARNESS_DIR/.env" ]] \
    || fail "Create $HARNESS_DIR/.env from .env.template (startup does this automatically)"
  set -a
  # shellcheck disable=SC1091
  source "$HARNESS_DIR/.env"
  set +a
}

load_environment() {
  load_environment_file
  : "${AIRFLOW_IMAGE:?AIRFLOW_IMAGE is required}"
  : "${POSTGRES_IMAGE:?POSTGRES_IMAGE is required}"
  : "${AIRFLOW_DB_PASSWORD:?AIRFLOW_DB_PASSWORD is required}"
  : "${AIRFLOW_FERNET_KEY:?AIRFLOW_FERNET_KEY is required}"
  : "${AIRFLOW_JWT_SECRET:?AIRFLOW_JWT_SECRET is required}"
  : "${AIRFLOW_API_SECRET_KEY:?AIRFLOW_API_SECRET_KEY is required}"
  [[ "$AIRFLOW_IMAGE" != *latest* ]] || fail "AIRFLOW_IMAGE must be pinned, never latest"
  [[ "$POSTGRES_IMAGE" == postgres:17.10* ]] \
    || fail "POSTGRES_IMAGE must remain on the approved PostgreSQL 17.10 matrix entry"
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
  local runtime project_dir env_file compose_file overlay_file
  local -a compose_files
  runtime="$(compose_runtime)"
  project_dir="$HARNESS_DIR"
  env_file="$HARNESS_DIR/.env"
  compose_file="$HARNESS_DIR/compose.yaml"
  compose_files=(-f "$compose_file")
  if [[ -n "${AIRFLOW_COMPOSE_OVERLAY:-}" ]]; then
    overlay_file="$AIRFLOW_COMPOSE_OVERLAY"
    compose_files+=(-f "$overlay_file")
  fi
  if [[ -n "${MSYSTEM:-}" ]] && command -v cygpath >/dev/null 2>&1; then
    project_dir="$(cygpath -w "$project_dir")"
    env_file="$(cygpath -w "$env_file")"
    compose_file="$(cygpath -w "$compose_file")"
    compose_files=(-f "$compose_file")
    if [[ -n "${AIRFLOW_COMPOSE_OVERLAY:-}" ]]; then
      overlay_file="$(cygpath -w "$AIRFLOW_COMPOSE_OVERLAY")"
      compose_files+=(-f "$overlay_file")
    fi
    MSYS_NO_PATHCONV=1 "$runtime" compose --project-directory "$project_dir" \
      --env-file "$env_file" "${compose_files[@]}" "$@"
  else
    "$runtime" compose --project-directory "$project_dir" --env-file "$env_file" \
      "${compose_files[@]}" "$@"
  fi
}

# Teardown deliberately does not load .env or interpolate compose.yaml.
compose_teardown() {
  if [[ -n "${MSYSTEM:-}" ]]; then
    MSYS_NO_PATHCONV=1 "$(compose_runtime)" compose --project-name "$AIRFLOW_COMPOSE_PROJECT" "$@"
  else
    "$(compose_runtime)" compose --project-name "$AIRFLOW_COMPOSE_PROJECT" "$@"
  fi
}

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
