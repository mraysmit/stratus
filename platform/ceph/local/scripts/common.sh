#!/usr/bin/env bash
set -euo pipefail

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd "$HARNESS_DIR/../../.." && pwd)"

# All harness status output carries an ISO-8601 UTC timestamp.
log_timestamp() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }
log() { printf '%s %s\n' "$(log_timestamp)" "$*"; }
fail() { printf '%s ERROR: %s\n' "$(log_timestamp)" "$*" >&2; exit 1; }

# Loads .env without validating certificates or endpoints. Teardown paths use
# this so a half-configured harness can still be shut down or reset.
load_environment_file() {
  [[ -f "$HARNESS_DIR/.env" ]] || fail "Create $HARNESS_DIR/.env from .env.template"
  set -a
  # shellcheck disable=SC1091
  source "$HARNESS_DIR/.env"
  set +a
}

load_environment() {
  load_environment_file
  : "${CEPH_RGW_ENDPOINT:?CEPH_RGW_ENDPOINT is required}"
  : "${CEPH_RGW_ACCESS_KEY:?CEPH_RGW_ACCESS_KEY is required}"
  : "${CEPH_RGW_SECRET_KEY:?CEPH_RGW_SECRET_KEY is required}"
  [[ -f "$HARNESS_DIR/certs/stratus-ca.crt" ]] || fail "Missing certs/stratus-ca.crt"
  [[ -f "$HARNESS_DIR/certs/object-store.stratus.local.crt" ]] || fail "Missing RGW server certificate"
  [[ -f "$HARNESS_DIR/private/object-store.stratus.local.key" ]] || fail "Missing RGW server private key"
  [[ "$CEPH_RGW_ENDPOINT" == https://* || "${CEPH_RGW_ALLOW_HTTP:-false}" == true ]] \
    || fail "CEPH_RGW_ENDPOINT must use HTTPS unless CEPH_RGW_ALLOW_HTTP=true"
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
  "$(compose_runtime)" compose --project-directory "$HARNESS_DIR" --env-file "$HARNESS_DIR/.env" -f "$HARNESS_DIR/compose.yaml" "$@"
}

# Tears down by compose project name alone, so it works even when .env is
# missing and the compose file's required variables cannot be interpolated.
compose_teardown() {
  "$(compose_runtime)" compose --project-name stratus-ceph-local "$@"
}

# The harness pins its network to 172.28.0.0/24. A foreign network on that
# subnet (for example a cluster left running under an old project name) makes
# 'compose up' fail with a cryptic pool-overlap error; fail early and name it.
require_free_harness_subnet() {
  local runtime conflict
  runtime="$(compose_runtime)"
  conflict="$("$runtime" network ls --format '{{.Name}}' | while read -r net; do
    if [[ "$net" == stratus-ceph-local_* ]]; then continue; fi
    if "$runtime" network inspect "$net" --format '{{range .IPAM.Config}}{{.Subnet}} {{end}}' 2>/dev/null | grep -q '172\.28\.0\.0/24'; then
      printf '%s' "$net"
      break
    fi
  done)"
  if [[ -n "$conflict" ]]; then
    fail "Network '$conflict' already uses the harness subnet 172.28.0.0/24. Tear down whatever owns it (for example: $runtime compose -p <old-project> down) and retry."
  fi
}
