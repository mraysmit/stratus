# Cephadm Cluster

This directory is a reserved placeholder. The reusable service specifications and automation listed below are delivered by the production-track tasks of the Phase 1 implementation plan ([ceph_storage.md](../../../docs/implementation/ceph_storage.md)); nothing here is implemented yet.

This directory owns reusable cephadm deployment assets for Linux clusters:

- host-inventory schemas and validation
- MON, MGR, OSD, and RGW service specifications
- drive-group and CRUSH templates
- certificate references
- monitoring and ingress service specifications

Environment-specific addresses, hostnames, and device assignments belong under `environments/`; reusable Ceph service definitions and orchestration automation belong here. Docker Desktop is not a cephadm target.
