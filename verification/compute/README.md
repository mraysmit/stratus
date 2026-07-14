# Compute Verification

Verifies that the Apache Spark standalone cluster is deployed, connected to Polaris as its Iceberg catalog, and able to read from and write to MinIO. Verification covers the full batch pipeline: ingestion from the landing zone into bronze, type normalisation and deduplication into silver, aggregation into gold, data quality checks writing results to `platform.quality.check_results`, and the promotion gate that blocks zone promotion when a blocking quality check fails. Table maintenance jobs (compaction, snapshot expiry) are also exercised. No job may complete without producing a lineage event payload.

Prerequisite: `catalog` verification passed against a live cluster.
