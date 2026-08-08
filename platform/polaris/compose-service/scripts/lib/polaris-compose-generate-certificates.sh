#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-08
source "$(dirname "$0")/polaris-compose-common.sh"

# Issues the disposable TLS material this harness terminates on.
#
# This harness mints its own CA rather than borrowing the Ceph one. The Ceph
# CA private key lives under that harness's private/ directory, and both its
# connection.env and this harness's compose file state that no private key
# crosses the harness boundary (ADR-P1-003): a consumer takes only what the
# provider publishes, and a signing key is never published. Two disposable lab
# CAs is the cost of that boundary.
#
# The leaf covers polaris.stratus.local for containers on the shared harness
# network and localhost/127.0.0.1 for the workstation, so a developer needs no
# hosts-file entry to reach the catalog over TLS.
#
# Certificates are regenerated when absent or expiring within seven days.
# Renewing the leaf preserves the CA; only an expiring CA forces consumers to
# re-trust.

command -v openssl >/dev/null 2>&1 \
  || fail "openssl is required to issue the harness TLS material; install it (Git Bash ships it on Windows)"

cd "$HARNESS_DIR"
export MSYS_NO_PATHCONV=1
umask 077
mkdir -p certs private

renew_window_seconds=604800
ca_key='private/stratus-polaris-ca.key'
ca_cert='certs/stratus-polaris-ca.crt'
leaf_key='private/polaris.stratus.local.key'
leaf_csr='certs/polaris.stratus.local.csr'
leaf_cert='certs/polaris.stratus.local.crt'
extensions='private/polaris-extensions.cnf'

needs_renewal() {
  { [[ -f "$1" ]] && [[ -f "$2" ]]; } || return 0
  openssl x509 -checkend "$renew_window_seconds" -noout -in "$2" >/dev/null 2>&1 && return 1
  return 0
}

key_matches_certificate() {
  { [[ -f "$1" ]] && [[ -f "$2" ]]; } || return 1
  local cert_public key_public
  cert_public="$(openssl x509 -in "$2" -pubkey -noout 2>/dev/null)" || return 1
  key_public="$(openssl pkey -in "$1" -pubout 2>/dev/null)" || return 1
  [[ "$cert_public" == "$key_public" ]]
}

if needs_renewal "$ca_key" "$ca_cert" || ! key_matches_certificate "$ca_key" "$ca_cert"; then
  if [[ -f "$ca_cert" ]]; then
    log "The Polaris Compose CA is expiring or does not match its key; regenerating it. Re-import $HARNESS_DIR/$ca_cert wherever the old CA was trusted."
  fi
  openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 365 \
    -subj '/CN=Stratus Disposable Polaris Compose CA' -keyout "$ca_key" -out "$ca_cert" 2>/dev/null
  # A new CA invalidates the leaf it signed.
  rm -f "$leaf_key" "$leaf_cert"
  log "Issued a new disposable Polaris Compose CA"
fi

if needs_renewal "$leaf_key" "$leaf_cert" || ! key_matches_certificate "$leaf_key" "$leaf_cert"; then
  openssl req -newkey rsa:3072 -nodes -sha256 -subj '/CN=polaris.stratus.local' \
    -keyout "$leaf_key" -out "$leaf_csr" 2>/dev/null
  printf 'subjectAltName=DNS:polaris.stratus.local,DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n' \
    >"$extensions"
  openssl x509 -req -sha256 -days 90 -in "$leaf_csr" -CA "$ca_cert" -CAkey "$ca_key" -CAcreateserial \
    -extfile "$extensions" -out "$leaf_cert" 2>/dev/null
  log "Issued the polaris.stratus.local certificate"
fi

# Public certificates must be readable by the proxy container; private keys
# stay owner-only even though every file was created under umask 077.
chmod 0644 "$ca_cert" "$leaf_cert"
chmod 0600 "$ca_key" "$leaf_key"
openssl verify -CAfile "$ca_cert" "$leaf_cert" >/dev/null \
  || fail "The issued certificate does not verify against $ca_cert"
