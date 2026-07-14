# Query Verification

Verifies that Trino is deployed, connected to Apache Polaris as its Iceberg catalog, and able to execute SQL queries over bronze, silver, and gold datasets. Verification covers table resolution via the Polaris REST catalog, correct row counts and aggregates against known dataset sizes produced by the compute layer, cross-namespace joins, schema enforcement on invalid column references, and direct query access to `platform.quality.check_results`. Trino must return results consistent with what Spark wrote — not a separate data path.

Prerequisite: `compute` verification passed against a live cluster.
