# Verification

This directory contains executable platform conformance suites. Each verifier is an independently testable Maven module and may own an image under its local `image/` directory.

Verifiers test stable platform requirements rather than implementation increments. They must not contain administrator credentials, environment inventory, or third-party service deployment configuration.

Current modules:

| Module | Platform layer | Status |
|---|---|---|
| `storage` | Object storage — Ceph RGW S3 operations | Active |
| `catalog` | Table catalog — Apache Iceberg + Apache Polaris | Active |
| `compute` | Batch compute — Apache Spark pipeline | Placeholder |
| `orchestration` | Workflow orchestration — Apache Airflow | Placeholder |
| `query` | Interactive query — Trino | Placeholder |
| `governance` | Metadata and policy — Apache Atlas + Apache Ranger | Placeholder |
| `identity` | Identity and security — FreeIPA + Keycloak | Placeholder |

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
