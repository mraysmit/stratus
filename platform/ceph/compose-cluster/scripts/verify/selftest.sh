#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"

# Behavior self-test for the harness scripts. Verifies what the static
# guardrails in testing/repo-guardrails cannot: certificate renewal, rejection
# of a vacuous verifier, and teardown from broken states. Requires Docker or
# Podman and a fully stopped harness with no preserved cluster volumes.

scripts_dir="$(cd "$(dirname "$0")" && pwd)"
lifecycle_dir="$(cd "$scripts_dir/../lifecycle" && pwd)"
lib_dir="$(cd "$scripts_dir/../lib" && pwd)"
runtime="$(compose_runtime)"
ceph_image='quay.io/ceph/ceph:v20.2.2@sha256:6b4b5ae33acd3d736eb26d2a19238bce71a22f9cfb99cca887ba6312d0957644'
fake_image='stratus/selftest-vacuous-verifier'

if [[ -n "$("$runtime" ps -q --filter label=com.docker.compose.project=stratus-ceph-local)" ]]; then
  fail "Stop the harness before running the self-test (scripts/lifecycle/shutdown.sh)"
fi
if [[ -n "$("$runtime" volume ls -q --filter label=com.docker.compose.project=stratus-ceph-local)" ]]; then
  fail "Cluster volumes exist and the self-test exercises destructive reset. Run scripts/lifecycle/reset.sh --force first if losing them is intended."
fi
require_free_harness_subnet

openssl_run() {
  if command -v openssl >/dev/null 2>&1; then
    (cd "$HARNESS_DIR" && MSYS_NO_PATHCONV=1 openssl "$@" 2>/dev/null)
  else
    "$runtime" run --rm --volume "$HARNESS_DIR:/work" --workdir /work --entrypoint openssl "$ceph_image" "$@" 2>/dev/null
  fi
}

scenario() { printf '\n'; log "=== SELFTEST: $1 ==="; }

tmp_dir="$(mktemp -d)"
created_env=false
moved_env=""
cleanup() {
  if [[ -f "$tmp_dir/env-original" ]]; then cp "$tmp_dir/env-original" "$HARNESS_DIR/.env"; fi
  if [[ -n "$moved_env" && -f "$moved_env" ]]; then mv "$moved_env" "$HARNESS_DIR/.env"; fi
  if [[ "$created_env" == true ]]; then rm -f "$HARNESS_DIR/.env"; fi
  "$runtime" rmi "$fake_image" >/dev/null 2>&1 || true
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

scenario "near-expiry leaf certificate renews while preserving the CA"
"$lib_dir/generate-compose-certificates.sh" >/dev/null
ca_before="$(openssl_run x509 -sha256 -fingerprint -noout -in certs/stratus-ca.crt)"
printf 'subjectAltName=DNS:object-store.stratus.local\nextendedKeyUsage=serverAuth\n' >"$HARNESS_DIR/private/rgw-extensions.cnf"
openssl_run req -newkey rsa:3072 -nodes -sha256 -subj "/CN=object-store.stratus.local" \
  -keyout private/object-store.stratus.local.key -out certs/object-store.stratus.local.csr
openssl_run x509 -req -sha256 -days 3 -in certs/object-store.stratus.local.csr \
  -CA certs/stratus-ca.crt -CAkey private/stratus-lab-ca.key -CAcreateserial \
  -extfile private/rgw-extensions.cnf -out certs/object-store.stratus.local.crt
"$lib_dir/generate-compose-certificates.sh" >/dev/null
openssl_run x509 -checkend 604800 -noout -in certs/object-store.stratus.local.crt >/dev/null \
  || fail "Leaf certificate was not renewed although it was within the renewal window"
ca_after="$(openssl_run x509 -sha256 -fingerprint -noout -in certs/stratus-ca.crt)"
[[ "$ca_before" == "$ca_after" ]] || fail "The CA changed during leaf renewal; renewal must preserve the CA"
log "PASS certificate-renewal"

scenario "verify-security rejects a verifier that exits 0 without denial evidence"
if [[ ! -f "$HARNESS_DIR/.env" ]]; then
  sed 's/generated-at-first-startup/selftest-placeholder/' "$HARNESS_DIR/.env.template" >"$HARNESS_DIR/.env"
  created_env=true
fi
printf '#!/bin/bash\necho "{}"\nexit 0\n' >"$tmp_dir/java"
printf 'FROM %s\nCOPY --chmod=0755 java /usr/local/bin/java\n' "$ceph_image" >"$tmp_dir/Dockerfile"
"$runtime" build -t "$fake_image" "$tmp_dir" >/dev/null
cp "$HARNESS_DIR/.env" "$tmp_dir/env-original"
sed "s|^VERIFIER_IMAGE=.*|VERIFIER_IMAGE=$fake_image|" "$tmp_dir/env-original" >"$HARNESS_DIR/.env"
touch "$tmp_dir/marker"
set +e
output="$("$scripts_dir/verify-security.sh" 2>&1)"
status=$?
set -e
cp "$tmp_dir/env-original" "$HARNESS_DIR/.env"
rm -f "$tmp_dir/env-original"
find "$HARNESS_DIR/evidence" -maxdepth 1 -name 'storage-*' -newer "$tmp_dir/marker" -delete 2>/dev/null || true
if [[ "$status" -eq 0 ]]; then
  fail "verify-security accepted a verifier that exits 0 without denial evidence"
fi
printf '%s\n' "$output" | grep -q "does not show invalid credentials being rejected" \
  || fail "verify-security failed for an unexpected reason: $output"
log "PASS vacuous-verifier-rejected"

scenario "shutdown and destructive reset work without .env"
if [[ "$created_env" == true ]]; then
  rm -f "$HARNESS_DIR/.env"
  created_env=false
elif [[ -f "$HARNESS_DIR/.env" ]]; then
  mv "$HARNESS_DIR/.env" "$tmp_dir/env-backup"
  moved_env="$tmp_dir/env-backup"
fi
"$lifecycle_dir/shutdown.sh" >/dev/null
"$lifecycle_dir/reset.sh" --force >/dev/null
if [[ -n "$moved_env" ]]; then
  mv "$moved_env" "$HARNESS_DIR/.env"
  moved_env=""
fi
log "PASS teardown-without-env"

printf '\n'
log "SELFTEST PASS: certificate-renewal, vacuous-verifier-rejected, teardown-without-env"
