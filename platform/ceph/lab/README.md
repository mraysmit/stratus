# Cephadm Deployment

This directory owns Ceph Linux deployment assets:

- host inventory templates
- MON, MGR, OSD, and RGW service specifications
- drive-group and CRUSH templates
- certificate references
- monitoring and ingress service specifications

Environment-specific addresses, hostnames, and device assignments belong under `environments/`; reusable Ceph service definitions belong here. Docker Desktop is not a cephadm target.
