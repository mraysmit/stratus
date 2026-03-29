# Stratus Phase 1 Implementation Plan

## 1. Purpose

This document translates the target on-prem data fabric architecture into an executable Phase 1 implementation plan for this repository.

The intent is to establish a credible foundation, not to pretend the entire platform can be delivered in one pass. Phase 1 should create the Java-side runtime, contracts, and verification harness needed to support later integration with Spark, Flink, Airflow, Atlas, MinIO, and a centrally managed Iceberg catalog.

This plan assumes that:

- Apache Iceberg remains the mandatory data contract for governed datasets
- this repository initially owns Java platform code rather than full infrastructure deployment assets
- the first implementation increment should be a working foundation runtime, not just folder scaffolding
- later phases will add streaming, operational maturity, and optional serving acceleration

---

## 2. Scope and Intended Outcome

### Phase 1 outcome

Phase 1 should deliver a governed batch-oriented foundation that makes the architecture real in code.

The repository should evolve from a placeholder Java scaffold into a platform foundation that can:

- represent bronze, silver, and gold datasets as explicit code-level contracts
- configure storage, catalog, governance, and environment settings consistently
- expose batch job entrypoints for ingestion and transformation workflows
- publish required metadata and lineage payloads through a governance integration boundary
- validate core rules locally through unit and integration tests

### Included scope

Phase 1 includes:

- Java package structure for core platform concerns
- configuration model for storage, catalog, governance, and environment settings
- dataset metadata model and validation rules
- abstractions for catalog access and storage integration
- batch workflow contracts for bronze, silver, gold, and table maintenance work
- governance publication interfaces and payload models
- local verification harness and repository documentation updates

### Excluded scope

Phase 1 does not include:

- production deployment of Spark, Flink, MinIO, Atlas, Airflow, or Firebolt
- a full Airflow DAG repository or operational control-plane implementation
- live streaming pipelines or Kafka infrastructure
- full serving-layer integration
- enterprise security platform integration beyond designing clean boundaries for it

---

## 3. Repository Boundary

The recommended repository boundary is narrow enough to stay coherent and broad enough to be useful.

This repository should own:

- Java platform libraries
- data contract and metadata models
- batch job entrypoints and workflow-friendly interfaces
- governance integration contracts
- local validation and test fixtures

This repository should not initially own:

- cluster deployment automation
- infrastructure provisioning for platform services
- complete Airflow environment packaging
- full Atlas installation and operations

If infrastructure ownership is added later, it should be added deliberately rather than mixed into the first foundation refactor.

---

## 4. Guiding Decisions

### 4.1 Iceberg is the hard contract

All governed analytical datasets must be modelled as Iceberg-backed contracts. Raw object-store layout is not the contract. Code written in this repository should reinforce that rule through naming, validation, and metadata structures.

### 4.2 Default catalog assumption

The default implementation assumption for Phase 1 is a centrally managed REST-oriented Iceberg catalog.

That assumption should not be baked into every class. The code should introduce a provider abstraction so the platform can still evaluate JDBC or Hive/Hadoop catalog variants without structural rewrites.

### 4.3 Batch first, streaming later

Spark and Flink are both part of the target architecture, but the first implementation increment should prioritize bounded batch contracts and platform semantics. Streaming can be added after the metadata model, catalog boundary, and maintenance expectations are stable.

### 4.4 Governance is mandatory work

Metadata publication is part of pipeline completion, not a post-processing accessory. The Java design should make it hard to create a governed dataset path that does not assemble ownership, classification, SLA, and lineage metadata.

---

## 5. Planned Package Structure

The current single-class scaffold should be replaced with a structure under `dev.mars.stratus` that reflects actual platform responsibilities.

Proposed package areas:

- `dev.mars.stratus.core` for shared configuration, identifiers, exceptions, and foundational interfaces
- `dev.mars.stratus.catalog` for Iceberg catalog abstractions and provider selection
- `dev.mars.stratus.storage` for object-storage integration boundaries and storage configuration
- `dev.mars.stratus.metadata` for dataset identity, zone, ownership, classification, retention, SLA, and validation models
- `dev.mars.stratus.jobs.ingestion` for landing-to-bronze contracts
- `dev.mars.stratus.jobs.transform` for bronze-to-silver and silver-to-gold workflow contracts
- `dev.mars.stratus.jobs.maintenance` for snapshot expiry, compaction, orphan cleanup, and metadata health work
- `dev.mars.stratus.governance` for Atlas-facing adapters, payload models, and lineage publication interfaces
- `dev.mars.stratus.orchestration` for workflow entrypoint contracts, parameter objects, and job status models
- `dev.mars.stratus.testing` for local fixtures and integration test support

This structure keeps orchestration concerns separate from job semantics and keeps governance as a first-class package rather than an afterthought.

---

## 6. Implementation Workstreams

### 6.1 Workstream A: Build foundation project structure

The current repository has a minimal Maven file and a placeholder main class. The first workstream is to replace that scaffold with a maintainable Java baseline.

Primary tasks:

- update Maven dependency and plugin management
- establish the `dev.mars.stratus` package tree
- remove or repurpose the placeholder entrypoint
- add logging and test support suitable for iterative platform development

Expected result:

The project compiles as a real foundation module rather than an IDE starter project.

### 6.2 Workstream B: Define configuration contracts

The platform needs an explicit configuration layer rather than ad hoc strings scattered through jobs.

Primary tasks:

- define storage configuration for MinIO or S3-compatible endpoints
- define catalog configuration for provider type, namespace defaults, and connection settings
- define governance configuration for metadata publication and endpoint settings
- define environment and maintenance settings for retention, compaction cadence, and health checks

Expected result:

The runtime can be parameterized consistently across local, test, and later deployed environments.

### 6.3 Workstream C: Implement the dataset contract model

This workstream encodes the architectural rule that bronze, silver, and gold are governed table contracts.

Primary tasks:

- define dataset identity and namespace objects
- represent bronze, silver, and gold zones as explicit types or validated enums
- encode owner, steward, source system, schema version, classification, SLA, retention, and quality status metadata
- implement validation rules for required metadata and naming standards

Expected result:

The repository has a single authoritative model for dataset definition and governance requirements.

### 6.4 Workstream D: Introduce catalog and storage abstractions

The codebase should depend on interfaces and provider contracts rather than a single hardwired catalog implementation.

Primary tasks:

- define catalog provider and catalog session abstractions
- define storage location and bucket or namespace mapping abstractions
- align table identity and storage resolution with the dataset contract model
- support REST-oriented catalog configuration as the default planning path

Expected result:

Later Spark, Flink, or maintenance components can interact with a stable platform abstraction layer instead of duplicating catalog logic.

### 6.5 Workstream E: Create batch workflow skeletons

Phase 1 should not claim to deliver every operational detail, but it should define clean entrypoints for bounded work.

Primary tasks:

- define landing-to-bronze job contract
- define bronze-to-silver transformation contract
- define silver-to-gold materialization contract
- define maintenance job contract for snapshot expiry, compaction, orphan cleanup, and metadata health checks
- define job parameter and status models for orchestration tools

Expected result:

Airflow can later orchestrate these jobs without those orchestration concerns being embedded in business logic.

### 6.6 Workstream F: Add governance publication boundaries

Atlas integration is likely to be one of the more integration-heavy parts of the platform. Phase 1 should start with stable contracts and payloads rather than trying to solve every environment-specific detail immediately.

Primary tasks:

- define Atlas publisher interfaces
- define metadata payload models covering mandatory dataset fields
- define lineage payload models for source-to-bronze, bronze-to-silver, and silver-to-gold transitions
- require governance payload assembly as part of job completion flow

Expected result:

The codebase can evolve from mock adapters to live Atlas integration without rewriting core job semantics.

### 6.7 Workstream G: Add local verification harness

The architecture is not credible unless its core rules are testable locally.

Primary tasks:

- add unit tests for naming and metadata completeness rules
- add tests for catalog provider selection and configuration validation
- add an integration fixture representing a bronze-silver-gold path
- verify governance payload generation for required metadata and lineage fields

Expected result:

The repository can prove the foundation rules before taking on heavier runtime integrations.

### 6.8 Workstream H: Align documentation

Repository documentation should explain what this codebase does and what it does not yet do.

Primary tasks:

- update the README with the implementation scope and module layout
- link implementation work back to the architecture design
- document the intended progression from Phase 1 to later phases

Expected result:

The codebase becomes understandable to contributors without relying on tribal memory.

---

## 7. Suggested Delivery Order

The work should proceed in an order that reduces rework and avoids inventing integration details before the contracts are stable.

### Step 1

Establish the Maven baseline and replace the placeholder project layout.

### Step 2

Define configuration contracts and the dataset metadata model.

### Step 3

Introduce catalog and storage abstractions on top of those models.

### Step 4

Create batch job entrypoints and maintenance workflow contracts.

### Step 5

Add governance payload models and publication interfaces.

### Step 6

Build the local verification harness.

### Step 7

Update README and supporting documentation to reflect the implemented foundation.

This sequence keeps the most stable concepts at the bottom of the stack and delays environment-heavy integrations until the project has a coherent internal design.

---

## 8. Validation Strategy

Validation for Phase 1 should prove that the repository enforces the architecture rather than merely restating it.

The following checks should exist by the end of the first implementation increment:

1. The project compiles cleanly after the package restructure and dependency updates.
2. Dataset definitions fail fast when required metadata is missing.
3. Naming and namespace validation reject invalid bronze, silver, and gold dataset identifiers.
4. Catalog provider selection behaves predictably for supported configuration combinations.
5. A local integration path demonstrates dataset progression through bronze, silver, and gold contract stages.
6. Governance payloads include owner, steward, source system, schema version, classification, SLA, retention, and quality status.
7. Job contracts exist for snapshot expiry, compaction, orphan cleanup, and metadata health checks.

The point is not to simulate an entire production platform in tests. The point is to verify the rules that make the platform architecture defensible.

---

## 9. Risks and Controls During Phase 1

### Risk: the repo becomes only a diagram in code form

Control:

Implement actual configuration, metadata, validation, and workflow entrypoint code rather than placeholder classes with no behavior.

### Risk: early coupling to one catalog implementation

Control:

Use a provider abstraction and keep the REST-oriented catalog assumption at the configuration layer rather than in every downstream class.

### Risk: governance is deferred until too late

Control:

Treat metadata payload assembly as part of the core job contract from the beginning.

### Risk: Airflow semantics leak into business code

Control:

Expose workflow-friendly entrypoints and statuses, but keep orchestration external.

### Risk: the foundation overreaches into deployment engineering

Control:

Keep production infrastructure and cluster automation out of the first implementation increment unless repository scope is intentionally expanded.

---

## 10. Phase 2 Handoff

Once Phase 1 is stable, the next major implementation wave should address runtime expansion rather than reworking the foundation.

Likely Phase 2 items:

- Kafka or equivalent event backbone
- Flink-based streaming ingestion and CDC patterns
- stronger lineage automation and Atlas integration depth
- operational Iceberg maintenance automation
- richer local environment support using containerized platform services

Those capabilities should build on the contracts introduced in Phase 1 rather than bypass them.

---

## 11. Immediate Next Action

The immediate next engineering task should be to replace the placeholder Maven and Java scaffold with the `dev.mars.stratus` foundation layout, then implement the configuration and dataset metadata contract layers first.