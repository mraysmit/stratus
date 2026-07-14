#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
command -v openssl >/dev/null 2>&1 || fail "OpenSSL is required. Run ./scripts/install-prerequisites.sh, then retry certificate generation."
mkdir -p "$HARNESS_DIR/certs" "$HARNESS_DIR/private"
umask 077
ca_key="$HARNESS_DIR/private/stratus-lab-ca.key"
ca_cert="$HARNESS_DIR/certs/stratus-ca.crt"
rgw_key="$HARNESS_DIR/private/object-store.stratus.local.key"
rgw_csr="$HARNESS_DIR/certs/object-store.stratus.local.csr"
rgw_cert="$HARNESS_DIR/certs/object-store.stratus.local.crt"
if [[ ! -f "$ca_key" || ! -f "$ca_cert" ]]; then
  openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 365 \
    -subj "/CN=Stratus Disposable Lab CA" -keyout "$ca_key" -out "$ca_cert"
fi
if [[ ! -f "$rgw_key" || ! -f "$rgw_cert" ]]; then
  openssl req -newkey rsa:3072 -nodes -sha256 -subj "/CN=object-store.stratus.local" \
    -keyout "$rgw_key" -out "$rgw_csr"
  openssl x509 -req -sha256 -days 90 -in "$rgw_csr" -CA "$ca_cert" -CAkey "$ca_key" -CAcreateserial \
    -extfile <(printf 'subjectAltName=DNS:object-store.stratus.local\nextendedKeyUsage=serverAuth\n') -out "$rgw_cert"
fi
openssl verify -CAfile "$ca_cert" "$rgw_cert"
printf 'Generated disposable lab certificate. Apply %s and its protected key to RGW; clients receive only %s.\n' "$rgw_cert" "$ca_cert"
