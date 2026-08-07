# Compute Verification

Verifies that the Apache Spark standalone cluster is deployed, connected to Polaris as its Iceberg catalog, and able to read from and write to Ceph RGW. Verification covers the full batch pipeline: ingestion from the landing zone into bronze, type normalisation and deduplication into silver, aggregation into gold, data quality checks writing results to `platform.quality_check_results`, and the promotion gate that blocks zone promotion when a blocking quality check fails. Table maintenance jobs (compaction, snapshot expiry) are also exercised. No job may complete without producing a lineage event payload.

Spark writes quality results into the permanent `platform.quality_check_results` table that the catalog bootstrap provisions and `stratus-catalog-verifier` verifies; its documented shape lives in `QualityCheckResultsTableDefinition` in `verification/catalog/`. The table is append-only, so this suite adds rows and never rewrites or truncates them.

Prerequisite: `catalog` verification passed against a live cluster.
