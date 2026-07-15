# Stratus Implementation Plan - Phase 1

## 1. Purpose

This document defines how Stratus Phase 1 is built and verified, increment by increment.

The architecture document defines what the platform is and why each component exists. This Phase 1 plan defines the order in which the governed batch lakehouse foundation is assembled, what each increment delivers, and how each increment is verified before the next begins.

The guiding principle is simple: **build the stack from the bottom up**. Storage before tables. Tables before compute. Compute before orchestration. Query before governance. Identity last, hardening what already works. Each increment should leave the platform in a working, demonstrable state.

Reference: [stratus_on_prem_data_fabric_architecture.md](../architecture/stratus_on_prem_data_fabric_architecture.md)

---

## 2. Stack and Build Order

The Stratus platform is built in layers. Each layer depends on the one below it being stable.

```text
Layer 7 — Identity and Security      FreeIPA · Keycloak · TLS hardening
Layer 6 — Governance                 Apache Atlas · Apache Ranger
Layer 5 — Query                      Trino
Layer 4 — Orchestration              Apache Airflow
Layer 3 — Compute                    Apache Spark
Layer 2 — Tables and Catalog         Apache Iceberg · Apache Polaris
Layer 1 — Storage                    Ceph RGW object storage
```

Implementation proceeds layer by layer. No layer is considered complete until its verification tests pass and the functional outcome is demonstrated.

After Layer 7, Phase 1 closes with an operational acceptance and production-readiness review. That review is not a new platform layer; it proves that the integrated stack is supportable before production dataset onboarding or Phase 2 work begins.

### 2.1 Developer and production tracks

Each increment advances through two related tracks:

| Track | Exit intent | May unblock |
|---|---|---|
| Developer profile | a new developer can start, stop, reset, and verify the capability on Docker Desktop or Podman with pinned artifacts and local test credentials | development of the next increment, provided the dependency contract is stable and the risk is recorded |
| Production profile | the same capability runs on its supported on-prem topology with durable state, trusted TLS, managed identities and secrets, monitoring, backup/restore, failure drills, and named operators | production onboarding and the Phase 1 operational-readiness gate |

Developer progress is deliberately allowed to lead infrastructure procurement and hardening. It does not convert a reduced replica count, embedded dependency, local filesystem, local CA, plaintext control endpoint, or bootstrap identity into a production design. Every increment document must list those differences and the promotion actions that remove them. The shared functional verification suite runs in both tracks; production adds security, durability, recovery, capacity, and operational evidence.

Phase 1 deliberately has an asymmetric gate order:

1. Developer gates for Increments 1-6 establish the working stack and unblock the next engineering increment.
2. Increment 7 replaces cross-cutting local identities, certificates, plaintext endpoints, and bootstrap credentials.
3. Production gates for Increments 1-7 are then closed against the hardened integrated stack. Earlier production preparation may proceed in parallel, but a gate that depends on Increment 7 controls remains open until those controls are verified.
4. The Phase 1 operational-readiness gate runs only after every production gate is accepted.

This is not a circular dependency: Increment 7 requires the Increment 1-6 developer contracts and production-capable configurations, not prior formal production acceptance of controls that Increment 7 itself supplies.

---

## 3. Progress Tracking Model

The increment sections below define the implementation sequence and acceptance intent. Day-to-day progress should be tracked at the work-package level, not at the command or checklist-line level. A work package is the smallest unit that can be owned, blocked, reviewed, and accepted with evidence.

Use the following status values consistently:

| Status | Meaning |
|---|---|
| Not started | Work has not begun. Dependencies may still be open. |
| In progress | Implementation work is underway. |
| Blocked | Work cannot continue without a decision, dependency, environment, credential, or external fix. |
| Built | The implementation artifact exists, but verification evidence is not complete. |
| Verified | Required tests and evidence are complete. |
| Accepted | The owning reviewer has accepted the evidence and the next dependent work can proceed. |

Every work package should have:

- a named owner
- a dependency or explicit statement that no dependency remains
- an expected artifact
- verification evidence
- an acceptance reviewer
- current status
- any blocker or material risk

The implementation tracker should use this shape:

| ID | Work package | Owner | Depends on | Exit evidence | Accepted by | Status |
|---|---|---|---|---|---|---|
| P1-1.1 | Example work package | Delivery owner role | Prior accepted gate or external prerequisite | Link to command output, test report, config snapshot, dashboard screenshot, ADR, or runbook section | Acceptance owner role | Not started |

Evidence should be durable enough that another engineer can understand what passed without rerunning the whole platform. Acceptable evidence includes test reports, command transcripts, query output, dashboard exports or screenshots, configuration snapshots, run IDs, ADRs, benchmark results, and signed-off runbook entries.

### Phase 1 Work-Package Tracker

The rows below are portfolio-level parent work packages. Each owning increment document decomposes its parent IDs into shared (`S`), developer (`D`), production (`P`), verification (`V`), recovery (`R`), and gate (`G`) child tasks with deliverable paths and gate mappings. The execution sources of truth are the Implementation Task Track sections in [Increment 1](ceph_storage.md#17-implementation-task-track), [Increment 2](iceberg_polaris_catalog.md#12-implementation-task-track), [Increment 3](spark_compute.md#12-implementation-task-track), [Increment 4](airflow_orchestration.md#15-implementation-task-track), [Increment 5](trino_query.md#13-implementation-task-track), [Increment 6](atlas_ranger_governance.md#15-implementation-task-track), and [Increment 7](freeipa_keycloak_identity.md#16-implementation-task-track). This table remains the portfolio roll-up.

| ID | Work package | Owner | Depends on | Exit evidence | Accepted by | Status |
|---|---|---|---|---|---|---|
| P1-0.1 | Build and artifact delivery baseline | Build/platform engineering owner | None | Approved build pipeline, artifact repository and container registry paths, immutable versioning rules, checksum/digest and provenance output, verifier-image template, protected configuration injection, and evidence export demonstrated with a smoke artifact | Platform owner and security owner | In progress |
| P1-1.1 | Ceph decision due diligence | Platform architect | Architecture storage requirements | Confirmed Ceph baseline, retained comparison record, proof-of-fit targets, production topology, and explicit reconsideration triggers | Architecture owner | Accepted |
| P1-1.2 | Ceph cluster baseline | Storage owner | P1-1.1 | `ceph status`, daemon inventory, pool/CRUSH/failure-domain configuration snapshot, capacity model | Operations owner | In progress |
| P1-1.3 | RGW endpoint and TLS | Storage owner | P1-1.2 | HTTPS endpoint test, certificate chain validation, plaintext rejection evidence | Security owner | In progress |
| P1-1.4 | Buckets and service credentials | Storage owner | P1-1.3 | Bucket listing, service-account policy matrix, positive and negative credential tests | Security owner | In progress |
| P1-1.5 | Storage observability | Operations owner | P1-1.2 | Ceph Dashboard view, RGW metrics, capacity and health alert evidence | Operations owner | In progress |
| P1-1.6 | Storage-only performance and cost evidence | Storage owner | P1-0.1, P1-1.4 | Pinned storage-verifier image digest and provenance plus concurrent synthetic S3 access, multipart, small-object/prefix listing, latency/error, capacity, operator-effort, and exported evidence results | Platform owner | In progress |
| P1-2.1 | Polaris production metadata store | Data platform owner | P1-1 accepted | Metadata-store product/version, owner, backup/restore plan, HA/RTO/RPO posture | Operations owner | Not started |
| P1-2.2 | Polaris service deployment | Data platform owner | P1-2.1 | Polaris endpoint health, service config snapshot, logs showing successful startup | Platform owner | Not started |
| P1-2.3 | Catalog namespaces and storage binding | Data platform owner | P1-2.2, P1-1.4 | Bronze/silver/gold/platform namespaces, Ceph RGW location mapping, credential validation | Platform owner | Not started |
| P1-2.4 | Iceberg verification tables and storage qualification | Data platform owner | P1-2.3 | Bronze/silver/gold test tables and `platform.quality_check_results` created and readable; Iceberg metadata, snapshot, manifest, listing, and S3FileIO evidence attached to storage qualification | Data engineering owner | Not started |
| P1-2.5 | Metadata-driven maintenance verification | Data platform owner | P1-2.4 | Metadata table queries and threshold-based maintenance decisions for files, snapshots, manifests, delete files, and orphan files | Data engineering owner | Not started |
| P1-2.6 | Catalog backup, restore, and audit | Data platform owner | P1-2.4 | Restore drill proving catalog, Iceberg metadata, manifests, and objects return to a consistent point; audit events captured | Operations owner | Not started |
| P1-3.1 | Spark runtime and cluster | Data engineering owner | P1-2 accepted | Spark standalone services running on Podman, image/artifact pin evidence, Spark UI or service health | Platform owner | Not started |
| P1-3.2 | Spark catalog, object-store configuration, and storage qualification | Data engineering owner | P1-3.1, P1-2.3 | Spark resolves Polaris tables and reads/writes Ceph RGW with `svc-spark`; ingestion/write throughput and multipart evidence attached to storage qualification | Data platform owner | Not started |
| P1-3.3 | Bronze ingestion job | Data engineering owner | P1-3.2 | Landing file produces bronze Iceberg table with expected schema and row count | Data owner | Not started |
| P1-3.4 | Silver and gold transformation jobs | Data engineering owner | P1-3.3 | Deduplicated silver output and aggregated gold output validated against expected results | Data owner | Not started |
| P1-3.5 | Quality and promotion gate | Data engineering owner | P1-3.4 | Passing and failing quality cases written to `platform.quality_check_results`; failed promotion blocked | Data owner | Not started |
| P1-3.6 | Spark maintenance and lineage payloads | Data engineering owner | P1-3.5 | Metadata-driven maintenance run evidence and logged lineage payloads for each job | Data platform owner | Not started |
| P1-4.1 | Airflow platform deployment | Operations owner | P1-3 accepted | Airflow services and PostgreSQL metadata database healthy; DAG directory/version recorded | Platform owner | Not started |
| P1-4.2 | Spark submission from Airflow | Operations owner | P1-4.1, P1-3.1 | Airflow task submits a Spark job with approved service credentials | Data engineering owner | Not started |
| P1-4.3 | Batch pipeline DAGs | Data engineering owner | P1-4.2 | Ingestion, bronze-to-silver, and silver-to-gold DAG run evidence with run IDs | Data owner | Not started |
| P1-4.4 | Quality gates in orchestration | Data engineering owner | P1-4.3, P1-3.5 | DAG success path and deliberate quality-failure halt evidence | Data owner | Not started |
| P1-4.5 | Maintenance DAG and alerts | Operations owner | P1-4.3, P1-3.6 | Metadata-threshold maintenance DAG evidence, task failure alert, Deadline Alert evidence | Operations owner | Not started |
| P1-5.1 | Trino cluster deployment | Query platform owner | P1-4 accepted | Trino coordinator/worker health, version pin, configuration snapshot | Platform owner | Not started |
| P1-5.2 | Trino Polaris and Ceph access | Query platform owner | P1-5.1, P1-2.3 | Trino resolves Polaris tables and reads Ceph-backed Iceberg data through approved credentials | Data platform owner | Not started |
| P1-5.3 | Bronze/silver/gold query and storage validation | Query platform owner | P1-5.2, P1-3.4 | Row counts, schemas, joins, and aggregates match Spark-produced outputs; Trino scan throughput evidence attached to storage qualification | Data owner | Not started |
| P1-5.4 | Quality-results query validation | Query platform owner | P1-5.2, P1-3.5 | `platform.quality_check_results` visible through Trino with expected pass/fail rows | Data owner | Not started |
| P1-5.5 | JDBC verification suite | Query platform owner | P1-5.3 | `TrinoQueryVerificationTest` report against the live cluster | Platform owner | Not started |
| P1-6.1 | Atlas deployment, external dependencies, and model setup | Governance owner | P1-5 accepted | Developer Atlas functional evidence plus production HBase, SolrCloud/ZooKeeper, external notification service, Atlas availability, entity type registration, authentication, backup, and restore evidence | Governance owner | Not started |
| P1-6.2 | Ranger deployment and baseline policies | Security owner | P1-5 accepted | Ranger health, usersync source, policy export for bronze/silver/gold zones | Security owner | Not started |
| P1-6.3 | Dataset registration and lineage publication | Governance owner | P1-6.1, P1-3.6 | Atlas entities and lineage for source-to-bronze-to-silver-to-gold runs | Data owner | Not started |
| P1-6.4 | Quality status metadata | Governance owner | P1-6.3, P1-3.5 | Atlas entity quality status reflects latest quality run | Data owner | Not started |
| P1-6.5 | Ranger allow/deny and classification tests | Security owner | P1-6.2, P1-6.3 | Allow, deny, and PII classification enforcement evidence through Trino | Security owner | Not started |
| P1-7.1 | FreeIPA deployment and service identities | Security owner | P1-6 developer gate; production-capable Increment 1-6 configurations available | FreeIPA health, groups, service principals, keytab validation | Security owner | Not started |
| P1-7.2 | Keycloak federation and OIDC clients | Security owner | P1-7.1 | Keycloak realm/client export, token issuance, issuer/claim validation | Security owner | Not started |
| P1-7.3 | Service authentication migration | Security owner | P1-7.2 | Polaris, Airflow, Trino, Ranger, and Atlas use approved FreeIPA/Keycloak paths | Platform owner | Not started |
| P1-7.4 | TLS certificate replacement | Security owner | P1-7.1 | FreeIPA Dogtag-issued certificates deployed and validated for all service endpoints | Security owner | Not started |
| P1-7.5 | Encryption-at-rest and credential rotation | Security owner | P1-7.1, P1-1.4 | Ceph/RGW encryption evidence for gold/platform zones and rotation runbook test | Security owner | Not started |
| P1-7.6 | Integrated security verification | Security owner | P1-7.3, P1-7.4, P1-7.5 | Positive and negative authentication, authorization, TLS, and no-shared-credential evidence | Platform owner | Not started |
| P1-R.1 | Phase 1 operational readiness signoff | Platform owner | P1-1 through P1-7 accepted | Completed `stratus_phase1_operational_readiness.md` checklist, concurrent Spark/Trino/Polaris/storage qualification, restore drill evidence, monitoring evidence, and acceptance record | Platform steering group | Not started |

### P1-0.1 Build and artifact delivery acceptance checklist

`P1-0.1` is one Phase 1 prerequisite work package. It is not an increment and does not introduce a separate child-task namespace. The work package is complete only when all of the following are true:

- [ ] The platform and security owners approve the build service, artifact repository, container registry, publishing identity, read-only verification identity, and durable evidence location.
- [ ] Immutable versioning maps the source revision to artifact coordinates and SHA-256, image tag and digest, SBOM, scan, provenance, and signature or attestation.
- [ ] A reusable verifier-image template accepts a prebuilt artifact, contains only the runtime and artifact, runs as non-root, and contains no source or build toolchain.
- [ ] A clean protected build worker runs canonical tests, packages the artifact, builds and scans the image, publishes it by immutable digest, and exports evidence.
- [ ] The verification workflow requires a digest-qualified image, uses read-only registry access, injects protected configuration, mounts trust material read-only, and exports results without building.
- [ ] An independent verification environment pulls and validates a published smoke verifier, executes it, and records the complete evidence manifest.
- [ ] Platform and security owners accept the evidence and confirm that no build capability or registry write credential exists in the verification environment.

Runtime and verification scripts must not invoke Maven or Gradle, build an image, mount a source tree, copy from a developer workstation `target/` directory, or resolve build dependencies.

Current status: `P1-0.1` is In progress and formal CI/CD is deferred by owner direction on 2026-07-14. Local development may continue only as an explicit development-only sequencing exception: builds run outside verification containers, while immutable publication and all dependent acceptance claims remain blocked. The repository contains a local Java verifier and an image-specific Dockerfile, but no approved build service, pipeline, artifact repository, registry path, workload identities, image digest, SBOM, scan, provenance, attestation, or smoke evidence.

### Phase 1 Gate Tracker

The table below records production-profile acceptance. Its row order mirrors the architecture, not the production signoff sequence. Developer-profile completion is recorded in the owning increment evidence and may support continued engineering, but it cannot set one of these gates to `Accepted`; Increments 1-6 close their final security-dependent checks after Increment 7 promotion.

| Gate | Required evidence | Accepted by | Status |
|---|---|---|---|
| Increment 1 accepted | Ceph decision due diligence, Ceph health, RGW TLS, buckets and credential isolation, core S3 and multipart tests, concurrent synthetic client load, small-object/prefix listing behavior, failure drill, observability, and preliminary performance/capacity/operator evidence | Platform architecture and operations owners | Not started |
| Increment 2 accepted | Polaris production metadata store, namespace/table tests, Iceberg maintenance tests, catalog/object restore drill, audit evidence | Platform architecture and data platform owners | Not started |
| Increment 3 accepted | Spark runtime, ingestion, transformation, materialisation, quality, promotion, maintenance, and lineage-payload evidence | Data engineering owner | Not started |
| Increment 4 accepted | Airflow deployment, DAG runs, quality gate halt, maintenance DAG, retry and alert evidence | Platform operations and data engineering owners | Not started |
| Increment 5 accepted | Trino deployment, Polaris table resolution, query correctness, quality-results access, JDBC test evidence | Query platform owner | Not started |
| Increment 6 accepted | Atlas production dependencies and restore evidence, metadata registration/lineage, Ranger policies, classification enforcement, and allow/deny evidence | Governance and security owners | Not started |
| Increment 7 accepted | FreeIPA, Keycloak, TLS, service authentication, encryption, credential rotation, positive/negative security tests | Security owner | Not started |
| Phase 1 production-readiness accepted | All developer shortcuts replaced; integrated backup/restore, DR, observability, security, performance, support, and operational acceptance evidence | Platform owner, operations owner, security owner, and data owner | Not started |

---

## 4. Increment 1 — Storage Foundation

### What we are building
Ceph Object Gateway (RGW) backed by a Ceph storage cluster — the production on-prem object-storage substrate for all platform data.

### Why this is first
Every other component in the stack writes to or reads from object storage. Nothing else can be verified without it. Ceph RGW must expose a working HTTPS S3-compatible endpoint, backed by healthy Ceph storage, before Iceberg, Polaris, Spark, or anything else is touched.

### What is delivered

- Storage due-diligence record completed before implementation begins, including Ceph RGW vs Apache Ozone requirements fit
- Ceph cluster deployed on-prem with monitor, manager, OSD, and RGW services sized for the target environment
- RGW S3 endpoint exposed as `https://object-store.stratus.local`
- Bucket structure created per the architecture domain and lifecycle zone model:
  - `stratus-landing` — raw source file ingestion zone
  - `stratus-bronze` — raw Iceberg data
  - `stratus-silver` — conformed Iceberg data
  - `stratus-gold` — curated Iceberg data
  - `stratus-platform` — platform-internal tables (quality results, audit, maintenance metadata)
- TLS enabled on the RGW S3 endpoint
- Service identities and scoped S3 credentials created for platform services (Spark, Polaris, Airflow, Trino)
- Ceph Dashboard enabled for operational visibility
- Ceph pool, CRUSH, placement group, and failure-domain assumptions documented for the deployment
- Measured storage-only evidence bundle completed for concurrent synthetic S3 access, multipart behavior, small-object and prefix-listing stress, request latency/error rates, capacity cost, failure behavior, and operator effort

### Verification

| Test | Pass condition |
|---|---|
| Storage decision gate | Ceph RGW is confirmed as the selected baseline against the documented requirements, with the Apache Ozone comparison retained and reconsideration triggers recorded |
| Bucket creation | All five buckets exist and are accessible |
| Write test | A test file can be written to each bucket using approved service credentials |
| Read test | A written file can be read back and content verified |
| TLS | All connections enforce TLS; plaintext connection is rejected |
| Credential isolation | Service identity A cannot read or write to a bucket it has no policy for |
| Core S3 compatibility | The pinned storage verifier passes bucket, put, get, head, list, delete, endpoint override, path-style, and error-handling tests |
| Multipart behavior | Create, upload, complete, abort, list-parts, retry, and abandoned-upload cleanup behavior passes |
| Concurrent storage access | Multiple isolated verifier identities run mixed S3 workloads without stale reads, authorization leakage, unexpected throttling, or threshold breach |
| Small-object and prefix-listing stress | Synthetic objects and prefixes establish object-count, list-latency, bucket-index, retry, and error baselines without requiring Iceberg or later engines |
| Request latency/error budget | Mixed S3 operations record p50/p95/p99 latency, 4xx/5xx rate, timeout rate, and retry rate against declared thresholds |
| Cost/capacity/operator model | Raw-to-usable capacity, growth assumptions, metadata overhead, expansion triggers, and operator effort are recorded and accepted |
| Ceph health | `ceph status` reports a healthy cluster and RGW service health is visible |
| Dashboard | Ceph Dashboard is accessible and shows expected cluster, pool, bucket, and daemon state |

### Demonstrated outcome
Object storage is operational. A file written to `stratus-landing` through Ceph RGW can be read back. Service identities are isolated. Ceph health and storage placement are visible. The storage foundation is ready for Iceberg.

---

## 5. Increment 2 — Tables and Catalog

### What we are building
Apache Iceberg table format and Apache Polaris REST catalog — the semantic layer that turns Ceph-backed object storage into a governed, multi-engine table platform.

### Why this comes second
Without Iceberg and a catalog, the storage layer is just buckets and files. Polaris is the single metadata control point that all compute engines (Spark, Trino, Flink) will use to discover and access tables. It must be stable before any engine is added.

### What is delivered

- Apache Polaris deployed and reachable
- Polaris configured with namespaces matching the bronze / silver / gold / platform zone structure
- Polaris connected to the Ceph RGW S3 endpoint as its underlying storage location
- A bronze Iceberg table created via Polaris to verify the end-to-end table creation path
- A silver Iceberg table and a gold Iceberg table created to verify namespace isolation
- `platform.quality_check_results` Iceberg table created in the platform namespace (schema per architecture §5.3)
- Iceberg table maintenance operations verified via the Iceberg Java API using metadata-table driven thresholds for file count, average file size, snapshot-chain length, manifest count, delete-file count, and orphan-file count
- Catalog production-readiness gates met for external Polaris metadata store, backup/restore, audit logging, HA/failover posture, and credential model

### Verification

| Test | Pass condition |
|---|---|
| Polaris reachable | Polaris REST API responds on configured endpoint |
| Namespace creation | Bronze, silver, gold, and platform namespaces exist in Polaris |
| Table creation | An Iceberg table can be created in each namespace via the Polaris REST API |
| Table resolution | A created table can be resolved by name and its location in Ceph-backed object storage confirmed |
| Data write | A parquet data file can be written to a table location and the Iceberg snapshot updated |
| Data read | Written data can be read back via the Iceberg Java API using the table's current snapshot |
| Schema enforcement | Writing a record that violates the table schema is rejected |
| Snapshot expiry | Expired snapshots are removed; data files for expired snapshots are eligible for cleanup |
| Compaction | Small files in a table can be rewritten into fewer larger files |
| Orphan cleanup | Orphaned files not referenced by any snapshot are identified and removed |
| `check_results` table | `platform.quality_check_results` exists with the correct schema and accepts a written record |

### Demonstrated outcome
Iceberg tables exist in Ceph-backed object storage, managed by Polaris. A parquet file written via the Iceberg API can be read back via the same API. The platform has a real table layer, not just storage.

---

## 6. Increment 3 — Batch Compute

### What we are building
Apache Spark — the primary batch ETL and transformation engine.

### Why this comes third
Spark is the engine that populates and transforms Iceberg tables. It depends on both the Ceph RGW object-storage endpoint and Polaris being operational. Spark jobs are meaningless without a stable table layer to write to.

### What is delivered

- Apache Spark standalone cluster deployed on Podman containers
- Spark configured to use Apache Polaris as its Iceberg catalog
- Spark configured to read and write to Ceph RGW using approved `svc-spark` S3 credentials
- An ingestion job: reads a CSV or JSON file from `stratus-landing`, writes it as an Iceberg table in `stratus-bronze`
- A transformation job: reads from bronze, applies type normalisation and deduplication, writes to `stratus-silver`
- A materialisation job: reads from silver, aggregates, writes a summary table to `stratus-gold`
- A metadata-driven maintenance job: inspects Iceberg metadata tables such as `files`, `snapshots`, `manifests`, and `history`; applies per-table policy; and runs snapshot expiry, rewrite/compaction, delete-file cleanup, or orphan cleanup only when thresholds are breached
- A data quality job: runs schema conformance, completeness, and uniqueness checks on a dataset; writes results to `platform.quality_check_results`
- A promotion gate: reads quality outcomes from `platform.quality_check_results` for a dataset run; blocks promotion if any blocking check failed

### Verification

| Test | Pass condition |
|---|---|
| Spark connects to Polaris | Spark session resolves tables via the Polaris REST catalog |
| Spark connects to object storage | Spark can read and write parquet files through Ceph RGW |
| Ingestion job | Source file in landing zone produces a bronze Iceberg table with correct row count and schema |
| Transform job | Bronze table produces a silver table with deduplication applied; row counts match expectations |
| Materialisation job | Silver table produces a gold summary table with correct aggregates |
| Quality job — pass | A clean dataset produces PASS outcomes in `platform.quality_check_results` |
| Quality job — fail | A dataset with nulls in a mandatory column produces a FAIL outcome with correct detail |
| Promotion gate — pass | Dataset with all PASS outcomes is promoted |
| Promotion gate — block | Dataset with a FAIL blocking outcome is not promoted; failure reason is recorded |
| Maintenance job | Compaction, snapshot expiry, delete-file cleanup, and orphan cleanup decisions are driven by Iceberg metadata-table signals and per-table thresholds |
| Atlas lineage payload | Each job produces a lineage event payload (logged; not yet sent to Atlas) |

### Demonstrated outcome
Data flows from a raw source file through bronze, silver, and gold Iceberg tables via Spark. Quality checks run and gate promotion. The batch data pipeline works end to end.

---

## 7. Increment 4 — Orchestration

### What we are building
Apache Airflow — the scheduler and control-plane for batch workflows.

### Why this comes fourth
Spark jobs exist but nothing runs them on a schedule or manages dependencies between them. Airflow provides the control plane that sequences ingestion, transformation, quality checks, promotion, and maintenance into reliable, observable workflows.

### What is delivered

- Apache Airflow deployed with PostgreSQL metadata database
- Airflow configured with service account credentials to submit Spark jobs
- DAGs created for:
  - **Ingestion DAG** — detect new files in landing zone → run ingestion job → run quality checks → promote to bronze
  - **Bronze-to-silver DAG** — run transform job → run quality checks → run promotion gate → promote to silver
  - **Silver-to-gold DAG** — run materialisation job → run quality checks → promote to gold
  - **Maintenance DAG** — scheduled trigger that queries Iceberg metadata tables, evaluates per-table thresholds, emits alerts for breached thresholds, and then runs snapshot expiry, compaction, delete-file cleanup, or orphan cleanup as policy requires
- Each DAG emits structured success/failure events
- Airflow alerts configured for job failure and Deadline Alert breach

### Verification

| Test | Pass condition |
|---|---|
| Airflow reachable | Airflow web UI accessible; DAGs listed |
| Ingestion DAG | Triggered by new file in landing zone; completes with bronze table updated |
| Bronze-to-silver DAG | Runs on schedule; silver table updated; quality gate enforced |
| Silver-to-gold DAG | Runs on schedule; gold table updated |
| Promotion gate in DAG | A deliberately failed quality check halts the DAG at the gate task; downstream tasks do not run |
| Maintenance DAG | Snapshot count reduced on active tables after run |
| Retry behaviour | A transiently failed task retries and succeeds on second attempt |
| Alert | A permanently failed task triggers an alert |
| Deadline Alert | A DAG that exceeds its defined timing expectation triggers a Deadline Alert |

### Demonstrated outcome
The batch pipeline runs on a schedule without manual intervention. Quality gates are enforced automatically. A failed job alerts rather than silently producing bad data.

---

## 8. Increment 5 — Interactive Query

### What we are building
Trino — the shared interactive SQL query plane over governed Iceberg datasets.

### Why this comes fifth
Data now exists in curated Iceberg tables, orchestrated by Airflow. Before governance is added, the platform needs a verified query layer so analysts can access curated data. Trino is the default open SQL interface over Polaris-managed Iceberg tables.

### What is delivered

- Trino cluster deployed and configured with the Iceberg connector pointing at Apache Polaris
- Trino configured to read from Ceph RGW using approved service credentials
- Bronze, silver, and gold datasets queryable via Trino SQL
- `platform.quality_check_results` queryable via Trino
- Trino query latency, row counts, schema visibility, and aggregates verified against Spark-produced outputs
- Java JDBC verification suite created for Trino query behavior

### Verification

| Test | Pass condition |
|---|---|
| Trino connects to Polaris | Trino resolves Iceberg tables via the Polaris REST catalog |
| Namespace discovery | Bronze, silver, gold, and platform schemas are visible through the `stratus` catalog |
| Bronze query | `SELECT count(*) FROM stratus.bronze.<table>` returns the Spark-produced bronze row count |
| Silver query | Silver table queryable; deduplicated row count matches Spark output |
| Gold query | Gold summary table queryable; aggregates match Spark output |
| Quality results query | `SELECT * FROM stratus.platform.quality_check_results WHERE status = 'failed'` returns correct rows |
| Schema enforcement | Query against a column that does not exist fails with a clear error |
| Cross-namespace query | A query joining bronze and silver tables returns correct results |
| JDBC verification | `TrinoQueryVerificationTest` passes against the live Trino cluster |

### Demonstrated outcome
An analyst can run SQL against curated Iceberg datasets via Trino without touching Spark or any file system. The query layer is operational and returns correct results.

---

## 9. Increment 6 — Metadata and Governance

### What we are building
Apache Atlas and Apache Ranger — the metadata, lineage, classification, and access control plane.

### Why this comes sixth
The data is flowing and queryable. Now it needs to be governed. Atlas provides the metadata and lineage record. Ranger enforces access policy. Both depend on the rest of the platform being stable — you cannot govern what does not yet exist.

### What is delivered

- Developer Atlas deployed with disposable embedded dependencies for fast functional work
- Production Atlas deployed with external HBase, SolrCloud/ZooKeeper, external Kafka notifications, availability, and restore evidence
- Atlas configured with lab-local authentication only in the developer profile; FreeIPA LDAP migration follows in Increment 7 and is required for production acceptance
- Apache Ranger developer profile uses a local user store; production acceptance requires FreeIPA LDAP usersync, durable policy state, and durable audit storage
- Atlas entity types registered for Iceberg datasets, namespaces, and pipeline runs
- Spark jobs updated to publish metadata and lineage payloads to Atlas on completion:
  - dataset registration on first write
  - lineage: source → bronze, bronze → silver, silver → gold
  - quality status update after each quality job run
- Ranger policies created for bronze, silver, and gold zones:
  - platform engineers: read/write all zones
  - domain analysts: read silver and gold for their domain only
  - implemented service accounts, such as Spark and Airflow, receive access appropriate to their assigned zones
- Atlas classifications applied to sensitive test datasets: `PII`, `CONFIDENTIAL`
- Ranger tag-based policies verified: a user without PII access is denied a query on a PII-classified table

### Verification

| Test | Pass condition |
|---|---|
| Atlas reachable | Atlas UI accessible; entity search returns results |
| Dataset registration | A bronze table created by Spark appears in Atlas with correct owner, sourceSystem, zone, and classification |
| Lineage — ingest | Atlas shows lineage from external source to bronze table |
| Lineage — transform | Atlas shows lineage from bronze to silver |
| Lineage — materialise | Atlas shows lineage from silver to gold |
| Quality status | Atlas entity for a dataset reflects `qualityStatus = passed` after a passing quality run |
| Ranger policy — allow | A user in `analysts-<domain>` can query silver and gold tables for their domain via Trino |
| Ranger policy — deny | A user in `analysts-<domain>` cannot query bronze tables |
| Classification policy | A user without PII access is denied a Trino query on a PII-classified table |
| Lineage completeness | Every Spark job run produces a lineage event in Atlas; no job completes without publishing |

### Demonstrated outcome
Every dataset in the platform has an Atlas entry with ownership, lineage, and quality status. Access to sensitive datasets is enforced by Ranger classification policy, not by process convention alone.

---

## 10. Increment 7 — Identity and Security Hardening

### What we are building
FreeIPA and Keycloak — the Linux-native identity foundation — and full TLS hardening across all platform services.

### Why this comes last
Identity integration touches every component. Hardening it last means each component is already working correctly before authentication and authorisation complexity is layered on top. Debugging a broken Spark job while also debugging Kerberos is a reliable way to make both harder.

### What is delivered

- FreeIPA deployed: Kerberos KDC, LDAP directory, Dogtag PKI, DNS
- Platform service accounts registered in FreeIPA: `svc-spark`, `svc-airflow`, `svc-polaris`, `svc-trino`, `svc-atlas`, `svc-ranger`
- `svc-flink` reserved in FreeIPA if Flink is still deferred
- FreeIPA groups created per the architecture group model: `platform-admins`, `platform-engineers`, `data-stewards-<domain>`, `analysts-<domain>`, `consumers-gold`
- MIT Kerberos clients installed and configured on all compute nodes
- SSSD configured on all Linux hosts for FreeIPA integration
- Keycloak deployed and configured as OIDC broker backed by FreeIPA
- Polaris configured to authenticate via Keycloak OIDC tokens
- Airflow web UI configured to authenticate via Keycloak
- Ranger usersync pointed at FreeIPA LDAP; groups imported; policies migrated to group-based
- Atlas configured to use FreeIPA LDAP for user authentication
- Spark job submission uses the approved service identity and keytab pattern where Kerberos is part of the selected runtime integration
- Flink service identity is prepared but not a completion dependency unless Flink has already been implemented
- Trino configured with HTTPS and Keycloak/OIDC for client access; internal trust uses the selected Trino release's supported secure-communication model
- TLS certificates replaced with FreeIPA Dogtag-issued certificates across all services
- Ceph/RGW encryption-at-rest model enabled for `stratus-gold` and `stratus-platform` according to the approved Ceph security design

### Verification

| Test | Pass condition |
|---|---|
| FreeIPA Kerberos | A service account can obtain a Kerberos ticket via keytab |
| FreeIPA LDAP | Ranger usersync imports all groups and users from FreeIPA |
| Keycloak OIDC | A Keycloak token can be obtained for a service principal and used to authenticate to Polaris |
| Polaris auth | A request to Polaris without a valid OIDC token is rejected with 401 |
| Airflow UI auth | Airflow web UI redirects unauthenticated users to Keycloak login |
| Spark service identity | A Spark job submitted with the approved service identity runs successfully; unauthenticated submission is rejected where Kerberos enforcement is enabled |
| Ranger group policy | A user added to `analysts-<domain>` in FreeIPA gains silver/gold read access within minutes of group sync |
| TLS everywhere | All inter-service connections use TLS with FreeIPA-issued certificates; connections without TLS are rejected |
| Encryption at rest | A file written to `stratus-gold` is protected by the approved Ceph/RGW encryption-at-rest model |
| No shared credentials | No service uses a shared password; every service authenticates via keytab or OIDC token |

### Demonstrated outcome
The platform is fully secured. Every service authenticates via FreeIPA Kerberos or Keycloak OIDC. Access policy is enforced by Ranger against FreeIPA groups. No service uses shared credentials. Sensitive data is encrypted at rest and in transit.

---

## 11. Increment Summary

| Increment | Builds | Demonstrated outcome |
|---|---|---|
| **1 — Storage** | Ceph RGW | Files written and read through S3; buckets isolated; TLS enforced; Ceph health visible |
| **2 — Tables and Catalog** | Iceberg + Polaris | Iceberg tables created, written, and read via Polaris; maintenance operations work |
| **3 — Batch Compute** | Spark | Data flows from landing to bronze to silver to gold; quality gates enforced |
| **4 — Orchestration** | Airflow | Batch pipeline runs on schedule; failures alert; quality gates halt bad promotions |
| **5 — Interactive Query** | Trino | Analysts query curated Iceberg data via SQL without touching infrastructure |
| **6 — Governance** | Atlas + Ranger | Lineage recorded; classifications enforced; access policy controls data zone access |
| **7 — Identity** | FreeIPA + Keycloak | All services secured; Kerberos and OIDC authentication; no shared credentials |
| **Readiness gate** | Operational acceptance | Backup/restore, observability, security, DR, support, and production-readiness evidence accepted |

---

## 12. What This Plan Does Not Cover

The following are explicitly deferred and belong to later increments:

- **Streaming and CDC** — the shared Kafka event backbone, Kafka Connect, Debezium, and Flink are not Phase 1 capabilities. An external Kafka service used solely as Atlas's supported production notification dependency remains within Increment 6 and does not constitute completion of the Phase 2 event backbone.
- **Firebolt Core** — optional serving acceleration. Not considered until the Iceberg and governance foundations are verified in production.
- **Multi-environment promotion** — development, staging, and production environment separation is an operational maturity concern for after the single-environment foundation works.
- **Self-service data discovery** — policy-driven glossary workflows and self-service onboarding follow after Atlas and Ranger are operating reliably.

Operational production-readiness signoff is covered by [stratus_phase1_operational_readiness.md](../operations/stratus_phase1_operational_readiness.md). It is not a separate platform increment because it validates Increments 1 through 7 rather than adding a new capability.

---

## 13. Design Documents

- [stratus_on_prem_data_fabric_architecture.md](../architecture/stratus_on_prem_data_fabric_architecture.md) — full architecture specification and component decisions
- [ceph_storage.md](ceph_storage.md) — Increment 1 Ceph object storage foundation implementation plan
- [iceberg_polaris_catalog.md](iceberg_polaris_catalog.md) — Increment 2 table and catalog implementation plan
- [spark_compute.md](spark_compute.md) — Increment 3 batch compute implementation plan
- [airflow_orchestration.md](airflow_orchestration.md) — Increment 4 orchestration implementation plan
- [trino_query.md](trino_query.md) — Increment 5 interactive query implementation plan
- [atlas_ranger_governance.md](atlas_ranger_governance.md) — Increment 6 metadata and governance implementation plan
- [freeipa_keycloak_identity.md](freeipa_keycloak_identity.md) — Increment 7 identity and security hardening implementation plan
- [stratus_phase1_operational_readiness.md](../operations/stratus_phase1_operational_readiness.md) — Phase 1 operational acceptance and production readiness checklist
- [stratus_implementation_plan_phase2.md](stratus_implementation_plan_phase2.md) — Phase 2 streaming and operational maturity implementation plan
- [stratus_implementation_plan_phase3.md](stratus_implementation_plan_phase3.md) — Phase 3 query acceleration and data products implementation plan
