# Platform Integration

This directory contains lifecycle, configuration, bootstrap, and developer assets for the open-source products that form Stratus.

Each product owns a stable directory such as `ceph`, `polaris`, `spark`, `airflow`, `trino`, `atlas`, `ranger`, `freeipa`, `keycloak`, `kafka`, `kafka-connect`, `debezium`, or `flink` when implementation begins.

Product directories may contain:

- upstream-derived image definitions
- service configuration templates
- developer Compose environments
- production deployment specifications
- bootstrap and policy automation
- product-specific operational helpers

Stratus-owned Java services belong under `applications/`, compute jobs under `jobs/`, and contract tests under `verification/`.
