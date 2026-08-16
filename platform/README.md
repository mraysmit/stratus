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

The Airflow image baseline has passed its zero-Critical vulnerability gate;
remaining High findings require triage before the shared baseline is accepted.

Stratus-owned Java services belong under `applications/`, compute jobs under `jobs/`, and conformance tests under `verification/`.
