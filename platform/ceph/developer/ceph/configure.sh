#!/usr/bin/env bash
set -euo pipefail

: "${CEPH_DEMO_UID:?CEPH_DEMO_UID is required}"
: "${CEPH_DEMO_ACCESS_KEY:?CEPH_DEMO_ACCESS_KEY is required}"
: "${CEPH_DEMO_SECRET_KEY:?CEPH_DEMO_SECRET_KEY is required}"
: "${CEPH_DENIED_UID:?CEPH_DENIED_UID is required}"
: "${CEPH_DENIED_ACCESS_KEY:?CEPH_DENIED_ACCESS_KEY is required}"
: "${CEPH_DENIED_SECRET_KEY:?CEPH_DENIED_SECRET_KEY is required}"
: "${CEPH_DASHBOARD_USER:?CEPH_DASHBOARD_USER is required}"
: "${CEPH_DASHBOARD_PASSWORD:?CEPH_DASHBOARD_PASSWORD is required}"

attempts=0
until ceph osd stat --format json | jq -e '.num_up_osds == 3 and .num_in_osds == 3' >/dev/null; do
  attempts=$((attempts + 1))
  if (( attempts >= 90 )); then
    echo "Three OSDs did not become up and in" >&2
    exit 1
  fi
  sleep 1
done

if ! radosgw-admin user info --uid "$CEPH_DEMO_UID" >/dev/null 2>&1; then
  radosgw-admin user create \
    --uid "$CEPH_DEMO_UID" \
    --display-name "Stratus local verifier" \
    --access-key "$CEPH_DEMO_ACCESS_KEY" \
    --secret-key "$CEPH_DEMO_SECRET_KEY" >/dev/null
fi

if ! radosgw-admin user info --uid "$CEPH_DENIED_UID" >/dev/null 2>&1; then
  radosgw-admin user create \
    --uid "$CEPH_DENIED_UID" \
    --display-name "Stratus local denied-bucket owner" \
    --access-key "$CEPH_DENIED_ACCESS_KEY" \
    --secret-key "$CEPH_DENIED_SECRET_KEY" >/dev/null
fi

for pool in $(ceph osd pool ls); do
  ceph osd pool set "$pool" size 2 >/dev/null
  ceph osd pool set "$pool" min_size 1 >/dev/null
done

# Ceph Dashboard: plain HTTP inside the cluster network on the active manager;
# rgw-proxy terminates TLS in front of it on the published dashboard port. The
# standby manager answers with an error instead of a redirect so the proxy can
# fail over to whichever manager is active.
ceph config set mgr mgr/dashboard/ssl false
ceph config set mgr mgr/dashboard/server_addr 0.0.0.0
ceph config set mgr mgr/dashboard/server_port 8500
ceph config set mgr mgr/dashboard/standby_behaviour error
if ! ceph mgr module ls --format json | jq -e '.enabled_modules | index("dashboard") != null' >/dev/null; then
  ceph mgr module enable dashboard
fi

attempts=0
until ceph dashboard ac-user-show >/dev/null 2>&1; do
  attempts=$((attempts + 1))
  if (( attempts >= 60 )); then
    echo "Dashboard module did not become available" >&2
    exit 1
  fi
  sleep 1
done

if ! ceph dashboard ac-user-show "$CEPH_DASHBOARD_USER" >/dev/null 2>&1; then
  password_file=$(mktemp)
  printf '%s' "$CEPH_DASHBOARD_PASSWORD" >"$password_file"
  ceph dashboard ac-user-create "$CEPH_DASHBOARD_USER" -i "$password_file" administrator >/dev/null
  rm -f "$password_file"
fi

echo "Configured replicated RGW pools, isolated local verification identities, and the dashboard"
