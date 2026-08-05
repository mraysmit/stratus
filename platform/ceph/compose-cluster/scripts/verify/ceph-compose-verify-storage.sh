#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-07-22
source "$(dirname "$0")/../lib/ceph-compose-common.sh"

# Runs the real Java storage verifier against the live cluster and records
# the evidence pair the README contract requires: the verification result and
# an environment snapshot proving which runtime, images, and cluster state
# produced it. A failed run renames its evidence *-FAILED so a later passing
# run can never be mistaken for it.

load_environment

# The verifier runs from a prebuilt image; this script must never build one
# (P1-0.1). That leaves a hole on the developer track, where the image is a
# hand-built tag: it can silently fall behind the sources it is supposed to
# prove, and a green run then attests to code nobody is running. Found on
# 2026-08-05 with a stratus/storage-verifier:dev image 16 days older than the
# verifier sources. Detect it and stop; rebuilding stays the operator's step.
#
# Only applies when the sources are present and the image carries no digest.
# A digest-pinned image published by the build system is immutable and
# authoritative, and a verification host legitimately has no source tree.
assert_verifier_image_not_stale() {
  local sources="$REPO_DIR/verification/storage"
  [[ -d "$sources/src/main" ]] || return 0
  [[ "${VERIFIER_IMAGE:-}" != *@sha256:* ]] || return 0
  local created marker newer
  created="$("$(compose_runtime)" image inspect --format '{{.Created}}' "$VERIFIER_IMAGE" 2>/dev/null)" || return 0
  [[ -n "$created" ]] || return 0
  marker="$(mktemp)"
  if ! touch -d "$created" "$marker" 2>/dev/null; then
    rm -f "$marker"
    return 0
  fi
  newer="$(find "$sources/src/main" "$sources/pom.xml" -newer "$marker" -print -quit 2>/dev/null || true)"
  rm -f "$marker"
  [[ -z "$newer" ]] || fail "$VERIFIER_IMAGE was built $created, which is older than $newer. The verification would attest to superseded code. Rebuild it:
  ./mvnw -pl :stratus-storage-verifier -am package
  $(compose_runtime) build -f verification/storage/image/Dockerfile -t $VERIFIER_IMAGE ."
}
assert_verifier_image_not_stale

mkdir -p "$HARNESS_DIR/evidence"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
evidence="$HARNESS_DIR/evidence/storage-verification-${timestamp}.json"
environment_evidence="$HARNESS_DIR/evidence/environment-${timestamp}.json"
# Per-run log name so the verifier log correlates with this run's evidence.
export STRATUS_LOG_FILE="/evidence/storage-verifier-${timestamp}.%g.log"
runtime="$(compose_runtime)"

# Environment snapshot required by the README evidence contract: runtime,
# resolved image identities, Ceph version, cluster status, and OSD state.
image_ref() {
  "$runtime" image inspect --format '{{if .RepoDigests}}{{index .RepoDigests 0}}{{else}}{{.Id}}{{end}}' "$1" 2>/dev/null \
    || printf 'unavailable'
}
cluster_json() {
  compose exec -T mon1 "$@" 2>/dev/null || printf 'null'
}
platform="$("$runtime" version --format '{{.Server.Os}}/{{.Server.Arch}}' 2>/dev/null || printf 'unknown')"
runtime_version="$("$runtime" --version 2>/dev/null || printf 'unknown')"
ceph_version="$(compose exec -T mon1 ceph version 2>/dev/null || printf 'unavailable')"
cat >"$environment_evidence" <<EOF
{
  "description": "Stratus verification environment snapshot: the runtime, images, and Ceph cluster state that produced the storage-verification evidence with the same timestamp",
  "timestamp": "${timestamp}",
  "rgwEndpoint": "${CEPH_RGW_ENDPOINT}",
  "composeRuntime": "${runtime}",
  "runtimeVersion": "${runtime_version}",
  "platform": "${platform}",
  "cephImage": "${CEPH_IMAGE:-unset}",
  "cephImageResolved": "$(image_ref "${CEPH_IMAGE:-}")",
  "verifierImage": "${VERIFIER_IMAGE:-unset}",
  "verifierImageResolved": "$(image_ref "${VERIFIER_IMAGE:-}")",
  "cephVersion": "${ceph_version}",
  "cephStatus": $(cluster_json ceph status --format json),
  "osdTree": $(cluster_json ceph osd tree --format json)
}
EOF
log "Environment: $environment_evidence"

# A newly created one-off container can briefly precede Docker's network DNS
# registration. Probe from the verifier image so the contract run starts only
# after the exact container/network boundary it depends on is ready.
endpoint_host="${CEPH_RGW_ENDPOINT#*://}"
endpoint_host="${endpoint_host%%/*}"
endpoint_host="${endpoint_host%%:*}"
for attempt in {1..10}; do
  if compose run --rm --no-deps -T --entrypoint /bin/sh verifier \
      -c 'getent hosts "$1" >/dev/null 2>&1' _ "$endpoint_host"; then
    break
  fi
  if [[ "$attempt" -eq 10 ]]; then
    fail "Verifier container could not resolve RGW endpoint host: $endpoint_host"
  fi
  sleep 1
done

set +e
compose run --rm --no-deps -T \
  -e "STRATUS_EVIDENCE_FILE=/evidence/storage-verification-${timestamp}.json" \
  verifier java -jar /opt/stratus/storage-verifier.jar
verifier_exit=$?
set -e
if [[ "$verifier_exit" -ne 0 ]]; then
  failed_evidence="${evidence%.json}-FAILED.json"
  if [[ -f "$evidence" ]]; then mv "$evidence" "$failed_evidence"; fi
  fail "Storage verification failed with exit code $verifier_exit; evidence: $failed_evidence"
fi
log "Evidence: $evidence"
log "Verifier log: $HARNESS_DIR/evidence/storage-verifier-${timestamp}.0.log"
