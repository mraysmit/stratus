#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/../lib/common.sh"

# Failure drills against the live cluster: stop real daemons one class at a
# time, prove the S3 contract continues through the outage, and require full
# recovery to HEALTH_OK with every placement group active+clean. These are
# real failures on the real cluster; nothing is simulated. This is a
# single-Docker-host drill: it proves daemon-level failover and recovery,
# not production resilience.

verify_dir="$(cd "$(dirname "$0")" && pwd)"
load_environment

if [[ -z "$(compose ps -q)" ]]; then
  fail "The harness is not running. Start it first: scripts/lifecycle/startup.sh"
fi

# A failed drill must never leave a daemon stopped; starting a running
# service is a no-op, so this restore is safe on every exit path.
restore_all_daemons() {
  compose start rgw1 mon3 osd1 >/dev/null 2>&1 || true
}
trap restore_all_daemons EXIT

require_healthy() {
  local context="$1"
  local deadline=$(( SECONDS + ${2:-300} ))
  until compose exec -T mon1 bash -c \
      'ceph status --format json | jq -e ".health.status == \"HEALTH_OK\" and ([.pgmap.pgs_by_state[].state_name] == [\"active+clean\"])"' >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      compose exec -T mon1 ceph status || true
      fail "Cluster did not recover to HEALTH_OK with all placement groups active+clean ($context)"
    fi
    sleep 5
  done
  log "PASS recovery context=$context health=HEALTH_OK pgs=active+clean"
}

scenario() { printf '\n'; log "=== DRILL: $1 ==="; }

require_healthy "baseline before drills" 60

scenario "RGW daemon outage: the S3 contract continues through the surviving gateway"
log "EXPECTED-DEGRADATION begin service=rgw1"
compose stop rgw1 >/dev/null
"$verify_dir/check.sh"
compose start rgw1 >/dev/null
log "EXPECTED-DEGRADATION end service=rgw1"
require_healthy "after RGW restart"

scenario "monitor outage: two of three monitors keep quorum and the S3 contract continues"
log "EXPECTED-DEGRADATION begin service=mon3"
compose stop mon3 >/dev/null
compose exec -T mon1 bash -c 'for attempt in $(seq 1 60); do ceph quorum_status --format json | jq -e ".quorum | length == 2" >/dev/null && exit 0; sleep 2; done; echo "quorum did not drop to two monitors" >&2; exit 1' \
  || fail "Expected a two-monitor quorum while mon3 is down"
"$verify_dir/check.sh"
compose start mon3 >/dev/null
log "EXPECTED-DEGRADATION end service=mon3"
compose exec -T mon1 bash -c 'for attempt in $(seq 1 60); do ceph quorum_status --format json | jq -e ".quorum | length == 3" >/dev/null && exit 0; sleep 2; done; echo "quorum did not return to three monitors" >&2; exit 1' \
  || fail "Expected the three-monitor quorum to re-form after mon3 restarted"
require_healthy "after monitor restart"

scenario "OSD outage: writes continue degraded, then all placement groups recover"
log "EXPECTED-DEGRADATION begin service=osd1"
compose stop osd1 >/dev/null
compose exec -T mon1 bash -c 'for attempt in $(seq 1 60); do ceph osd stat --format json | jq -e ".num_up_osds == 2" >/dev/null && exit 0; sleep 2; done; echo "the stopped OSD was not marked down" >&2; exit 1' \
  || fail "Expected two OSDs up while osd1 is stopped"
"$verify_dir/check.sh"
"$verify_dir/verify-java.sh"
compose start osd1 >/dev/null
log "EXPECTED-DEGRADATION end service=osd1"
require_healthy "after OSD restart and backfill" 600

printf '\n'
log "FAILURE-DRILL PASS: rgw-failover, monitor-quorum-loss, osd-degraded-write-and-recovery"
