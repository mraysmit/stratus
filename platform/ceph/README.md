# Ceph Platform Integration

Ceph is the Stratus object-storage baseline. This directory owns Ceph-specific deployment and bootstrap assets.

```text
ceph/
├── tests/             # shared Ceph/RGW conformance suites and implementation guardrails
├── compose-cluster/   # genuine Ceph cluster orchestrated by Compose
├── cephadm-cluster/   # reusable multi-host cephadm deployment assets
└── rgw-bootstrap/     # buckets, RGW identities, quotas, and policy automation
```

The `compose-cluster` implementation deploys three genuine Ceph MONs, two MGRs, three BlueStore OSDs, and two RGW daemons in separate containers using Docker or Podman Compose. It is a real distributed Ceph cluster with container-level CRUSH host boundaries, currently executed through one container engine on one infrastructure host.

`tests/` owns the `stratus-ceph-tests` Maven module. Its
`CephRgwConformanceTest` runs the same live S3, security, consistency, pagination,
and multipart conformance checks against any Ceph RGW endpoint supplied through
environment variables; it does not start or depend on a particular service
implementation. Compose-specific static checks are kept in separately named
`ComposeCluster*Test` classes in the same Ceph-owned module.

`compose-cluster/` is currently implemented. `cephadm-cluster/` and `rgw-bootstrap/` are reserved placeholders whose content is delivered by the production-track tasks in the Phase 1 implementation plan. Environment-specific inventory and values remain under `environments/`.

To create and verify the workstation cluster for the first time, use the
[Ceph Compose Cluster Quick Start Guide](compose-cluster/CEPH_COMPOSE_CLUSTER_QUICK_START_GUIDE.md).
