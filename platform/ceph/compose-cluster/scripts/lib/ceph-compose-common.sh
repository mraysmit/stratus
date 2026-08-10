#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-07-22

HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REPO_DIR="$(cd "$HARNESS_DIR/../../.." && pwd)"

# Single definition of this harness's compose project identity. Must match
# the 'name:' field in compose.yaml and connection.env; the
# guardrails in platform/ceph/tests enforce the agreement.
CEPH_COMPOSE_PROJECT="stratus-ceph-local"

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
    MSYS_NO_PATHCONV=1 "$(compose_runtime)" compose --project-name "$CEPH_COMPOSE_PROJECT" "$@"
  else
    "$(compose_runtime)" compose --project-name "$CEPH_COMPOSE_PROJECT" "$@"
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

# Container health is not cluster health. An OSD whose process is alive and
# whose container reports healthy can still be marked down by the monitors, and
# every write then hangs for thirty seconds and fails as a client timeout —
# three services away from the cause. 'compose up --wait' cannot see this: it
# waits on healthchecks, and asks the cluster nothing.
#
# Observed 2026-08-10 on a harness that had been up for twenty hours. Nothing
# reported it: the containers said Up, the OSD logs showed scrubs completing,
# and only 'ceph -s' knew. So startup asks the cluster directly, and refuses to
# report success until the storage is usable.
require_osds_up() {
  local expected="${CEPH_EXPECTED_OSDS:-3}"
  local deadline=$(( SECONDS + ${CEPH_OSD_READY_DEADLINE_SECONDS:-180} ))
  local status up
  while (( SECONDS < deadline )); do
    # 'ceph osd stat' answers in one line: "3 osds: 3 up (since 1m), 3 in ...".
    status="$(compose exec -T mon1 ceph osd stat 2>/dev/null || true)"
    up="$(printf '%s' "$status" | sed -nE 's/.*: *([0-9]+) up.*/\1/p')"
    if [[ "$up" == "$expected" ]]; then
      log "READY osds=$up/$expected"
      return 0
    fi
    sleep 5
  done
  fail "Only ${up:-0} of $expected OSDs are up after ${CEPH_OSD_READY_DEADLINE_SECONDS:-180}s, so every write will hang rather than fail. Check 'ceph -s'; if the OSD processes are alive but marked down, restart them: $(compose_runtime) compose --project-name $CEPH_COMPOSE_PROJECT restart osd1 osd2 osd3"
}

# The harness pins its network to 172.28.0.0/24. A foreign network on that
# subnet (for example a cluster left running under an old project name) makes
# 'compose up' fail with a cryptic pool-overlap error; fail early and name it.
require_free_harness_subnet() {
  local runtime conflict
  runtime="$(compose_runtime)"
  conflict="$("$runtime" network ls --format '{{.Name}}' | while read -r net; do
    if [[ "$net" == "${CEPH_COMPOSE_PROJECT}_"* ]]; then continue; fi
    if "$runtime" network inspect "$net" --format '{{range .IPAM.Config}}{{.Subnet}} {{end}}' 2>/dev/null | grep -q '172\.28\.0\.0/24'; then
      printf '%s' "$net"
      break
    fi
  done)"
  if [[ -n "$conflict" ]]; then
    fail "Network '$conflict' already uses the harness subnet 172.28.0.0/24. Tear down whatever owns it (for example: $runtime compose -p <old-project> down) and retry."
  fi
}
