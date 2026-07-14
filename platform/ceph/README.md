# Ceph Platform Integration

Ceph is the Stratus object-storage baseline. This directory owns Ceph-specific deployment and bootstrap assets.

```text
ceph/
├── bootstrap/   # buckets, RGW identities, quotas, and policy automation
├── lab/     # production Linux-host inventory, service specs, and templates
└── local/   # disposable genuine Ceph/RGW local Docker environment
```

The `local` environment deploys three genuine Ceph MONs, two MGRs, three BlueStore OSDs, and two RGW daemons in separate local Docker containers. Production lifecycle remains separately managed by cephadm assets in `lab/`.
