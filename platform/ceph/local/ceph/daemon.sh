#!/usr/bin/env bash
set -euo pipefail

: "${DAEMON_TYPE:?DAEMON_TYPE is required}"
: "${DAEMON_ID:?DAEMON_ID is required}"

wait_for_quorum() {
  local attempts=0
  until ceph --connect-timeout 2 quorum_status --format json 2>/dev/null | jq -e '.quorum | length == 3' >/dev/null; do
    attempts=$((attempts + 1))
    if (( attempts >= 90 )); then
      echo "Three-monitor quorum did not become ready" >&2
      exit 1
    fi
    sleep 1
  done
}

case "$DAEMON_TYPE" in
  mon)
    : "${MON_IP:?MON_IP is required for a monitor}"
    data="/var/lib/ceph/mon/ceph-${DAEMON_ID}"
    chown -R ceph:ceph "$data"
    exec ceph-mon -f --cluster ceph --setuser ceph --setgroup ceph -i "$DAEMON_ID" --mon-data "$data" --public-addr "$MON_IP" --default-log-to-file=false --default-log-to-stderr=true
    ;;
  mgr)
    wait_for_quorum
    data="/var/lib/ceph/mgr/ceph-${DAEMON_ID}"
    mkdir -p "$data"
    cp "/etc/ceph/keys/mgr.${DAEMON_ID}.keyring" "$data/keyring"
    chown -R ceph:ceph "$data"
    exec ceph-mgr -f --cluster ceph --setuser ceph --setgroup ceph -i "$DAEMON_ID" --keyring "$data/keyring" --default-log-to-file=false --default-log-to-stderr=true
    ;;
  osd)
    wait_for_quorum
    data="/var/lib/ceph/osd/ceph-${DAEMON_ID}"
    keyring="/etc/ceph/keys/osd.${DAEMON_ID}.keyring"
    mkdir -p "$data"
    if [[ ! -f "$data/whoami" ]]; then
      cp "$keyring" "$data/keyring"
      chown -R ceph:ceph "$data"
      ceph-osd --cluster ceph --osd-data "$data" --mkfs -i "$DAEMON_ID" --keyring "$data/keyring"
    fi
    chown -R ceph:ceph "$data"
    exec ceph-osd -f --cluster ceph --setuser ceph --setgroup ceph -i "$DAEMON_ID" --osd-data "$data" --default-log-to-file=false --default-log-to-stderr=true
    ;;
  rgw)
    wait_for_quorum
    data="/var/lib/ceph/radosgw/ceph-rgw.${DAEMON_ID}"
    mkdir -p "$data"
    cp "/etc/ceph/keys/client.rgw.${DAEMON_ID}.keyring" "$data/keyring"
    chown -R ceph:ceph "$data"
    exec radosgw -f --cluster ceph --setuser ceph --setgroup ceph -n "client.rgw.${DAEMON_ID}" -k "$data/keyring" --default-log-to-file=false --default-log-to-stderr=true
    ;;
  *)
    echo "Unsupported DAEMON_TYPE=$DAEMON_TYPE" >&2
    exit 64
    ;;
esac
