# On-Prem Data Fabric Architecture

## 1. Executive Summary

This document defines a pragmatic on-prem data fabric architecture built around **MinIO (S3-compatible object storage)**, **Apache Iceberg**, **Apache Spark**, **Apache Flink**, a **central REST-oriented Iceberg catalog**, **Apache Atlas**, **Apache Ranger**, **Apache Airflow**, **Trino**, and an optional **Kafka-backed event backbone** plus optional **Firebolt Core** serving layer.

The design goal is not to assemble a random list of fashionable tools. The goal is to create a governed, scalable, batch-and-streaming-capable platform that supports:

- enterprise data ingestion
- streaming and batch processing
- open table semantics
- strong metadata and lineage
- low-latency SQL serving for curated data products
- on-prem deployment and control

The recommended architectural position is:

- **MinIO object storage** is the persistence layer
- **Iceberg** is the mandatory table abstraction and data contract
- **Spark** is the primary batch compute engine
- **Flink** is the primary streaming and real-time compute engine
- **Kafka** is the recommended event backbone when the platform requires durable, replayable shared streaming and CDC ingestion
- **Apache Polaris** is the chosen REST catalog implementation and metadata control point for multi-engine interoperability
- **Atlas** is the metadata and governance plane
- **Ranger** is the policy enforcement layer for classification-driven access control
- **Airflow** is the orchestration and control-plane scheduler
- **Trino** is the default shared interactive SQL query plane over governed Iceberg datasets
- **Firebolt Core** is an optional acceleration layer for interactive analytics over Iceberg-backed data

The most important decision in this design is to make **Apache Iceberg the foundational abstraction**. Without that, the platform degenerates into a file swamp. Apache Iceberg is explicitly designed as a high-performance table format for large analytic datasets and supports safe multi-engine access from engines including Spark and Flink. The catalog choice is also a first-order design decision, not a later implementation detail. This architecture standardizes on a centrally managed REST-oriented catalog to reduce cross-engine ambiguity. See the Iceberg documentation and multi-engine support references:

- Apache Iceberg documentation: https://iceberg.apache.org/docs/latest/
- Apache Iceberg overview: https://iceberg.apache.org/
- Multi-engine support: https://iceberg.apache.org/multi-engine-support/

---

## 2. Architecture Principles

### 2.1 Open formats over proprietary lock-in
All persisted analytical datasets should be stored in open formats using **Apache Iceberg tables** over files in object storage.

### 2.2 Storage and table semantics must be separated
MinIO provides S3-compatible object storage for files. It is **not** the semantic contract for consumers. The semantic contract is the **Iceberg table**.

### 2.3 Streaming and batch are separate compute concerns
- **Flink** handles continuous, stateful, event-driven, near-real-time processing
- **Spark** handles scheduled, bounded, heavy compute and historical reshaping

Trying to force one engine to do both jobs badly is poor architecture.

### 2.4 Governance is a first-class platform capability
A data fabric without cataloguing, ownership, lineage, classification, and enforceable policy is just storage plus processing. Governance must be built in from day one via **Apache Atlas**, **Apache Ranger**, and enforced naming, ownership, metadata publication, and classification conventions.

### 2.5 Orchestration is not streaming
**Airflow** should orchestrate finite, bounded workflows and platform operations. It should not be used as a streaming runtime or a pseudo event bus. Apache Airflow is explicitly a workflow platform for authoring, scheduling, and monitoring workflows as directed acyclic graphs of tasks:

- Apache Airflow overview: https://airflow.apache.org/

### 2.6 Query serving is structured in two layers
The default shared query plane should be **Trino over Iceberg** for open interactive SQL access. If deployed, **Firebolt Core** should sit northbound of curated Iceberg datasets as an optional acceleration and serving layer, not as the foundational storage or governance layer.

---

## 3. Logical Architecture

```text
                    ┌───────────────────────────────────────────────┐
                    │                 Users / Apps                  │
                    │ BI / SQL / APIs / ML / Data Science / AI     │
                    └───────────────────────────────────────────────┘
                                          │
                         ┌────────────────┴────────────────┐
                         │                                 │
                         ▼               ▼                 ▼
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
                              │ snapshots / evolution   │
                              └─────────────────────────┘
                                          │
                         ┌────────────────┼────────────────┐
                         │                │                │
                         ▼                ▼                ▼
             ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
             │ Apache Spark     │  │ Apache Flink     │  │ Table Maintenance│
             │ batch ETL / ELT  │  │ streaming / CDC  │  │ compaction etc.  │
             └──────────────────┘  └──────────────────┘  └──────────────────┘
                         │                │                │
                         └────────────────┼────────────────┘
                                          │
                                          ▼
                          ┌───────────────────────────────┐
                          │       MinIO Object Storage    │
                          │  raw files + Iceberg data /   │
                          │  metadata files + manifests   │
                          └───────────────────────────────┘

  ┌─────────────────────────────────┐      ┌──────────────────────────────────────┐
  │      Apache Polaris             │      │   Kafka / Event Backbone             │
  │      REST Catalog               │      │   (when deployed — Phase 2+)         │
  │                                 │      │                                      │
  │  metadata control plane         │      │  CDC + event ingestion for Flink     │
  │  consulted by all engines       │◄────►│  Atlas entity change notifications   │
  │  Spark / Flink / Trino /        │      │  (Phase 2: replaces embedded         │
  │  Maintenance / Firebolt         │      │   Atlas notifier)                    │
  └─────────────────────────────────┘      └──────────────────────────────────────┘
            ▲        ▲        ▲
            │        │        │
     Spark  │  Flink │  Trino │  (all engines register and resolve tables via Polaris)

  ┌──────────────────────────────────────────────────────────────────────────────┐
  │ Governance / Control Plane                                                   │
  │ Apache Atlas (JanusGraph + embedded Solr) — metadata, lineage, glossary     │
  │ Apache Ranger — policy enforcement, classification-driven access control     │
  │ Airflow — orchestration, scheduling, promotion gates, maintenance            │
  │ FreeIPA — Kerberos, LDAP, PKI          Keycloak — OIDC for REST services   │
  └──────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Core Components and Responsibilities

### 4.1 MinIO (S3-Compatible Object Storage)

### Role
MinIO is the durable persistence substrate. It stores:

- landed raw files
- parquet/orc data files
- Iceberg metadata files
- manifests and snapshots
- curated datasets
- archive and retention data

### Responsibilities
- high-scale durable storage
- cheap separation of storage from compute
- storage for both raw and curated datasets
- support for Iceberg metadata and data files

### Design Position
Do **not** expose raw files in object storage as the enterprise data contract.

That is a bad pattern because:
- schemas become implicit or tribal knowledge
- partitions become engine-specific guesswork
- concurrency becomes unsafe
- consumers couple themselves to physical file layout

The contract must be **Iceberg tables**, not folders and filenames.

---

### 4.2 Apache Iceberg

### Role
Iceberg is the open table format and the core semantic layer of the platform.

### Why Iceberg is foundational
Apache Iceberg provides capabilities including:
- schema evolution
- partition evolution
- hidden partitioning
- snapshots and time travel
- rollback
- metadata-based planning
- multi-engine interoperability

References:
- Docs: https://iceberg.apache.org/docs/latest/
- Spark quickstart: https://iceberg.apache.org/spark-quickstart/
- Flink integration: https://iceberg.apache.org/docs/latest/flink/

### Design rules
- all bronze, silver, and gold analytical datasets should be Iceberg tables
- all engines should go through the chosen Iceberg catalog strategy
- table naming, namespace, retention, and ownership standards must be enforced centrally
- maintenance operations such as compaction and snapshot expiry must be owned explicitly

### Why this matters
Iceberg prevents the platform from collapsing into a set of loosely-related files. It gives the storage layer transactional table semantics suitable for multiple engines.

---

### 4.3 Apache Spark

### Role
Spark is the batch and large-scale transformation engine.

### Best-fit responsibilities
- heavy ETL / ELT
- backfills
- historical reprocessing
- large joins
- quality standardisation
- enrichment at scale
- feature engineering
- materialising silver and gold tables

### Why Spark belongs here
Spark remains the best fit for:
- large bounded workloads
- historical reshaping
- expensive joins and aggregations
- notebook-based engineering workflows

### Design rules
- Spark writes to and reads from Iceberg tables rather than unmanaged file paths
- Spark jobs should be orchestrated by Airflow for batch workflows
- large maintenance jobs should be isolated from business-critical serving windows

---

### 4.4 Apache Flink

### Role
Flink is the real-time and streaming engine.

### Best-fit responsibilities
- CDC ingestion
- event stream ingestion
- continuous enrichment
- stateful stream processing
- exactly-once stateful computation where needed
- near-real-time writes into Iceberg

### Why Flink belongs here
Flink is built for bounded and unbounded streams and supports stateful stream processing with event-time semantics. Iceberg explicitly documents Flink integration for reading and writing tables:

- Flink integration docs: https://iceberg.apache.org/docs/latest/flink/

### Design rules
- Flink should own real-time data movement and continuous pipelines
- Flink should not be replaced by Airflow for event-driven processing
- streaming write patterns into Iceberg should be tested carefully for commit cadence, compaction, and consumer freshness
- in steady state, Flink should be the sole writer for streaming-owned tables rather than sharing write ownership casually with batch jobs

---

### 4.5 Apache Kafka

### Role
Kafka is the preferred event backbone when the platform requires durable, replayable shared streaming and CDC use cases.

### Responsibilities
- CDC event transport
- business event ingestion
- replayable streams for Flink consumption
- buffering and back-pressure smoothing between producers and stream processors
- Atlas entity change notification bus (Phase 2+)

### Kafka Connect and Debezium

**Kafka Connect** is the standard data integration framework bundled with Apache Kafka (Apache 2.0 licence). It runs as a cluster of worker processes alongside the Kafka brokers and manages connectors that move data between Kafka and external systems. No additional product or commercial dependency is introduced.

**Debezium** (Apache 2.0, Red Hat-sponsored) is the CDC connector library that runs inside Kafka Connect. It captures row-level change events directly from source database transaction logs and publishes them as structured Kafka topic messages.

Together, Kafka Connect + Debezium form the CDC ingestion path:

```text
Source DB                Kafka Connect            Kafka topic       Flink
(Postgres / MySQL  ───►  Debezium connector  ───► <db>.<table>  ───► streaming
 Oracle / Mongo)          (runs on Connect         CDC events        ingestion
                           worker cluster)
```

#### Supported source systems
- PostgreSQL (via pgoutput or wal2json logical replication)
- MySQL / MariaDB (via binlog)
- Oracle (via LogMiner)
- SQL Server (via CDC)
- MongoDB (via change streams)

#### Design rules
- Kafka Connect workers run as a dedicated cluster, co-located with or alongside Kafka brokers
- each source system CDC feed is managed as a named Debezium connector with explicit configuration versioned in source control
- connector configuration, offsets, and status are stored in dedicated Kafka topics — no external state store required
- Flink consumes CDC topic messages directly; no intermediate transformation layer between Debezium output and Flink ingestion jobs
- connector deployment and lifecycle management is owned by the platform team, not individual domain teams

References:
- Apache Kafka Connect: https://kafka.apache.org/documentation/#connect
- Debezium: https://debezium.io/

### When Kafka is justified
- continuous CDC ingestion with replay requirements
- multiple downstream consumers on the same event stream
- bursty producers that need durable buffering
- event-driven platform patterns beyond analytical table production

### When Kafka is not required in the foundation
- scheduled batch ingestion dominates the workload
- source systems land files or bounded extracts in object storage
- Spark-driven bounded processing is the primary operating model
- near-real-time needs are modest and do not require a shared replayable event log

### Design Position
Kafka should not be treated as automatically mandatory just because Flink exists in the architecture. Kafka becomes a core component when the platform needs durable event retention, independent consumers, replay, back-pressure absorption, or CDC at meaningful scale. For batch-heavy or file-landed foundations, Kafka can remain a Phase 2 addition rather than a Phase 1 requirement.

When Kafka is deployed, Kafka Connect and Debezium are deployed with it as part of the same Phase 2 increment — they are not optional additions to the streaming stack.

---

### 4.6 Apache Atlas

### Role
Atlas is the metadata and governance plane.

### Responsibilities
- technical metadata catalog
- business glossary
- lineage
- data classifications and tags
- ownership and stewardship metadata
- discovery and search

Reference:
- Apache Atlas project: https://atlas.apache.org/

### Operational dependencies

Atlas requires a set of backing services to run. The platform adopts the **minimal dependency configuration**:

| Dependency | Chosen option | Rationale |
|---|---|---|
| Graph store | JanusGraph with embedded BerkeleyDB | eliminates external HBase and ZooKeeper clusters |
| Search index | Embedded Solr | eliminates a standalone Solr cluster; adequate for moderate metadata volumes |
| Notification bus | Embedded Kafka-compatible notifier (Phase 1); external Kafka (Phase 2+) | avoids a Kafka dependency in Phase 1; migrates to the platform Kafka instance when it is deployed in Phase 2 |

This configuration runs Atlas as a single deployable service with no external cluster dependencies in Phase 1. When Phase 2 deploys the Kafka event backbone, Atlas is reconfigured to use it for entity change notifications, which also enables downstream consumers of Atlas events.

**Scale ceiling**: embedded BerkeleyDB and embedded Solr are appropriate for moderate metadata volumes — tens of thousands of tables and attributes. If the platform grows to very large catalogue scale, the graph backend can be migrated to a standalone JanusGraph with Cassandra or HBase. That is a deliberate future migration, not a Phase 1 concern.

### Blunt reality
Atlas is useful, but it is not plug-and-play magic. It often becomes the most integration-heavy element in the stack.

The hard part is not installing Atlas. The hard part is:
- harvesting schemas and table metadata reliably
- pushing lineage from Spark and Flink jobs
- mapping technical assets to business terms
- keeping metadata current and trusted

### Design rules
- treat metadata publication as part of pipeline completion, not an optional afterthought
- define mandatory metadata fields for every dataset
- define ownership, data steward, SLA, sensitivity, and domain tags
- integrate Atlas with data quality and promotion workflows where possible
- treat lineage emission from Spark and Flink jobs as a delivery contract, not an aspiration

---

### 4.7 Apache Ranger

### Role
Ranger is the authorization and policy enforcement plane for data access controls.

### Responsibilities
- enforce role-based access by domain and environment
- apply tag-based or classification-driven policies for sensitive datasets
- align policy enforcement with Atlas classifications where possible
- provide an auditable enforcement path instead of relying on naming conventions and process discipline alone

### Design Position
Atlas without an enforcement layer is incomplete for sensitive-data governance. The platform should pair Atlas metadata and classifications with Ranger policy enforcement so classifications can drive real access rules.

---

### 4.8 Apache Airflow

### Role
Airflow is the workflow orchestration and control-plane scheduler.

### Best-fit responsibilities
- Spark job scheduling
- bounded batch workflow orchestration
- dependency management
- backfills
- table maintenance jobs
- data quality jobs
- metadata sync workflows
- promotion gates from bronze to silver to gold

Reference:
- Apache Airflow overview: https://airflow.apache.org/

### What Airflow should not do
Airflow should not be treated as:
- a streaming runtime
- a low-latency event processor
- a substitute for Flink
- an always-on stateful compute system

### Design rules
- use Airflow for finite workflows and control-plane operations
- keep DAGs readable and domain-oriented
- avoid building all platform logic into Airflow itself
- use Airflow to orchestrate engines, not replace them

---

### 4.9 Trino

### Role
Trino is the default open interactive SQL and shared query plane over governed Iceberg datasets.

### Best-fit responsibilities
- analyst and BI access to curated datasets
- shared SQL access across domains
- ad hoc query and federated read patterns where appropriate
- open query access when Firebolt is not deployed

### Design position
Spark SQL and notebooks remain engineering tools. They should not be treated as the default enterprise interactive query plane.

---

### 4.10 Firebolt Core

### Role
Firebolt Core is an optional high-performance serving engine for interactive SQL over external data and Iceberg datasets.

Reference:
- Firebolt Iceberg and external data: https://docs.firebolt.io/performance-and-observability/iceberg-and-external-data

### Best-fit responsibilities
- low-latency BI queries
- interactive dashboards
- app-facing analytics
- serving curated analytical datasets
- query acceleration over Iceberg-backed data

### Design position
Firebolt should sit **northbound of Iceberg** and serve curated data products. It should not become the foundational metadata or ingestion layer.

### When it fits well
- demanding dashboard latency requirements
- high-concurrency SQL serving
- curated data marts and semantic consumption layers

### When to be cautious
- when the platform has not yet stabilised its Iceberg and governance foundations
- when cost or licensing complexity is unclear
- when the operating model is already too heavy
- when the platform has not yet proven a stable curated layer and a real low-latency concurrency requirement

---

## 5. Data Quality Subsystem

The data quality subsystem is built entirely from components already in the platform stack. No additional framework is introduced.

### 5.1 Design position

Quality execution, result storage, orchestration, metadata publication, and access control are all handled by existing platform components:

- **Spark** executes quality checks as bounded Spark jobs using DataFrame assertions and SQL
- **Iceberg** stores quality results as a first-class platform table, queryable and versioned like any other dataset
- **Airflow** orchestrates check execution and enforces promotion gates as explicit DAG tasks
- **Atlas** carries quality status as a metadata attribute on each dataset, updated on every check run
- **Ranger** can restrict access to datasets that have not passed promotion gates
- **Trino** provides ad-hoc SQL access to quality results for operators and analysts

---

### 5.2 Quality check execution

Quality checks run as dedicated Spark jobs. Each job targets a specific dataset and zone transition — for example, bronze validation before silver promotion, or silver validation before gold materialisation.

### Check types

| Check type | Description |
|---|---|
| Schema conformance | column names, types, and nullability match the registered schema |
| Completeness | row count meets minimum threshold; mandatory columns have no null rate above tolerance |
| Uniqueness | primary or business key columns contain no unexpected duplicates |
| Freshness | latest record timestamp is within the expected SLA window |
| Referential integrity | foreign key values resolve against reference datasets |
| Business rule | domain-specific assertions expressed as SQL predicates or DataFrame conditions |

### Check definition

Checks are defined as configuration — YAML or equivalent — co-located with the pipeline definition and version-controlled alongside job code. Each check definition specifies:

- target dataset (namespace and table name in Polaris)
- check type and parameters
- pass threshold (absolute or percentage tolerance)
- severity: `blocking` or `warning`
- owner

Blocking checks must pass before promotion proceeds. Warning checks are recorded but do not halt the pipeline.

---

### 5.3 Quality result storage

Every check run writes a result record to a dedicated platform Iceberg table:

```
platform.quality.check_results
```

### Result record schema

| Column | Type | Description |
|---|---|---|
| run_id | string | unique identifier for the check run |
| dataset_namespace | string | Polaris namespace |
| dataset_name | string | Iceberg table name |
| zone | string | bronze / silver / gold |
| check_type | string | completeness / uniqueness / freshness / etc. |
| check_name | string | descriptive name of the specific check |
| severity | string | blocking / warning |
| status | string | passed / failed / warning |
| metric_value | double | observed metric (e.g. null rate, row count) |
| threshold | double | configured pass threshold |
| failure_detail | string | human-readable failure context |
| pipeline_run_id | string | Airflow DAG run ID |
| checked_at | timestamp | check execution time |
| iceberg_snapshot_id | long | Iceberg snapshot ID of the checked dataset |

Results are append-only. Historical quality records are retained as a permanent audit trail. Trino and Spark SQL can query this table directly.

---

### 5.4 Promotion gates

Promotion from one zone to the next is an explicit Airflow task, not an implicit side effect of a transformation job.

### Gate pattern

```
ingest / transform job
        │
        ▼
quality check Spark job
        │
        ▼
Airflow gate task — query check_results for this run_id
        │
   ┌────┴────┐
   │         │
 PASS       FAIL
   │         │
promote   halt pipeline / alert / await override
```

The Airflow gate task queries `platform.quality.check_results` for the current `run_id`, asserts that all blocking checks have status `passed`, and only then triggers the downstream promotion or materialisation task.

### Override model

A failed blocking check halts the pipeline. Manual override requires:
- an explicit Airflow task approval by a named data steward or platform operator
- an override reason recorded in the pipeline run metadata
- the override event written to `platform.quality.check_results` as a separate audit record with status `overridden`

Overrides are auditable. They do not silently suppress the failure record.

---

### 5.5 Atlas metadata integration

On completion of each check run, the pipeline publishes quality status back to Atlas for the target dataset:

- `quality_status`: `passed` / `failed` / `warning`
- `quality_last_checked`: timestamp of the most recent check run
- `quality_run_id`: link to the check run in `platform.quality.check_results`
- `quality_blocking_failures`: count of blocking check failures in the last run

This means Atlas dataset entries reflect current quality state and consumers can discover whether a dataset is in a passing or failing quality condition without querying the results table directly.

Quality status publication to Atlas is a mandatory step in every pipeline's completion contract, not an optional integration.

---

### 5.6 Access control integration

Ranger policies can be applied to restrict read access to datasets in a failing quality state where the sensitivity of the data and the nature of the failure warrant it. This is a platform-level policy decision, not automatic — classification-driven Ranger rules should be applied deliberately for sensitive zones and datasets.

---

### 5.7 Ownership

- **Platform team** owns the `platform.quality.check_results` table, its schema, retention policy, and Iceberg maintenance schedule
- **Domain pipeline owners** define and maintain check configurations for their datasets
- **Data stewards** hold override authority for their domain's blocking failures
- **No check run should complete without a result record** — silent quality execution is not permitted

---

## 6. Data Lifecycle Model

A three-layer model is recommended.

### 6.1 Bronze
Raw or lightly normalised data.

### Characteristics
- closest possible fidelity to source
- append-biased
- minimal correction
- schema capture and source metadata preserved
- suitable for replay and audit

### Typical producers
- landed batch files
- CDC feeds
- Flink ingestion pipelines
- external source extracts

### 6.2 Silver
Conformed, validated, reusable enterprise data.

### Characteristics
- deduplicated
- typed and normalised
- standard business keys
- reference-data-enriched
- suitable for broad reuse across teams

### Typical producers
- Spark transformation jobs
- Flink continuous enrichment pipelines

### 6.3 Gold
Consumption-ready data products.

### Characteristics
- business-facing marts
- KPI tables
- aggregates
- semantic views
- application-ready analytical tables

### Typical consumers
- BI dashboards
- data APIs
- serving/query engines such as Firebolt
- data science and analytics consumers

### Rule
All three zones should be implemented as **Iceberg tables**, not folder conventions alone.

### Write ownership rule
For any given table, steady-state write ownership should be explicit and narrow. The platform should prefer a one-writer pattern per table where possible, with compaction ownership assigned deliberately and not left ambiguous across engines.

---

## 7. Control Planes

A clean design separates three planes.

### 7.1 Data Plane
Contains:
- MinIO object storage
- Iceberg tables
- Apache Polaris REST catalog
- Spark
- Flink
- Kafka, Kafka Connect, and Debezium when required by the streaming use case
- Trino
- Firebolt serving access

### 7.2 Metadata and Governance Plane
Contains:
- Atlas
- Ranger
- glossary
- lineage
- classification
- ownership and stewardship

### 7.3 Orchestration and Operations Plane
Contains:
- Airflow
- platform automation
- retries and alerts
- table maintenance scheduling
- backfills
- promotions and approvals

This separation matters because failed platforms often blur these layers into one mess.

---

## 8. Catalog Strategy

A critical design decision is the Iceberg catalog strategy.

### Options evaluated
Common Iceberg catalog approaches include:
- Hadoop catalog — filesystem-based, no multi-engine coordination, not suitable for a shared platform
- Hive catalog — legacy coupling to Hive Metastore, limits engine flexibility
- REST catalog — open API standard, engine-agnostic, fits multi-engine design
- JDBC catalog — simple but operationally fragile at scale; lacks REST API compatibility
- Nessie — REST-compatible with Git-like branching semantics; strong fit for data-environment workflows
- **Apache Polaris** — Apache-incubated open source REST catalog server; implements the Iceberg REST Catalog spec natively

Iceberg’s Flink docs explicitly call out catalog configuration options such as `hive`, `hadoop`, `rest`, `jdbc`, and others depending on implementation support:
- Iceberg Flink catalog configuration: https://iceberg.apache.org/docs/latest/flink/

### Decision
**Apache Polaris** is the chosen REST catalog implementation for this architecture.

Reference:
- Apache Polaris: https://polaris.apache.org/
- Polaris GitHub: https://github.com/apache/polaris

### Why Apache Polaris
- implements the Iceberg REST Catalog open API specification natively — no vendor lock-in at the catalog layer
- Apache-incubated open source project with strong Iceberg ecosystem alignment
- designed as a multi-engine catalog; Spark, Flink, and Trino all connect via the standard REST Catalog interface
- supports fine-grained access control at the catalog and namespace level
- on-prem deployable without commercial licensing requirements
- strategically cleaner than embedding catalog behavior in engine-local configurations
- keeps metadata control in a dedicated, independently operable service boundary

### Why not the alternatives
- **Hadoop catalog**: no shared multi-engine coordination, not suitable for a governed platform
- **Hive Metastore**: legacy dependency, limits engine flexibility, adds operational complexity
- **JDBC catalog**: operationally fragile at scale, lacks REST API compatibility, no access control model
- **Nessie**: strong Git-like branching semantics are valuable but add operational complexity that is not required in Phase 1; remains the preferred alternative if data-environment branching becomes a platform requirement

### Alternative path
If the platform later needs Git-like branching, tagging, and data-environment workflows at the catalog layer — for example, isolated branch environments for engineering, staging, and production data — **Nessie** is the next candidate to evaluate. That should be treated as a deliberate platform decision, not an incremental implementation detail.

### Design rule
Spark, Flink, Trino, and all maintenance workflows must be configured to use the central Apache Polaris REST catalog. Engine-local catalog configurations that bypass Polaris are not permitted in governed environments.

---

## 9. Governance Model

### 9.1 Minimum metadata standard for every dataset
Every bronze, silver, and gold dataset should have:
- business name
- technical name
- domain
- owner
- steward
- source system
- schema version
- sensitivity / classification
- SLA / refresh expectation
- quality status
- retention rule
- downstream usage tags

### 9.2 Lineage expectations
Lineage should cover:
- source files / streams to bronze
- bronze to silver transformations
- silver to gold materialisations
- consuming marts and serving tables

### 9.3 Lineage delivery contract
Lineage quality will be poor unless metadata emission is standardized. Spark and Flink jobs should emit dataset, schema, run, and transformation metadata in a consistent format, and publication to Atlas should be part of deployment and job-completion contracts rather than a best-effort extra.

### 9.4 Domain ownership
The platform team should own the shared platform and conventions.
Domain teams should own their data products.

That split is important. Otherwise the central team becomes a bottleneck and the platform becomes theatre.

---

## 10. Orchestration Model

### 10.1 Airflow should orchestrate
- Spark batch jobs
- data quality tasks
- metadata publication tasks
- promotion workflows
- Iceberg maintenance jobs
- periodic compaction windows
- snapshot retention enforcement

### 10.2 Flink should execute continuously
- streaming jobs remain long-running where appropriate
- operational lifecycle for Flink jobs should be separate from normal Airflow-style task semantics

### 10.3 Table maintenance
Iceberg maintenance is not optional.

The platform must schedule:
- snapshot expiration — target cadence: daily for active tables, weekly for archival
- compaction / file rewrite — target: keep file counts within 2× the optimal range for each table's typical query pattern
- orphan file cleanup — run at least weekly; alert if orphan volume exceeds a configurable threshold
- metadata health checks — verify manifest list depth and metadata.json file count; compact metadata when thresholds are breached

**Ownership**: The platform / infrastructure team owns the maintenance jobs and their scheduling. Domain teams do not run ad-hoc maintenance. Compaction ownership must be explicitly assigned per table (normally the platform team); no table should be left without a designated maintenance owner.

**Targets are starting points.** Adjust cadences based on observed table activity and query performance. The key discipline is that every table has a maintenance schedule and an owner — not the specific numbers.

If this is neglected, performance and reliability will degrade over time.

### 10.4 Data quality and promotion
Quality check execution, result storage, promotion gates, override handling, and ownership are fully specified in **§5 Data Quality Subsystem**.

---

## 11. Reference End-to-End Flow

### 11.1 Batch flow
1. source files arrive in MinIO landing zone
2. Airflow triggers ingestion and validation pipeline
3. Spark normalises and writes bronze Iceberg tables
4. Spark transforms bronze to silver
5. Spark or SQL jobs materialise gold tables
6. Atlas metadata and lineage are updated
7. Firebolt optionally serves curated gold datasets

### 11.2 Streaming flow
This flow applies when a streaming backbone such as Kafka is deployed (typically Phase 2+).

1. source database changes are captured by a Debezium connector running in Kafka Connect and published to a Kafka topic
2. business events from application producers land directly in Kafka topics
3. Flink consumes Kafka topics, enriches and transforms streams
4. Flink writes bronze or silver Iceberg tables continuously via the Polaris catalog
5. downstream Spark or Flink jobs materialise higher-order views
6. Atlas metadata and lineage are synchronised
7. Firebolt or BI consumers query curated outputs via Trino or directly

---

## 12. Physical Deployment View

A realistic on-prem deployment would separate infrastructure by concern.

### 12.1 Storage tier
- MinIO object store cluster
- erasure coding / replication depending on product choice
- separate buckets or namespaces by domain and lifecycle zone

### 12.2 Compute tier
- Spark cluster for batch compute
- Flink cluster for stateful stream processing
- Firebolt Core nodes if deployed

### 12.3 Control tier
- Airflow scheduler, webserver, workers, and metadata DB (PostgreSQL)
- Apache Atlas with embedded JanusGraph (BerkeleyDB) and embedded Solr — single deployable service in Phase 1
- FreeIPA identity services (Kerberos KDC, LDAP, DNS, PKI)
- Keycloak OIDC broker
- Apache Ranger admin server and usersync service
- monitoring and logging stack

### 12.4 Platform services
- Apache Polaris REST catalog service
- FreeIPA identity services (Kerberos KDC, LDAP, DNS, PKI)
- Keycloak OIDC broker
- secrets management
- `platform.quality.check_results` Iceberg table (owned by platform team)
- observability stack (see §14.1)
- certificate and TLS management via FreeIPA Dogtag PKI

---

## 13. Security and Access Model

### 13.1 Identity foundation

The platform is Linux-only. The identity stack is:

| Component | Role |
|---|---|
| **FreeIPA** | core identity provider — Kerberos KDC, LDAP directory, DNS, PKI |
| **Keycloak** | OIDC broker for REST-facing services — backed by FreeIPA |
| **MIT Kerberos client** | installed on all compute nodes for ticket-based service authentication |
| **SSSD** | Linux host integration with FreeIPA for OS-level authentication |

### Why FreeIPA
FreeIPA bundles MIT Kerberos, 389 Directory Server (LDAP), Dogtag PKI, and DNS into a single managed identity service designed for Linux-native on-prem deployments. It eliminates the need for Windows Active Directory entirely while providing the same Kerberos and LDAP interfaces that Ranger, Atlas, Spark, and Flink expect.

Reference:
- FreeIPA: https://www.freeipa.org/

### Why Keycloak
Keycloak is an open source OIDC/SAML identity broker. It sits in front of FreeIPA and provides token-based authentication for REST-facing platform services — Apache Polaris, Airflow web UI, and any data API layer. This avoids embedding Kerberos ticket handling into REST clients while keeping FreeIPA as the single source of truth for principals and groups.

Reference:
- Keycloak: https://www.keycloak.org/

---

### 13.2 Authentication model by component

| Component | Authentication mechanism |
|---|---|
| Spark jobs | Kerberos service account (keytab on compute node) |
| Flink jobs | Kerberos service account (keytab on compute node) |
| Airflow workers | Kerberos service account for job submission |
| Apache Polaris | OIDC token via Keycloak |
| Trino | Kerberos for internal cluster; OIDC via Keycloak for client access |
| Atlas | LDAP via FreeIPA for user authentication; Kerberos for internal service calls |
| Ranger | LDAP via FreeIPA for user/group sync; Kerberos for plugin-to-server communication |
| MinIO | service accounts with access/secret key pairs; TLS enforced |
| Airflow web UI | OIDC via Keycloak |

### Service account model
Every pipeline component runs as a named Linux service account registered in FreeIPA. No shared credentials. No human credentials used for job execution. Keytabs are managed centrally and rotated on a defined schedule.

---

### 13.3 Authorisation and policy enforcement

The platform uses a two-layer authorisation model:

**Layer 1 — Ranger policy enforcement**
Ranger enforces data access policy at the engine layer for Spark, Flink, Trino, and Atlas. Ranger's `usersync` service polls FreeIPA LDAP on a schedule to import users and groups. Policies are defined against FreeIPA groups, not individual users.

**Layer 2 — Polaris catalog access control**
Apache Polaris enforces catalog-level access at the namespace and table level. Polaris principals are mapped to Keycloak identities. Polaris roles control which principals can read, write, or manage catalog namespaces and tables.

These two layers are complementary:
- Polaris controls what the catalog exposes to each engine identity
- Ranger controls what each engine identity can do with the data once the catalog resolves it

---

### 13.4 Group and role model

FreeIPA groups are the unit of policy assignment. Ranger and Polaris policies are written against groups, not individuals.

Recommended group structure:

| Group | Access scope |
|---|---|
| `platform-admins` | full platform administration |
| `platform-engineers` | pipeline development and deployment |
| `data-stewards-<domain>` | domain metadata management and quality override authority |
| `analysts-<domain>` | read access to silver and gold datasets for their domain |
| `consumers-gold` | read access to curated gold datasets across domains |
| `svc-spark` | Spark service account group |
| `svc-flink` | Flink service account group |
| `svc-airflow` | Airflow service account group |

Group membership is managed in FreeIPA. Policy assignment is managed in Ranger and Polaris. The two concerns are kept separate.

---

### 13.5 Classification-driven access control

Atlas classifications are the bridge between governance metadata and Ranger enforcement:

1. Atlas classifies a dataset (e.g. `PII`, `CONFIDENTIAL`, `RESTRICTED`)
2. Ranger tag-based policies apply access rules to any dataset carrying that classification
3. Access is enforced automatically when a new dataset is classified — no manual policy update per table required

This means sensitive datasets are protected by classification, not by remembering to write a new Ranger rule for each table. The enforcement model scales with the data catalogue.

---

### 13.6 Encryption

- **In transit**: TLS enforced across all inter-service communication; certificates issued by FreeIPA's Dogtag PKI
- **At rest**: MinIO supports server-side encryption; enable per bucket for sensitive zones
- **Kerberos tickets**: short-lived; keytab rotation managed via FreeIPA

---

### 13.7 Minimum access control requirements

The platform must enforce:
- role-based access by domain and environment, backed by FreeIPA groups
- separation between raw (bronze), curated (silver/gold), and sensitive zones enforced by Ranger
- service-account-based job execution — no human credentials in pipelines
- encryption in transit and at rest
- audited data access via Ranger audit logs
- classification-driven policy attachment via Atlas + Ranger tag-based policies

Sensitive datasets must not rely on bucket naming and tribal process alone. Classification, policy, and enforcement must be aligned from day one.

---

### 13.8 Source references

- FreeIPA: https://www.freeipa.org/
- Keycloak: https://www.keycloak.org/
- Apache Ranger LDAP usersync: https://ranger.apache.org/
- MIT Kerberos: https://web.mit.edu/kerberos/

---

## 14. Operational Model

### 14.1 Observability

#### Tooling
The observability stack uses open source components consistent with the Linux-only, no-proprietary-dependency constraint:

| Concern | Tooling |
|---|---|
| Metrics collection and storage | **Prometheus** |
| Dashboards and alerting | **Grafana** |
| Log aggregation | **Grafana Loki** (or OpenSearch if full-text search of logs is required) |
| Distributed tracing (optional) | **Grafana Tempo** |

All four are open source (Apache 2.0 / AGPLv3), on-prem deployable, and integrate natively with each other. Prometheus exporters exist for Spark, Flink, Kafka, MinIO, Airflow, Trino, and the JVM-based services (Atlas, Polaris, Ranger).

References:
- Prometheus: https://prometheus.io/
- Grafana: https://grafana.com/oss/grafana/
- Grafana Loki: https://grafana.com/oss/loki/

#### What to track
- pipeline success/failure rates and durations (Airflow DAG metrics via Prometheus StatsD exporter)
- end-to-end data freshness per dataset (derived from Atlas `quality_last_checked` and Iceberg snapshot timestamps)
- Flink job lag and checkpoint health (Flink metrics exposed via Prometheus reporter)
- Spark job duration and failure causes (Spark metrics sink to Prometheus)
- Iceberg metadata growth — manifest list depth and metadata file count per table
- file-count explosion and compaction debt (query `platform.quality.check_results` and Iceberg table metrics)
- Kafka consumer lag for Flink source topics (Kafka exporter to Prometheus)
- serving-layer query latency (Trino JMX metrics via Prometheus)
- metadata publication freshness in Atlas
- Ranger audit log volume and policy evaluation latency

### 14.2 Reliability disciplines
- explicit retry policies
- backfill procedures
- replay procedures for bronze data
- change management for schema evolution
- controlled promotion between environments

### 14.3 Data quality
Quality execution, result storage, promotion gates, and override handling are fully specified in **§5 Data Quality Subsystem**. Operationally, monitor the `platform.quality.check_results` Iceberg table for failure trends, override frequency, and check coverage per domain.

---

## 15. Risks and Mitigations

### 15.1 Risk: file swamp instead of platform
**Cause:** raw files treated as the contract rather than Iceberg tables.

**Mitigation:** mandate Iceberg for all governed analytical datasets.

### 15.2 Risk: Spark and Flink commit contention or ownership confusion
**Cause:** multiple engines writing the same tables without clear rules.

**Mitigation:** define one-writer-per-table steady-state ownership where possible, assign compaction ownership explicitly, and separate streaming append patterns from batch-upsert or serving-table materialization patterns.

### 15.3 Risk: Atlas becomes shelfware
**Cause:** no serious metadata ingestion, no ownership discipline, no lineage automation.

**Mitigation:** make metadata publication mandatory in delivery pipelines, standardize metadata emission from Spark and Flink jobs, and establish data stewardship roles.

### 15.4 Risk: Airflow becomes a platform dumping ground
**Cause:** every dependency and runtime concern pushed into DAGs.

**Mitigation:** keep Airflow focused on orchestration and control-plane workflows.

### 15.5 Risk: Firebolt adopted too early
**Cause:** performance tooling added before storage, catalog, and governance foundations are stable.

**Mitigation:** keep Firebolt optional and phase it after core platform maturity.

### 15.6 Risk: too much platform, not enough product value
**Cause:** architecture overbuild without domain-aligned data products.

**Mitigation:** deliver by domain use case, with measurable product outcomes and curated gold datasets.

### 15.7 Risk: platform ambiguity at the seams
**Cause:** catalog undecided, query plane undecided, authorization undecided, lineage automation assumed.

**Mitigation:** treat catalog, query plane, authorization model, and lineage publication as named platform products with explicit owners and operating expectations.

---

## 16. Phased Roadmap

### Phase 1 – Foundation
Deliver:
- MinIO object storage
- Iceberg table standard
- Apache Polaris REST catalog
- Spark batch processing
- Trino shared query layer
- Airflow orchestration
- Atlas and Ranger baseline governance and policy model
- bronze/silver/gold standards

Primary outcome:
- governed batch lakehouse foundation

### Phase 1 scope note
Phase 1 includes REST catalog, Trino, Atlas, and Ranger alongside the core batch stack. That is a meaningful deployment surface. If Phase 1 needs to be narrower, Trino and Ranger can be deferred to a Phase 1.5 increment after MinIO, Iceberg, Spark, Airflow, and Atlas are stable.

### Phase 2 – Streaming and Operational Maturity
Deliver:
- Kafka event backbone
- Kafka Connect worker cluster
- Debezium CDC connectors for source systems
- Flink streaming ingestion
- CDC pipelines
- Atlas reconfigured to use platform Kafka for entity change notifications (replacing embedded notifier)
- lineage automation improvements
- maintenance automation for Iceberg

Primary outcome:
- near-real-time ingestion and processing

### Phase 3 – Query Acceleration and Data Products
Deliver:
- Firebolt Core serving layer if justified
- curated business marts
- semantic serving views
- domain-owned data products

Primary outcome:
- low-latency consumption and broader business adoption

### Phase 4 – Advanced Governance and Self-Service
Deliver:
- stronger glossary alignment
- policy-driven classification workflows
- self-service dataset discovery and onboarding
- reusable domain patterns and templates

Primary outcome:
- scalable enterprise data fabric operating model

---

## 17. Recommended Final Position

The recommended architecture is:

**Data layer**
- **MinIO** for durable object storage
- **Apache Iceberg** as the mandatory table abstraction and data contract
- **Apache Polaris** as the central REST catalog and multi-engine metadata control point

**Compute layer**
- **Apache Spark** for heavy batch compute and historical transforms
- **Apache Flink** for streaming and real-time computation
- **Trino** as the default open interactive SQL query plane
- **Firebolt Core** as an optional serving/query acceleration layer over curated Iceberg datasets

**Streaming and CDC layer** (Phase 2+)
- **Apache Kafka** as the durable event backbone
- **Kafka Connect** as the connector framework for source system integration
- **Debezium** as the CDC connector for database change capture

**Governance layer**
- **Apache Atlas** for metadata, glossary, lineage, and classifications
- **Apache Ranger** for policy enforcement tied to Atlas classifications and access rules

**Orchestration layer**
- **Apache Airflow** for batch orchestration, promotion gates, and control-plane automation

**Identity and security layer**
- **FreeIPA** as the Linux-native identity provider (Kerberos, LDAP, PKI)
- **Keycloak** as the OIDC broker for REST-facing services

**Observability layer**
- **Prometheus + Grafana** for metrics and dashboards
- **Grafana Loki** for log aggregation

This is a credible and modern on-prem data fabric design.

The blunt truth is that the hardest part is not installing the components. The hardest parts are:
- enforcing Iceberg as the real contract
- committing to a central catalog and query plane early
- defining dataset ownership and metadata standards
- pairing governance metadata with enforceable authorization
- controlling multi-engine write semantics
- maintaining table health over time
- making lineage trustworthy through standardized publication
- stopping orchestration and governance layers from becoming chaotic

Get those parts right and the platform can work well.
Get them wrong and you will just have an expensive collection of tools.

---

## 18. Source References

- Apache Iceberg documentation: https://iceberg.apache.org/docs/latest/
- Apache Iceberg REST Catalog spec: https://iceberg.apache.org/docs/latest/rest-catalog/
- Apache Polaris: https://polaris.apache.org/
- Apache Polaris GitHub: https://github.com/apache/polaris
- Apache Iceberg overview: https://iceberg.apache.org/
- Apache Iceberg multi-engine support: https://iceberg.apache.org/multi-engine-support/
- Apache Iceberg Spark quickstart: https://iceberg.apache.org/spark-quickstart/
- Apache Iceberg Flink integration: https://iceberg.apache.org/docs/latest/flink/
- Apache Airflow: https://airflow.apache.org/
- Apache Atlas: https://atlas.apache.org/
- Apache Ranger: https://ranger.apache.org/
- Apache Kafka: https://kafka.apache.org/
- Apache Kafka Connect: https://kafka.apache.org/documentation/#connect
- Debezium: https://debezium.io/
- Trino documentation: https://trino.io/docs/current/
- Trino Iceberg connector: https://trino.io/docs/current/connector/iceberg.html
- Firebolt external data and Iceberg: https://docs.firebolt.io/performance-and-observability/iceberg-and-external-data
- FreeIPA: https://www.freeipa.org/
- Keycloak: https://www.keycloak.org/
- MIT Kerberos: https://web.mit.edu/kerberos/
- Prometheus: https://prometheus.io/
- Grafana: https://grafana.com/oss/grafana/
- Grafana Loki: https://grafana.com/oss/loki/
