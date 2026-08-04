#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-03
source "$(dirname "$0")/../lib/polaris-compose-common.sh"

# Brings the Polaris developer service up. Idempotent: .env is generated from
# the template once with a per-machine disposable bootstrap credential and
# then left alone. Requires the Ceph harness to be running (ADR-P1-003); it
# is never started transitively from here.
#
# Scaffold status: the first two start/verify/stop validation cycles belong
# to P1-2.2-D1 and have not yet been recorded.

rand_hex() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }

if [[ ! -f "$HARNESS_DIR/.env" ]]; then
  sed \
    -e "s|^POLARIS_BOOTSTRAP_CREDENTIALS=POLARIS,stratus-root,.*|POLARIS_BOOTSTRAP_CREDENTIALS=POLARIS,stratus-root,$(rand_hex 20)|" \
    "$HARNESS_DIR/.env.template" >"$HARNESS_DIR/.env"
  chmod 600 "$HARNESS_DIR/.env"
  harden_windows_acl "$HARNESS_DIR/.env"
  log "Generated $HARNESS_DIR/.env with a per-machine disposable bootstrap credential"
fi

require_ceph_harness_network
load_environment
mkdir -p "$HARNESS_DIR/evidence" "$HARNESS_DIR/logs" "$HARNESS_DIR/certs"

# Polaris writes Iceberg table metadata to RGW server-side, so its JVM must
# trust the disposable lab CA. The truststore holds only that public
# certificate and is rebuilt whenever the CA changes; JKS reads need no
# password, so none reaches the container command line.
truststore="$HARNESS_DIR/certs/stratus-truststore.jks"
if [[ ! -f "$truststore" || "$CEPH_HARNESS_DIR/$CEPH_HARNESS_CA_CERT" -nt "$truststore" ]]; then
  : "${JAVA_HOME:?JAVA_HOME is required to build the Polaris JVM truststore}"
  rm -f "$truststore"
  # Explicit JKS: certificate entries in JKS are readable without a
  # password, so none ever reaches the container command line. Modern
  # keytool would otherwise default to PKCS12, which yields no trust
  # anchors on a passwordless read.
  "$JAVA_HOME/bin/keytool" -importcert -noprompt -alias stratus-lab-ca \
    -storetype JKS \
    -file "$CEPH_HARNESS_CA_FILE" -keystore "$truststore" -storepass changeit >/dev/null 2>&1
  log "Built the Polaris JVM truststore from the Ceph harness CA"
fi

# Validate interpolation before touching container state so a broken .env
# fails here with a compose diagnostic rather than mid-startup.
compose config --quiet
compose up --detach --remove-orphans

log "Polaris starting from $POLARIS_IMAGE on ${POLARIS_BIND_ADDRESS:-127.0.0.1}:${POLARIS_PORT:-8181}"
log "Check liveness with: bash scripts/verify/polaris-compose-verify-endpoint.sh"
compose ps
