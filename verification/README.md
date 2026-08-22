# Verification

**Current stage:** Development implementation and functional acceptance.

**Later stage:** Production deployment hardening and readiness.

This directory contains executable platform conformance suites. Each verifier is an independently testable Maven module and may own an image under its local `image/` directory.

Verifiers test stable platform requirements rather than implementation increments. They must not contain administrator credentials, environment inventory, or third-party service deployment configuration.

Current modules:

| Module | Platform layer | Status |
|---|---|---|
| `storage` | Object storage — Ceph RGW S3 operations | Active |
| `catalog` | Table catalog — Apache Iceberg + Apache Polaris | Active |
| `secrets` | Secret distribution — OpenBao (ADR-P1-004) | Active |
| `compute` | Batch compute — Apache Spark pipeline | Placeholder |
| `orchestration` | Workflow orchestration — Apache Airflow | Placeholder |
| `query` | Interactive query — Trino | Placeholder |
| `governance` | Metadata and policy — Apache Atlas + Apache Ranger | Placeholder |
| `identity` | Identity and security — FreeIPA + Keycloak | Placeholder |

Orchestration status checkpoint (2026-08-22): `P1-4.1-S2` produced the accepted
Airflow 3.3.1 development image, `P1-4.1-D1` passed two complete
LocalExecutor/PostgreSQL lifecycle cycles, and `P1-4.2-D1` passed a real packaged
Java submission to the Spark/Polaris/Ceph development stack. The S2 scan reported
zero Critical and 61 High occurrences across 38 unique package/CVE pairs; those
High findings remain tracked. The `orchestration` module correctly remains a
placeholder because `P1-4.3-V1` is only partly implemented: the landing-to-bronze
source contract parses and registers in Airflow, but the executable verifier and
complete live ingestion, transform, quality-halt, maintenance, retry and alert
scenarios remain. Registry publication and readiness controls remain deferred to the
later production-hardening stage. See
[`platform/airflow/development-acceptance-20260822.md`](../platform/airflow/development-acceptance-20260822.md).

## Quality Gate

Run the complete verifier build from the repository root:

```powershell
.\mvnw.cmd clean verify
```

```bash
./mvnw clean verify
```

The centrally managed JaCoCo gate requires 100% line coverage and 100% branch coverage for every verifier module. Any uncovered production line or branch fails Maven's `verify` phase. The storage HTML report is generated at `verification/storage/target/site/jacoco/index.html`.

Tests must also exercise operational logging at both supported levels. `INFO` covers lifecycle results and `DEBUG` covers diagnostic operation detail; neither level may expose access keys, secret keys, or object payloads.
