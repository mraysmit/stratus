#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

# Same pinned image compose uses; only needed when host OpenSSL is unavailable.
ceph_image='quay.io/ceph/ceph:v20.2.2@sha256:6b4b5ae33acd3d736eb26d2a19238bce71a22f9cfb99cca887ba6312d0957644'

# Certificates are regenerated when absent or expiring within seven days. Leaf
# renewal preserves the existing CA; only an expiring CA forces re-trusting.
read -r -d '' generator <<'EOF' || true
set -euo pipefail
export MSYS_NO_PATHCONV=1
umask 077
mkdir -p certs private
renew_window_seconds=604800
ca_key=private/stratus-lab-ca.key
ca_cert=certs/stratus-ca.crt
rgw_key=private/object-store.stratus.local.key
rgw_csr=certs/object-store.stratus.local.csr
rgw_cert=certs/object-store.stratus.local.crt
extensions=private/rgw-extensions.cnf
needs_renewal() {
  { [ -f "$1" ] && [ -f "$2" ]; } || return 0
  openssl x509 -checkend "$renew_window_seconds" -noout -in "$2" >/dev/null 2>&1 && return 1
  return 0
}
key_matches_certificate() {
  { [ -f "$1" ] && [ -f "$2" ]; } || return 1
  cert_public="$(openssl x509 -in "$2" -pubkey -noout 2>/dev/null)" || return 1
  key_public="$(openssl pkey -in "$1" -pubout 2>/dev/null)" || return 1
  [ "$cert_public" = "$key_public" ]
}
if needs_renewal "$ca_key" "$ca_cert" || ! key_matches_certificate "$ca_key" "$ca_cert"; then
  if [ -f "$ca_cert" ]; then
    echo "Existing Compose CA is expiring or does not match its key; regenerating it. Re-import $ca_cert wherever the old CA was trusted." >&2
  fi
  openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 365 \
    -subj "/CN=Stratus Disposable Compose CA" -keyout "$ca_key" -out "$ca_cert"
  rm -f "$rgw_key" "$rgw_cert"
fi
if needs_renewal "$rgw_key" "$rgw_cert" || ! key_matches_certificate "$rgw_key" "$rgw_cert"; then
  openssl req -newkey rsa:3072 -nodes -sha256 -subj "/CN=object-store.stratus.local" \
    -keyout "$rgw_key" -out "$rgw_csr"
  printf 'subjectAltName=DNS:object-store.stratus.local\nextendedKeyUsage=serverAuth\n' >"$extensions"
  openssl x509 -req -sha256 -days 90 -in "$rgw_csr" -CA "$ca_cert" -CAkey "$ca_key" -CAcreateserial \
    -extfile "$extensions" -out "$rgw_cert"
fi
# Public certificates must be readable by non-root client containers; private
# keys remain owner-only even though all files were created under umask 077.
chmod 0644 "$ca_cert" "$rgw_cert"
chmod 0600 "$ca_key" "$rgw_key"
openssl verify -CAfile "$ca_cert" "$rgw_cert"
EOF

if command -v openssl >/dev/null 2>&1; then
  (cd "$HARNESS_DIR" && bash -c "$generator")
elif command -v docker >/dev/null 2>&1; then
  docker run --rm --volume "$HARNESS_DIR:/work" --workdir /work --entrypoint /bin/bash "$ceph_image" -c "$generator"
elif command -v podman >/dev/null 2>&1; then
  podman run --rm --volume "$HARNESS_DIR:/work" --workdir /work --entrypoint /bin/bash "$ceph_image" -c "$generator"
else
  fail "OpenSSL, Docker, or Podman is required. Run ./scripts/lifecycle/install-prerequisites.sh, then retry certificate generation."
fi
log "Disposable Compose certificate is current. Apply $HARNESS_DIR/certs/object-store.stratus.local.crt and its protected key to RGW; clients receive only $HARNESS_DIR/certs/stratus-ca.crt."
