# Governance Verification

Verifies that Apache Atlas and Apache Ranger are deployed and integrated with the running platform. Atlas verification covers entity registration for Iceberg datasets on first write, lineage publication from each Spark job (source to bronze, bronze to silver, silver to gold), and quality status propagation as a dataset attribute. Ranger verification covers zone-based access policies (engineers read/write all zones; domain analysts read silver and gold only), denial of bronze access to analyst principals, and tag-based policy enforcement denying access to PII-classified tables for unpermissioned users. Every Spark job must produce a lineage event — no job may complete silently.

Prerequisite: `query` verification passed against a live cluster.
