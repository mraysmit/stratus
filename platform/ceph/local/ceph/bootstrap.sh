#!/usr/bin/env bash
set -euo pipefail

conf=/etc/ceph/ceph.conf
admin_keyring=/etc/ceph/ceph.client.admin.keyring
mon_keyring=/etc/ceph/ceph.mon.keyring
monmap=/etc/ceph/monmap
keys=/etc/ceph/keys

if [[ -f /etc/ceph/STRATUS_CLUSTER_BOOTSTRAPPED ]]; then
  echo "Ceph cluster configuration already exists"
  exit 0
fi

# The sentinel is the sole source of truth: a missing sentinel with leftover
# artifacts means an earlier bootstrap failed part-way. Clear them so a re-run
# never mixes keys, monmaps, or fsids from different attempts.
rm -f "$conf" "$admin_keyring" "$mon_keyring" "$monmap"
rm -rf "$keys" /var/lib/ceph/mon1/* /var/lib/ceph/mon2/* /var/lib/ceph/mon3/*

mkdir -p "$keys" /var/lib/ceph/mon1 /var/lib/ceph/mon2 /var/lib/ceph/mon3
fsid=$(uuidgen)

cat >"$conf" <<EOF
[global]
fsid = $fsid
mon initial members = mon1, mon2, mon3
mon host = [v2:172.28.0.11:3300/0,v1:172.28.0.11:6789/0],[v2:172.28.0.12:3300/0,v1:172.28.0.12:6789/0],[v2:172.28.0.13:3300/0,v1:172.28.0.13:6789/0]
public network = 172.28.0.0/24
cluster network = 172.28.0.0/24
osd pool default size = 2
osd pool default min size = 1
osd pool default pg num = 8
osd pool default pg autoscale mode = on
osd memory target = 268435456
auth allow insecure global id reclaim = false
mon allow pool delete = true

[osd]
osd objectstore = bluestore
bluestore block create = true
bluestore block size = 1073741824
EOF

ceph-authtool "$admin_keyring" --create-keyring --gen-key -n client.admin \
  --cap mon 'allow *' --cap osd 'allow *' --cap mgr 'allow *' --cap mds 'allow *'
ceph-authtool "$mon_keyring" --create-keyring --gen-key -n mon. --cap mon 'allow *'
ceph-authtool "$mon_keyring" --import-keyring "$admin_keyring"

create_and_import_key() {
  local path=$1
  local entity=$2
  shift 2
  ceph-authtool "$path" --create-keyring --gen-key -n "$entity" "$@"
  ceph-authtool "$mon_keyring" --import-keyring "$path"
}

create_and_import_key "$keys/mgr.mgr1.keyring" mgr.mgr1 --cap mon 'allow profile mgr' --cap osd 'allow *' --cap mds 'allow *'
create_and_import_key "$keys/mgr.mgr2.keyring" mgr.mgr2 --cap mon 'allow profile mgr' --cap osd 'allow *' --cap mds 'allow *'

for id in 0 1 2; do
  create_and_import_key "$keys/osd.${id}.keyring" "osd.${id}" --cap mon 'allow profile osd' --cap osd 'allow *' --cap mgr 'allow profile osd'
done

for id in rgw1 rgw2; do
  create_and_import_key "$keys/client.rgw.${id}.keyring" "client.rgw.${id}" --cap mon 'allow rw' --cap osd 'allow rwx'
  cat >>"$conf" <<EOF

[client.rgw.$id]
rgw frontends = beast endpoint=0.0.0.0:8080
rgw dns name = object-store.stratus.local
EOF
done

monmaptool --create --fsid "$fsid" "$monmap"
monmaptool --addv mon1 '[v2:172.28.0.11:3300,v1:172.28.0.11:6789]' "$monmap"
monmaptool --addv mon2 '[v2:172.28.0.12:3300,v1:172.28.0.12:6789]' "$monmap"
monmaptool --addv mon3 '[v2:172.28.0.13:3300,v1:172.28.0.13:6789]' "$monmap"

for id in 1 2 3; do
  data="/var/lib/ceph/mon${id}"
  ceph-mon --cluster ceph --mkfs -i "mon${id}" --monmap "$monmap" --keyring "$mon_keyring" --mon-data "$data"
done

chown -R ceph:ceph /etc/ceph /var/lib/ceph/mon1 /var/lib/ceph/mon2 /var/lib/ceph/mon3
touch /etc/ceph/STRATUS_CLUSTER_BOOTSTRAPPED
echo "Initialized three-monitor Ceph cluster configuration"
