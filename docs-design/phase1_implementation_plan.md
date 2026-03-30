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

## 5. Project Structure

The current single-class scaffold should be replaced with a structure under `dev.mars.stratus` that reflects actual platform responsibilities.

Phase 1 remains a single Maven module. Multi-module layout is a Phase 2 concern — premature module splitting creates build complexity with no payoff while the service boundaries are still forming.

```text
stratus/
├── pom.xml
├── README.md
├── docs-design/
│   ├── on_prem_data_fabric_architecture.md
│   └── phase1_implementation_plan.md
├── src/
│   ├── main/java/dev/mars/stratus/
│   │   ├── core/
│   │   │   ├── StratusConfig.java              # root configuration record
│   │   │   ├── StorageConfig.java              # MinIO / S3 connection settings
│   │   │   ├── CatalogConfig.java              # catalog provider type, endpoint, namespace defaults
│   │   │   ├── GovernanceConfig.java           # Atlas / Ranger endpoint and publication settings
│   │   │   ├── MaintenanceConfig.java          # compaction cadence, snapshot expiry, thresholds
│   │   │   ├── Zone.java                       # enum: BRONZE, SILVER, GOLD
│   │   │   ├── DatasetId.java                  # value: namespace + table name + zone
│   │   │   └── StratusException.java           # base checked exception hierarchy
│   │   │
│   │   ├── catalog/
│   │   │   ├── CatalogProvider.java            # interface: create CatalogSession from config
│   │   │   ├── CatalogSession.java             # interface: table ops — resolve, exists, properties, drop
│   │   │   ├── TableReference.java             # value: resolved Iceberg table identity + location
│   │   │   └── RestCatalogProvider.java        # default CatalogProvider for REST catalog
│   │   │
│   │   ├── storage/
│   │   │   ├── StorageResolver.java            # interface: DatasetId → physical storage location
│   │   │   ├── BucketLayout.java               # strategy: namespace/zone → bucket + prefix mapping
│   │   │   └── MinioStorageResolver.java       # default StorageResolver for MinIO
│   │   │
│   │   ├── metadata/
│   │   │   ├── DatasetDescriptor.java          # value: DatasetId + DatasetMetadata + schema ref
│   │   │   ├── DatasetMetadata.java            # value: owner, steward, source, classification, SLA, retention, quality status
│   │   │   ├── MetadataValidator.java          # enforces required-field and naming rules per zone
│   │   │   └── DatasetRegistry.java            # interface: register, lookup, list dataset descriptors
│   │   │
│   │   ├── quality/
│   │   │   ├── QualityRule.java                # interface: evaluate(DatasetDescriptor, context) → QualityOutcome
│   │   │   ├── QualityOutcome.java             # value: pass/fail/warning + rule id + context + timestamp
│   │   │   ├── QualityStore.java               # interface: persist and retrieve quality outcomes
│   │   │   └── PromotionGate.java              # evaluates quality outcomes → promote / block / override decision
│   │   │
│   │   ├── governance/
│   │   │   ├── GovernancePublisher.java         # interface: publish metadata + lineage payloads
│   │   │   ├── MetadataPayload.java            # value: what goes to Atlas for a dataset registration/update
│   │   │   ├── LineageEvent.java               # value: source → target + transformation context + run id
│   │   │   └── AtlasGovernancePublisher.java   # Atlas-specific implementation of GovernancePublisher
│   │   │
│   │   ├── jobs/
│   │   │   ├── Job.java                        # interface: run(JobContext) → JobResult
│   │   │   ├── JobContext.java                 # value: config + catalog session + parameters + run metadata
│   │   │   ├── JobResult.java                  # value: status + metrics + lineage events + quality outcomes
│   │   │   ├── ingestion/
│   │   │   │   └── IngestionJob.java           # landing-to-bronze: validates source, writes bronze, emits lineage
│   │   │   ├── transform/
│   │   │   │   └── TransformJob.java           # bronze-to-silver / silver-to-gold: validates input, transforms, emits lineage
│   │   │   └── maintenance/
│   │   │       └── MaintenanceJob.java         # snapshot expiry, compaction, orphan cleanup, metadata health
│   │   │
│   │   └── orchestration/
│   │       ├── JobEntrypoint.java              # CLI/API surface: parse args → JobContext → run → report
│   │       ├── JobRequest.java                 # value: job type + parameters + overrides
│   │       └── JobResponse.java                # value: exit status + result summary + error context
│   │
│   └── test/java/dev/mars/stratus/
│       ├── core/
│       │   └── DatasetIdTest.java
│       ├── metadata/
│       │   ├── MetadataValidatorTest.java
│       │   └── DatasetDescriptorTest.java
│       ├── catalog/
│       │   └── CatalogProviderTest.java
│       ├── quality/
│       │   ├── PromotionGateTest.java
│       │   └── QualityRuleTest.java
│       ├── governance/
│       │   └── MetadataPayloadTest.java
│       ├── jobs/
│       │   ├── IngestionJobTest.java
│       │   └── MaintenanceJobTest.java
│       └── integration/
│           └── BronzeSilverGoldFlowTest.java   # end-to-end: ingest → transform → promote with governance
```

### 5.1 Non-obvious structural decisions

**Why `quality` is a separate package from `metadata`.**
Quality rules execute against datasets and produce durable outcomes. Those outcomes drive promotion gates. This is runtime behavior, not metadata modeling. Mixing them would conflate definition with execution.

**Why `orchestration` exists separately from `jobs`.**
Jobs own business logic and produce results. Orchestration owns the external-facing surface (CLI args, exit codes, request/response shapes). Airflow interacts with orchestration. Jobs are unaware of Airflow.

**Why there is no `testing` package under `main`.**
Test fixtures live in `src/test`. If shared test support classes are needed across multiple test packages, they go in a `testing` package under `src/test`, not `src/main`. Production code should not carry test scaffolding.

---

## 6. Services Design

This section defines the key interfaces, their responsibilities, dependencies, and interaction patterns.

### 6.1 Core types

These are value objects and configuration records. They have no dependencies on other packages.

| Type | Kind | Purpose |
|---|---|---|
| `StratusConfig` | record | Root config: aggregates StorageConfig, CatalogConfig, GovernanceConfig, MaintenanceConfig. Loaded once at startup. |
| `StorageConfig` | record | MinIO endpoint, credentials reference, default bucket, TLS flag. |
| `CatalogConfig` | record | Provider type (REST / JDBC / HIVE), endpoint, default namespace, connection properties. |
| `GovernanceConfig` | record | Atlas endpoint, publication mode (sync / async / disabled), Ranger endpoint (optional for Phase 1). |
| `MaintenanceConfig` | record | Snapshot expiry cadence, compaction thresholds, orphan cleanup schedule, metadata depth limits. |
| `Zone` | enum | `BRONZE`, `SILVER`, `GOLD`. Carries naming prefix and metadata requirements per zone. |
| `DatasetId` | value | Immutable: namespace + table name + zone. Validated on construction — rejects invalid names. |
| `StratusException` | exception | Base checked exception. Subclassed by catalog, storage, governance, and validation failures. |

### 6.2 Catalog service

```java
public interface CatalogProvider {
    CatalogSession openSession(CatalogConfig config);
}

public interface CatalogSession extends AutoCloseable {
    TableReference resolveTable(DatasetId id);
    boolean tableExists(DatasetId id);
    Map<String, String> tableProperties(DatasetId id);
    void createTable(DatasetId id, Schema schema, PartitionSpec spec);
}
```

**Dependency direction:** `catalog` depends on `core` only. No knowledge of jobs, governance, or orchestration.

**Provider selection:** `CatalogProvider` implementations are selected by `CatalogConfig.providerType()`. Phase 1 ships `RestCatalogProvider`. JDBC and Hive providers are future additions — the interface exists so they can be added without restructuring.

**`TableReference`** wraps the resolved Iceberg table identity, catalog namespace path, and physical storage location. Downstream code uses `TableReference` rather than re-resolving from raw strings.

### 6.3 Storage service

```java
public interface StorageResolver {
    URI resolveLocation(DatasetId id);
    URI resolveLandingZone(String sourceSystem);
}
```

**Dependency direction:** `storage` depends on `core` only.

**`BucketLayout`** encodes the mapping convention: `{bucket}/{zone}/{namespace}/{table}/`. This is a strategy object injected into `MinioStorageResolver`, not a hardcoded path pattern. Different environments or domains can use different layouts.

### 6.4 Metadata model

```java
public record DatasetDescriptor(
    DatasetId id,
    DatasetMetadata metadata,
    String schemaRef       // pointer to schema version — not the schema itself
) { }

public record DatasetMetadata(
    String owner,
    String steward,
    String sourceSystem,
    int schemaVersion,
    String classification,  // e.g. "INTERNAL", "CONFIDENTIAL", "RESTRICTED"
    String sla,
    String retentionPolicy,
    String qualityStatus    // e.g. "UNCHECKED", "PASSED", "FAILED", "OVERRIDDEN"
) { }
```

**`MetadataValidator`** enforces per-zone rules:
- Bronze: owner, sourceSystem, schemaVersion required. Classification defaults to INTERNAL.
- Silver: all bronze fields plus steward, sla, and qualityStatus must be non-null.
- Gold: all silver fields plus retentionPolicy and explicit classification.

Validation fails fast with specific error messages, not silent defaults.

**`DatasetRegistry`** is an interface, not an in-memory singleton. Phase 1 implementation may back it with a local store or delegate to catalog properties. The interface exists because the registry concern is distinct from catalog table resolution.

```java
public interface DatasetRegistry {
    void register(DatasetDescriptor descriptor);
    Optional<DatasetDescriptor> lookup(DatasetId id);
    List<DatasetDescriptor> listByZone(Zone zone);
    List<DatasetDescriptor> listByOwner(String owner);
}
```

### 6.5 Quality service

```java
public interface QualityRule {
    String ruleId();
    QualityOutcome evaluate(DatasetDescriptor target, JobContext context);
}

public record QualityOutcome(
    String ruleId,
    Status status,           // PASS, FAIL, WARNING
    String detail,
    Instant evaluatedAt
) {
    public enum Status { PASS, FAIL, WARNING }
}

public interface QualityStore {
    void record(DatasetId id, List<QualityOutcome> outcomes);
    List<QualityOutcome> latest(DatasetId id);
}
```

**`PromotionGate`** is not an interface — it is a concrete class with deterministic logic:
- If any outcome is FAIL → block promotion.
- If all outcomes are PASS → allow promotion.
- WARNING outcomes are logged but do not block.
- Override requires explicit `PromotionOverride` parameter with reason and principal.

### 6.6 Governance service

```java
public interface GovernancePublisher {
    void publishMetadata(MetadataPayload payload);
    void publishLineage(LineageEvent event);
}
```

**`MetadataPayload`** contains the full `DatasetDescriptor` plus operational context (pipeline run id, timestamp, environment). It is the serialization boundary — callers assemble a payload, the publisher sends it.

**`LineageEvent`** contains:
- source `DatasetId` (or external source identifier)
- target `DatasetId`
- transformation type (INGESTION, TRANSFORM, MATERIALIZATION, MAINTENANCE)
- run id and timestamp
- optional field-level mapping reference

**`AtlasGovernancePublisher`** translates payloads to Atlas REST API calls. Phase 1 may ship with a `LoggingGovernancePublisher` for local development that writes payloads to structured log output.

### 6.7 Job service

```java
public interface Job {
    JobResult run(JobContext context);
}

public record JobContext(
    StratusConfig config,
    CatalogSession catalog,
    GovernancePublisher governance,
    QualityStore qualityStore,
    Map<String, String> parameters
) { }

public record JobResult(
    Status status,
    Map<String, Object> metrics,
    List<LineageEvent> lineageEvents,
    List<QualityOutcome> qualityOutcomes
) {
    public enum Status { SUCCESS, FAILED, PARTIAL }
}
```

**All concrete jobs implement `Job`.** The contract guarantees:
1. A job receives everything it needs through `JobContext` — no service locator, no static state.
2. A job returns a `JobResult` containing lineage events and quality outcomes — governance publication is not optional, it is structurally enforced by the caller.
3. A job does not publish governance data itself. The orchestration layer publishes after the job completes. This keeps jobs testable without mocking Atlas.

**`IngestionJob`** — landing-to-bronze:
- resolves landing zone via `StorageResolver`
- validates source files exist and meet schema expectations
- writes bronze Iceberg table via `CatalogSession`
- emits lineage: external source → bronze table
- emits quality outcomes: schema validation results

**`TransformJob`** — bronze-to-silver or silver-to-gold:
- resolves source and target datasets via `DatasetRegistry` + `CatalogSession`
- validates source dataset quality status (uses `PromotionGate` for zone transitions)
- transforms and writes target Iceberg table
- emits lineage: source zone → target zone
- emits quality outcomes for output dataset

**`MaintenanceJob`** — table health:
- operates on a single table identified by `DatasetId`
- executes one or more maintenance operations: snapshot expiry, compaction, orphan cleanup, metadata compaction
- reports metrics: files removed, bytes reclaimed, new snapshot count
- does not emit lineage (maintenance is operational, not data movement)

### 6.8 Orchestration layer

```java
public class JobEntrypoint {
    public JobResponse execute(JobRequest request, StratusConfig config);
}

public record JobRequest(
    String jobType,          // "ingest", "transform", "maintenance"
    Map<String, String> parameters,
    Map<String, String> overrides
) { }

public record JobResponse(
    int exitCode,
    JobResult.Status status,
    String summary,
    List<String> errors
) { }
```

**`JobEntrypoint`** is the boundary Airflow (or any external scheduler) interacts with.

Execution flow:
1. Parse `JobRequest` from CLI args or API call
2. Build `StratusConfig` (from files / env / overrides)
3. Open `CatalogSession` via `CatalogProvider`
4. Instantiate the appropriate `Job` implementation
5. Call `job.run(context)` → `JobResult`
6. Publish lineage and metadata via `GovernancePublisher`
7. Record quality outcomes via `QualityStore`
8. Return `JobResponse` with exit code

Step 6 happens here, not inside the job. This is deliberate — it means jobs are pure compute-and-report. Publication failures do not corrupt job state, and jobs can be tested without governance infrastructure.

### 6.9 Dependency graph

```text
orchestration ──→ jobs ──→ metadata ──→ core
      │             │         │
      │             ├──→ catalog ───→ core
      │             │
      │             ├──→ quality ───→ core
      │             │
      │             └──→ governance → core
      │                      │
      └──→ catalog           └──→ metadata
      └──→ governance
      └──→ quality
```

**Rules:**
- `core` depends on nothing inside `dev.mars.stratus`
- `catalog`, `storage`, `metadata` depend only on `core`
- `quality` depends on `core` and `metadata`
- `governance` depends on `core` and `metadata`
- `jobs` depends on `core`, `catalog`, `storage`, `metadata`, `quality`, `governance`
- `orchestration` depends on everything — it is the composition root
- No circular dependencies. No package depends on a package that depends on it.

### 6.10 What is deliberately not designed here

- **Spark/Flink job internals.** The `Job` interface defines the contract. How a Spark job actually reads and writes Iceberg tables is engine-specific implementation behind that interface. Phase 1 defines the contract; Phase 2 fills in engine-specific implementations.
- **Atlas type definitions.** `AtlasGovernancePublisher` will need Atlas entity types and relationship definitions. Those are operational configuration, not platform code design.
- **Airflow DAGs.** DAGs call `JobEntrypoint`. DAG structure is an Airflow concern, not a Java concern.
- **Authentication and authorization internals.** Ranger integration is a deployment concern. The code provides clean boundaries where auth checks can be inserted.

---

## 7. Implementation Workstreams

### 7.1 Workstream A: Build foundation project structure

The current repository has a minimal Maven file and a placeholder main class. The first workstream is to replace that scaffold with the structure defined in §5.

Primary tasks:

- update `pom.xml` with dependency management (Iceberg, SLF4J, JUnit 5, AssertJ) and plugin configuration
- create the package tree under `dev.mars.stratus` matching the §5 layout
- remove the placeholder `Main.java`
- add logging configuration and test support

Expected result:

The project compiles with the full package structure. All packages exist with at least a `package-info.java`.

### 7.2 Workstream B: Implement core types and configuration

Implement the types defined in §6.1.

Primary tasks:

- implement `StratusConfig`, `StorageConfig`, `CatalogConfig`, `GovernanceConfig`, `MaintenanceConfig` as Java records
- implement `Zone` enum with per-zone naming prefix and metadata requirements
- implement `DatasetId` as a validated value object — reject invalid names on construction
- implement `StratusException` hierarchy
- add unit tests for `DatasetId` validation and `Zone` behavior

Expected result:

All core types compile, are tested, and enforce their invariants.

### 7.3 Workstream C: Implement dataset metadata model

Implement the types defined in §6.4.

Primary tasks:

- implement `DatasetMetadata` record with all mandatory fields
- implement `DatasetDescriptor` record composing `DatasetId` + `DatasetMetadata` + schema ref
- implement `MetadataValidator` with per-zone required-field rules (bronze/silver/gold escalation)
- define `DatasetRegistry` interface
- add unit tests: validator rejects incomplete metadata per zone, accepts valid metadata

Expected result:

The repository has a single authoritative model for dataset definition. Validation fails fast with specific error messages.

### 7.4 Workstream D: Implement catalog and storage abstractions

Implement the interfaces defined in §6.2 and §6.3.

Primary tasks:

- implement `CatalogProvider` and `CatalogSession` interfaces
- implement `TableReference` value object
- implement `RestCatalogProvider` (initially against Iceberg's REST catalog client)
- implement `StorageResolver` interface and `BucketLayout` strategy
- implement `MinioStorageResolver`
- add unit tests: provider selection from config, storage location resolution for each zone

Expected result:

Downstream code depends on `CatalogSession` and `StorageResolver`, not on REST catalog specifics.

### 7.5 Workstream E: Implement quality subsystem

Implement the types defined in §6.5.

Primary tasks:

- implement `QualityRule` interface and `QualityOutcome` record
- implement `QualityStore` interface
- implement `PromotionGate` with deterministic PASS/FAIL/WARNING logic and override support
- add unit tests: promotion blocked on FAIL, allowed on PASS, warning logged, override requires reason

Expected result:

Quality outcomes drive promotion decisions with clear, tested rules.

### 7.6 Workstream F: Implement job contracts and governance

Implement the types defined in §6.6 and §6.7.

Primary tasks:

- implement `Job` interface, `JobContext` record, `JobResult` record
- implement `GovernancePublisher` interface, `MetadataPayload`, `LineageEvent`
- implement `LoggingGovernancePublisher` for local development
- implement `IngestionJob`, `TransformJob`, `MaintenanceJob` skeletons conforming to the `Job` contract
- verify that jobs return lineage events and quality outcomes — structurally, not optionally
- add unit tests: job produces expected `JobResult` shape, lineage events present for each job type

Expected result:

Job contracts are executable, testable, and structurally enforce governance output.

### 7.7 Workstream G: Implement orchestration layer and verification harness

Implement the types defined in §6.8 and prove the end-to-end flow.

Primary tasks:

- implement `JobEntrypoint`, `JobRequest`, `JobResponse`
- implement the 8-step execution flow from §6.8 in `JobEntrypoint.execute()`
- add `BronzeSilverGoldFlowTest`: ingest → validate → transform → promote → publish, verifying governance payloads and quality outcomes at each step
- add tests for `MetadataValidator` rejection paths and `PromotionGate` blocking paths

Expected result:

A local integration test proves the full contract chain from job request through governance publication.

### 7.8 Workstream H: Align documentation

Primary tasks:

- update README with implementation scope, module layout, and build instructions
- link README to architecture doc and implementation plan
- document what Phase 1 delivers and what it does not

Expected result:

The codebase is understandable to contributors without tribal knowledge.

---

## 8. Suggested Delivery Order

The work should proceed in dependency order. Each step builds on verified output from the previous step.

### Step 1 — Project scaffold (Workstream A, §7.1)

Establish Maven baseline, create full package tree from §5, remove placeholder code. Gate: project compiles clean.

### Step 2 — Core types and configuration (Workstream B, §7.2)

Implement `StratusConfig`, `Zone`, `DatasetId`, `StratusException` from §6.1. Gate: unit tests pass for `DatasetId` validation and `Zone` behavior.

### Step 3 — Dataset metadata model (Workstream C, §7.3)

Implement `DatasetMetadata`, `DatasetDescriptor`, `MetadataValidator`, `DatasetRegistry` from §6.4. Gate: per-zone validation rules tested and enforced.

### Step 4 — Catalog and storage (Workstream D, §7.4)

Implement `CatalogProvider`, `CatalogSession`, `StorageResolver`, `BucketLayout` from §6.2–§6.3. Gate: provider selection and storage resolution tested.

### Step 5 — Quality subsystem (Workstream E, §7.5)

Implement `QualityRule`, `QualityOutcome`, `QualityStore`, `PromotionGate` from §6.5. Gate: promotion gate logic tested.

### Step 6 — Jobs and governance (Workstream F, §7.6)

Implement `Job`, `JobContext`, `JobResult`, `GovernancePublisher`, `LineageEvent`, concrete job types from §6.6–§6.7. Gate: jobs produce correct `JobResult` shapes with lineage events.

### Step 7 — Orchestration and integration tests (Workstream G, §7.7)

Implement `JobEntrypoint` and the end-to-end `BronzeSilverGoldFlowTest`. Gate: full chain from job request through governance publication passes.

### Step 8 — Documentation (Workstream H, §7.8)

Update README and linking. Gate: a new contributor can understand the codebase from docs alone.

This sequence keeps the most stable concepts at the bottom of the stack and delays environment-heavy integrations until the project has a coherent internal design.

---

## 9. Validation Strategy

Validation for Phase 1 should prove that the repository enforces the architecture rather than merely restating it.

The following checks should exist by the end of the first implementation increment:

1. The project compiles cleanly with the full package structure from §5.
2. `DatasetId` rejects invalid namespace/table/zone combinations on construction.
3. `MetadataValidator` rejects incomplete metadata per zone (bronze < silver < gold escalation).
4. `CatalogProvider` selection from `CatalogConfig` resolves to `RestCatalogProvider` by default.
5. `StorageResolver` maps each `DatasetId` to the correct bucket/prefix per `BucketLayout`.
6. `PromotionGate` blocks on FAIL, allows on PASS, logs WARNING, requires override with reason.
7. `IngestionJob`, `TransformJob`, `MaintenanceJob` all return `JobResult` with lineage events populated.
8. `GovernancePublisher.publishMetadata()` receives payloads containing all mandatory fields from §6.4.
9. `BronzeSilverGoldFlowTest` executes the full chain: ingest → validate → transform → quality check → promote → publish governance.
10. `JobEntrypoint.execute()` follows the 8-step flow from §6.8 end to end.

The point is not to simulate an entire production platform in tests. The point is to verify the rules that make the platform architecture defensible.

---

## 10. Risks and Controls During Phase 1

### Risk: the repo becomes only a diagram in code form

Control:

Every type in §6 must have behavior, not just fields. `MetadataValidator` enforces rules. `PromotionGate` blocks on failure. `JobEntrypoint` executes the 8-step flow. If a class has no tested behavior, it should not exist yet.

### Risk: early coupling to one catalog implementation

Control:

All downstream code depends on `CatalogSession` (§6.2), never on `RestCatalogProvider` directly. Provider selection is a `CatalogConfig` concern.

### Risk: governance is deferred until too late

Control:

`JobResult` structurally requires `List<LineageEvent>` (§6.7). The orchestration layer publishes after every job. There is no code path that completes a governed job without assembling governance output.

### Risk: Airflow semantics leak into business code

Control:

Jobs depend on `JobContext`, not on Airflow APIs. `JobEntrypoint` (§6.8) is the only Airflow-facing surface. Job implementations are unaware of scheduling.

### Risk: the foundation overreaches into deployment engineering

Control:

Keep production infrastructure and cluster automation out of the first implementation increment unless repository scope is intentionally expanded.

---

## 11. Phase 2 Handoff

Once Phase 1 is stable, the next major implementation wave should address runtime expansion rather than reworking the foundation.

Likely Phase 2 items:

- Kafka or equivalent event backbone
- Flink-based streaming ingestion and CDC patterns
- stronger lineage automation and Atlas integration depth
- operational Iceberg maintenance automation
- richer local environment support using containerized platform services

Those capabilities should build on the contracts introduced in Phase 1 rather than bypass them.

---

## 12. Immediate Next Action

The immediate next engineering task should be Workstream A (§7.1): replace the placeholder Maven and Java scaffold with the `dev.mars.stratus` package structure from §5, then implement the core types from §6.1.