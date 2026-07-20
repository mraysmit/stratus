# RGW Bootstrap

This directory is reserved for reusable, idempotent automation that provisions Stratus RGW buckets, scoped identities, quotas, and policies. It is currently a placeholder: the bucket and identity automation for Phase 1 lives in `../compose-cluster/scripts/verify/bootstrap-buckets.{ps1,sh}` and `../compose-cluster/ceph/configure.sh`, and will be extracted here when a second deployment needs it.

Deployment-specific wrappers may call this automation from `../compose-cluster/scripts/` or `../cephadm-cluster/`, but the canonical desired state belongs here. Secret values and Ceph administrator keyrings must never be committed.
