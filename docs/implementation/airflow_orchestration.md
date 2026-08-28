# Stratus Increment 4 — Apache Airflow Orchestration

**Canonical implementation guide**

**Last reviewed:** 2026-08-28

**Current stage:** Development implementation and functional acceptance.

**Later stage:** Production deployment hardening and readiness.

**Developer profile:** V1 accepted on 2026-08-24; revised V2 implementation evidence passed on
2026-08-28 and source revision `7dba05e` is recorded; superseding acceptance remains pending

**Production profile:** planned and not yet accepted

## 1. Purpose and source of truth

Airflow is the Stratus batch control plane. It coordinates packaged Java Spark jobs; it does not
perform data-plane transformations itself. The checked-in DAGs, Compose manifests, lifecycle
scripts and executable tests are the implementation source of truth. This guide describes those
artifacts and the remaining production boundary without copying executable DAG source into prose.

The definitive implementation locations are:

- developer runtime: [`platform/airflow/developer/`](../../platform/airflow/developer/);
- DAGs: [`platform/airflow/developer/dags/`](../../platform/airflow/developer/dags/);
- live tests: [`platform/airflow/developer/scripts/tests/`](../../platform/airflow/developer/scripts/tests/);
- Java REST verifier: [`verification/orchestration/`](../../verification/orchestration/);
- accepted point-in-time evidence: [`platform/airflow/developer-gate-20260824.md`](../../platform/airflow/developer-gate-20260824.md);
- active performance and remediation register:
  [`stratus_performance_review_20260828.md`](stratus_performance_review_20260828.md);
- promotion-boundary decision: [`ADR-P1-007`](../decisions/ADR-P1-007-airflow-promotion-gate-boundary.md).

## 2. Supported profiles

| Concern | Developer profile | Production profile |
|---|---|---|
| Runtime | Docker Compose on Docker Desktop/Engine or compatible Compose implementation | Linux OCI runtime selected by the environment; Podman uses Quadlet-managed units |
| Airflow | 3.3.1, one API server, DAG processor, scheduler and triggerer | same accepted DAG and image contract, with availability sized to RTO/RPO |
| Executor | LocalExecutor | selected only after capacity and isolation evidence |
| Metadata | PostgreSQL 17.10 named volume | durable external PostgreSQL with backup and restore proof |
| Logs | local named volume/bind mount | approved durable remote log store with continuity proof |
| Identity | loopback-only SimpleAuthManager exception | trusted HTTPS and Keycloak/OIDC; no anonymous administrator |
| Secrets | generated ignored `.env` and protected Airflow connections | managed secrets, rotation and audited retrieval |
| Acceptance | deterministic small fixtures and functional evidence | representative load, recovery, security and capacity evidence |

The developer topology is disposable and is not production evidence. The production deployment
manifest does not yet exist. The deprecated `podman generate systemd` workflow is not part of the
design; a future Podman deployment must use Quadlet or another approved current mechanism.

## 3. Runtime and dependency baseline

The image is built from the digest-pinned Airflow 3.3.1 Python 3.14 base and obtains the Spark
4.1.3 Java 21 command-line runtime from a digest-pinned OCI stage. Stratus uses
`SparkSubmitOperator` with packaged Java applications and `pyspark-client`; full PySpark and its
duplicate JAR tree are intentionally absent. The data-plane cluster has separately accepted
Spark-client compatibility evidence.

Airflow 3.3.1's official Python 3.14 constraints select:

| Dependency | Retained version | Decision on 2026-08-25 |
|---|---:|---|
| Spark provider | 6.3.1 | retain the official Airflow constraint while 6.3.2 is assessed |
| Amazon provider | 9.34.0 | retain the official Airflow constraint while 9.35.0 is assessed |
| boto3 | 1.43.56 | retain the official constraint as one tested set |

Spark provider 6.3.2 and Amazon provider 9.35.0 were released on 2026-08-23. Their existence does
not justify an isolated patch bump: any upgrade must regenerate a complete locked dependency set,
build the candidate image, run provider imports and smoke tests, and repeat the Spark submission,
landing sensor and orchestration API proofs. The current official constraint set remains the
accepted compatibility baseline until that audit passes.

The Java policy for Stratus-owned builds and the Spark/Airflow runtime is Java 21. Component-specific
exceptions are recorded separately and must not silently change this runtime.

The image resolver builds and hash-checks a temporary wheelhouse before promotion. A failed move
restores the previous cache immediately, and a later invocation recovers the previous cache if the
process stopped between directory moves. Image preflight asks pip to select the locked artifacts
offline and requires that selected filename set to match the complete wheelhouse, excluding only
its checksum manifest. This rejects both missing requirements and unreferenced archives before the
Dockerfile can copy them into an image layer.

## 4. Developer topology and lifecycle

`compose.yaml` runs PostgreSQL, Airflow init, API server, DAG processor, scheduler and triggerer.
`compose.spark.yaml` adds only the Spark-facing mounts and configuration required by submission
tests. The API is published on loopback. DAGs and helper modules are mounted from the repository;
metadata and logs use named volumes so ordinary shutdown is non-destructive.

Run lifecycle operations from the repository root with Bash 4+:

```bash
bash platform/airflow/developer/scripts/lifecycle/airflow-compose-startup.sh
bash platform/airflow/developer/scripts/tests/airflow-compose-verify-health.sh
bash platform/airflow/developer/scripts/lifecycle/airflow-compose-shutdown.sh
```

The first startup creates ignored `.env` state from `.env.template`. Reset deletes disposable
Airflow developer state and therefore prompts unless explicitly forced:

```bash
bash platform/airflow/developer/scripts/lifecycle/airflow-compose-reset.sh
```

Lifecycle scripts own Compose project naming, migration, health polling and checked shutdown.
Operators should use those scripts instead of issuing ad hoc container commands.

## 5. DAG inventory and schedules

| DAG | Current schedule | Task chain | Purpose |
|---|---|---|---|
| `stratus_landing_to_bronze` | `*/15 * * * *` | landing sensor → ingestion → bronze quality | detect one landing object, append its batch to bronze and persist checks |
| `stratus_bronze_to_silver` | manual/API (`None`) | bronze promotion gate → transform → silver quality | fail closed on persisted bronze evidence, upsert the correlated batch and record silver checks |
| `stratus_silver_to_gold` | manual/API (`None`) | silver quality → silver promotion gate → materialisation → gold quality | persist and expose the silver verdict before rebuilding and checking gold |
| `stratus_table_maintenance` | `@daily` | maintenance policy job | inspect Iceberg metadata and apply the named policy |
| `stratus_api_contract_probe` | manual/API (`None`) | empty positive task | test the Airflow REST trigger and task-state protocol without starting Spark |

The two transition DAGs intentionally remain manual/API-triggered in the developer profile. Their
production cadence and triggering contract must be approved with source-arrival, backfill and
capacity policy; documentation must not claim they already run hourly or daily.

Data-processing work uses the shared `spark_submit_task` helper and `SparkSubmitOperator`. The
promotion decision uses a direct Iceberg REST-catalog reader because reading a few quality rows
does not justify a Spark driver and executor allocation. The Spark helper selects the protected
Airflow connection and packaged Stratus jobs JAR. DAG configuration
accepts caller-owned correlation IDs and isolated source/target names where the contract permits.
Normal retries are two attempts separated by five minutes. Expected-failure live tests may set the
documented retry environment variables to zero so a deliberately blocked proof does not wait
through production-like retry delays.

## 6. Promotion-gate contract

Blocking quality results are persisted in `platform.quality_check_results`. A promotion requires an
exact quality `runId` and source `targetTable`; missing results and any blocking failure deny the
promotion.

For every governed write boundary:

1. Airflow runs `dev.stratus.jobs.spark.CatalogPromotionGateJob` as a named Bash task. It uses
   Iceberg's REST catalog and generic row reader and does not create a `SparkContext`.
2. A denied verdict fails that task and downstream writers remain `upstream_failed`.
3. A permitted verdict allows the writer task to start.
4. `TransformJob` or `MaterialisationJob` rechecks the same evidence immediately before writing.
5. The next layer's quality task independently persists its results.

The explicit task makes the decision, retry history and named owner visible in Airflow. The in-job
check is defence in depth against task clearing, DAG misuse and time-of-check/time-of-use drift.
Developer DAGs do not accept promotion overrides. A future production override requires an
authenticated named steward, reason, timestamp, affected table/run, immutable audit record and
post-event review as defined by the architecture and ADR.

## 7. Accountability, correlation and alerting

Every DAG has a named Airflow owner and every governed table has a named data steward. Run IDs,
pipeline IDs, source batches, source/target tables, task attempts and quality result IDs must remain
correlated across Airflow logs, Spark events, Iceberg snapshots and audit evidence. Stewardship is
not satisfied by a team alias alone: production procedures must identify who approved access,
movement, override or lifecycle action.

Failure callbacks emit structured identifiers, attempt information, log URL, elapsed time and
exception class without exception messages or secrets. Retry callbacks, terminal failures and
native Deadline Alerts have isolated live tests. Development callbacks write structured local log
events; routing to the approved production alert sink is deferred and must be proven before the
production gate.

## 8. Verification strategy

Use the narrowest tier that can invalidate the change:

| Tier | Command or entry point | Intended use |
|---|---|---|
| Offline contracts | `./mvnw -o verify` | ordinary feedback for source, lock, DAG and documentation drift |
| Image smoke/security | `platform/airflow/image/scripts/tests/airflow-image-acceptance-test.sh` | candidate image or dependency change |
| Lifecycle/parse | `airflow-compose-lifecycle-test.sh`, `airflow-pipeline-dag-parse-test.sh` | Compose or DAG-import change |
| Focused live slice | named landing, bronze/silver, silver/gold, maintenance, retry or deadline script | behavior changed in that slice |
| API control plane | `airflow-api-orchestration-live-test.sh` | API, task-state or promotion-boundary change; uses an empty positive DAG, the real blocked gate and a direct catalog no-write check |
| Canonical suite | `airflow-development-acceptance-suite.sh` | release/gate evidence only, not routine feedback |

The long suite deliberately composes many proofs and is not an acceptable inner development loop.
Tests must reuse suite-scoped providers where isolation allows, generate unique tables/run IDs, emit
phase timings, enforce bounded waits and clean exact fixtures. Tiny queries taking tens of seconds
can be dominated by Spark application startup, dependency distribution, catalog/object-store
initialisation and Airflow scheduling; timing records must keep those phases separate from query
execution rather than labelling the whole interval as query latency.

The Java verifier authenticates through Airflow's public REST API, validates health and DAG
registration, triggers caller-correlated runs, polls with a bound and checks exact DAG/task terminal
states. A separate direct Iceberg catalog check proves the blocked target is absent without starting
another Spark application. The blocked API scenario expects
`evaluate_bronze_promotion=failed`, with transform and downstream quality both
`upstream_failed`.

## 9. Evidence status

The 2026-08-24 developer gate is point-in-time evidence for the V1 implementation; it is not a
claim that every later commit is accepted. `P1-4.3-V2` changes the observable promotion boundary
and therefore requires fresh offline, DAG-parse, focused live and API task-state evidence before it
can supersede V1. Historical run durations and task states remain unchanged in their dated records.

Current task state:

| Task | State | Exit condition |
|---|---|---|
| `P1-4.1-S2` image/runtime assembly | Reverified 2026-08-25 after exact-set and recovery hardening; build and smoke passed | retain the locked artifact set; later publication remains a separate task |
| `P1-4.1-D1` Compose lifecycle | Accepted for development | production topology remains separate |
| `P1-4.2-D1` Airflow-to-Spark submission | Accepted for development | repeat on dependency/runtime change |
| `P1-4.3-V1` embedded gate evidence | Accepted point-in-time on 2026-08-24 | retained as historical evidence |
| `P1-4.3-V2` explicit gate task plus writer recheck | Implementation evidence passed 2026-08-28; source revision `7dba05e` is recorded | rerun the evidence against that revision and record dated superseding acceptance |
| `P1-4.G-D` developer gate | Accepted for the V1 state on 2026-08-24 | does not automatically accept V2 |
| `P1-4.1-P1`, `P1-4.5-R1`, `P1-4.4-V1` | Planned | hardened deployment, recovery, observability, capacity and schedule evidence pass |

### Developer-to-production promotion controls

- [x] **D1** — V1 functional behavior and its producing evidence were complete on 2026-08-24.
- [x] **D2** — V1 developer shortcuts and production replacements were recorded in the gate.

The [`P1-4.G-D`](../../platform/airflow/developer-gate-20260824.md) developer gate is accepted only
for the dated V1 state. V2 remains in progress and cannot inherit that acceptance.
In short, the `P1-4.G-D` developer gate is accepted for V1, not for subsequent source changes.

### Gate traceability rule

Every later acceptance record must name the producing task, immutable source revision, run IDs,
task states, data-side result, cleanup result, owner and exceptions. Dated evidence is never edited
to make it appear to describe a later implementation.

### Implementation task track

| Task | Dependency | Required evidence | State |
|---|---|---|---|
| `P1-4.1-D1` | `P1-4.1-S2` local development image | two Compose lifecycle cycles and health | Accepted for development |
| `P1-4.1-P1` | `P1-4.1-S2`, `P1-0.1` | published digest, hardened topology, restore and continuity | Planned |
| `P1-4.3-V2` | `P1-4.2-D1`, ADR-P1-007 | offline, parse, focused live, API states and no-write proof | Pre-commit evidence passed; committed rerun and acceptance record remain pending |

## 10. Production acceptance boundary

Production acceptance requires, at minimum:

- immutable published image digest, SBOM, provenance and vulnerability disposition;
- durable PostgreSQL backup/restore and remote-log continuity;
- trusted TLS, OIDC, managed secrets and named service identities;
- accepted scheduler/DAG-processor/executor availability and capacity;
- approved transition schedules, backfill rules and overlap behavior;
- promotion override audit and named steward procedure;
- alert routing, retry, deadline, dependency-loss and recovery drills;
- unchanged DAG/API/data-side verification against representative workloads.

No developer Compose result may be used as evidence for these controls.

The rebuilt local image passed its smoke and security acceptance on 2026-08-27. The real developer
deployment parsed the V2 DAGs on 2026-08-28, and the direct catalog gate demonstrated both an
accepted evidence run and a missing-evidence denial. The focused-live and public-API evidence set
passed before revision `7dba05e` was recorded. Superseding V1 still requires a rerun against that
committed revision and a dated acceptance record; the existing runs remain diagnostic evidence.

## 11. Troubleshooting

- Start with `airflow-compose-verify-health.sh`; it checks service and metadata health consistently.
- Use Airflow's import-error endpoint/CLI if DAGs do not register, then run the parse test.
- Verify the protected `spark_default` connection, mounted jobs JAR, Spark defaults, truststore and
  locked runtime hashes when submission fails.
- Check the exact quality `runId` and source table when a promotion task denies access.
- A gate denial with downstream `upstream_failed` is expected fail-closed behavior, not a missing
  retry. Investigate the persisted quality result before clearing tasks.
- Use the lifecycle shutdown script after tests; use reset only when disposable metadata/log state
  is intentionally being removed.

## 12. Authoritative upstream references

- Airflow downloads and supported versions: https://airflow.apache.org/docs/apache-airflow/stable/installation/supported-versions.html
- Airflow 3.3.1 constraints: https://raw.githubusercontent.com/apache/airflow/constraints-3.3.1/constraints-3.14.txt
- Spark provider: https://airflow.apache.org/docs/apache-airflow-providers-apache-spark/stable/
- Amazon provider: https://airflow.apache.org/docs/apache-airflow-providers-amazon/stable/
- Spark 4.1.3: https://spark.apache.org/docs/4.1.3/
- Podman systemd/Quadlet guidance: https://docs.podman.io/en/latest/markdown/podman-systemd.unit.5.html
