# Platform Integration

This directory contains lifecycle, configuration, bootstrap, and developer assets for the open-source products that form Stratus.

Each product owns a stable directory such as `ceph`, `polaris`, `openbao`, `spark`, `airflow`, `trino`, `atlas`, `ranger`, `freeipa`, `keycloak`, `kafka`, `kafka-connect`, `debezium`, or `flink` when implementation begins. Ceph, Polaris, OpenBao, Spark, and Airflow have implemented developer assets. Airflow's current image, two-cycle LocalExecutor/PostgreSQL deployment, and live submission to the Spark/Polaris/Ceph development stack were accepted on 2026-08-22. Pipeline DAG work is now in progress: the landing-to-bronze contract parses and registers in Airflow, while its live execution and the remaining DAG/verifier scenarios are open.

Product directories may contain:

- upstream-derived image definitions
- service configuration templates
- developer Compose environments
- production deployment specifications
- bootstrap and policy automation
- product-specific operational helpers

The current Airflow 3.3.1 S2 image passed its 2026-08-22 zero-Critical
development vulnerability gate. Its 61 High occurrences represent 38 unique
package/CVE pairs and remain tracked in
[`airflow/image/development-vulnerability-review-s2.md`](airflow/image/development-vulnerability-review-s2.md).
The older 2026-08-17 S1 image and waiver are historical only. No Airflow
development result is a production promotion or readiness claim.

Stratus-owned Java services belong under `applications/`, compute jobs under `jobs/`, and conformance tests under `verification/`.
