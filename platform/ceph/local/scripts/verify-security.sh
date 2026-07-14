#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_environment

evidence_dir="${HARNESS_DIR}/evidence"
mkdir -p "$evidence_dir"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"

compose run --rm --no-deps -T \
  -e STRATUS_VERIFICATION_MODE=AUTH_FAILURE \
  -e CEPH_RGW_SECRET_KEY=deliberately-invalid-local-secret \
  verifier java -jar /opt/stratus/storage-contract-verifier.jar \
  | tee "${evidence_dir}/storage-invalid-credentials-${timestamp}.json"

compose run --rm --no-deps -T \
  -e STRATUS_VERIFICATION_MODE=ACCESS_DENIED \
  verifier java -jar /opt/stratus/storage-contract-verifier.jar \
  | tee "${evidence_dir}/storage-cross-identity-denial-${timestamp}.json"

set +e
tls_output="$(compose run --rm --no-deps -T verifier-untrusted \
  java -jar /opt/stratus/storage-contract-verifier.jar 2>&1)"
tls_exit=$?
set -e
printf '%s\n' "$tls_output" | tee "${evidence_dir}/storage-untrusted-tls-${timestamp}.log"
if [ "$tls_exit" -ne 2 ]; then
  printf 'Expected untrusted TLS verifier exit code 2 but received %s\n' "$tls_exit" >&2
  exit 1
fi
if ! printf '%s\n' "$tls_output" | grep -Eq 'PKIX|SSLHandshake|certification path'; then
  printf 'Verifier failed, but not because Java rejected the untrusted TLS certificate\n' >&2
  exit 1
fi

printf 'PASS invalid-credentials\nPASS cross-identity-denial\nPASS untrusted-tls\n'
