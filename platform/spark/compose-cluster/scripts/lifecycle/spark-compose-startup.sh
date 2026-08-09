#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-08
source "$(dirname "$0")/../lib/spark-compose-common.sh"

# Brings the Spark developer cluster up. Idempotent: .env is generated from the
# template once with a per-machine disposable principal secret and then left
# alone; the rendered Spark configuration and the truststore are rebuilt
# whenever their inputs change.
#
# Requires the Ceph and Polaris harnesses to be running (ADR-P1-003); neither
# is ever started transitively from here.

rand_hex() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }

if [[ ! -f "$HARNESS_DIR/.env" ]]; then
  sed -e "s|^SPARK_POLARIS_CLIENT_SECRET=.*|SPARK_POLARIS_CLIENT_SECRET=$(rand_hex 20)|" \
    "$HARNESS_DIR/.env.template" >"$HARNESS_DIR/.env"
  chmod 600 "$HARNESS_DIR/.env"
  harden_windows_acl "$HARNESS_DIR/.env"
  log "Generated $HARNESS_DIR/.env with a per-machine disposable principal secret"
fi

require_provider_harnesses
require_jobs_jar
load_environment
mkdir -p "$HARNESS_DIR/evidence" "$HARNESS_DIR/logs" "$HARNESS_DIR/certs"

[[ -n "${SPARK_POLARIS_CLIENT_SECRET:-}" ]] \
  || fail "SPARK_POLARIS_CLIENT_SECRET is empty in $HARNESS_DIR/.env; delete .env and re-run this script"

"$(compose_runtime)" image inspect "$SPARK_IMAGE" >/dev/null 2>&1 \
  || fail "The Spark runtime image $SPARK_IMAGE does not exist. Resolve its artifacts and build it:
  bash $HARNESS_DIR/scripts/lib/spark-compose-resolve-artifacts.sh
  docker build -f platform/spark/image/Dockerfile -t $SPARK_IMAGE platform/spark/image"

# Spark reaches object storage through Ceph's certificate and the catalog
# through Polaris's, and neither harness signs for the other, so both CAs go
# into one truststore. Explicit JKS: certificate entries in JKS are readable
# without a password, so none reaches a container command line; modern keytool
# would otherwise default to PKCS12, which yields no trust anchors on a
# passwordless read.
truststore="$HARNESS_DIR/certs/stratus-truststore.jks"
if [[ ! -f "$truststore" \
      || "$CEPH_HARNESS_DIR/$CEPH_HARNESS_CA_CERT" -nt "$truststore" \
      || "$POLARIS_HARNESS_DIR/$POLARIS_HARNESS_CA_CERT" -nt "$truststore" ]]; then
  : "${JAVA_HOME:?JAVA_HOME is required to build the Spark JVM truststore}"
  rm -f "$truststore"
  "$JAVA_HOME/bin/keytool" -importcert -noprompt -alias stratus-lab-ca -storetype JKS \
    -file "$CEPH_HARNESS_CA_FILE" -keystore "$truststore" -storepass changeit >/dev/null 2>&1
  "$JAVA_HOME/bin/keytool" -importcert -noprompt -alias stratus-polaris-lab-ca -storetype JKS \
    -file "$POLARIS_HARNESS_CA_FILE" -keystore "$truststore" -storepass changeit >/dev/null 2>&1
  log "Built the Spark JVM truststore from both harness CAs"
fi

# Render the Spark configuration from the providers' published values, so no
# endpoint, network name, or catalog name is duplicated in a tracked file.
rendered="$HARNESS_DIR/config/spark-defaults.conf"
sed \
  -e "s|__CATALOG__|$POLARIS_CATALOG|g" \
  -e "s|__POLARIS_ENDPOINT__|$POLARIS_ENDPOINT|g" \
  -e "s|__CEPH_RGW_ENDPOINT__|$CEPH_RGW_ENDPOINT|g" \
  -e "s|__POLARIS_CREDENTIAL__|$SPARK_POLARIS_CLIENT_ID:$SPARK_POLARIS_CLIENT_SECRET|g" \
  "$HARNESS_DIR/config/spark-defaults.conf.template" >"$rendered"
chmod 600 "$rendered"
harden_windows_acl "$rendered"
log "Rendered $rendered from the provider connection settings"

# Validate interpolation before touching container state so a broken .env fails
# here with a compose diagnostic rather than mid-startup.
compose config --quiet
compose up --detach --remove-orphans --wait

log "Spark cluster started from $SPARK_IMAGE"
log "Master UI: http://${SPARK_BIND_ADDRESS:-127.0.0.1}:${SPARK_MASTER_UI_PORT:-8090}"
log "Register the catalog principal with: bash scripts/verify/spark-compose-bootstrap-principal.sh"
compose ps
