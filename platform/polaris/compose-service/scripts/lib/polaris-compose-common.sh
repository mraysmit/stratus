#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-03

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPO_DIR="$(cd "$HARNESS_DIR/../../.." && pwd)"

# All harness status output carries an ISO-8601 UTC timestamp.
log_timestamp() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }
log() { printf '%s %s\n' "$(log_timestamp)" "$*"; }
fail() { printf '%s ERROR: %s\n' "$(log_timestamp)" "$*" >&2; exit 1; }

# The single hardcoded provider value: the Ceph harness directory, stable under
# the guardrail-enforced repository layout. Every other provider value —
# network name, endpoints, CA location — comes from the provider's published
# connection.env (ADR-P1-003) and must never be copied here or into
# any other consumer file.
CEPH_HARNESS_DIR="$REPO_DIR/platform/ceph/compose-cluster"
[[ -f "$CEPH_HARNESS_DIR/connection.env" ]] \
  || fail "Missing $CEPH_HARNESS_DIR/connection.env; the Ceph harness must publish its connection settings"
set -a
# shellcheck disable=SC1091
source "$CEPH_HARNESS_DIR/connection.env"
set +a
: "${CEPH_HARNESS_NETWORK:?connection.env must define CEPH_HARNESS_NETWORK}"
: "${CEPH_RGW_ENDPOINT:?connection.env must define CEPH_RGW_ENDPOINT}"
: "${CEPH_HARNESS_CA_CERT:?connection.env must define CEPH_HARNESS_CA_CERT}"

# Absolute CA path for Compose volume mounts and curl. Mixed form (C:/...)
# under Git Bash so Docker receives a Windows path while Linux is unaffected.
CEPH_HARNESS_CA_FILE="$CEPH_HARNESS_DIR/$CEPH_HARNESS_CA_CERT"
if [[ -n "${MSYSTEM:-}" ]] && command -v cygpath >/dev/null 2>&1; then
  CEPH_HARNESS_CA_FILE="$(cygpath -m "$CEPH_HARNESS_CA_FILE")"
fi
export CEPH_HARNESS_CA_FILE

# Loads .env without validation. Teardown paths use this so a half-configured
# harness can still be shut down.
load_environment_file() {
  [[ -f "$HARNESS_DIR/.env" ]] || fail "Create $HARNESS_DIR/.env from .env.template (lifecycle/polaris-compose-startup.sh does this)"
  set -a
  # shellcheck disable=SC1091
  source "$HARNESS_DIR/.env"
  set +a
}

load_environment() {
  load_environment_file
  : "${POLARIS_IMAGE:?POLARIS_IMAGE is required}"
  [[ "$POLARIS_IMAGE" != *latest* ]] || fail "POLARIS_IMAGE must be a pinned release, never latest"
  [[ -f "$CEPH_HARNESS_DIR/$CEPH_HARNESS_CA_CERT" ]] \
    || fail "Missing $CEPH_HARNESS_DIR/$CEPH_HARNESS_CA_CERT; start the Ceph harness once to generate it"
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
    MSYS_NO_PATHCONV=1 "$(compose_runtime)" compose --project-name stratus-polaris-local "$@"
  else
    "$(compose_runtime)" compose --project-name stratus-polaris-local "$@"
  fi
}

# ADR-P1-003: this harness attaches to the Ceph harness network and must
# never start the provider transitively. Fail fast with the remediation.
require_ceph_harness_network() {
  "$(compose_runtime)" network inspect "$CEPH_HARNESS_NETWORK" >/dev/null 2>&1 \
    || fail "The Ceph harness network '$CEPH_HARNESS_NETWORK' does not exist. Start the Ceph harness first: bash platform/ceph/compose-cluster/scripts/lifecycle/ceph-compose-startup.sh"
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
