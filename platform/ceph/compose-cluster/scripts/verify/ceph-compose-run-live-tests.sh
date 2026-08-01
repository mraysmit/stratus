#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-07-30
source "$(dirname "$0")/../lib/ceph-compose-common.sh"

# Runs the deployment-neutral live Maven contracts in platform/ceph/tests
# against this Compose cluster. The contracts execute in the workstation JVM,
# not inside a container, so they need three things every other verify script
# gets for free: the harness environment, the live opt-in switch, and a
# truststore holding the disposable CA. This script supplies all three.
#
# Arguments are passed through to Maven. With none, it runs the targeted live
# suite; pass "clean verify -Pall-tests" for the full completion gate.

load_environment

# Resolution and reachability collapse into the same symptom from the host, so
# probe once and name both causes. /dev/tcp is a bash builtin: no extra tooling.
endpoint_host="${CEPH_RGW_ENDPOINT#*://}"
endpoint_host="${endpoint_host%%/*}"
host_only="${endpoint_host%%:*}"
port="${endpoint_host##*:}"
[[ "$port" == "$host_only" ]] && port=443
if ! timeout 5 bash -c "</dev/tcp/${host_only}/${port}" 2>/dev/null; then
  fail "Cannot reach $CEPH_RGW_ENDPOINT from this workstation. Ensure the cluster is running, then run scripts/lifecycle/ceph-compose-configure-hostname.sh to configure and verify the system hosts file."
fi

# Rebuilt whenever it is missing or older than the CA. ceph-compose-reset.sh regenerates the
# CA, and a stale truststore would otherwise fail every handshake with no clue
# why. Lives under certs/, which is already ignored and guardrail-enforced.
: "${JAVA_HOME:?JAVA_HOME is required to build the contract truststore}"
truststore="$HARNESS_DIR/certs/stratus-truststore.jks"
ca_certificate="$HARNESS_DIR/certs/stratus-ca.crt"
if [[ ! -f "$truststore" || "$ca_certificate" -nt "$truststore" ]]; then
  rm -f "$truststore"
  cp "$JAVA_HOME/lib/security/cacerts" "$truststore"
  "$JAVA_HOME/bin/keytool" -importcert -noprompt -alias stratus-compose-ca \
    -file "$ca_certificate" -keystore "$truststore" -storepass changeit >/dev/null
  log "Built $truststore from the current disposable CA"
fi

# JAVA_TOOL_OPTIONS rather than MAVEN_OPTS: Surefire forks a JVM for the tests
# and MAVEN_OPTS does not reach it.
truststore_path="$truststore"
if command -v cygpath >/dev/null 2>&1; then
  truststore_path="$(cygpath -m "$truststore")"
fi
export CEPH_RGW_INTEGRATION=true
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=${truststore_path} -Djavax.net.ssl.trustStorePassword=changeit"

log "Running the live Maven contracts against $CEPH_RGW_ENDPOINT"
cd "$REPO_DIR"
if [[ "$#" -eq 0 ]]; then
  exec ./mvnw test -Pceph-integration-tests -pl :stratus-ceph-tests -am
fi
exec ./mvnw "$@"
