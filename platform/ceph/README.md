# Ceph Platform Integration

Ceph is the Stratus object-storage baseline. This directory owns Ceph-specific deployment and bootstrap assets.

```text
ceph/
├── bootstrap/   # buckets, RGW identities, quotas, and policy automation
├── cephadm/     # Linux host inventory, service specs, and templates
└── developer/   # Docker/Podman client harness for an existing RGW endpoint
```

The `developer` environment does not deploy Ceph. MON, MGR, OSD, and RGW lifecycle remains under cephadm in `cephadm/`.
