#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-08

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPO_DIR="$(cd "$HARNESS_DIR/../../.." && pwd)"

# All harness status output carries an ISO-8601 UTC timestamp.
log_timestamp() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }
log() { printf '%s %s\n' "$(log_timestamp)" "$*"; }
fail() { printf '%s ERROR: %s\n' "$(log_timestamp)" "$*" >&2; exit 1; }

# The two hardcoded provider values: the Ceph and Polaris harness directories,
# stable under the guardrail-enforced repository layout. Every other provider
# value — network name, endpoints, CA locations, catalog name — comes from the
# provider's published connection.env (ADR-P1-003) and must never be copied
# into this harness's own files.
CEPH_HARNESS_DIR="$REPO_DIR/platform/ceph/compose-cluster"
POLARIS_HARNESS_DIR="$REPO_DIR/platform/polaris/compose-service"

[[ -f "$CEPH_HARNESS_DIR/connection.env" ]] \
  || fail "Missing $CEPH_HARNESS_DIR/connection.env; the Ceph harness must publish its connection settings"
[[ -f "$POLARIS_HARNESS_DIR/connection.env" ]] \
  || fail "Missing $POLARIS_HARNESS_DIR/connection.env; the Polaris harness must publish its connection settings"

set -a
# shellcheck disable=SC1091
source <(sed 's/\r$//' "$CEPH_HARNESS_DIR/connection.env")
# shellcheck disable=SC1091
source <(sed 's/\r$//' "$POLARIS_HARNESS_DIR/connection.env")
set +a

: "${CEPH_HARNESS_NETWORK:?connection.env must define CEPH_HARNESS_NETWORK}"
: "${CEPH_RGW_ENDPOINT:?connection.env must define CEPH_RGW_ENDPOINT}"
: "${CEPH_HARNESS_CA_CERT:?connection.env must define CEPH_HARNESS_CA_CERT}"
: "${POLARIS_ENDPOINT:?connection.env must define POLARIS_ENDPOINT}"
: "${POLARIS_CATALOG:?connection.env must define POLARIS_CATALOG}"
: "${POLARIS_HARNESS_CA_CERT:?connection.env must define POLARIS_HARNESS_CA_CERT}"

# Absolute CA paths for Compose volume mounts. Mixed form (C:/...) under Git
# Bash so Docker receives a Windows path while Linux is unaffected. Spark
# trusts both lab CAs: object storage terminates on Ceph's and the catalog on
# Polaris's, because neither harness signs for the other.
CEPH_HARNESS_CA_FILE="$CEPH_HARNESS_DIR/$CEPH_HARNESS_CA_CERT"
POLARIS_HARNESS_CA_FILE="$POLARIS_HARNESS_DIR/$POLARIS_HARNESS_CA_CERT"
if [[ -n "${MSYSTEM:-}" ]] && command -v cygpath >/dev/null 2>&1; then
  CEPH_HARNESS_CA_FILE="$(cygpath -m "$CEPH_HARNESS_CA_FILE")"
  POLARIS_HARNESS_CA_FILE="$(cygpath -m "$POLARIS_HARNESS_CA_FILE")"
fi
export CEPH_HARNESS_CA_FILE POLARIS_HARNESS_CA_FILE

SPARK_BASE_IMAGE='apache/spark:4.1.2-scala2.13-java17-python3-ubuntu'
SPARK_IMAGE="${SPARK_IMAGE:-stratus/spark-runtime:dev}"
export SPARK_BASE_IMAGE SPARK_IMAGE

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

# The platform job jar the cluster mounts. Built by the reactor, not by this
# harness (P1-0.1): the scripts fail with the build command rather than
# running it.
SPARK_JOBS_JAR="$REPO_DIR/jobs/spark/target/stratus-spark-jobs-1.0-SNAPSHOT.jar"
if [[ -n "${MSYSTEM:-}" ]] && command -v cygpath >/dev/null 2>&1; then
  SPARK_JOBS_JAR="$(cygpath -m "$SPARK_JOBS_JAR")"
fi
export SPARK_JOBS_JAR

require_jobs_jar() {
  [[ -f "$REPO_DIR/jobs/spark/target/stratus-spark-jobs-1.0-SNAPSHOT.jar" ]] \
    || fail "The platform job jar is missing. Build it: ./mvnw -pl :stratus-spark-jobs -am package -DskipTests"
}

# Loads .env without validation. Teardown paths use this so a half-configured
# harness can still be shut down.
load_environment_file() {
  [[ -f "$HARNESS_DIR/.env" ]] \
    || fail "Create $HARNESS_DIR/.env from .env.template (lifecycle/spark-compose-startup.sh does this)"
  set -a
  # shellcheck disable=SC1091
  source <(sed 's/\r$//' "$HARNESS_DIR/.env")
  set +a
}

load_environment() {
  load_environment_file
  : "${SPARK_IMAGE:?SPARK_IMAGE is required}"
  [[ "$SPARK_IMAGE" != *latest* ]] || fail "SPARK_IMAGE must be a pinned tag, never latest"
  [[ -f "$CEPH_HARNESS_DIR/$CEPH_HARNESS_CA_CERT" ]] \
    || fail "Missing the Ceph harness CA; start the Ceph harness once to generate it"
  [[ -f "$POLARIS_HARNESS_DIR/$POLARIS_HARNESS_CA_CERT" ]] \
    || fail "Missing the Polaris harness CA; start the Polaris harness once to generate it"
  fetch_service_identity_from_openbao
}

# Pull-based credentials (ADR-P1-004): the svc-spark key pair comes from the
# developer secret store, published there by the Ceph provisioning step. An
# explicit .env override still wins for diagnosis, but the template carries no
# credential field — nothing is copied by hand.
fetch_service_identity_from_openbao() {
  if [[ -n "${SPARK_RGW_ACCESS_KEY:-}" && -n "${SPARK_RGW_SECRET_KEY:-}" ]]; then
    return 0
  fi
  local openbao_dir="$REPO_DIR/platform/openbao/compose-service"
  [[ -f "$openbao_dir/connection.env" ]] \
    || fail "Missing $openbao_dir/connection.env; the OpenBao harness must publish its connection settings"
  set -a
  # shellcheck disable=SC1091
  source <(sed 's/\r$//' "$openbao_dir/connection.env")
  set +a
  local token_file="$openbao_dir/$OPENBAO_TOKEN_FILE"
  [[ -f "$token_file" ]] \
    || fail "Missing $token_file. Start the secret store first: bash platform/openbao/compose-service/scripts/lifecycle/openbao-compose-startup.sh"
  local response
  response="$(curl --silent --max-time 10 -H "X-Vault-Token: $(cat "$token_file")" \
    "$OPENBAO_ENDPOINT/v1/$OPENBAO_KV_MOUNT/data/$OPENBAO_SERVICE_IDENTITY_PATH/svc-spark" || true)"
  SPARK_RGW_ACCESS_KEY="$(printf '%s' "$response" | sed -nE 's/.*"access_key" *: *"([^"]+)".*/\1/p')"
  SPARK_RGW_SECRET_KEY="$(printf '%s' "$response" | sed -nE 's/.*"secret_key" *: *"([^"]+)".*/\1/p')"
  [[ -n "$SPARK_RGW_ACCESS_KEY" && -n "$SPARK_RGW_SECRET_KEY" ]] \
    || fail "svc-spark credentials are not in OpenBao. Publish them by running: bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-provision-service-identities.sh (with the OpenBao harness running)"
  export SPARK_RGW_ACCESS_KEY SPARK_RGW_SECRET_KEY
  log "Fetched svc-spark credentials from OpenBao"
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
    MSYS_NO_PATHCONV=1 "$(compose_runtime)" compose --project-name stratus-spark-local "$@"
  else
    "$(compose_runtime)" compose --project-name stratus-spark-local "$@"
  fi
}

# ADR-P1-003: this harness attaches to the Ceph harness network and must never
# start a provider transitively. Fail fast with the remediation.
require_provider_harnesses() {
  "$(compose_runtime)" network inspect "$CEPH_HARNESS_NETWORK" >/dev/null 2>&1 \
    || fail "The Ceph harness network '$CEPH_HARNESS_NETWORK' does not exist. Start the Ceph harness first: bash platform/ceph/compose-cluster/scripts/lifecycle/ceph-compose-startup.sh"
  "$(compose_runtime)" ps --filter "name=stratus-polaris-local-polaris-proxy" --filter status=running --format '{{.Names}}' \
    | grep -q polaris \
    || fail "The Polaris catalog is not running. Start it first: bash platform/polaris/compose-service/scripts/lifecycle/polaris-compose-startup.sh"
}

# Runs curl inside the harness network. The workstation cannot validate the
# disposable lab CAs on Windows, where curl is Schannel-backed and refuses a
# privately issued certificate whose revocation status it cannot determine.
spark_curl() {
  compose exec -T spark-master curl --silent \
    --cacert /opt/stratus/certs/stratus-polaris-ca.crt --max-time 30 "$@"
}

# chmod cannot strip inherited NTFS ACLs, so under Git Bash the secret files
# additionally get an owner-only icacls grant. Best-effort: ACL hardening must
# never abort the harness on an exotic filesystem.
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
