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

# This harness's own CA, which signs the certificate its TLS proxy presents.
# Separate from the Ceph CA because a signing key never crosses the harness
# boundary (ADR-P1-003), so a client talking to both trusts both.
POLARIS_HARNESS_CA_FILE="$HARNESS_DIR/certs/stratus-polaris-ca.crt"
if [[ -n "${MSYSTEM:-}" ]] && command -v cygpath >/dev/null 2>&1; then
  POLARIS_HARNESS_CA_FILE="$(cygpath -m "$POLARIS_HARNESS_CA_FILE")"
fi
export POLARIS_HARNESS_CA_FILE

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
  # An .env written before TLS termination landed still carries this flag.
  # It no longer has any effect, and leaving it unremarked would let an
  # operator believe the catalog is reachable over plain HTTP.
  [[ "${POLARIS_ALLOW_HTTP:-}" != true ]] \
    || fail "POLARIS_ALLOW_HTTP=true in $HARNESS_DIR/.env, but this harness now terminates TLS at its proxy and Polaris publishes no plain-HTTP port. Remove that line, or delete .env and re-run lifecycle/polaris-compose-startup.sh to regenerate it from the template."
  : "${POLARIS_IMAGE:?POLARIS_IMAGE is required}"
  [[ "$POLARIS_IMAGE" != *latest* ]] || fail "POLARIS_IMAGE must be a pinned release, never latest"
  [[ -f "$CEPH_HARNESS_DIR/$CEPH_HARNESS_CA_CERT" ]] \
    || fail "Missing $CEPH_HARNESS_DIR/$CEPH_HARNESS_CA_CERT; start the Ceph harness once to generate it"
  fetch_service_identity_from_openbao
}

# Pull-based credentials (ADR-P1-004): the svc-polaris key pair comes from
# the developer secret store, published there by the Ceph provisioning step.
# An explicit .env override still wins for diagnosis, but the template
# deliberately carries no credential fields — nothing is copied by hand.
fetch_service_identity_from_openbao() {
  if [[ -n "${CEPH_RGW_ACCESS_KEY:-}" && -n "${CEPH_RGW_SECRET_KEY:-}" ]]; then
    return 0
  fi
  local openbao_dir="$REPO_DIR/platform/openbao/compose-service"
  [[ -f "$openbao_dir/connection.env" ]] \
    || fail "Missing $openbao_dir/connection.env; the OpenBao harness must publish its connection settings"
  set -a
  # shellcheck disable=SC1091
  source "$openbao_dir/connection.env"
  set +a
  local token_file="$openbao_dir/$OPENBAO_TOKEN_FILE"
  [[ -f "$token_file" ]] \
    || fail "Missing $token_file. Start the secret store first: bash platform/openbao/compose-service/scripts/lifecycle/openbao-compose-startup.sh"
  local response
  response="$(curl --silent --max-time 10 -H "X-Vault-Token: $(cat "$token_file")" \
    "$OPENBAO_ENDPOINT/v1/$OPENBAO_KV_MOUNT/data/$OPENBAO_SERVICE_IDENTITY_PATH/svc-polaris" || true)"
  CEPH_RGW_ACCESS_KEY="$(printf '%s' "$response" | sed -nE 's/.*"access_key" *: *"([^"]+)".*/\1/p')"
  CEPH_RGW_SECRET_KEY="$(printf '%s' "$response" | sed -nE 's/.*"secret_key" *: *"([^"]+)".*/\1/p')"
  [[ -n "$CEPH_RGW_ACCESS_KEY" && -n "$CEPH_RGW_SECRET_KEY" ]] \
    || fail "svc-polaris credentials are not in OpenBao. Publish them by running: bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-provision-service-identities.sh (with the OpenBao harness running)"
  export CEPH_RGW_ACCESS_KEY CEPH_RGW_SECRET_KEY
  log "Fetched svc-polaris credentials from OpenBao"
}

# Always HTTPS: the catalog is published only through this harness's TLS
# proxy, and Polaris itself binds no host port. Loopback rather than
# polaris.stratus.local because the proxy certificate carries 127.0.0.1 as a
# subject alternative name, which spares the developer a hosts-file entry.
# This is the address for JVM clients on the workstation, which validate
# against their own truststore; shell checks use polaris_curl instead.
polaris_api_base() {
  printf 'https://127.0.0.1:%s/api' "${POLARIS_PORT:-8181}"
}

# The catalog as seen from inside the shared harness network.
polaris_network_api_base() {
  printf 'https://polaris.stratus.local:%s/api' "${POLARIS_PORT:-8181}"
}

# Runs curl inside the harness network rather than on the workstation.
#
# This is not a convenience. On Windows the workstation's curl is
# Schannel-backed, and Schannel refuses a privately issued certificate whose
# revocation status it cannot determine — the connection closes with no HTTP
# status at all. Passing --ssl-no-revoke would paper over it by weakening the
# client, which the Increment 2 promotion manifest forbids as a way to satisfy
# a TLS check. The Polaris container carries curl and mounts this harness's
# CA, so the chain is validated in full, exactly as the Ceph harness runs its
# checks inside mon1.
polaris_curl() {
  compose exec -T polaris curl --silent \
    --cacert /etc/stratus/certs/stratus-polaris-ca.crt --max-time 30 "$@"
}

# Polaris cold-start takes tens of seconds (slower still while the Ceph cluster
# shares the host), so every caller that talks to the API must wait first
# rather than guess a sleep. This is not a convenience: curl answers a
# not-yet-listening Polaris with exit 52, which under `set -e` aborts the
# caller before it can print its own "is Polaris running?" diagnostic.
# An unauthenticated 401/403 still proves the API is listening.
# Sets POLARIS_API_STATUS and POLARIS_API_WAITED_SECONDS on success.
wait_for_polaris_api() {
  local endpoint deadline_seconds elapsed status_code
  endpoint="$(polaris_network_api_base)/catalog/v1/config"
  deadline_seconds="${POLARIS_STARTUP_DEADLINE_SECONDS:-90}"
  elapsed=0
  while (( elapsed < deadline_seconds )); do
    status_code="$(polaris_curl --output /dev/null --write-out '%{http_code}' \
      "$endpoint" || true)"
    case "$status_code" in
      200|401|403)
        POLARIS_API_STATUS="$status_code"
        POLARIS_API_WAITED_SECONDS="$elapsed"
        POLARIS_API_PROBED_ENDPOINT="$endpoint"
        return 0
        ;;
    esac
    sleep 3
    elapsed=$((elapsed + 3))
  done
  fail "Polaris API did not answer on $endpoint within ${deadline_seconds}s (last result '${status_code:-no response}'); inspect logs with: docker compose --project-name stratus-polaris-local logs polaris"
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
