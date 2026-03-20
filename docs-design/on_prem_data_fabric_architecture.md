# On-Prem Data Fabric Architecture

## 1. Executive Summary

This document defines a pragmatic on-prem data fabric architecture built around **MinIO (S3-compatible object storage)**, **Apache Iceberg**, **Apache Spark**, **Apache Flink**, **Apache Atlas**, **Apache Airflow**, and an optional **Firebolt Core** serving layer.

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
- **Atlas** is the metadata and governance plane
- **Airflow** is the orchestration and control-plane scheduler
- **Firebolt Core** is an optional acceleration layer for interactive analytics over Iceberg-backed data

The most important decision in this design is to make **Apache Iceberg the centre of gravity**. Without that, the platform degenerates into a file swamp. Apache Iceberg is explicitly designed as a high-performance table format for large analytic datasets and supports safe multi-engine access from engines including Spark and Flink. See the Iceberg documentation and multi-engine support references:

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
A data fabric without cataloguing, ownership, lineage, and classification is just storage plus processing. Governance must be built in from day one via **Apache Atlas** and enforced naming, ownership, and metadata publication conventions.

### 2.5 Orchestration is not streaming
**Airflow** should orchestrate finite, bounded workflows and platform operations. It should not be used as a streaming runtime or a pseudo event bus. Apache Airflow is explicitly a workflow platform for authoring, scheduling, and monitoring workflows as directed acyclic graphs of tasks:

- Apache Airflow overview: https://airflow.apache.org/

### 2.6 Query serving is optional and pluggable
The serving/query layer should be replaceable. If deployed, **Firebolt Core** should sit northbound of curated Iceberg datasets as an acceleration and serving layer, not as the foundational storage or governance layer.

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
                     ▼                                 ▼
          ┌─────────────────────┐           ┌──────────────────────┐
          │   Firebolt Core     │           │ Spark SQL / Notebook │
          │ low-latency serving │           │ engineering access   │
          └─────────────────────┘           └──────────────────────┘
                     │                                 │
                     └────────────────┬────────────────┘
                                      ▼
                          ┌─────────────────────────┐
                          │   Apache Iceberg Tables │
                          │ bronze / silver / gold  │
                          │ snapshots / evolution   │
                          └─────────────────────────┘
                                      │
                    ┌─────────────────┼─────────────────┐
                    │                 │                 │
                    ▼                 ▼                 ▼
        ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
        │ Apache Spark     │  │ Apache Flink     │  │ Table Maintenance│
        │ batch ETL / ELT  │  │ streaming / CDC  │  │ compaction etc.  │
        └──────────────────┘  └──────────────────┘  └──────────────────┘
                    │                 │                 │
                    └─────────────────┴─────────────────┘
                                      │
                                      ▼
                     ┌────────────────────────────────┐
                     │ MinIO Object Storage           │
                     │ raw files + Iceberg data/meta  │
                     └────────────────────────────────┘

            ┌─────────────────────────────────────────────────────┐
            │ Governance / Control Plane                         │
            │ Apache Atlas + glossary + lineage + classifications│
            │ Airflow + scheduling + dependencies + operations   │
            └─────────────────────────────────────────────────────┘
```

---

## 4. Core Components and Responsibilities

## 4.1 MinIO (S3-Compatible Object Storage)

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

## 4.2 Apache Iceberg

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

## 4.3 Apache Spark

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

## 4.4 Apache Flink

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

---

## 4.5 Apache Atlas

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

---

## 4.6 Apache Airflow

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

## 4.7 Firebolt Core

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

---

## 5. Recommended Additional Components

The requested toolset is close, but one obvious omission is **Kafka or an equivalent event backbone**.

## 5.1 Kafka or equivalent message/event backbone

### Why it matters
If the platform needs genuine real-time ingestion and stream processing, Flink usually needs an event source that is not just polling files.

### Recommended use
- CDC events
- business event ingestion
- decoupled producer/consumer patterns
- replayable event streams
- buffering and back-pressure smoothing

Without an event backbone, Flink may still be usable, but the platform will be weaker for serious streaming use cases.

---

## 6. Data Lifecycle Model

A three-layer model is recommended.

## 6.1 Bronze
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

## 6.2 Silver
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

## 6.3 Gold
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

---

## 7. Control Planes

A clean design separates three planes.

## 7.1 Data Plane
Contains:
- MinIO object storage
- Iceberg tables
- Spark
- Flink
- Firebolt serving access

## 7.2 Metadata and Governance Plane
Contains:
- Atlas
- glossary
- lineage
- classification
- ownership and stewardship

## 7.3 Orchestration and Operations Plane
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

### Options
Common Iceberg catalog approaches include:
- Hadoop catalog
- Hive catalog
- REST catalog
- JDBC catalog
- Nessie or equivalent catalog integration where relevant

Iceberg’s Flink docs explicitly call out catalog configuration options such as `hive`, `hadoop`, `rest`, `jdbc`, and others depending on implementation support:
- Iceberg Flink catalog configuration: https://iceberg.apache.org/docs/latest/flink/

### Recommendation
For a serious multi-engine platform, use a catalog strategy that is:
- centrally managed
- compatible with Spark and Flink
- supportable in your environment
- decoupled enough to avoid engine-specific lock-in

For many enterprise designs, a **REST-oriented or centrally managed catalog approach** is strategically cleaner than scattering metadata into ad hoc engine-specific setups.

---

## 9. Governance Model

## 9.1 Minimum metadata standard for every dataset
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

## 9.2 Lineage expectations
Lineage should cover:
- source files / streams to bronze
- bronze to silver transformations
- silver to gold materialisations
- consuming marts and serving tables

## 9.3 Domain ownership
The platform team should own the shared platform and conventions.
Domain teams should own their data products.

That split is important. Otherwise the central team becomes a bottleneck and the platform becomes theatre.

---

## 10. Orchestration Model

## 10.1 Airflow should orchestrate
- Spark batch jobs
- data quality tasks
- metadata publication tasks
- promotion workflows
- Iceberg maintenance jobs
- periodic compaction windows
- snapshot retention enforcement

## 10.2 Flink should execute continuously
- streaming jobs remain long-running where appropriate
- operational lifecycle for Flink jobs should be separate from normal Airflow-style task semantics

## 10.3 Table maintenance
Iceberg maintenance is not optional.

The platform must schedule:
- snapshot expiration
- compaction / file rewrite
- orphan file cleanup
- metadata health checks

If this is neglected, performance and reliability will degrade over time.

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
1. source events or CDC changes land in Kafka or equivalent
2. Flink ingests and enriches streams
3. Flink writes bronze or silver Iceberg tables continuously
4. downstream Spark or Flink jobs materialise higher-order views
5. Atlas metadata and lineage are synchronised
6. Firebolt or BI consumers query curated outputs

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
- Airflow scheduler, webserver, workers, metadata DB
- Atlas services and backing dependencies as required by chosen packaging
- monitoring and logging stack

### 12.4 Platform services
- catalog services
- secrets management
- identity and access integration
- certificate and TLS management
- observability stack

---

## 13. Security and Access Model

At minimum, the platform should support:
- role-based access by domain and environment
- separation between raw, curated, and sensitive zones
- service-account-based job execution
- encryption in transit and at rest
- audited metadata changes and data access where possible
- classification-driven policy attachment through governance tooling

Sensitive datasets should not rely on bucket naming and tribal process alone. Access control and classification must align.

---

## 14. Operational Model

## 14.1 Observability
Track at least:
- pipeline success/failure
- end-to-end data freshness
- Flink job lag and checkpoint health
- Spark job duration and failure causes
- Iceberg metadata growth
- file-count explosion and compaction debt
- serving-layer query latency
- metadata publication freshness in Atlas

## 14.2 Reliability disciplines
- explicit retry policies
- backfill procedures
- replay procedures for bronze data
- change management for schema evolution
- controlled promotion between environments

## 14.3 Data quality
Data quality should be treated as part of the delivery pipeline, not a decorative dashboard.

Quality gates should be applied before promotion from bronze to silver and from silver to gold where appropriate.

---

## 15. Risks and Mitigations

## 15.1 Risk: file swamp instead of platform
**Cause:** raw files treated as the contract rather than Iceberg tables.

**Mitigation:** mandate Iceberg for all governed analytical datasets.

## 15.2 Risk: Spark and Flink commit contention or ownership confusion
**Cause:** multiple engines writing the same tables without clear rules.

**Mitigation:** define ownership boundaries for layers, compaction, retention, and update patterns.

## 15.3 Risk: Atlas becomes shelfware
**Cause:** no serious metadata ingestion, no ownership discipline, no lineage automation.

**Mitigation:** make metadata publication mandatory in delivery pipelines and establish data stewardship roles.

## 15.4 Risk: Airflow becomes a platform dumping ground
**Cause:** every dependency and runtime concern pushed into DAGs.

**Mitigation:** keep Airflow focused on orchestration and control-plane workflows.

## 15.5 Risk: Firebolt adopted too early
**Cause:** performance tooling added before storage, catalog, and governance foundations are stable.

**Mitigation:** keep Firebolt optional and phase it after core platform maturity.

## 15.6 Risk: too much platform, not enough product value
**Cause:** architecture overbuild without domain-aligned data products.

**Mitigation:** deliver by domain use case, with measurable product outcomes and curated gold datasets.

---

## 16. Phased Roadmap

## Phase 1 – Foundation
Deliver:
- MinIO object storage
- Iceberg table standard
- Spark batch processing
- Airflow orchestration
- Atlas basic metadata model
- bronze/silver/gold standards

Primary outcome:
- governed batch lakehouse foundation

## Phase 2 – Streaming and Operational Maturity
Deliver:
- Kafka or equivalent event backbone
- Flink streaming ingestion
- CDC pipelines
- lineage automation improvements
- maintenance automation for Iceberg

Primary outcome:
- near-real-time ingestion and processing

## Phase 3 – Query Acceleration and Data Products
Deliver:
- Firebolt Core serving layer if justified
- curated business marts
- semantic serving views
- domain-owned data products

Primary outcome:
- low-latency consumption and broader business adoption

## Phase 4 – Advanced Governance and Self-Service
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

- **MinIO object storage** for persistence
- **Apache Iceberg** as the mandatory table abstraction and data contract
- **Apache Spark** for heavy batch compute and historical transforms
- **Apache Flink** for streaming and real-time computation
- **Apache Atlas** for metadata, glossary, lineage, and governance
- **Apache Airflow** for batch orchestration and control-plane automation
- **Firebolt Core** as an optional serving/query acceleration layer over curated Iceberg datasets

This is a credible and modern on-prem data fabric design.

The blunt truth is that the hardest part is not installing the components. The hardest parts are:
- enforcing Iceberg as the real contract
- defining dataset ownership and metadata standards
- controlling multi-engine write semantics
- maintaining table health over time
- stopping orchestration and governance layers from becoming chaotic

Get those parts right and the platform can work well.
Get them wrong and you will just have an expensive collection of tools.

---

## 18. Source References

- Apache Iceberg documentation: https://iceberg.apache.org/docs/latest/
- Apache Iceberg overview: https://iceberg.apache.org/
- Apache Iceberg multi-engine support: https://iceberg.apache.org/multi-engine-support/
- Apache Iceberg Spark quickstart: https://iceberg.apache.org/spark-quickstart/
- Apache Iceberg Flink integration: https://iceberg.apache.org/docs/latest/flink/
- Apache Airflow: https://airflow.apache.org/
- Apache Atlas: https://atlas.apache.org/
- Firebolt external data and Iceberg: https://docs.firebolt.io/performance-and-observability/iceberg-and-external-data
