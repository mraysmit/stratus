# Platform Integration

This directory contains lifecycle, configuration, bootstrap, and developer assets for the open-source products that form Stratus.

Each product owns a stable directory such as `ceph`, `polaris`, `openbao`, `spark`, `airflow`, `trino`, `atlas`, `ranger`, `freeipa`, `keycloak`, `kafka`, `kafka-connect`, `debezium`, or `flink` when implementation begins. Ceph, Polaris, OpenBao, and Spark have implemented developer assets. Airflow has an implemented shared image baseline; its developer deployment is the next Increment 4 task.

Product directories may contain:

- upstream-derived image definitions
- service configuration templates
- developer Compose environments
- production deployment specifications
- bootstrap and policy automation
- product-specific operational helpers

The Airflow 3.3.1 image baseline passed its 2026-08-17 zero-Critical
vulnerability gate. Its remaining 84 High occurrences represent 35 unique
package/CVE pairs, all in the upstream Spark/Hadoop JAR set. They require an
upstream upgrade and reachability analysis. The shared baseline is accepted for
developer use under `WAIVER-P1-4.1-S1-20260817` through 2026-09-16; production
promotion remains prohibited.

Stratus-owned Java services belong under `applications/`, compute jobs under `jobs/`, and conformance tests under `verification/`.
