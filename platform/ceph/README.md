# Ceph Platform Integration

Ceph is the Stratus object-storage baseline. This directory owns Ceph-specific deployment and bootstrap assets.

```text
ceph/
├── bootstrap/   # buckets, RGW identities, quotas, and policy automation
├── developer/   # disposable single-workstation Ceph/RGW Compose environment
└── lab/         # production Linux-host inventory, service specs, and templates
```

The `developer` environment deploys three genuine Ceph MONs, two MGRs, three BlueStore OSDs, and two RGW daemons in separate containers on one developer workstation using Docker or Podman. Production lifecycle remains separately managed by cephadm assets in `lab/`.

`developer/` is the currently implemented environment. `bootstrap/` and `lab/` are reserved placeholders whose content is delivered by the production-track tasks in the Phase 1 implementation plan.
