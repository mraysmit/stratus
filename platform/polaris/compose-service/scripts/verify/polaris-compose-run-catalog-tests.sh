#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-04
source "$(dirname "$0")/../lib/polaris-compose-common.sh"

# Runs the live catalog conformance suite (stratus-catalog-verifier) against
# this Polaris service and the Ceph cluster behind it. Supplies the
# environment, the live opt-in switch, and a CA truststore for the
# workstation JVM's S3FileIO. Arguments pass through to Maven. Every
# invocation writes a timestamped transcript with explicit run boundaries to
# this harness's logs/.

load_environment
: "${JAVA_HOME:?JAVA_HOME is required to build the conformance-test truststore}"

client_id="$(printf '%s' "$POLARIS_BOOTSTRAP_CREDENTIALS" | cut -d, -f2)"
client_secret="$(printf '%s' "$POLARIS_BOOTSTRAP_CREDENTIALS" | cut -d, -f3)"
[[ -n "$client_id" && -n "$client_secret" ]] \
  || fail "POLARIS_BOOTSTRAP_CREDENTIALS must be realm,client-id,client-secret (startup generates it)"

# Workstation JVM truststore: a copy of the JVM's own CA set with the
# disposable lab CA added, because JAVA_TOOL_OPTIONS also applies to Maven
# itself, which still needs the public CAs for repository downloads. Reads
# need no password, so none reaches any JVM command line.
truststore="$(mktemp -d)/stratus-catalog-cacerts"
cp "$JAVA_HOME/lib/security/cacerts" "$truststore"
# Both harness CAs: the suite reaches the catalog through this harness's TLS
# proxy and object storage through Ceph's, and neither harness signs for the
# other because a signing key never crosses the boundary (ADR-P1-003).
"$JAVA_HOME/bin/keytool" -importcert -noprompt -alias stratus-lab-ca \
  -file "$CEPH_HARNESS_CA_FILE" -keystore "$truststore" -storepass changeit >/dev/null 2>&1
"$JAVA_HOME/bin/keytool" -importcert -noprompt -alias stratus-polaris-lab-ca \
  -file "$POLARIS_HARNESS_CA_FILE" -keystore "$truststore" -storepass changeit >/dev/null 2>&1
truststore_path="$truststore"
if command -v cygpath >/dev/null 2>&1; then
  truststore_path="$(cygpath -m "$truststore")"
fi

export STRATUS_CATALOG_INTEGRATION=true
# DEBUG by default so transcripts prove both operational log levels.
export STRATUS_LOG_LEVEL="${STRATUS_LOG_LEVEL:-DEBUG}"
# Loopback rather than polaris.stratus.local: the proxy certificate carries
# 127.0.0.1 as a subject alternative name, so the workstation needs no
# hosts-file entry to validate the catalog's TLS.
export STRATUS_POLARIS_URI="$(polaris_api_base)/catalog"
export STRATUS_POLARIS_CLIENT_ID="$client_id"
export STRATUS_POLARIS_CLIENT_SECRET="$client_secret"
export STRATUS_POLARIS_CATALOG=stratus
export S3_PATH_STYLE_ACCESS=true
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=${truststore_path}"

cd "$REPO_DIR"
test_log_dir="$HARNESS_DIR/logs"
mkdir -p "$test_log_dir"
run_timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
run_started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
run_id="catalog-conformance-tests-$run_timestamp"
test_log="$test_log_dir/$run_id.log"
log "Writing the complete catalog conformance transcript to $test_log"
if [[ "$#" -eq 0 ]]; then
  set -- test -Pcatalog-integration-tests -pl :stratus-catalog-verifier -am
fi

set +e
{
  printf 'RUN startedAtUtc=%s runId=%s polaris=%s storage=%s\n' \
    "$run_started_at" "$run_id" "$STRATUS_POLARIS_URI" "$CEPH_RGW_ENDPOINT"
  ./mvnw "$@"
} 2>&1 | tee "$test_log"
maven_status="${PIPESTATUS[0]}"
set -e
printf 'RUN completedAtUtc=%s runId=%s exitCode=%s\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$run_id" "$maven_status" | tee -a "$test_log"
exit "$maven_status"
