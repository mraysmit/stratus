#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-07-22
source "$(dirname "$0")/../lib/ceph-compose-common.sh"

# Brings the cluster up, creating it on first run. Idempotent: .env is
# generated from the template once with per-machine disposable credentials
# and then left alone, and certificates regenerate only when absent or near
# expiry. Every generated secret is local to this disposable harness.

rand_hex() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }
if [[ ! -f "$HARNESS_DIR/.env" ]]; then
  sed \
    -e "s|^CEPH_RGW_ACCESS_KEY=.*|CEPH_RGW_ACCESS_KEY=stratus-local-$(rand_hex 6)|" \
    -e "s|^CEPH_RGW_SECRET_KEY=.*|CEPH_RGW_SECRET_KEY=$(rand_hex 20)|" \
    -e "s|^CEPH_DENIED_ACCESS_KEY=.*|CEPH_DENIED_ACCESS_KEY=stratus-denied-$(rand_hex 6)|" \
    -e "s|^CEPH_DENIED_SECRET_KEY=.*|CEPH_DENIED_SECRET_KEY=$(rand_hex 20)|" \
    -e "s|^CEPH_DASHBOARD_PASSWORD=.*|CEPH_DASHBOARD_PASSWORD=$(rand_hex 20)|" \
    -e "s|^CEPH_ADMIN_OPS_ACCESS_KEY=.*|CEPH_ADMIN_OPS_ACCESS_KEY=stratus-adminops-$(rand_hex 6)|" \
    -e "s|^CEPH_ADMIN_OPS_SECRET_KEY=.*|CEPH_ADMIN_OPS_SECRET_KEY=$(rand_hex 20)|" \
    "$HARNESS_DIR/.env.template" >"$HARNESS_DIR/.env"
  chmod 600 "$HARNESS_DIR/.env"
  harden_windows_acl "$HARNESS_DIR/.env"
  log "Generated $HARNESS_DIR/.env with per-machine disposable credentials"
elif ! grep -q '^CEPH_DASHBOARD_PASSWORD=' "$HARNESS_DIR/.env"; then
  # Backfill for .env files generated before the dashboard existed.
  {
    echo ''
    echo '# Ceph Dashboard (management console) sign-in, added by startup.'
    echo 'CEPH_DASHBOARD_USER=stratus-dashboard'
    echo "CEPH_DASHBOARD_PASSWORD=$(rand_hex 20)"
  } >>"$HARNESS_DIR/.env"
  log "Added generated dashboard credentials to $HARNESS_DIR/.env"
fi
# Independent of the block above: an .env generated before the Admin
# Operations API reader existed is otherwise valid and must not be regenerated,
# so the reader is backfilled on its own condition.
if ! grep -q '^CEPH_ADMIN_OPS_ACCESS_KEY=' "$HARNESS_DIR/.env"; then
  {
    echo ''
    echo '# Scoped RGW Admin Operations API reader, added by startup.'
    echo 'CEPH_ADMIN_OPS_UID=stratus-admin-ops-reader'
    echo "CEPH_ADMIN_OPS_ACCESS_KEY=stratus-adminops-$(rand_hex 6)"
    echo "CEPH_ADMIN_OPS_SECRET_KEY=$(rand_hex 20)"
  } >>"$HARNESS_DIR/.env"
  log "Added a generated Admin Operations API reader to $HARNESS_DIR/.env"
fi
# Backfilled separately from the credentials above: this is a non-secret URL and
# an .env generated before the dashboard REST contract existed will not have it.
if ! grep -q '^CEPH_DASHBOARD_ENDPOINT=' "$HARNESS_DIR/.env"; then
  {
    echo ''
    echo '# Dashboard REST API URL, added by startup.'
    echo "CEPH_DASHBOARD_ENDPOINT=https://object-store.stratus.local:${CEPH_DASHBOARD_PORT:-8444}"
  } >>"$HARNESS_DIR/.env"
  log "Added CEPH_DASHBOARD_ENDPOINT to $HARNESS_DIR/.env"
fi
# Idempotent: generates on first run, renews when a certificate nears expiry.
"$(dirname "$0")/../lib/ceph-compose-generate-certificates.sh"
load_environment
require_free_harness_subnet
mkdir -p "$HARNESS_DIR/evidence"
# Validate interpolation before touching container state so a broken .env
# fails here with a compose diagnostic rather than mid-startup.
compose config --quiet
compose up --detach --remove-orphans --wait

# The Dashboard's RGW pages and its /api/rgw REST endpoints need an RGW identity
# of their own. This runs here rather than in ceph/configure.sh because the
# command requires a running RGW daemon, and configure.sh completes before the
# RGW services start. It is idempotent. A failure is reported but does not abort
# startup: the cluster and the S3 endpoint are fully usable without it, and only
# the dashboard's RGW views depend on it.
if compose exec -T mon1 ceph dashboard set-rgw-credentials >/dev/null 2>&1; then
  log "Configured the Dashboard RGW credentials"
else
  log "WARNING: could not configure Dashboard RGW credentials; /api/rgw endpoints will be unavailable"
fi

compose ps
