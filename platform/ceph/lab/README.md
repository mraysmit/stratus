# Cephadm Deployment

This directory is a reserved placeholder. The host inventory, service specifications, and templates listed below are delivered by the production-track tasks of the Increment 1 implementation plan ([ceph_storage.md](../../../docs/implementation/ceph_storage.md)); nothing here is implemented yet.

This directory owns Ceph Linux deployment assets:

- host inventory templates
- MON, MGR, OSD, and RGW service specifications
- drive-group and CRUSH templates
- certificate references
- monitoring and ingress service specifications

Environment-specific addresses, hostnames, and device assignments belong under `environments/`; reusable Ceph service definitions belong here. Docker Desktop is not a cephadm target.
