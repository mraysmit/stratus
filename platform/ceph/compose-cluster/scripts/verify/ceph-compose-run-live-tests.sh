#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-07-30
source "$(dirname "$0")/../lib/ceph-compose-common.sh"

# Runs the deployment-neutral live Maven conformance tests in platform/ceph/tests
# against this Compose cluster. The conformance tests execute in the workstation JVM,
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
: "${JAVA_HOME:?JAVA_HOME is required to build the conformance-test truststore}"
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
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=${truststore_path}"

log "Running the live Maven conformance tests against $CEPH_RGW_ENDPOINT"
cd "$REPO_DIR"
test_log_dir="$REPO_DIR/platform/ceph/tests/logs"
mkdir -p "$test_log_dir"
run_timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
run_started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
run_id="ceph-live-tests-$run_timestamp"
test_log="$test_log_dir/$run_id.log"
log "Writing the complete live-test transcript to $test_log"
if [[ "$#" -eq 0 ]]; then
  set -- test -Pceph-integration-tests -pl :stratus-ceph-tests -am
fi

# Preserve Maven's exit code through tee, then create a REST-only evidence file
# when this invocation exercised the REST conformance tests. Both artifacts have an
# explicit run boundary and final result; neither appends unrelated runs.
set +e
{
  printf 'RUN startedAtUtc=%s runId=%s endpoint=%s\n' \
    "$run_started_at" "$run_id" "$CEPH_RGW_ENDPOINT"
  ./mvnw "$@"
} 2>&1 | tee "$test_log"
maven_status="${PIPESTATUS[0]}"
set -e
run_completed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'RUN completedAtUtc=%s runId=%s exitCode=%s\n' \
  "$run_completed_at" "$run_id" "$maven_status" | tee -a "$test_log"

if grep -q 'dev.stratus.platform.ceph.RestApiLogging' "$test_log"; then
  rest_log="$test_log_dir/rest-api-tests-$run_timestamp.log"
  {
    printf 'RUN startedAtUtc=%s runId=%s sourceTranscript=%s\n' \
      "$run_started_at" "$run_id" "$(basename "$test_log")"
    grep -E 'dev\.stratus\.platform\.ceph\.RestApiLogging|^\[INFO\] Tests run:|^\[INFO\] BUILD (SUCCESS|FAILURE)' \
      "$test_log"
    printf 'RUN completedAtUtc=%s runId=%s exitCode=%s\n' \
      "$run_completed_at" "$run_id" "$maven_status"
  } > "$rest_log"
  log "Writing the REST evidence log to $rest_log"
fi

exit "$maven_status"
