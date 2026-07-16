# Ceph Bootstrap

This directory is reserved for reusable, idempotent automation that provisions Stratus RGW buckets, scoped identities, quotas, and policies. It is currently a placeholder: the bucket and identity automation for Increment 1 lives in `../local/scripts/verify/bootstrap-buckets.{ps1,sh}` and `../local/ceph/configure.sh`, and will be extracted here when a second environment needs it.

Developer-only wrappers may call this automation from `../local/scripts/`, but the canonical desired state belongs here. Secret values and Ceph administrator keyrings must never be committed.
