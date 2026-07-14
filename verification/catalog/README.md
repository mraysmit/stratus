# Catalog Verification

Verifies that Apache Polaris is deployed and correctly wired to MinIO object storage, that the required namespaces exist, and that the Iceberg table layer operates as specified. Verification covers catalog bootstrap, namespace and table creation via the Polaris REST API, read/write round-trips using the Iceberg Java API, schema enforcement, and table maintenance operations including snapshot expiry, file compaction, and orphan cleanup. The `platform.quality.check_results` table must exist with the correct schema and accept written records before this verification is considered complete.

Prerequisite: `storage` verification passed against a live cluster.
