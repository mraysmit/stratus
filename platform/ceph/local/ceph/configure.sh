#!/usr/bin/env bash
set -euo pipefail

: "${CEPH_DEMO_UID:?CEPH_DEMO_UID is required}"
: "${CEPH_DEMO_ACCESS_KEY:?CEPH_DEMO_ACCESS_KEY is required}"
: "${CEPH_DEMO_SECRET_KEY:?CEPH_DEMO_SECRET_KEY is required}"
: "${CEPH_DENIED_UID:?CEPH_DENIED_UID is required}"
: "${CEPH_DENIED_ACCESS_KEY:?CEPH_DENIED_ACCESS_KEY is required}"
: "${CEPH_DENIED_SECRET_KEY:?CEPH_DENIED_SECRET_KEY is required}"

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

echo "Configured replicated RGW pools and isolated local verification identities"
