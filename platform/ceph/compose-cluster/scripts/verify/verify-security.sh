#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"
load_environment

evidence_dir="${HARNESS_DIR}/evidence"
mkdir -p "$evidence_dir"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"

log "=== NEGATIVE TEST 1/3: invalid credentials — authentication failures below are EXPECTED ==="
auth_evidence="${evidence_dir}/storage-invalid-credentials-${timestamp}.json"
compose run --rm --no-deps -T \
  -e STRATUS_VERIFICATION_MODE=AUTH_FAILURE \
  -e CEPH_RGW_SECRET_KEY=deliberately-invalid-local-secret \
  -e "STRATUS_EVIDENCE_FILE=/evidence/storage-invalid-credentials-${timestamp}.json" \
  verifier java -jar /opt/stratus/storage-verifier.jar
grep -qs '"name":"invalid-credentials-rejected","passed":true' "$auth_evidence" \
  || fail "Verifier exited successfully but the evidence does not show invalid credentials being rejected: $auth_evidence"

log "=== NEGATIVE TEST 2/3: cross-identity access — access-denied errors below are EXPECTED ==="
policy_evidence="${evidence_dir}/storage-cross-identity-denial-${timestamp}.json"
compose run --rm --no-deps -T \
  -e STRATUS_VERIFICATION_MODE=ACCESS_DENIED \
  -e "STRATUS_EVIDENCE_FILE=/evidence/storage-cross-identity-denial-${timestamp}.json" \
  verifier java -jar /opt/stratus/storage-verifier.jar
grep -qs '"name":"cross-identity-access-denied","passed":true' "$policy_evidence" \
  || fail "Verifier exited successfully but the evidence does not show the cross-identity denial: $policy_evidence"

log "=== NEGATIVE TEST 3/3: untrusted TLS — PKIX certificate errors below are EXPECTED ==="
set +e
tls_output="$(compose run --rm --no-deps -T verifier-untrusted \
  java -jar /opt/stratus/storage-verifier.jar 2>&1)"
tls_exit=$?
set -e
{
  log "Untrusted TLS negative-test capture: output of a verifier run WITHOUT the lab CA; the PKIX failure below is the expected, asserted result."
  printf '%s\n' "$tls_output"
} | tee "${evidence_dir}/storage-untrusted-tls-${timestamp}.log"
if [ "$tls_exit" -ne 2 ]; then
  fail "Expected untrusted TLS verifier exit code 2 but received $tls_exit"
fi
if ! printf '%s\n' "$tls_output" | grep -Eq 'PKIX|SSLHandshake|certification path'; then
  fail "Verifier failed, but not because Java rejected the untrusted TLS certificate"
fi

log "=== NEGATIVE TESTS COMPLETE: all three failures occurred as required and were asserted ==="
log "PASS invalid-credentials evidence=$auth_evidence"
log "PASS cross-identity-denial evidence=$policy_evidence"
log "PASS untrusted-tls evidence=${evidence_dir}/storage-untrusted-tls-${timestamp}.log"
