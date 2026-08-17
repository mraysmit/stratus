# Verification

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

Orchestration status checkpoint (2026-08-17): the shared Airflow 3.3.1 image
baseline is accepted for developer use under `WAIVER-P1-4.1-S1-20260817`
through 2026-09-16. Production promotion is prohibited. The 35 unique residual
Spark/Hadoop JAR High findings still require permanent disposition, while
deployment and executable verification have not started. The `orchestration`
module therefore correctly remains a placeholder; the image baseline is not a
live-platform verification result.

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
