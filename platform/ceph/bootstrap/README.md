# Ceph Bootstrap

This directory is reserved for reusable, idempotent automation that provisions Stratus RGW buckets, scoped identities, quotas, and policies.

Developer-only wrappers may call this automation from `../developer/scripts/`, but the canonical desired state belongs here. Secret values and Ceph administrator keyrings must never be committed.
