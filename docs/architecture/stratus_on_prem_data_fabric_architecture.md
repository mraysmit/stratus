# On-Prem Data Fabric Architecture

## 1. Executive Summary

This document defines a pragmatic on-prem data fabric architecture built around **Ceph RGW object storage**, **Apache Iceberg**, **Apache Spark**, **Apache Flink**, a **central REST-oriented Iceberg catalog**, **Apache Atlas**, **Apache Ranger**, **Apache Airflow**, **Trino**, and an optional **Kafka-backed event backbone** plus optional **Firebolt Core** serving layer.

The design goal is not to assemble a random list of fashionable tools. The goal is to create a governed, scalable, batch-and-streaming-capable platform that supports:

- enterprise data ingestion
- streaming and batch processing
- open table semantics
- strong metadata and lineage
- low-latency SQL serving for curated data products
- on-prem deployment and control

The recommended architectural position is:

- **Ceph RGW object storage** is the persistence layer
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
Ceph RGW provides S3-compatible object storage for files. It is **not** the semantic contract for consumers. The semantic contract is the **Iceberg table**.

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

### 2.7 Platform behavior must be verified by contracts
Every platform layer should expose a small set of explicit contracts that can be verified automatically: storage buckets and policies, catalog namespaces, table schemas, Spark job outcomes, Airflow DAG behavior, quality gate decisions, query results, lineage publication, and access control. The platform is not considered healthy because services are running; it is healthy when those contracts pass against the live stack.

### 2.7.1 Two implementation profiles

Every phase and increment is implemented through two explicit tracks. They share service contracts, schemas, protocols, verifier artifacts, and functional tests, but they do not claim the same operational evidence.

| Track | Purpose | Permitted simplifications | Required evidence |
|---|---|---|---|
| Developer profile | fast, repeatable engineering on a workstation or small integration host | Docker Desktop or Podman, reduced replicas, local bind mounts or named volumes, local CA certificates, loopback/private-network HTTP where explicitly permitted, and disposable state | idempotent startup/shutdown, health checks, functional contract tests, pinned artifacts, and a documented reset path |
| Production profile | durable on-prem service for governed datasets | only exceptions recorded with an owner, risk, expiry or retest trigger, and approved remediation | supported distributed topology, durable shared state, trusted TLS, managed service identities and secrets, authorization, observability, backup/restore, failure drills, capacity evidence, and operational ownership |

A developer-profile gate proves that engineers can build and exercise the capability. It never proves production readiness. Production promotion must replace every developer-only shortcut and rerun the shared functional suite plus the production security, durability, recovery, and operations tests. An increment that cannot identify the replacement for a shortcut is incomplete.

The version policy applies to both tracks: select the latest compatible supported release, pin the exact patch and artifact digest, and retest the compatibility set together. An older pin requires a written compatibility reason, owner, test evidence, and upgrade trigger; release age alone is not a reason to destabilize a validated integration.

---

### 2.8 Storage architecture decision

The storage platform decision belongs at the architecture level because every downstream increment depends on it. Increment 1 implements the selected storage target; it does not own the architecture tradeoff.

The storage decision is not approved merely because a product is named. Qualification is staged so that the architecture decision does not depend on components that have not yet been delivered:

- **candidate admission before Increment 1** uses documented requirements, release and deployment review, operational fit, known compatibility evidence, and explicit proof targets
- **Increment 1 acceptance** proves the deployed storage service independently through S3 protocol, security, health, failure, synthetic load, capacity, and operational tests
- **cross-increment qualification** proves each real engine when that engine is introduced
- **Phase 1 readiness** proves the complete concurrent engine workload and production operating model

The decision process is:

1. Confirm the Stratus storage requirements.
2. Screen plausible open-source, on-prem storage candidates.
3. Compare the viable candidates against the requirements.
4. Define the staged proof-of-fit targets and admit one candidate into Increment 1.
5. Record any gaps, mitigations, and disqualifying risks.
6. Only then proceed into the Increment 1 implementation runbook.

This section exists because "S3-compatible" is not specific enough for a production data-fabric storage decision. Stratus needs a storage platform that works with Iceberg, Polaris, Spark, Airflow, Trino, and future Flink workloads, and that can be operated on premises with a real production recovery model.

#### 2.8.1 Stratus storage requirements

| Requirement | Why it matters | Acceptance evidence |
|---|---|---|
| Open-source on-prem deployment | Stratus must not depend on a proprietary managed object store for the foundation layer. | License and deployment model recorded for the selected release. |
| Production distributed storage | The storage layer must survive node/disk failure and support capacity growth. | HA topology, failure-domain design, and recovery drills documented. |
| S3-compatible client contract | Polaris, Iceberg, Spark, Trino, Airflow, and Java verification use an S3-style object API. | Required S3 operations pass through the verification suite. |
| Iceberg table safety | Iceberg writes metadata and data files and relies on predictable read-after-write/list behavior. | Iceberg create/write/read/snapshot/maintenance tests pass through Polaris and Spark. |
| Multipart upload behavior | Iceberg and Spark may use multipart upload for larger parquet files. | Multipart create, complete, abort, list parts, and retry behavior verified. |
| Concurrent engine access | Spark, Trino, Polaris, and Airflow can all touch the storage layer during normal operation. | Concurrent read/write/list tests run without throttling, stale reads, or authorization leakage. |
| Metadata-heavy Iceberg behavior | Iceberg creates many metadata files, manifests, and object listings as tables grow. | Object count, listing latency, bucket-index behavior, and metadata file growth are tested with representative table counts. |
| Large scan and ingestion throughput | Storage must sustain initial batch ingestion and analytical scans without becoming the first bottleneck. | Baseline throughput, request latency, request error rate, and retry rate are recorded under Spark and Trino smoke loads. |
| Path-style endpoint support | Internal DNS and lab deployments often use endpoint overrides rather than virtual-hosted buckets. | Spark, Trino, Java SDK, and Polaris configs work with `S3_PATH_STYLE_ACCESS=true`. |
| Service identity isolation | Platform services must not share one storage credential. | `svc-spark`, `svc-polaris`, `svc-airflow`, and `svc-trino` access tests pass and cross-bucket denies are proven. |
| TLS and CA trust | Production traffic must not rely on insecure TLS bypass. | HTTPS endpoint works with trusted CA; plaintext and untrusted connections fail. |
| Encryption-at-rest path | Gold/platform data require an approved at-rest protection model. | Storage encryption design is selected and tested for the chosen target. |
| Operational observability | Operators need health, capacity, request/error, and recovery visibility. | Dashboard/CLI/metrics checks are part of acceptance. |
| Backup, recovery, and failure drills | Production readiness requires proof that loss and recovery procedures work. | Disk/node/gateway failure drills and restore tests complete successfully. |
| Upgrade and lifecycle model | The storage platform will need patching and upgrades after Phase 1. | Release pinning, upgrade path, and rollback constraints are documented. |
| Cost and operating model | On-prem storage shifts cost into hardware, power, capacity planning, and operator effort. | Capacity model, growth assumptions, replication/erasure-coding overhead, and operational ownership are recorded before production onboarding. |
| Governance integration path | Increment 6 adds Ranger/Atlas; storage must not block identity and policy integration. | Authz boundary is defined: storage service credentials at layer 1; analytical user policy through Polaris/Trino/Ranger later. |

#### 2.8.1.1 Phase 1 integrated storage qualification evidence

The storage decision must define measurable proof targets, not only product features. The following matrix is accumulated across Phase 1 as the owning engines become available; it is not the Increment 1 exit gate and is not a prerequisite for starting Increment 2. Exact numeric targets are environment-specific, but each run must record the target, observed result, error budget, test dataset size, object count, concurrency level, hardware profile, and operator effort.

| Evidence area | Required workload | Required metrics | Minimum acceptance rule |
|---|---|---|---|
| Concurrent engine access | Spark writes Iceberg data, Trino reads an existing table, Polaris resolves namespaces/tables, and an operator S3 client lists representative prefixes at the same time. | p50/p95/p99 request latency, 4xx/5xx rate, retry rate, stale-read incidents, authz failures, failed Iceberg commits. | No stale reads, no authorization leakage, no failed committed writes, and request/error metrics remain within the pre-declared smoke-test threshold. |
| Large scan/read throughput | Trino scans a scaled gold table while Spark reads the same table or a representative silver table. | Sustained read throughput, query elapsed time, request latency, retry rate, RGW/S3 gateway CPU/network saturation. | Throughput baseline is recorded and the object store is not the first saturated component unless explicitly accepted with a capacity plan. |
| Ingestion/write throughput | Spark writes a scaled bronze/silver dataset using the same S3 client settings intended for production. | Sustained write throughput, multipart upload success/abort behavior, commit duration, request error rate, retry count. | Writes complete without multipart leaks, failed commits, or elevated retry/error rates beyond the declared threshold. |
| Metadata-heavy listing behavior | Create representative Iceberg table layouts with many snapshots, manifests, partition prefixes, and metadata files. | List latency by prefix, object count, bucket-index health, metadata-file growth, manifest count, snapshot chain length. | Listing and metadata resolution remain predictable enough for maintenance and query planning; any degraded behavior has a documented object-count threshold and mitigation. |
| Small-file/object-count stress | Generate small-file debt, then run compaction and orphan cleanup through Iceberg maintenance. | Object count before/after, average file size, compaction duration, orphan count, delete-file count, request latency/error rate during maintenance. | Maintenance reduces file-count debt and does not destabilize concurrent reads or catalog operations. |
| Request latency and error budget | Run mixed read/write/list/head/delete/multipart operations through the selected S3 endpoint. | p50/p95/p99 latency by operation, 4xx/5xx rate, timeout rate, retry rate. | The owning gate must meet its declared smoke-test SLO or produce an accepted remediation and retest plan. |
| Cost and capacity model | Model usable capacity for the selected replication/erasure-coding profile and expected growth. | Raw-to-usable ratio, metadata overhead, bucket-index overhead, projected 12/24/36-month capacity, power/rack assumptions where known. | Capacity model shows usable headroom for onboarding and defines expansion triggers before production data is accepted. |
| Operator effort | Execute install, upgrade rehearsal, failure drill, restore drill, credential rotation, and dashboard/alert setup. | Operator steps, elapsed operator time, specialist skills, automation gaps, runbook defects. | Operational burden is recorded and accepted by the owning operations team, including any staffing or automation gaps. |

#### 2.8.2 Candidate screen

The initial open-source on-prem candidate set is:

| Candidate | Advanced to detailed fit? | Reason |
|---|---|---|
| Ceph RGW | Yes | Mature open-source distributed storage platform with S3-compatible RGW, replication/erasure coding, dashboard, health model, and broad object-store operations. |
| Apache Ozone | Yes | Strong open-source on-prem object-storage candidate with Ozone Manager/SCM/Datanodes, S3 Gateway, Kerberos/Ranger integration options, Recon, replication, and erasure coding. |
| OpenStack Swift | No for Phase 1 baseline | Mature object store, but its native API and operational ecosystem are less directly aligned to the Iceberg/Spark/Trino S3 client contract chosen for Stratus Phase 1. It can be revisited if Swift is already an enterprise standard. |
| SeaweedFS | No for production baseline | Useful lightweight distributed file/object system, but not selected as the primary governed lakehouse storage substrate without deeper evidence for Iceberg/Polaris/Spark/Trino production behavior, security, and operations. |
| DAOS | No for Phase 1 baseline | Strong HPC-oriented object storage, but the Stratus Phase 1 contract is S3 lakehouse compatibility and general on-prem platform operations, not a specialized HPC storage interface. |

Only Ceph RGW and Apache Ozone advance to detailed comparison because both plausibly satisfy the Stratus requirement for open-source, production-capable, on-prem object storage with an S3 access path.

#### 2.8.3 Ceph RGW vs Apache Ozone requirements fit

Scoring scale:

- `5` = strong fit with low validation risk
- `4` = good fit with normal release/configuration validation required
- `3` = viable, but material proof-of-fit required before approval
- `2` = weak fit or requires an architectural change
- `1` = poor fit for the stated requirement
- `0` = does not satisfy the requirement

| Requirement | Ceph RGW fit | Ceph score | Apache Ozone fit | Ozone score | Decision implication |
|---|---|---:|---|---:|---|
| On-prem open-source production storage | Strong. Ceph is a production distributed storage platform with MON/MGR/OSD/RGW services. | 5 | Strong. Ozone is an Apache distributed object store with OM/SCM/Datanodes and production deployment patterns. | 5 | Both remain viable. |
| S3 API coverage for lakehouse engines | Strong baseline. Ceph documents S3-compatible object access and support for core bucket/object/multipart operations. Must still test the exact release. | 5 | Viable but must be proven. Ozone S3 Gateway provides S3 access, but the design must verify the subset needed by Iceberg/Polaris/Spark/Trino. | 3 | Ceph has the lower S3-compatibility risk for the selected client contract. |
| Iceberg/Spark/Trino fit | Strong if S3FileIO, Spark S3 client, and Trino endpoint/path-style settings pass against RGW. | 4 | Viable if the selected Ozone S3 Gateway release passes the same Iceberg/Spark/Trino endpoint and path-style tests. | 3 | Ceph has the lower assumed compatibility risk, but both must be proven. |
| Kerberos/Ranger alignment | Possible through adjacent identity/policy integrations, but not the native center of the object-store contract. | 3 | Stronger integration options if Stratus later requires storage-layer Ranger/Kerberos enforcement. | 4 | Ozone gains weight only if storage-layer policy enforcement becomes an explicit requirement. |
| Analytical user authorization model | Storage layer uses service credentials; analytical user policy is enforced later through Polaris/Trino/Ranger. | 4 | Can support stronger storage-layer authorization, but that is not required by the current Phase 1 storage contract. | 4 | Both are acceptable if user-facing authorization stays above storage. |
| Operational model | Strong but complex. Ceph has mature health, dashboard, metrics, CRUSH placement, recovery, and cephadm lifecycle management. | 4 | Strong but also complex. Ozone has OM/SCM HA, Recon, Datanodes, security, and object-store operational patterns. | 4 | Team skill set matters; both require real operators. |
| Performance evidence path | Strong tooling and metrics path through RGW, Ceph Dashboard, Prometheus, and client-side S3 measurements; still must be measured on target hardware. | 4 | Viable through Ozone Recon, S3 Gateway metrics, and client-side S3 measurements; proof-of-fit must validate the selected gateway release. | 3 | Ceph has the lower measurement-risk baseline, but neither candidate is approved without workload evidence. |
| Metadata and small-object behavior | Mature RGW/bucket-index behavior, but Iceberg metadata and small-file stress must be measured explicitly. | 4 | Viable, but S3 Gateway list behavior and metadata-heavy Iceberg layouts need stronger proof before approval. | 3 | Synthetic behavior is proved in Increment 1; real Iceberg behavior is a required Increment 2/3 and readiness gate, not optional later tuning. |
| Cost/capacity and operator effort | Strong capacity and failure-domain modeling through Ceph pool/CRUSH design; operator skill requirement is material. | 4 | Strong storage-specific model with its own operational complexity; operator skill requirement is material. | 4 | Both require an accepted cost/capacity model and named operating team. |
| Failure-domain and recovery controls | Strong. CRUSH, pools, OSD health, backfill/recovery behavior are central Ceph concepts. | 5 | Strong. Ozone has replication/erasure coding and service HA patterns. | 4 | Both viable; prove via drills. |
| Encryption and key management | Supported through Ceph/RGW encryption options and storage-layer controls; exact mode must be release-validated. | 4 | Supported through Ozone/KMS/transparent data encryption path; exact mode must be release-validated. | 4 | Both viable; neither should be assumed from AWS S3 semantics. |
| Future multi-protocol storage | Strong if Stratus later needs object plus block/file from one storage substrate. | 5 | Strong for object storage; less relevant if Stratus later needs block/file from the same storage substrate. | 3 | Ceph has broader infrastructure-storage optionality. |
| **Total** |  | **51 / 60** |  | **44 / 60** | Ceph scores higher under the current Stratus requirements because the comparison scores stated storage, S3 compatibility, security, operations, recovery, performance evidence, metadata behavior, and cost/operator needs. |

The score is not the decision by itself. Both Ceph RGW and Apache Ozone expose S3-compatible APIs, so "S3-compatible" alone does not decide this. Under the current Stratus requirements, the tradeoff is compatibility risk and operating model: Ceph RGW is the selected lower-risk S3 API target for the Iceberg/Spark/Trino/Polaris path. Ozone remains technically plausible but is superseded unless a recorded reconsideration trigger opens a new architecture decision.

#### 2.8.4 Decision due diligence before Increment 1

Ceph RGW is the selected Phase 1 implementation baseline. Before implementation starts, the team records the following decision due-diligence evidence:

- Official release and deployment method selected and pinned.
- release documentation shows the required S3 operations and deployment topology are supported or identifies the exact items Increment 1 must prove
- the target Linux, container runtime, node, disk, network, failure-domain, TLS, monitoring, recovery, and upgrade assumptions are documented
- a preliminary capacity and operator-ownership model is accepted
- the required service-identity boundaries and active Ceph client contract (`CEPH_RGW_ENDPOINT`, scoped RGW credentials, and `S3_PATH_STYLE_ACCESS`) are defined
- measurable Increment 1 and later cross-increment proof targets are recorded with owners and owning gates
- Apache Ozone is retained only as a superseded alternative with explicit reconsideration triggers.

Increment 1 determines whether the selected Ceph implementation passes its engineering gate. A failed storage-only gate stops Increment 2 and triggers remediation or a new architecture decision record. A later engine-specific failure reopens Ceph qualification but does not create a circular prerequisite for Increment 1.

#### 2.8.5 Decision summary

Ceph RGW is the selected Phase 1 baseline because Stratus depends on the exact S3-compatible behavior required by Iceberg, Spark, Trino, Polaris, Airflow, and the Java verification suite. The selection does not waive proof: the developer profile must pass the client contract quickly, and the production profile must additionally pass distributed-storage, failure-domain, recovery, capacity, security, and operations gates.

Apache Ozone remains a documented superseded alternative. It is reconsidered only through a new architecture decision if requirements shift toward storage-layer Ranger/Kerberos enforcement or Ozone's volume/bucket namespace. That change would require revising the storage contract and the downstream Increment 2/3/5 engine configuration, not merely swapping an endpoint URL.

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
                          │      Ceph RGW Object Storage  │
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
  │ Apache Atlas — metadata, lineage, glossary; backing services vary by profile│
  │ Apache Ranger — policy enforcement, classification-driven access control     │
  │ Airflow — orchestration, scheduling, promotion gates, maintenance            │
  │ FreeIPA — Kerberos, LDAP, PKI          Keycloak — OIDC for REST services   │
  └──────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Core Components and Responsibilities

### 4.1 Ceph RGW Object Storage

### Role
Ceph RGW is the durable persistence substrate. It exposes the Ceph storage cluster through an S3-compatible API and stores:

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

### Bucket and object layout
The foundation uses a small number of platform buckets rather than one bucket per dataset:

| Bucket | Purpose |
|---|---|
| `stratus-landing` | raw source files and bounded external extracts before table ingestion |
| `stratus-bronze` | bronze Iceberg data and metadata |
| `stratus-silver` | silver Iceberg data and metadata |
| `stratus-gold` | gold Iceberg data and metadata |
| `stratus-platform` | platform-internal data such as quality results, Spark event logs, audit extracts, and maintenance metadata |

Landing-zone object keys should be predictable and source-oriented:

```text
s3://stratus-landing/<source-system>/<dataset>/<ingest-date>/<file-name>
```

For example:

```text
s3://stratus-landing/crm/customers/2026-07-04/customers.csv
```

Landing files are transient inputs to governed table creation. Retention should be long enough to support replay and audit, but consumers should not query landing files directly.

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
Kafka should not be treated as automatically mandatory just because Flink exists in the architecture. The **shared platform event backbone** becomes a Phase 2 core component when the platform needs durable event retention, independent consumers, replay, back-pressure absorption, or CDC at meaningful scale. Phase 1 does not deliver those streaming capabilities. A small external Kafka service brought forward solely as Atlas's supported notification dependency remains part of the Atlas production topology, not a completed Kafka platform increment.

Kafka is delivered by Increment 8. Kafka Connect and Debezium are delivered by Increment 9 and are required before the CDC path is accepted; they are separate increments so the event backbone can be secured and proven first.

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

Atlas requires graph, search, and notification services. Stratus uses the minimal topology only for developer work and requires supported external dependencies before Atlas is accepted for production:

| Dependency | Developer profile | Production profile |
|---|---|---|
| Graph store | embedded/disposable backend supplied by the approved Atlas development distribution | external supported HBase topology with durable storage, backup, monitoring, and tested recovery |
| Search index | embedded/disposable Solr | external SolrCloud with ZooKeeper, persistent collections, backup, monitoring, and tested recovery |
| Notification bus | embedded notifier for local functional testing only | external supported Kafka notification service; production may bring the Phase 2 Kafka backbone forward as a dependency without enabling CDC/Flink, or use an Atlas-dedicated cluster that Increment 12 later consolidates |

The developer profile may run Atlas as one disposable service. It is not eligible for the Phase 1 production-readiness gate. Production acceptance requires the external dependency topology, two Atlas application instances or an approved availability exception, encrypted service protocols, and restore evidence for graph, search, types, glossary, and classifications. If the production notification service is deferred, Atlas production acceptance and governed production dataset onboarding are also deferred; the lab may continue.

The production dependency choice is validated against the selected Atlas release documentation. Embedded stores are convenience tooling, not a small-production sizing tier.

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
- keep transformation, quality, and maintenance logic in versioned Spark jobs or platform libraries
- make promotion gates explicit tasks that fail closed
- include a stable `run_id` in every DAG run, Spark job, quality record, and lineage event
- treat DAG import errors as platform incidents, not harmless UI noise

### Deployment posture
The initial deployment uses Airflow 3.x with one API server, one DAG processor, one scheduler, one triggerer, PostgreSQL for metadata, and `LocalExecutor`. That is sufficient for the first governed batch workflows and keeps the operational surface small. If task concurrency, isolation, or worker placement becomes a real constraint, move to CeleryExecutor or KubernetesExecutor as a deliberate scaling step.

Airflow's metadata database is part of the control plane and must be backed up. Losing it means losing run history, task state, retry state, and operational audit context.

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

### Design rules
- Trino must use the central Apache Polaris REST catalog for Iceberg table discovery.
- Trino must query Iceberg tables, not raw object-storage paths.
- Trino is the shared read/query plane, not the primary ETL engine for bronze-to-silver or silver-to-gold processing.
- Trino query validation should compare row counts, aggregates, schemas, and quality-result visibility against Spark-produced outputs.
- Trino access should initially be constrained to internal platform validation, then integrated with Ranger and Keycloak/FreeIPA as the governance and identity increments land.
- Trino must expose `platform.quality_check_results` so operators and analysts can inspect quality outcomes without Spark access.

### Query contract
Increment 5 should prove that Trino can answer the same business questions as Spark over the same Iceberg snapshots. The minimum query contract is:

| Query class | Expected behavior |
|---|---|
| Discovery | `SHOW SCHEMAS` and `SHOW TABLES` expose Polaris namespaces and tables |
| Bronze validation | raw row counts match Spark ingestion output |
| Silver validation | deduplicated row counts and schema match Spark transform output |
| Gold validation | aggregate results match Spark materialisation output |
| Quality visibility | `stratus.platform.quality_check_results` is queryable |
| Error behavior | invalid columns and missing tables fail with clear SQL errors |
| Cross-zone query | joins across bronze, silver, and gold work where policy permits |

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
platform.quality_check_results
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

The Airflow gate task queries `platform.quality_check_results` for the current `run_id`, asserts that all blocking checks have status `passed`, and only then triggers the downstream promotion or materialisation task.

### Override model

A failed blocking check halts the pipeline. Manual override requires:
- an explicit Airflow task approval by a named data steward or platform operator
- an override reason recorded in the pipeline run metadata
- the override event written to `platform.quality_check_results` as a separate audit record with status `overridden`

Overrides are auditable. They do not silently suppress the failure record.

---

### 5.5 Atlas metadata integration

On completion of each check run, the pipeline publishes quality status back to Atlas for the target dataset:

- `quality_status`: `passed` / `failed` / `warning`
- `quality_last_checked`: timestamp of the most recent check run
- `quality_run_id`: link to the check run in `platform.quality_check_results`
- `quality_blocking_failures`: count of blocking check failures in the last run

This means Atlas dataset entries reflect current quality state and consumers can discover whether a dataset is in a passing or failing quality condition without querying the results table directly.

Quality status publication to Atlas is a mandatory step in every pipeline's completion contract, not an optional integration.

---

### 5.6 Access control integration

Ranger policies can be applied to restrict read access to datasets in a failing quality state where the sensitivity of the data and the nature of the failure warrant it. This is a platform-level policy decision, not automatic — classification-driven Ranger rules should be applied deliberately for sensitive zones and datasets.

---

### 5.7 Ownership

- **Platform team** owns the `platform.quality_check_results` table, its schema, retention policy, and Iceberg maintenance schedule
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

### Dataset naming convention
Dataset names should be stable, lowercase, and domain-oriented:

```text
<catalog>.<zone>.<domain>_<dataset>
```

Examples:

```text
stratus.bronze.crm_customers
stratus.silver.crm_customers
stratus.gold.sales_customer_summary
```

Small verification tables may use simple names such as `verification_customers`, but production datasets should include a domain prefix. Naming should make ownership and lifecycle obvious without encoding sensitive classifications into names.

### Schema and partition governance
Schema evolution is allowed, but it must be governed:
- additive nullable columns are normally safe
- type widening must be reviewed
- dropping or renaming columns requires compatibility analysis and consumer notification
- partition evolution must be justified by query patterns, not guessed up front
- every table should have an owner-approved retention and maintenance policy before production use

Hidden partitioning should be preferred over exposing physical partition assumptions to consumers. Consumers should query tables, not construct paths.

---

## 7. Control Planes

A clean design separates three planes.

### 7.1 Data Plane
Contains:
- Ceph RGW object storage
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
- filesystem catalog — file-path-based metadata, no multi-engine coordination, not suitable for a shared platform
- Hive catalog — legacy coupling to Hive Metastore, limits engine flexibility
- REST catalog — open API standard, engine-agnostic, fits multi-engine design
- JDBC catalog — simple but operationally fragile at scale; lacks REST API compatibility
- Nessie — REST-compatible with Git-like branching semantics; strong fit for data-environment workflows
- **Apache Polaris** — Apache-incubated open source REST catalog server; implements the Iceberg REST Catalog spec natively

Iceberg’s Flink docs explicitly call out catalog configuration options such as `hive`, `rest`, `jdbc`, and other implementation-specific catalog types:
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
- **Filesystem catalog**: no shared multi-engine coordination, not suitable for a governed platform
- **Hive Metastore**: legacy dependency, limits engine flexibility, adds operational complexity
- **JDBC catalog**: operationally fragile at scale, lacks REST API compatibility, no access control model
- **Nessie**: strong Git-like branching semantics are valuable but add operational complexity that is not required in Phase 1; remains the preferred alternative if data-environment branching becomes a platform requirement

### Alternative path
If the platform later needs Git-like branching, tagging, and data-environment workflows at the catalog layer — for example, isolated branch environments for engineering, staging, and production data — **Nessie** is the next candidate to evaluate. That should be treated as a deliberate platform decision, not an incremental implementation detail.

### Design rule
Spark, Flink, Trino, and all maintenance workflows must be configured to use the central Apache Polaris REST catalog. Engine-local catalog configurations that bypass Polaris are not permitted in governed environments.

### Production catalog requirements
The catalog is not just a lookup service. It is the control point for table namespace, current metadata location, transactional commits, and engine coordination. Production readiness therefore requires evidence for:

- catalog availability and latency under concurrent Spark, Trino, and maintenance access
- an approved external Polaris metadata store with backup, restore, and ownership defined
- auditability for namespace, table, credential, and metadata-location changes
- identity integration, scoped service principals, and a documented credential model
- restore drills that recover catalog state, Iceberg metadata files, manifests, and object data consistently
- compatibility checks for every engine that reads or writes governed tables

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

Airflow DAGs should be structured around platform outcomes, not individual shell commands. A typical DAG should read as:

```text
detect input → run Spark job → run quality checks → evaluate promotion → publish metadata → notify
```

Each task should emit structured log fields:

| Field | Purpose |
|---|---|
| `run_id` | stable pipeline run identifier shared across Airflow, Spark, quality, and lineage |
| `dataset` | fully qualified Iceberg table name |
| `zone` | bronze / silver / gold / platform |
| `spark_application_id` | Spark application id for debugging |
| `quality_status` | passed / failed / warning / overridden |
| `promotion_decision` | promote / block / overridden |

### 10.1.1 DAG ownership and source control
All DAGs should be version-controlled with the application code and reviewed like production code. DAG owners must be named explicitly. A DAG without a clear owner, retry policy, alert route, and expected runtime should not be promoted to production.

### 10.1.2 Retry and backfill semantics
Retries are for transient infrastructure or source availability failures. They must not hide deterministic data failures such as schema incompatibility, failed quality checks, or authorization denial.

Backfills must be idempotent. Re-running a DAG for a historical date should either produce the same target table state or create a clearly versioned replacement snapshot. Backfills should not silently append duplicate business records.

### 10.2 Flink should execute continuously
- streaming jobs remain long-running where appropriate
- operational lifecycle for Flink jobs should be separate from normal Airflow-style task semantics

### 10.3 Table maintenance
Iceberg maintenance is not optional.

The platform must schedule:
- snapshot expiration — target cadence: daily for active tables, weekly for archival
- compaction / file rewrite — target: keep file counts within 2x the optimal range for each table's typical query pattern
- orphan file cleanup — run at least weekly; alert if orphan volume exceeds a configurable threshold
- metadata health checks — verify manifest list depth, metadata.json file count, delete-file count, and manifest growth; compact metadata when thresholds are breached

Maintenance must be metadata-driven. Jobs should inspect Iceberg metadata tables such as `files`, `snapshots`, `manifests`, and `history` before deciding whether to compact, expire snapshots, or raise an alert. Fixed schedules are acceptable as triggers, but the action taken should depend on table-specific policy and current table state.

Each governed table must have a maintenance policy before production onboarding. The policy must define at least:

| Policy field | Purpose |
|---|---|
| table owner and maintenance owner | identifies who approves thresholds and who responds to alerts |
| target file size and minimum average file size | drives rewrite/compaction decisions from the `files` metadata table |
| maximum file count and small-file count | prevents unbounded small-file growth and planning overhead |
| maximum snapshot-chain length and snapshot retention | drives snapshot expiry from the `snapshots` and `history` metadata tables |
| maximum manifest count or manifest growth rate | drives manifest rewrite or alerting from the `manifests` metadata table |
| maximum delete-file count | triggers delete-file cleanup or rewrite for tables with row-level deletes |
| orphan-file threshold and cleanup cadence | controls cleanup and alerting for unreferenced objects |
| alert severity and allowed maintenance window | prevents disruptive maintenance during critical serving windows |

**Ownership**: The platform / infrastructure team owns the maintenance jobs and their scheduling. Domain teams do not run ad-hoc maintenance. Compaction ownership must be explicitly assigned per table (normally the platform team); no table should be left without a designated maintenance owner.

**Targets are starting points.** Adjust cadences based on observed table activity and query performance. The key discipline is that every table has a maintenance schedule and an owner — not the specific numbers.

If this is neglected, performance and reliability will degrade over time.

### 10.4 Data quality and promotion
Quality check execution, result storage, promotion gates, override handling, and ownership are fully specified in **§5 Data Quality Subsystem**.

### 10.5 Control-plane state
The following are control-plane state and must be protected by backup, restore, and change-control procedures:

| Component | State to protect |
|---|---|
| Airflow | metadata database, DAG code, connection definitions, variables, task logs |
| Polaris | catalog metadata store and principal/role configuration |
| Atlas | graph store, search index, type definitions, glossary, classifications |
| Ranger | policies, tag policies, audit configuration, usersync configuration |
| FreeIPA | Kerberos principals, LDAP users/groups, DNS, PKI material |
| Keycloak | realms, clients, identity mappings, token configuration |

If control-plane state is not backed up, the platform is not recoverable even if the data files in object storage survive.

---

## 11. Reference End-to-End Flow

### 11.1 Batch flow
1. source files arrive in the object-storage landing zone
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
- Ceph RGW object store backed by a Ceph cluster
- erasure coding / replication depending on product choice
- separate buckets or namespaces by domain and lifecycle zone

### 12.2 Compute tier
- Spark cluster for batch compute
- Flink cluster for stateful stream processing
- Firebolt Core nodes if deployed

### 12.3 Control tier
- Airflow API server, DAG processor, scheduler, triggerer, local executor, and metadata DB (PostgreSQL)
- Apache Atlas developer profile with disposable embedded dependencies; production profile with external HBase, SolrCloud/ZooKeeper, and an external supported notification service
- FreeIPA identity services (Kerberos KDC, LDAP, DNS, PKI)
- Keycloak OIDC broker
- Apache Ranger admin server and usersync service
- monitoring and logging stack

### 12.3.1 Control-tier availability posture
Phase 1 may run single instances of Polaris, Atlas, Ranger, Airflow, and Keycloak for lab and early platform validation. Production must define recovery objectives for each control-plane service before onboarding critical datasets.

Minimum production posture should include:
- PostgreSQL backup and restore testing for Airflow and any catalog or governance metadata stores that use PostgreSQL
- exported Keycloak realm configuration and FreeIPA backup procedures
- documented rebuild path for every Podman image and systemd unit
- persistent volumes outside container writable layers
- restore drills before sensitive or business-critical datasets are onboarded

### 12.4 Platform services
- Apache Polaris REST catalog service
- FreeIPA identity services (Kerberos KDC, LDAP, DNS, PKI)
- Keycloak OIDC broker
- secrets management
- `platform.quality_check_results` Iceberg table (owned by platform team)
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
| Spark jobs | approved service identity; Kerberos keytab where the selected runtime integration uses Kerberos |
| Flink jobs | future service identity; Kerberos keytab where the selected runtime integration uses Kerberos |
| Airflow workers | approved service identity for job submission |
| Apache Polaris | OIDC token via Keycloak where supported by the selected Polaris release |
| Trino | HTTPS and OIDC via Keycloak for client access; internal trust via the selected Trino secure-communication model |
| Atlas | LDAP via FreeIPA for user authentication |
| Ranger | LDAP via FreeIPA for user/group sync; HTTPS policy download to engine plugins |
| Ceph RGW | service accounts with access/secret key pairs; TLS enforced |
| Airflow web UI | OIDC via Keycloak |

### 13.2.1 Service and protocol interaction model

Security in Stratus is not one mechanism. Each protocol has a specific job:

| Protocol | Platform role |
|---|---|
| DNS | stable service identity, certificate SAN alignment, and Kerberos host canonicalization |
| Kerberos | Linux and service principal authentication where ticket-based service identity is implemented |
| LDAP / LDAPS | FreeIPA user and group lookup for Keycloak, Ranger usersync, Atlas, and SSSD |
| OIDC / OAuth2 | browser, CLI, and REST-facing service authentication through Keycloak |
| HTTPS / TLS | transport encryption and server identity for every platform endpoint |
| S3 API over HTTPS | object access to Ceph RGW for Spark, Trino, Polaris, Airflow, and verification tools |
| Iceberg REST catalog over HTTPS | table and namespace resolution through Polaris |
| Ranger policy REST | policy download from Ranger Admin to the Trino Ranger plugin |
| JDBC over HTTPS | analyst, BI, and verification access through Trino |

The core identity chain is:

```text
FreeIPA users/groups
      ├── LDAPS ──► Keycloak ──OIDC tokens──► Trino / Airflow / Polaris where supported
      ├── LDAPS ──► Ranger usersync ──policies──► Trino Ranger plugin
      ├── LDAPS ──► Atlas authentication
      └── SSSD/Kerberos ──► Linux host and service identities
```

The core data access chain is:

```text
User / BI tool ──OIDC/JDBC──► Trino
Trino ──Ranger policy check──► Ranger
Trino ──Iceberg REST──► Polaris
Trino ──S3 API──► Ceph RGW
```

Ranger governs what an authenticated user can query through Trino. Polaris governs catalog, namespace, and table metadata resolution. Ceph RGW governs service-account object access. These layers are complementary and must not be collapsed into a single bucket policy or a single OIDC login check.

### Service account model
Every pipeline component runs as a named Linux service account registered in FreeIPA. No shared credentials. No human credentials used for job execution. Keytabs are managed centrally and rotated on a defined schedule.

### Secrets handling
Service credentials must not be committed to source control or embedded directly in DAG code, Spark code, Dockerfiles, or documentation examples used as live configuration. Early increments may use environment files on secured hosts, but the target model is:

- credentials stored in a dedicated secrets manager or equivalent secured platform service
- Airflow reads credentials through a secrets backend or protected connections
- Spark and Flink jobs receive short-lived or centrally rotated service credentials
- Ceph RGW access keys, Polaris client secrets, Keycloak client secrets, and Kerberos keytabs have named owners and rotation schedules
- every secret has a documented consumer list and emergency rotation procedure

Plaintext environment files are acceptable only as a lab bootstrap mechanism and must be treated as temporary.

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
- **At rest**: use the approved Ceph/RGW encryption-at-rest model for sensitive zones
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

All four are open source (Apache 2.0 / AGPLv3), on-prem deployable, and integrate natively with each other. Prometheus exporters or metrics integrations exist for Spark, Flink, Kafka, Ceph, Airflow, Trino, and the JVM-based services (Atlas, Polaris, Ranger).

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
- file-count explosion and compaction debt (query `platform.quality_check_results` and Iceberg table metrics)
- Kafka consumer lag for Flink source topics (Kafka exporter to Prometheus)
- serving-layer query latency (Trino JMX metrics via Prometheus)
- metadata publication freshness in Atlas
- Ranger audit log volume and policy evaluation latency
- DAG import errors and scheduler heartbeat freshness
- promotion gate block rate and override frequency
- Ceph RGW bucket growth by zone and dataset
- Polaris catalog request latency and authentication failures
- control-plane backup age and last restore-test status

### 14.1.1 Logging contract
Task logs and service logs should be structured enough to correlate a platform run across Airflow, Spark, Polaris, Ceph RGW, and Atlas.

At minimum, logs emitted by pipeline jobs should include:
- `run_id`
- `dag_id` or job name
- source dataset and target dataset
- Iceberg snapshot id after write
- Spark application id where applicable
- quality status and promotion decision
- exception class and failure category on error

Logs should avoid printing secrets, tokens, access keys, or full connection strings.

### 14.2 Reliability disciplines
- explicit retry policies
- backfill procedures
- replay procedures for bronze data
- change management for schema evolution
- controlled promotion between environments
- backup and restore procedures for control-plane metadata
- idempotent job design for scheduled and manually retried workflows
- documented incident response for failed quality gates, stale datasets, and table maintenance failures

### 14.2.1 Recovery expectations
The platform should define recovery expectations separately for data and control-plane state:

| Area | Recovery expectation |
|---|---|
| Ceph data | recover from node or drive loss according to replication or erasure-coding policy |
| Iceberg tables | recover through snapshots, rollback, and retained metadata |
| Airflow | restore metadata DB and DAG code sufficiently to resume scheduling with run history |
| Polaris | restore catalog metadata so table identifiers continue resolving to the same locations |
| Atlas and Ranger | restore lineage, classifications, policies, and audit configuration |
| Identity | restore FreeIPA and Keycloak without changing service identities unexpectedly |

Recovery drills should verify that restored services can run an end-to-end bronze-to-gold workflow, not merely start containers.

### 14.3 Data quality
Quality execution, result storage, promotion gates, and override handling are fully specified in **§5 Data Quality Subsystem**. Operationally, monitor the `platform.quality_check_results` Iceberg table for failure trends, override frequency, and check coverage per domain.

### 14.4 Cross-increment QA traceability
Each implementation increment must leave behind verified platform capability that the next increment consumes. Verification should therefore test both the new component and the inherited contracts from previous increments.

| Increment | Produces | Consumed by | Cross-check required |
|---|---|---|---|
| 1 — Storage | TLS S3 endpoint, five platform buckets, service accounts, bucket policies | Polaris, Spark, Airflow, Trino | bucket existence, path-style S3 access, credential isolation, HTTPS-only access, Ceph health |
| 2 — Iceberg and Polaris | `stratus` catalog, bronze/silver/gold/platform namespaces, `platform.quality_check_results` | Spark, Airflow, Trino, quality subsystem | namespace resolution, table creation, table reads/writes, quality table schema |
| 3 — Spark | ingestion, transform, materialisation, quality, promotion, and maintenance jobs | Airflow orchestration and Trino result validation | Spark can read/write via Polaris and Ceph RGW; quality records are written with run IDs and snapshot IDs |
| 4 — Airflow | scheduled DAGs, retries, alerts, promotion gates, maintenance orchestration | Trino query validation and operational monitoring | DAGs submit Spark jobs, failed blocking checks halt downstream tasks, maintenance runs on schedule |
| 5 — Trino | shared SQL query plane over governed Iceberg tables | analysts, BI, governance validation | SQL results match Spark outputs; Trino resolves tables through Polaris and does not bypass the catalog |
| 6 — Atlas and Ranger | metadata entities, lineage, classifications, policies, and audit | identity hardening and secure operations | Atlas metadata is searchable; Ranger allow/deny behavior works through Trino |
| 7 — FreeIPA and Keycloak | identity, OIDC, Kerberos, LDAP groups, trusted certificates | secure platform operations | earlier increment behavior still passes after replacing lab users, self-signed certificates, and bootstrap credentials |

This traceability prevents each increment from becoming a local installation exercise. For example, Increment 5 should not merely prove that Trino starts. It should prove that Trino can query the exact Iceberg tables produced by Spark, orchestrated by Airflow, registered in Polaris, and stored in Ceph RGW.

### 14.5 End-to-end functional assertions
The platform should maintain a small permanent verification dataset that can be recreated safely in every environment. It should exercise the full batch path:

```text
landing file → Spark ingestion → bronze table → quality checks → Airflow gate
             → silver table → quality checks → Airflow gate
             → gold table → Trino query
```

The following assertions should be stable across increments:

| Assertion | Why it matters |
|---|---|
| landing file is readable only by intended service accounts | validates storage service-account policies from Increment 1 |
| bronze table preserves raw row count, including intentional duplicate | validates ingestion fidelity from Increment 3 |
| quality check records both warning and blocking outcomes | validates the quality result contract from Increment 2 and 3 |
| Airflow blocks promotion on a failed blocking check | validates orchestration behavior from Increment 4 |
| silver table contains deduplicated rows only after the gate permits promotion | validates promotion sequencing and transform correctness |
| gold aggregate matches expected grouped output | validates materialisation correctness |
| Trino returns the same counts and aggregates as Spark | validates Increment 5 as an independent query plane |
| query against a nonexistent column fails clearly | validates schema enforcement and user-facing error behavior |

### 14.6 QA ownership
QA for the platform is not a separate after-the-fact activity. Each platform layer owns its verification contract:

| Area | Owner | Verification mechanism |
|---|---|---|
| Storage | platform infrastructure | S3 SDK tests and Ceph/RGW operational checks |
| Catalog and tables | platform data architecture | Iceberg Java API tests and Polaris API checks |
| Batch compute | data engineering platform | Spark verification jobs and table assertions |
| Orchestration | platform operations | Airflow REST API tests and DAG-state assertions |
| Query | analytics platform | Trino JDBC tests and SQL result checks |
| Governance | data governance | Atlas entity checks and Ranger policy tests |
| Identity | platform security | Kerberos/OIDC authentication and authorization tests |

Every completion gate should have an automated verification suite where practical and an operational checklist for conditions that cannot be fully automated in the lab.

Phase 1 operational acceptance is captured in [stratus_phase1_operational_readiness.md](../operations/stratus_phase1_operational_readiness.md). That document is the closeout gate for Increments 1 through 7: it verifies integrated function, recovery, observability, runbooks, security posture, governance controls, quality gates, and operational ownership before production dataset onboarding or Phase 2 work begins.

### 14.7 Upstream reference audit and version discipline

Reference baseline: 2026-07-10.

The platform depends on fast-moving open source projects. Each implementation increment must start by checking current upstream documentation and release notes for the selected versions, then recording the approved version matrix in the increment runbook. A design document may intentionally pin an older version for compatibility, but that pin must be explicit and verified.

Minimum version matrix to maintain:

| Component | Version discipline |
|---|---|
| Java | use Java 25 LTS for Stratus-owned builds and verifier images; pin the vendor and latest approved Java 25 patch; compile component-bound artifacts with the JDK 25 toolchain using the `--release` target supported by that component runtime |
| Ceph RGW | choose a supported Ceph release and pin all images/packages; do not use `latest` |
| Apache Polaris | pin the catalog release and validate REST catalog behavior against Iceberg clients |
| Apache Iceberg | align Java API, Spark runtime, Flink runtime, and Trino connector expectations |
| Apache Spark | align Spark major version, Scala version, and Iceberg Spark runtime artifact |
| Apache Airflow | standardize on Airflow 3.x and keep API server, DAG processor, scheduler, triggerer, provider packages, and auth-manager behavior aligned |
| Trino | keep coordinator, workers, JDBC driver, Iceberg connector behavior, and Ranger plugin config on the same release line |
| Apache Atlas / Ranger | build pinned platform images from approved Apache releases and record Java/database/plugin compatibility |
| Kafka / Kafka Connect / Debezium | align Kafka broker/KRaft mode, Connect worker version, and Debezium connector series |
| Apache Flink | align Flink major version, Java support, connectors, checkpointing, and Iceberg runtime |
| FreeIPA / Keycloak | use current identity-provider documentation for LDAP/Kerberos/OIDC integration and avoid stale user-guide assumptions |

Current Phase 1 target baseline as of 2026-07-10:

| Component | Target |
|---|---|
| Stratus Java build and verifier baseline | Java 25 LTS, latest approved patch |
| Java build tool | Apache Maven 3.9.16; Maven 4 remains pre-GA and is not the production build baseline |
| OCI runtime baseline | Podman 5.8.2 preferred; Docker Engine 29.5.3 permitted where selected and component-supported; exact package and patch pinned per environment |
| Component runtime exceptions | Spark 4.1 uses its supported Java 17 runtime; Airflow's Spark client uses Java 21; Atlas/Ranger use their selected release's supported runtime; all exceptions are pinned and recorded |
| Apache Polaris | 1.5.0 |
| Apache Iceberg | 1.11.0 |
| Apache Spark | 4.1.2 with Scala 2.13 |
| Apache Airflow | 3.3.0 |
| Airflow Python runtime | Python 3.14, using the matching official image and constraints/provider compatibility tests |
| Airflow metadata database | PostgreSQL 17.10, latest patch in Airflow 3.3.0's newest tested PostgreSQL major |
| Airflow Spark provider | 6.2.0 |
| Airflow Amazon provider | 9.31.0 |
| boto3 | 1.43.40 |
| Trino | 482 |
| Keycloak | 26.6.4 |
| Keycloak metadata database | PostgreSQL 18.4, latest patch in Keycloak's newest supported PostgreSQL major |
| Ceph RGW | Ceph Tentacle 20.2.2, verified as the current Tentacle patch on 2026-07-14; pin by package version or image tag plus digest |
| Apache Atlas | 2.5.0, built as an internal image and pinned by tag plus digest after dependency compatibility review |
| Apache Ranger | 2.8.0, built as an internal image and pinned by tag plus digest after plugin/database compatibility review |
| FreeIPA | approved package stream from the selected Linux distribution, pinned by repository/channel and package version in the environment version matrix |

Spark 4.2.0 is treated as preview and is not the Phase 1 production target until it becomes a stable release and the Iceberg runtime, Airflow Spark provider, Trino connector, and verification suites are updated together.

No implementation increment should be signed off with floating container tags, unverified compatibility assumptions, or examples copied from quickstarts without adapting them to the Stratus security and QA model.

### 14.8 Build, artifact, and runtime separation

Stratus builds software and container images in the approved build system. Runtime and verification environments consume immutable, versioned artifacts; they do not compile source code or resolve build dependencies at execution time.

The following contract applies to every increment:

- application JARs, verifier JARs, Python distributions, plugins, and custom container images are built, tested, scanned, and published by the build system
- every deployed artifact is identified by version plus checksum or image digest and is recorded in the evidence bundle
- runtime and verification containers contain only the runtime, deployed artifact, configuration, certificates, and narrowly scoped credentials required to execute their role
- source trees, Maven or Gradle project directories, compiler toolchains, and writable dependency caches are not mounted into runtime or verification containers
- verification is performed by deploying a pinned verifier container image that contains the prebuilt verifier artifact and compatible runtime, then executing it against the target environment
- verification results are written to a dedicated evidence volume or collected from standard output; the application artifact and runtime filesystem remain read-only where practical
- Dockerfiles and Containerfiles are build inputs executed by the build system, never ad hoc production-host build instructions
- `spark-submit`, Flink job submission, Airflow task execution, and similar commands must reference an already-built, checksummed job artifact
- the standard Stratus build JDK and verifier runtime is Java 25 LTS at the latest approved patch level
- the JDK 25 build toolchain may emit a lower bytecode/API target with `--release` only when a third-party runtime does not support Java 25; the exception, owning component, target release, and removal trigger must be recorded in the version matrix

An increment document may show a build-system command such as `mvn verify` only in a clearly labelled build/publish stage. Its deployment and acceptance steps must execute the published verifier image without invoking Maven, Gradle, a compiler, or a package build. Verifier images use the verifier artifact as their entrypoint, accept configuration through a protected environment file or approved secret injection, mount trust material read-only, and write only to a dedicated evidence mount.

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
- Ceph RGW object storage
- Iceberg table standard
- Apache Polaris REST catalog
- Spark batch processing
- Trino shared query layer
- Airflow orchestration
- Atlas and Ranger baseline governance and policy model
- bronze/silver/gold standards

Primary outcome:
- governed batch lakehouse foundation

Phase 1 acceptance:
- completed through the operational readiness gate in [stratus_phase1_operational_readiness.md](../operations/stratus_phase1_operational_readiness.md), which validates integrated function, recovery, observability, security, governance, quality, and runbook ownership

### Phase 1 scope note
Phase 1 is the fixed Increment 1 through Increment 7 foundation. Developer-profile completion may proceed while production dependencies are being provisioned, but no named component is silently deferred and the production-readiness gate remains blocked until every production profile passes.

### Phase 2 – Streaming and Operational Maturity
Deliver:
- Kafka event backbone
- Kafka Connect worker cluster
- Debezium CDC connectors for source systems
- Flink streaming ingestion
- CDC pipelines
- Atlas notification traffic consolidated onto the platform Kafka backbone, replacing the Phase 1 dedicated service or developer embedded notifier
- lineage automation improvements
- streaming-aware Iceberg maintenance and commit coordination

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
- **Ceph RGW** for durable object storage
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

- OpenJDK JDK 25: https://openjdk.org/projects/jdk/25/
- Oracle Java SE Support Roadmap: https://www.oracle.com/java/technologies/java-se-support-roadmap.html
- Apache Iceberg documentation: https://iceberg.apache.org/docs/latest/
- Apache Iceberg REST Catalog spec: https://iceberg.apache.org/docs/latest/rest-catalog/
- Apache Polaris: https://polaris.apache.org/
- Ceph Tentacle Object Gateway documentation: https://docs.ceph.com/en/tentacle/radosgw/
- Ceph Tentacle Object Gateway S3 API: https://docs.ceph.com/en/tentacle/radosgw/s3/
- Apache Polaris GitHub: https://github.com/apache/polaris
- Apache Iceberg overview: https://iceberg.apache.org/
- Apache Iceberg multi-engine support: https://iceberg.apache.org/multi-engine-support/
- Apache Iceberg Spark quickstart: https://iceberg.apache.org/spark-quickstart/
- Apache Iceberg Flink integration: https://iceberg.apache.org/docs/latest/flink/
- Apache Airflow: https://airflow.apache.org/
- Apache Airflow prerequisites: https://airflow.apache.org/docs/apache-airflow/stable/installation/prerequisites.html
- Apache Airflow Docker guide: https://airflow.apache.org/docs/apache-airflow/stable/howto/docker-compose/index.html
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
- Keycloak supported configurations: https://www.keycloak.org/server/supported-configurations
- PostgreSQL release documentation: https://www.postgresql.org/docs/release/
- Apache Maven release history: https://maven.apache.org/docs/history.html
- Podman releases: https://github.com/containers/podman/releases
- Docker Engine release notes: https://docs.docker.com/engine/release-notes/29/
- MIT Kerberos: https://web.mit.edu/kerberos/
- Prometheus: https://prometheus.io/
- Grafana: https://grafana.com/oss/grafana/
- Grafana Loki: https://grafana.com/oss/loki/
