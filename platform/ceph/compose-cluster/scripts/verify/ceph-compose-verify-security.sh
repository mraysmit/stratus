#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-07-22
source "$(dirname "$0")/../lib/ceph-compose-common.sh"

# One negative that only a container can express: a verifier run without the
# harness CA must die in the TLS handshake. The trust store belongs to the
# image and the Compose service, so no JVM test on the workstation can state
# this — the verifier-untrusted service exists for exactly this check.
#
# It deliberately does NOT check that Ceph rejects invalid credentials or
# denies cross-identity access. Those are product behavior and belong to
# CephRgwConformanceTest.liveRgwRejectsInvalidCredentials and
# liveRgwDeniesListingTheSeparateOwnersBucket, which drive the same verifier
# modes and assert the same evidence (code style rules 10.1).
#
# A verifier exit code alone is never trusted: requiring exit code 2 and a
# PKIX error means a vacuous verifier that exits cleanly is still rejected
# (ceph-compose-verify-harness.sh exercises exactly that regression).

load_environment

evidence_dir="${HARNESS_DIR}/evidence"
mkdir -p "$evidence_dir"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"

log "=== NEGATIVE TEST: untrusted TLS — PKIX certificate errors below are EXPECTED ==="
# The verifier reserves exit code 2 for transport-layer failure. Requiring 2
# (not just non-zero) plus the PKIX pattern proves the run died in the TLS
# handshake and not on credentials, buckets, or a crash.
set +e
tls_output="$(compose run --rm --no-deps -T verifier-untrusted \
  java -jar /opt/stratus/storage-verifier.jar 2>&1)"
tls_exit=$?
set -e
{
  log "Untrusted TLS negative-test capture: output of a verifier run WITHOUT the Compose CA; the PKIX failure below is the expected, asserted result."
  printf '%s\n' "$tls_output"
} | tee "${evidence_dir}/storage-untrusted-tls-${timestamp}.log"
if [ "$tls_exit" -ne 2 ]; then
  fail "Expected untrusted TLS verifier exit code 2 but received $tls_exit"
fi
if ! printf '%s\n' "$tls_output" | grep -Eq 'PKIX|SSLHandshake|certification path'; then
  fail "Verifier failed, but not because Java rejected the untrusted TLS certificate"
fi

log "=== NEGATIVE TEST COMPLETE: the failure occurred as required and was asserted ==="
log "PASS untrusted-tls evidence=${evidence_dir}/storage-untrusted-tls-${timestamp}.log"
log "Credential rejection and cross-identity denial are proven by CephRgwConformanceTest in the live suite"
