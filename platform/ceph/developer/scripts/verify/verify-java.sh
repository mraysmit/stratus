#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
load_environment
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
