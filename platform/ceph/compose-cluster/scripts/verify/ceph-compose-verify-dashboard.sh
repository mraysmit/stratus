#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-07-25
source "$(dirname "$0")/../lib/ceph-compose-common.sh"

# Live verification of the Ceph Dashboard REST API published on port 8444.
# The checks run inside mon1 because the pinned Ceph image guarantees curl
# and jq and Compose DNS resolves object-store.stratus.local, so the request
# path exercises the same TLS proxy a browser or API client uses. The
# dashboard credentials and the CA travel over stdin: they must never appear
# on a command line, in the process table, or in the evidence.
#
# Three checks, all of them about this cluster rather than about the product:
# cluster health is HEALTH_OK, the daemon inventory matches the Compose
# topology (3 monitors, 3 OSDs up and in), and the reported version identifies
# Ceph. Those are properties of the deployment in front of us, which no JVM
# test can state.
#
# Authenticating is a precondition, not a check: the health, inventory, and
# version endpoints need a token. Logging out is cleanup, so the run does not
# leave a live session behind.
#
# It deliberately does NOT check that an unauthenticated request is rejected,
# that the dashboard RGW identity can write, or that logout revokes the token.
# Those are product behavior and belong to CephDashboardRestConformanceTest
# (code style rules 10.1). The RGW write path in particular is already proven
# there: when the dashboard RGW client began answering 403 after the 2026-08-05
# secret rotation, that suite is what failed while the read-only checks here
# all passed.

load_environment
: "${CEPH_DASHBOARD_USER:?CEPH_DASHBOARD_USER is required; run ceph-compose-startup.sh to generate it}"
: "${CEPH_DASHBOARD_PASSWORD:?CEPH_DASHBOARD_PASSWORD is required; run ceph-compose-startup.sh to generate it}"
: "${CEPH_DASHBOARD_ENDPOINT:?CEPH_DASHBOARD_ENDPOINT is required; update .env from .env.template}"

evidence_dir="${HARNESS_DIR}/evidence"
mkdir -p "$evidence_dir"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
evidence_file="${evidence_dir}/dashboard-verification-${timestamp}.json"

log "Verifying the Ceph Dashboard REST API at $CEPH_DASHBOARD_ENDPOINT from inside the Compose network"

set +e
{
  printf '%s\n%s\n%s\n' "$CEPH_DASHBOARD_USER" "$CEPH_DASHBOARD_PASSWORD" \
    "$CEPH_DASHBOARD_ENDPOINT"
  cat "$HARNESS_DIR/certs/stratus-ca.crt"
} | compose exec -T mon1 bash -c '
set -euo pipefail
IFS= read -r user; IFS= read -r pass; IFS= read -r base
work=$(mktemp -d); trap "rm -rf $work" EXIT
cat > "$work/ca.crt"

accept="Accept: application/vnd.ceph.api.v1.0+json"
started=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# Check 1: authentication. POST /api/auth must answer 201 with a JWT.
jq -n --arg u "$user" --arg p "$pass" "{username: \$u, password: \$p}" > "$work/auth.json"
auth_code=$(curl -sS --cacert "$work/ca.crt" -o "$work/auth-resp.json" -w "%{http_code}" \
  -H "$accept" -H "Content-Type: application/json" \
  -X POST --data @"$work/auth.json" "$base/api/auth" || printf 000)
token=$(jq -r ".token // empty" "$work/auth-resp.json" 2>/dev/null || true)
auth_pass=false
[ "$auth_code" = 201 ] && [ -n "$token" ] && auth_pass=true
echo "dashboard authentication: http=$auth_code token_present=$([ -n "$token" ] && echo true || echo false)" >&2

# The checks need the token; they report unavailable when authentication failed.
health_status=unavailable; mon_count=0; osd_up_in=0; version=unavailable; logout_code=000
if $auth_pass; then
  # Poll for HEALTH_OK to a bounded deadline rather than reading once. A
  # cluster bootstrapped moments ago reports HEALTH_WARN while placement
  # groups settle, and a single read turns that into a failed gate on a
  # healthy cluster — the same false negative the RGW write check below
  # already avoids by retrying. A cluster that never reaches HEALTH_OK still
  # fails, so the signal is preserved rather than relaxed.
  health_timeout=${STRATUS_HEALTH_TIMEOUT_SECONDS:-120}
  health_deadline=$(( SECONDS + health_timeout ))
  health_attempts=0
  while :; do
    health_attempts=$(( health_attempts + 1 ))
    if curl -sS --cacert "$work/ca.crt" -H "$accept" -H "Authorization: Bearer $token" \
         -o "$work/health.json" "$base/api/health/minimal"; then
      health_status=$(jq -r ".health.status // \"unavailable\"" "$work/health.json")
      mon_count=$(jq -r ".mon_status.monmap.mons | length" "$work/health.json")
      osd_up_in=$(jq -r "[.osd_map.osds[] | select(.up == 1 and .in == 1)] | length" "$work/health.json")
    fi
    [ "$health_status" = HEALTH_OK ] && break
    [ "$SECONDS" -ge "$health_deadline" ] && break
    sleep 5
  done
  echo "cluster health settled to $health_status after $health_attempts attempt(s)" >&2
  version=$(curl -sS --cacert "$work/ca.crt" -H "$accept" -H "Authorization: Bearer $token" \
    "$base/api/summary" | jq -r ".version // \"unavailable\"")

  # Cleanup: end the session this run opened. Asserted, because a cleanup whose
  # result is discarded leaves a live token behind on every run. That logout
  # actually revokes the token is a different claim, and belongs to
  # CephDashboardRestConformanceTest.logoutRevokesTheSessionToken.
  #
  # Logout takes no parameters but is still a JSON POST, and the dashboard is
  # strict about the envelope: an absent body answers 411, an empty string 400,
  # and a body without a content type 415. The only accepted form is an empty
  # JSON document with the JSON content type.
  logout_code=$(curl -sS --cacert "$work/ca.crt" -o /dev/null -w "%{http_code}" \
    -H "$accept" -H "Content-Type: application/json" -H "Authorization: Bearer $token" \
    -X POST --data "{}" "$base/api/auth/logout" || printf 000)
fi
health_pass=false; [ "$health_status" = HEALTH_OK ] && health_pass=true
inventory_pass=false; [ "$mon_count" = 3 ] && [ "$osd_up_in" = 3 ] && inventory_pass=true
version_pass=false; case "$version" in "ceph version"*) version_pass=true ;; esac
logout_pass=false; [ "$logout_code" = 200 ] && logout_pass=true
echo "cluster health: $health_status mons=$mon_count osds_up_in=$osd_up_in" >&2
echo "reported version: $version" >&2
echo "session cleanup: http=$logout_code" >&2

overall=false
$auth_pass && $health_pass && $inventory_pass && $version_pass \
  && $logout_pass && overall=true

jq -n \
  --arg ts "$started" \
  --argjson success "$overall" \
  --argjson auth "$auth_pass" --arg auth_code "$auth_code" \
  --argjson health "$health_pass" --arg health_status "$health_status" \
  --argjson inventory "$inventory_pass" --arg mons "$mon_count" --arg osds "$osd_up_in" \
  --argjson ver "$version_pass" --arg version "$version" \
  --argjson logout "$logout_pass" --arg logout_code "$logout_code" \
  "{
    description: \"Stratus Ceph Dashboard deployment evidence: success=true means this cluster is healthy and matches its declared topology. Dashboard API behavior is asserted by CephDashboardRestConformanceTest, not here\",
    timestamp: \$ts,
    success: \$success,
    preconditions: [
      {name: \"dashboard-authentication\", passed: \$auth, detail: (\"POST /api/auth answered HTTP \" + \$auth_code + \" with a session token\")}
    ],
    checks: [
      {name: \"cluster-health\", passed: \$health, detail: (\"GET /api/health/minimal reported \" + \$health_status)},
      {name: \"daemon-inventory\", passed: \$inventory, detail: (\$mons + \" monitors in the map and \" + \$osds + \" OSDs up and in\")},
      {name: \"reported-version\", passed: \$ver, detail: (\"GET /api/summary reported \" + \$version)}
    ],
    cleanup: [
      {name: \"session-ended\", passed: \$logout, detail: (\"POST /api/auth/logout answered HTTP \" + \$logout_code)}
    ]
  }"
$overall
' > "$evidence_file"
verify_exit=$?
set -e

if [[ -s "$evidence_file" ]]; then
  cat "$evidence_file"
fi
if [[ "$verify_exit" -ne 0 ]]; then
  fail "Dashboard REST API verification failed; evidence: $evidence_file"
fi
grep -qs '"success": true' "$evidence_file" \
  || fail "Verification exited successfully but the evidence does not record success: $evidence_file"
log "PASS dashboard-rest-api evidence=$evidence_file"
