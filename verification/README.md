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
| `orchestration` | Workflow orchestration — Apache Airflow | Active |
| `query` | Interactive query — Trino | Placeholder |
| `governance` | Metadata and policy — Apache Atlas + Apache Ranger | Placeholder |
| `identity` | Identity and security — FreeIPA + Keycloak | Placeholder |

Orchestration status checkpoint (2026-08-24): `P1-4.3-V1` is implemented. The
active Java verifier authenticates to Airflow 3.3.1, checks scheduler and metadata
health, requires all four Stratus DAGs to be registered and unpaused, triggers
caller-correlated runs through the public REST API, polls them with bounded
timeouts, and records exact run/task states and timings. Its checked-in live
harness also proves a successful metadata-policy maintenance run and a deliberate
quality-gate failure that prevents the downstream write, verifies both outcomes
independently in Iceberg, scans the transcript for secrets, removes exact fixtures,
and shuts down all Stratus providers. See
[`platform/airflow/development-acceptance-20260822.md`](../platform/airflow/development-acceptance-20260822.md).
The focused public-API run was
`airflow-api-orchestration-20260824T073836Z` (420,772 ms), followed by exact
fixture cleanup and zero remaining Stratus containers.
Canonical suite `airflow-development-acceptance-20260824T103411Z` then passed
every Airflow development phase in 2,975.509 seconds, ran 294 offline tests both
before and after live verification, and again left zero Stratus containers.
The `P1-4.G-D` D1 evidence matrix and D2 development-state manifest are accepted
in
[`platform/airflow/developer-gate-20260824.md`](../platform/airflow/developer-gate-20260824.md).
Increment 4 development is complete; Increment 5 development engineering is the
next portfolio work package.

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
