#!/usr/bin/env bash
set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$HARNESS_DIR/../../.." && pwd)"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

load_environment() {
  [[ -f "$HARNESS_DIR/.env" ]] || fail "Create $HARNESS_DIR/.env from .env.template"
  set -a
  # shellcheck disable=SC1091
  source "$HARNESS_DIR/.env"
  set +a
  : "${CEPH_RGW_ENDPOINT:?CEPH_RGW_ENDPOINT is required}"
  : "${CEPH_RGW_ACCESS_KEY:?CEPH_RGW_ACCESS_KEY is required}"
  : "${CEPH_RGW_SECRET_KEY:?CEPH_RGW_SECRET_KEY is required}"
  [[ -f "$HARNESS_DIR/certs/stratus-ca.crt" ]] || fail "Missing certs/stratus-ca.crt"
  [[ -f "$HARNESS_DIR/certs/object-store.stratus.local.crt" ]] || fail "Missing RGW server certificate"
  [[ -f "$HARNESS_DIR/private/object-store.stratus.local.key" ]] || fail "Missing RGW server private key"
  [[ "$CEPH_RGW_ENDPOINT" == https://* || "${CEPH_RGW_ALLOW_HTTP:-false}" == true ]] \
    || fail "CEPH_RGW_ENDPOINT must use HTTPS unless CEPH_RGW_ALLOW_HTTP=true"
}

compose() {
  local implementation="${COMPOSE_IMPLEMENTATION:-auto}"
  if [[ "$implementation" == docker ]] || { [[ "$implementation" == auto ]] && command -v docker >/dev/null 2>&1; }; then
    docker compose --project-directory "$HARNESS_DIR" --env-file "$HARNESS_DIR/.env" -f "$HARNESS_DIR/compose.yaml" "$@"
  elif [[ "$implementation" == podman ]] || [[ "$implementation" == auto ]]; then
    command -v podman >/dev/null 2>&1 || fail "Neither Docker Compose nor Podman is available"
    podman compose --project-directory "$HARNESS_DIR" --env-file "$HARNESS_DIR/.env" -f "$HARNESS_DIR/compose.yaml" "$@"
  else
    fail "COMPOSE_IMPLEMENTATION must be auto, docker, or podman"
  fi
}
