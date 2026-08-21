
<img src="docs/images/stratus-logo.png" alt="Stratus logo: an iceberg beneath a waterline, capped by a cloud and mountain peak" width="220">

# Stratus

An on-prem data fabric platform built on open standards, governed data lifecycle, and separated compute concerns.

The foundational decision is that **Apache Iceberg is the mandatory table abstraction**. Every analytical dataset — bronze, silver, and gold — is an Iceberg table. Without that constraint the platform degenerates into a file swamp.

## Architecture Overview

```text
                    ┌───────────────────────────────────────────────┐
                    │                 Users / Apps                  │
                    │ BI / SQL / APIs / ML / Data Science / AI      │
                    └───────────────────────────────────────────────┘
                                          │
                         ┌────────────────┴────────────────┐
                         │                |                │
                         ▼                ▼                ▼
          ┌─────────────────────┐  ┌──────────────┐  ┌──────────────────────┐
          │   Firebolt Core     │  │    Trino     │  │ Spark SQL / Notebook │
          │ low-latency serving │  │ shared query │  │ engineering access   │
          └─────────────────────┘  └──────────────┘  └──────────────────────┘
                         │                 │                 │
                         └─────────────────┴─────────────────┘
                                          │
                                          ▼
                              ┌─────────────────────────┐
                              │   Apache Iceberg Tables │
                              │ bronze / silver / gold  │
                              └─────────────────────────┘
                                          │
                         ┌────────────────┼────────────────┐
                         ▼                ▼                ▼
             ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
             │ Apache Spark     │  │ Apache Flink     │  │ Table Maintenance│
             │ batch ETL / ELT  │  │ streaming / CDC  │  │ compaction etc.  │
             └──────────────────┘  └──────────────────┘  └──────────────────┘
                                          │
                                          ▼
                          ┌───────────────────────────────┐
                          │       Ceph RGW Object Storage │
                          │  raw files + Iceberg data /   │
                          │  metadata files + manifests   │
                          └───────────────────────────────┘

  ┌─────────────────────────────────┐      ┌──────────────────────────────────────┐
  │      Apache Polaris             │      │   Kafka / Kafka Connect / Debezium   │
  │      REST Catalog               │      │ (streaming capability — CDC/events)  │
  │  metadata control plane         │      │                                      │
  │  consulted by all engines       │      │                                      │
  └─────────────────────────────────┘      └──────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────────────────────┐
  │ Governance / Control Plane                                                   │
  │ Apache Atlas — metadata, lineage, glossary, classification, ownership        │
  │ Apache Ranger — policy enforcement, classification-driven access control     │
  │ Airflow — orchestration, scheduling, promotion gates, maintenance            │
  │ FreeIPA — Kerberos, LDAP, PKI          Keycloak — OIDC for REST services     │
  └──────────────────────────────────────────────────────────────────────────────┘
```

## Core Components

| Component | Role |
|---|---|
| **Ceph RGW** | S3-compatible durable object storage for raw files, Iceberg data and metadata |
| **Apache Iceberg** | Open table format — schema/partition evolution, snapshots, time travel, multi-engine access |
| **Apache Polaris** | Central REST catalog — multi-engine metadata control point for Spark, Flink, Trino |
| **Apache Spark** | Batch ETL/ELT, backfills, historical reprocessing, quality checks, silver/gold materialisation |
| **Apache Flink** | CDC ingestion, event streams, continuous enrichment, near-real-time Iceberg writes |
| **Trino** | Default shared interactive SQL query plane over governed Iceberg datasets |
| **Apache Kafka** | Durable event backbone for CDC and streaming |
| **Kafka Connect** | Connector framework for source system integration |
| **Debezium** | CDC connector — captures database change events into Kafka |
| **Apache Atlas** | Technical metadata catalog, business glossary, lineage, classification, ownership |
| **Apache Ranger** | Policy enforcement — classification-driven access control across all engines |
| **Apache Airflow** | Bounded workflow orchestration, Spark scheduling, promotion gates, table maintenance |
| **FreeIPA** | Linux-native identity provider — Kerberos KDC, LDAP directory, PKI |
| **Keycloak** | OIDC broker for REST-facing services (Polaris, Airflow UI) |
| **OpenBao** | Platform secret store — pull-based service-credential distribution |
| **Prometheus + Grafana** | Metrics collection, dashboards, and alerting |
| **Grafana Loki** | Log aggregation |
| **Firebolt Core** | Optional low-latency SQL serving over curated Iceberg datasets |

**Apache Pulsar** was evaluated as the event backbone and is documented as a qualified alternative rather than adopted. It would gain independent broker/storage scaling, tiered-storage offload onto the existing Ceph RGW cluster, and native multi-tenancy; it would cost a three-part runtime, a second CDC runtime for Oracle and SQL Server, and — because Apache Atlas supports Kafka only for entity change notification — a permanent second messaging system rather than a replacement. The evaluation and its reconsideration triggers are recorded in the architecture document.

## Repository Organization

The monorepo is organized by stable capability:

| Directory | Purpose |
|---|---|
| `applications/` | Stratus-owned long-running services |
| `jobs/` | Spark and Flink workloads |
| `verification/` | executable platform conformance suites |
| `platform/` | open-source product integration and deployment assets |
| `environments/` | environment inventory and overlays without secrets |
| `operations/` | monitoring, alerting, backup/restore, security, drills, and runbooks |
| `testing/` | cross-component end-to-end and non-functional suites |
| `schemas/` | shared governed event and data contracts |
| `build-support/` | centralized dependency and Maven build policy |
| `docs/` | architecture, decisions, implementation, operations, and reference documentation |
| `scripts/` | repository maintenance tooling (license and copyright headers) |
| `evidence/` | verification and operational evidence anchor; generated evidence is not committed |
| `logs/` | git-ignored local Maven build logs, created per workstation |

The authoritative layout table — including the placement rules for new artifacts and the guardrail test that enforces the directory set — is `docs/reference/repository-layout.md`.

Maven conformance modules live under `verification/`. Spark's executable tests
are under `platform/spark/tests/`, with packaged workloads under `jobs/spark/`.
Each verification directory corresponds to a stable platform capability.

Dependency versions are owned by `build-support/stratus-bom`. Build-plugin versions are owned by `build-support/stratus-build-parent`. Child module POMs do not pin dependency or plugin versions.

## Data Lifecycle

| Zone | Purpose | Typical Producers |
|---|---|---|
| **Bronze** | Raw / lightly normalised, append-biased, source-fidelity data | Batch file landing, CDC feeds, Flink ingestion |
| **Silver** | Conformed, deduplicated, typed, reference-enriched enterprise data | Spark transforms, Flink enrichment |
| **Gold** | Consumption-ready marts, KPIs, aggregates, semantic views | Spark/SQL materialisation |

All three zones are implemented as **Iceberg tables**, not folder conventions.

## Governance, Accountability, and Traceability

Governance in Stratus is an operating contract, not just a catalog screen. Every
governed dataset must be attributable to named accountable identities, every
material processing step must emit durable evidence, and every control must
identify both an accountable owner and an independent evidence approver.

The terms below follow the common distinction that lineage explains where data
came from and how it changed, while traceability adds the evidence needed to
verify integrity, access, responsibility, and control across the lifecycle.
External overviews include [SAP's data-governance
guide](https://www.sap.com/hk/resources/what-is-data-governance), [Data Dynamics'
traceability glossary](https://www.datadynamicsinc.com/glossary/data-traceability/),
and [Atlan's lineage and traceability
comparison](https://atlan.com/data-lineage-vs-data-traceability/). The normative
Stratus requirements remain the repository architecture, capability
specifications, and operational controls.

### 1. Accountability & Ownership

Every Iceberg table, source, pipeline, quality rule, policy, and data product must
have a named owner. “Data team” or an unowned service account is not sufficient.
Atlas is the target system of record for dataset `owner`, `steward`, `domain`,
`source`, `zone`, classification, quality status, and latest snapshot identity.
Control records connect changes to an accountable owner, approver, evidence
location, affected datasets, and applicable policy.

The names themselves are environment-specific and therefore are not hard-coded
in this README. In a deployed environment, each owner and steward field must
resolve to an active person or managed group with contact, escalation, delegate,
and review-date information; stale or unresolvable ownership fails governance
reconciliation.

The minimum lifecycle assignment is:

| Lifecycle stage | Named accountability | Required responsibility and evidence |
|---|---|---|
| Source onboarding and landing | Source-system owner and data steward | Approve purpose, schema contract, sensitivity, retention, expected volume, and landing access; record the source identity and approval. |
| Bronze ingestion | Ingestion/pipeline owner and source-domain steward | Preserve source fidelity, identify the producer and run, quarantine invalid input, and reconcile landed versus written records. |
| Silver conformance | Transformation owner and domain data steward | Own mapping, deduplication, reference data, schema changes, quality rules, and failed-record disposition. |
| Gold products and metrics | Data-product owner and metric/business steward | Approve definitions, fitness for use, consumer expectations, freshness, and material changes to published metrics. |
| Access and query | Security policy owner and dataset steward | Approve least-privilege policy, classifications, exceptions, periodic access review, and allow/deny evidence. |
| Movement, export, or sharing | Pipeline/service owner and receiving steward | Record source, destination, purpose, run identity, transformation, receiving owner, and transfer result. |
| Retention, legal hold, and deletion | Data owner, compliance approver, and storage custodian | Approve retention and hold rules; prove snapshot expiry, object deletion, exceptions, and final disposition without silently removing audit evidence. |
| Platform operation and recovery | Platform service owner and recovery control approver | Own availability, backup/restore, monitoring, incident response, recovery evidence, and unresolved risk. |

One writer owns each table at a time. Ownership changes, schema changes, policy
changes, quality overrides, and retention exceptions require an identified
approver and a durable record. The full metadata and enforcement contract is in
[the Atlas and Ranger governance
specification](docs/implementation/atlas_ranger_governance.md).

### 2. Compliance & Auditing

Stratus is designed to produce evidence that an organization can use in a legal,
regulatory, or internal-control assessment. It does **not** make a deployment
GDPR-, HIPAA-, or otherwise compliant by itself. Applicability, lawful purpose,
retention, consent, data-subject handling, access-review frequency, and evidence
retention must be configured and approved by the responsible organization.

The evidence chain is designed to answer who did what, to which data, when, why,
through which approved process, and with what result:

- **Ranger** records query allow/deny decisions with user, resource, access type,
  timestamp, result, and policy identity.
- **Polaris and Ceph RGW** provide catalog authorization and object-access
  evidence at the storage and metadata boundaries.
- **Airflow** records workflow, task, retry, approval, failure, and promotion-gate
  outcomes under stable run identities.
- **Spark and Flink** emit processing and lineage payloads linking inputs,
  outputs, jobs, code/artifact versions, and run identities.
- **Iceberg** snapshots preserve table-state history, schema evolution, and the
  metadata required to identify which files composed a table state.
- **Atlas** records ownership, classification, glossary, entity, and lineage
  state; governance reconciliation detects missing or stale metadata.
- **Git, change records, and evidence bundles** connect configuration and code
  changes to reviewers, approved artifacts, control results, exceptions, and
  audit evidence.

Audit records are protected operational data: they require access control,
retention, backup, time synchronization, integrity monitoring, and tested
retrieval. General traceability guidance similarly treats the lifecycle record as
an audit trail for governance and compliance ([Monte Carlo](https://montecarlo.ai/blog-data-traceability-101));
the GDPR accountability principle is defined in Article 5(2) of the [official EU
regulation](https://eur-lex.europa.eu/eli/reg/2016/679/oj).

### 3. Data Integrity & Quality

Traceability must show whether data remained accurate and reliable, not merely
that a job ran. Stratus therefore links every quality result to the dataset,
snapshot, rule version, pipeline run, observed value, threshold, severity, and
owner responsible for disposition.

- Spark and Flink validate schema, completeness, uniqueness, freshness,
  referential integrity, reconciliation totals, and business rules.
- Results are appended to `platform.quality_check_results`; clean, blocking,
  warning, and missing-result cases are distinguishable.
- Blocking failures stop promotion. No result is never interpreted as a pass.
- Iceberg snapshots and time travel support before/after comparison and
  investigation without relying on mutable folder state.
- Atlas carries the current quality status and latest quality-run identity so
  discovery does not hide known defects.
- Overrides require a named steward, reason, scope, expiry, affected snapshot,
  and approval record.

This implements the principle that lineage tracks movement while traceability
validates integrity at each step, described in [Atlan's
comparison](https://atlan.com/data-lineage-vs-data-traceability/) and the [DEV
Community overview supplied for this
review](https://dev.to/buzzgk/data-traceability-key-concepts-and-best-practices-15f5).

### 4. Lineage Mapping

The required lineage graph follows data from source to consumption:

```text
source system / file / CDC record
  -> landing object or Kafka topic
  -> bronze Iceberg table and snapshot
  -> silver transformation and snapshot
  -> gold product, metric, or semantic view
  -> Trino/serving query, report, API, or downstream product
```

Every process edge records the input and output dataset identities, pipeline/job
identity, immutable code or artifact version, `run_id`, event time, processing
time, and outcome. Dataset and process lineage is mandatory; column-level lineage
is added where the transformation contract can produce it reliably. Spark and
Flink emit the same lineage contract, while Atlas publication and reconciliation
keep batch, streaming, and CDC metadata consistent.

Lineage must survive retries, backfills, compaction, snapshot expiry, replay, and
recovery. Maintenance may replace physical files without pretending that the
logical dataset history or accountable processing chain disappeared. This use of
lineage to expose origin, movement, and transformation is consistent with the
[Vanta governance overview](https://www.vanta.com/collection/grc/data-governance),
[Data Dynamics traceability
definition](https://www.datadynamicsinc.com/glossary/data-traceability/), and
[Hyperbots traceability glossary](https://www.hyperbots.com/glossary/data-traceability).

## Data Quality

The quality subsystem is built entirely from platform components — no additional framework.

- **Spark** executes quality checks as bounded jobs (schema, completeness, uniqueness, freshness, referential integrity, business rules)
- **Iceberg** stores quality results in `platform.quality_check_results` — append-only durable evidence, partitioned by zone and check date and retained under approved policy
- **Airflow** enforces promotion gates — blocking check failures halt the pipeline
- **Atlas** carries current quality status as a metadata attribute on every dataset
- **Trino** provides ad-hoc query access to quality results

Overrides are explicit, require a named data steward, and are written as auditable records.

## Architecture Principles

1. **Open formats over proprietary lock-in** — Iceberg tables on object storage, not vendor-specific formats.
2. **Storage and table semantics are separated** — Ceph RGW stores files through the S3 API; Iceberg provides the semantic contract.
3. **Streaming and batch are separate compute concerns** — Flink for unbounded streams, Spark for bounded workloads. Engine choice does not decide how data lands: append-only, copy-on-write upsert, merge-on-read upsert, and buffer-then-merge are per-table decisions, defined in the architecture document.
4. **Governance is first-class** — Metadata, lineage, ownership, and classification are built in from day one.
5. **Orchestration is not streaming** — Airflow orchestrates finite workflows; Flink runs continuous pipelines.
6. **Query serving is layered** — Trino is the default open SQL plane; Firebolt is optional acceleration northbound of Iceberg.

## Control Planes

The design explicitly separates three planes:

- **Data Plane** — Ceph RGW, Iceberg tables, Apache Polaris, Spark, Flink, Kafka + Kafka Connect + Debezium, Trino, Firebolt
- **Metadata & Governance Plane** — Atlas, Ranger, glossary, lineage, classification, stewardship
- **Orchestration & Operations Plane** — Airflow, retries, alerts, maintenance scheduling, promotion gates

## Identity and Security

Production runtimes are Linux-only. Windows developer workstations are supported
through Git Bash and a Linux container engine, but no platform service depends on
a Windows runtime.

The production target is:

- **FreeIPA** — Kerberos authentication, LDAP directory, Dogtag PKI; single identity source of truth
- **Keycloak** — OIDC broker backed by FreeIPA for REST-facing services
- **Ranger** — enforces data access policy backed by FreeIPA groups; tag-based policies driven by Atlas classifications
- **Polaris** — enforces catalog-level access at namespace and table level
- All inter-service communication is TLS; certificates issued by FreeIPA Dogtag PKI

## Key Risks

- **File swamp** — mitigated by mandating Iceberg for all governed datasets.
- **Multi-engine write contention** — mitigated by one-writer-per-table ownership rules and explicit compaction assignment.
- **Governance shelfware** — mitigated by making metadata and lineage publication mandatory in every pipeline.
- **Orchestration sprawl** — mitigated by keeping Airflow focused on finite workflows only.
- **Firebolt too early** — mitigated by keeping it optional until the Iceberg and governance foundations are stable.
- **Atlas operational underestimation** — mitigated by limiting embedded dependencies to the developer profile and explicitly tasking production HBase, SolrCloud/ZooKeeper, notification Kafka, backup/restore, capacity, and failure recovery.

## Running the Platform

The developer harnesses are Compose-based and driven by bash scripts (run them from Git Bash on Windows). The full sequence — prerequisites, start order, every test layer, destructive operations, shutdown, where evidence is written, and troubleshooting — is the harness operations runbook in `docs/operations/`.

The short version:

```bash
# offline regression — the mandatory gate for any change
./mvnw clean verify

# bring up storage, secrets, and catalog (order matters)
bash platform/ceph/compose-cluster/scripts/lifecycle/ceph-compose-startup.sh
bash platform/openbao/compose-service/scripts/lifecycle/openbao-compose-startup.sh
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-bootstrap-buckets.sh
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-provision-service-identities.sh
bash platform/polaris/compose-service/scripts/lifecycle/polaris-compose-startup.sh
bash platform/polaris/compose-service/scripts/verify/polaris-compose-bootstrap-catalog.sh

# batch compute, optional — a consumer of the three above
./mvnw -pl :stratus-spark-jobs -am package -DskipTests
bash platform/spark/compose-cluster/scripts/lifecycle/spark-compose-startup.sh
bash platform/spark/compose-cluster/scripts/verify/spark-compose-bootstrap-principal.sh

# live conformance suites
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-run-live-tests.sh
bash platform/polaris/compose-service/scripts/verify/polaris-compose-run-catalog-tests.sh
bash platform/openbao/compose-service/scripts/verify/openbao-compose-run-secrets-tests.sh
bash platform/spark/compose-cluster/scripts/tests/spark-compose-run-live-tests.sh
```

Live suites are excluded from the default build and select by JUnit tag. Mocks, fakes, and simulated product endpoints are prohibited: product behaviour is proven against the running product.

## Documentation

All documentation lives under `docs/`, organized by kind:

| Directory | Contents |
|---|---|
| `docs/architecture/` | The system architecture and enduring design constraints — the full specification, the component selection decisions, and the write-mode model |
| `docs/decisions/` | Architecture decision records: storage baseline, harness scripting, harness networking, secret distribution, and event backbone selection |
| `docs/implementation/` | Per-capability implementation, configuration, verification, and operating specifications |
| `docs/operations/` | Operational runbooks, harness operation, monitoring, recovery, and production-readiness controls |
| `docs/reference/` | Stable references: code style and engineering rules, Maven test and build commands, repository layout |
| `docs/images/` | Image assets referenced by the documents above; no prose lives here |

Start with the architecture for the system design, then use the specification for the capability you are working on. Implementation documents are named for their capability — `ceph_storage`, `iceberg_polaris_catalog`, `spark_compute`, `airflow_orchestration`, `trino_query`, `atlas_ranger_governance`, `freeipa_keycloak_identity`, `kafka_event_backbone`, `kafka_connect_debezium_cdc`, `flink_streaming_compute`, `flink_streaming_iceberg`, `atlas_streaming_lineage`, and `streaming_production_readiness`.

### Naming Conventions

Capability documents are named for the capability they describe, for example `ceph_storage.md`. Runtime artifacts, Java packages, Maven modules, images, and deployment paths must use stable capability names as well.
