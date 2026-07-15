# Stratus Implementation Plan - Phase 2

## 1. Purpose

This document defines how Stratus Phase 2 is built and verified.

Phase 1 establishes the governed batch lakehouse foundation: Ceph RGW, Iceberg, Polaris, Spark, Airflow, Trino, Atlas, Ranger, FreeIPA, Keycloak, and operational readiness. Phase 2 adds the streaming and CDC layer on top of that foundation. It should not reopen Phase 1 architecture decisions unless the Phase 1 operational readiness gate found a blocking issue.

The Phase 2 goal is simple: **make governed data movement continuous where continuous movement is justified**. Batch remains the right tool for bounded workloads. Streaming is added for CDC, event streams, replay, low-latency ingestion, and stateful processing.

References:
- [stratus_on_prem_data_fabric_architecture.md](../architecture/stratus_on_prem_data_fabric_architecture.md)
- [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)
- [stratus_phase1_operational_readiness.md](../operations/stratus_phase1_operational_readiness.md)

---

## 2. Phase 2 Entry Criteria

Phase 2 production-profile deployment should not begin until:

- Phase 1 operational acceptance has passed.
- Polaris, Iceberg, Ceph RGW, Spark, Airflow, Trino, Atlas, Ranger, FreeIPA, and Keycloak are operational.
- The Phase 1 verification dataset can still run end to end.
- Control-plane backup and restore procedures are tested.
- Platform certificates and OIDC flows no longer require lab-only insecure settings.
- The Phase 2 source systems and event-producing applications are known.
- At least one CDC source and one event-stream use case have named business owners.

Phase 2 should be delayed if the platform still cannot answer who owns a dataset, which table is the source of truth, how access is enforced, or how a failed pipeline is recovered.

Developer-profile prototyping may begin earlier against isolated test topics and non-production data when it does not bypass a blocking Phase 1 architecture decision. That work may validate versions, connector packaging, schemas, and verifier code; it does not satisfy a Phase 2 entry or production gate.

---

## 3. Phase 2 Build Order

Phase 2 builds from the event backbone outward:

```text
Increment 8  - Kafka Event Backbone
Increment 9  - Kafka Connect and Debezium CDC
Increment 10 - Flink Streaming Compute
Increment 11 - Streaming Writes to Iceberg
Increment 12 - Atlas Event Bus and Lineage Automation
Increment 13 - Streaming Operations and Production Readiness
```

Kafka comes before Kafka Connect and Debezium because Connect stores connector configuration, offsets, and status in Kafka topics. Flink comes after Kafka because the first Phase 2 streaming jobs consume Kafka topics. Streaming writes to Iceberg come after Flink because they require the runtime, checkpointing, catalog, and object-storage path to be stable.

### 3.1 Developer and production tracks

| Concern | Developer profile | Production profile |
|---|---|---|
| Runtime topology | reduced broker, worker, and TaskManager counts on Docker Desktop or Podman | fault-tolerant on-prem topology with declared failure domains and capacity |
| Control APIs | HTTP only on loopback or an isolated developer network when the increment permits it | TLS-protected and authenticated Connect, Flink, and administration endpoints |
| State | local volumes and local Flink checkpoint/savepoint paths for disposable tests | durable Kafka replication, protected Connect internal topics, and Ceph RGW-backed Flink checkpoints/savepoints |
| Identity and secrets | generated test identities and local protected environment files | FreeIPA/Keycloak-integrated service identities, approved secret injection, rotation, and least privilege |
| Acceptance | startup/reset plus functional event-flow tests | shared functional regression plus security, recovery, replay, failure, capacity, observability, and runbook evidence |

No Increment 8-13 production gate accepts a developer-only replica count, plaintext control API, local filesystem state, generated bootstrap identity, or untested reset procedure. Promotion is an explicit configuration change with evidence, not a relabelling of the same deployment.

### 3.2 Phase 2 Task and Gate Tracker

The owning increment documents contain the executable task rows. Their `P2-*` IDs are canonical and must be used in issues, pull requests, evidence directories, blockers, and acceptance records. This table is the portfolio roll-up; it does not replace task-level status in the increment documents.

| Increment | Canonical task IDs | Delivery owner | Depends on | Roll-up exit evidence | Developer gate | Production gate | Status |
|---|---|---|---|---|---|---|---|
| 8 Kafka event backbone | `P2-8.S1`, `P2-8.D1`-`D2`, `P2-8.P1`-`P2`, `P2-8.R1`, `P2-8.V1`, `P2-8.G-D`, `P2-8.G-P` | Data-platform owner | Phase 1 readiness | immutable artifacts, KRaft health, topic/ACL inventory, client tests, failure/rebuild evidence | Not started | Not started | Not started |
| 9 Connect and Debezium | `P2-9.S1`, `P2-9.D1`-`D2`, `P2-9.P1`-`P2`, `P2-9.R1`, `P2-9.V1`, `P2-9.G-D`, `P2-9.G-P` | Data-integration owner | Increment 8 appropriate gate | image/plugin lock, connector/offset/schema evidence, restart/recovery results | Not started | Not started | Not started |
| 10 Flink compute | `P2-10.S1`, `P2-10.D1`-`D2`, `P2-10.P1`-`P2`, `P2-10.R1`, `P2-10.V1`, `P2-10.G-D`, `P2-10.G-P` | Streaming-platform owner | Increment 8 appropriate gate | runtime/job artifacts, checkpoint/savepoint/HA and recovery evidence | Not started | Not started | Not started |
| 11 Streaming Iceberg | `P2-11.S1`, `P2-11.D1`-`D2`, `P2-11.P1`-`P2`, `P2-11.R1`, `P2-11.V1`, `P2-11.G-D`, `P2-11.G-P` | Data-engineering owner | Increment 10 appropriate gate | table/commit/replay/maintenance/query evidence | Not started | Not started | Not started |
| 12 Atlas event lineage | `P2-12.S1`, `P2-12.D1`-`D2`, `P2-12.P1`-`P2`, `P2-12.R1`, `P2-12.V1`, `P2-12.G-D`, `P2-12.G-P` | Governance owner | Increment 11 appropriate gate | event/model/publisher, reconciliation, DLQ and recovery evidence | Not started | Not started | Not started |
| 13 Production readiness | `P2-13.E-D`, `P2-13.S1`, `P2-13.R1`-`R4`, `P2-13.V1`, `P2-13.G-P` | Platform owner | Increments 8-12 production gates | frozen manifest, completed drills, defect reruns, residual-risk decisions, signoff | Evidence only | Not started | Not started |

Valid task states are `Not started`, `In progress`, `Blocked`, `Built`, `Verified`, and `Accepted`. A roll-up may become `Accepted` only when every canonical child task required by the applicable gate is accepted and its evidence link resolves. Aggregate wording such as “appropriate gate” means the developer gate for downstream engineering and the production gate for production acceptance.

---

## 4. Reference Documentation Audit

Reference baseline: 2026-07-10.

Phase 2 uses fast-moving projects. Before implementation, the platform team must check current upstream release notes and documentation, then record the approved version matrix in the runbook. The values below are the current design targets or compatibility gates at the time this document was written.

| Component | Phase 2 target |
|---|---|
| Apache Kafka | 4.3.1, pinned by artifact checksum or internal image digest in the Phase 2 version matrix |
| Kafka Connect | bundled with the selected Kafka release |
| Debezium | 3.6.0.Final, with connector artifact and image digest pinned in the Phase 2 version matrix |
| Apache Flink | 2.1.3, the latest patch in the Iceberg 1.11-compatible Flink 2.1 line; upgrade the Flink/Iceberg/connector set together |
| Flink Kafka connector | 5.0.0-2.1, using the full upstream artifact version |
| Apache Flink CDC | 3.6.0 if direct Flink CDC connectors are used; exact artifact pinned in the Phase 2 version matrix |
| Apache Iceberg | 1.11.0 unless a newer approved release is selected and all dependent runtime artifacts are updated together |
| Iceberg Flink runtime | must match the selected Flink major/minor line; Iceberg 1.11.0 publishes Flink 2.1, 2.0, and 1.20 runtime jars |
| Apache Polaris | 1.5.0 unless superseded by a newer approved release before implementation |
| Apache Atlas | approved Apache release image built internally and pinned by tag plus digest in the Phase 2 version matrix |
| Apache Ranger | approved Apache release image built internally and pinned by tag plus digest in the Phase 2 version matrix |
| Java | Java 25 LTS for Stratus builds, verifiers, Kafka 4.3, and Trino 482; component-supported runtime exceptions are Java 17 for Spark 4.1 and Flink 2.1 job execution, with job artifacts compiled using the JDK 25 toolchain and the matching `--release` target |
| Build/runtime tooling | Apache Maven 3.9.16 and Podman 5.8.2, or newer compatible stable patches after the full image and runtime regression suite |

Important compatibility rule: do not select Flink solely by release recency if the selected Iceberg release does not publish or document a compatible Flink runtime. The implementation owner must choose either:

- a pinned Flink release with an officially compatible Iceberg runtime, or
- a pinned Flink release plus a documented, tested Iceberg connector build path approved by platform engineering.

No Phase 2 increment should use floating image tags, unverified connector versions, copied quickstart defaults, or ZooKeeper-era Kafka assumptions.

---

## 5. Increment 8 - Kafka Event Backbone

### What we are building

Apache Kafka as the durable, replayable event backbone for CDC, application events, Flink consumption, and Atlas notifications.

### Why this is first

Kafka is the substrate for the rest of Phase 2. Kafka Connect depends on Kafka internal topics. Debezium publishes CDC events into Kafka. Flink consumes Kafka topics. Atlas moves from its Phase 1 production notification service to the shared platform Kafka backbone after Kafka is stable. A developer profile that used the embedded notifier is migrated at the same point but was never production accepted.

### What is delivered

- Kafka cluster deployed in KRaft mode; ZooKeeper is not introduced.
- Dedicated brokers/controllers sized for the initial Phase 2 workload.
- TLS enabled for broker, controller, producer, consumer, and admin traffic.
- SASL/OIDC or SASL/SCRAM authentication selected and integrated with the Phase 1 identity model.
- Topic naming standard for CDC, application events, dead-letter topics, and platform internal topics.
- Retention policies defined per topic class.
- Replication factor and minimum in-sync replica policy defined.
- Kafka ACLs mapped to FreeIPA service groups.
- Prometheus metrics and Kafka dashboards configured.
- Backup/rebuild procedure documented for broker configuration and topic definitions.

### Verification

| Test | Pass condition |
|---|---|
| Cluster health | all brokers and controllers are online; quorum is healthy |
| TLS | clients connect with trusted certificates; plaintext access is rejected |
| Authentication | unauthorized client cannot produce or consume |
| ACL enforcement | producer and consumer service accounts can access only approved topics |
| Topic creation | standard topic templates create partitions, replication, retention, and cleanup policy correctly |
| Produce/consume | a test producer writes events and a test consumer reads them in order per partition |
| Retention | retention settings are visible and match topic class |
| Broker failure | cluster remains available within the approved fault-tolerance target |
| Metrics | broker health, under-replicated partitions, consumer lag, and request latency appear in Grafana |

### Demonstrated outcome

The platform has a secure event backbone. Events can be produced, retained, replayed, consumed, monitored, and governed by service identity.

---

## 6. Increment 9 - Kafka Connect and Debezium CDC

### What we are building

Kafka Connect worker cluster and Debezium connectors for database change data capture.

### Why this comes second

CDC requires a durable event backbone. Kafka Connect stores connector configuration, status, and offsets in Kafka internal topics. Debezium connectors run inside Connect workers and publish source database changes into Kafka topics.

### What is delivered

- Kafka Connect distributed worker cluster.
- Internal Connect topics for configs, offsets, and status.
- Debezium connector plugin installation path.
- Connector configuration repository with one file per connector.
- Initial PostgreSQL CDC connector for the Phase 2 verification source.
- Optional MySQL, SQL Server, Oracle, or MongoDB connector template if a named source requires it.
- Secret handling pattern for source database credentials.
- CDC topic naming, key schema, value schema, tombstone handling, and delete semantics.
- Dead-letter topic policy for malformed or rejected records.
- Connector status, lag, restart, and error dashboards.

### Verification

| Test | Pass condition |
|---|---|
| Connect health | worker cluster responds and reports all workers |
| Plugin discovery | Debezium connector classes are listed by the Connect REST API |
| Internal topics | config, offset, and status topics exist with approved replication and ACLs |
| Source snapshot | initial snapshot publishes expected records to the CDC topic |
| Insert capture | inserted source row appears in Kafka with correct key and payload |
| Update capture | updated source row appears with before/after or configured payload semantics |
| Delete capture | delete event behavior matches the documented tombstone/delete contract |
| Offset recovery | connector resumes from committed offsets after worker restart |
| Credential isolation | connector cannot access unrelated source databases |
| Error handling | bad record goes to the configured dead-letter topic or fails according to policy |

### Demonstrated outcome

Source database changes flow into Kafka as governed, replayable CDC topics with connector state, errors, and lag visible to operators.

---

## 7. Increment 10 - Flink Streaming Compute

### What we are building

Apache Flink as the stateful streaming runtime for consuming Kafka topics, applying continuous transformations, and preparing records for Iceberg writes.

### Why this comes third

Flink needs a stable event source. It should not be introduced as a generic compute engine before the platform has Kafka topics and CDC events to process. Flink is not an Airflow replacement; it owns long-running, stateful, event-time streaming jobs.

### What is delivered

- Flink cluster deployed on Linux with Podman or the approved runtime pattern.
- JobManager and TaskManagers configured with TLS and trusted platform CA.
- Checkpoint storage configured on Ceph RGW or another approved durable path.
- Savepoint procedure documented.
- Kafka connector installed for the selected Flink release.
- Prometheus metrics enabled.
- Service identity `svc-flink` activated from the Phase 1 reserved identity.
- Initial stream job that consumes a verification Kafka topic and writes validated output to a test sink.
- Operational lifecycle for submit, stop, drain, savepoint, restore, and upgrade.

### Verification

| Test | Pass condition |
|---|---|
| Cluster health | JobManager and TaskManagers are online |
| Authentication | Flink service identity can access only approved Kafka topics and storage paths |
| Kafka consume | Flink job consumes verification topic records |
| Checkpointing | checkpoints complete successfully at the configured interval |
| Failure recovery | task failure recovers from the latest checkpoint |
| Savepoint | operator can stop with savepoint and restart from it |
| Metrics | lag, checkpoint duration, failed checkpoints, restart count, and backpressure are visible |
| Event time | job handles out-of-order verification events according to watermark policy |

### Demonstrated outcome

The platform has a working streaming runtime that can consume Kafka events continuously, recover state, and expose operational health.

---

## 8. Increment 11 - Streaming Writes to Iceberg

### What we are building

Flink-to-Iceberg streaming ingestion using Apache Polaris as the catalog and Ceph RGW as the object store.

### Why this comes fourth

Streaming compute must work before streaming table writes are introduced. Iceberg commit cadence, checkpoint alignment, table ownership, compaction, and reader freshness must be validated carefully to avoid creating small-file debt or write conflicts with batch jobs.

### What is delivered

- Flink configured to resolve Iceberg tables through Apache Polaris.
- Flink configured to write Iceberg data files to Ceph RGW through approved service credentials.
- A streaming-owned bronze table populated from the verification CDC topic.
- A streaming-owned silver table populated after lightweight validation and normalization.
- One-writer-per-table ownership policy for streaming tables.
- Commit cadence, checkpoint interval, target file size, and compaction policy defined.
- Table maintenance handoff between Flink, Spark, and Airflow documented.
- Trino validation query for streaming-populated tables.
- Quality result publication to `platform.quality_check_results` for streaming checks.

### Verification

| Test | Pass condition |
|---|---|
| Catalog resolution | Flink resolves target tables through Polaris |
| Write path | Flink writes Iceberg data files to approved Ceph RGW locations |
| Snapshot creation | streaming job creates new Iceberg snapshots after checkpoint commits |
| Trino visibility | Trino sees committed streaming records after snapshot publication |
| Restart safety | job restart does not duplicate committed records beyond the documented semantics |
| Small-file control | file count and file size remain within the approved threshold for the test workload |
| Batch conflict | Spark maintenance does not conflict with active Flink writes |
| Quality checks | streaming quality outcomes are written to `platform.quality_check_results` |

### Demonstrated outcome

Kafka events and CDC records are continuously written into governed Iceberg tables, visible through Trino and governed by the same catalog, quality, and access-control model as Phase 1 data.

---

## 9. Increment 12 - Atlas Event Bus and Lineage Automation

### What we are building

Phase 2 metadata automation: Atlas reconfigured to use the platform Kafka backbone for entity change notifications, plus standardized metadata and lineage publication from streaming jobs.

### Why this comes fifth

Atlas starts in Phase 1 with an embedded notification posture to avoid making Kafka a foundation dependency. Once Kafka exists, Atlas should use the platform Kafka backbone so metadata change events become durable, observable, and available to downstream governance consumers.

### What is delivered

- Atlas notification configuration migrated from embedded Phase 1 mode to platform Kafka.
- Kafka topics for Atlas entity change notifications.
- ACLs for Atlas producers and governance consumers.
- Flink lineage event contract aligned with the Spark lineage contract from Phase 1.
- Streaming dataset metadata updates for owner, steward, domain, source, quality status, and freshness.
- Atlas classification propagation for streaming-owned tables.
- Ranger tag or policy alignment for new streaming tables.
- Governance dashboard for metadata freshness and lineage publication lag.

### Verification

| Test | Pass condition |
|---|---|
| Atlas Kafka notification | Atlas publishes entity changes to the configured Kafka topic |
| Consumer access | approved governance consumer can read Atlas notification events |
| Flink lineage | streaming job publishes source topic to Iceberg table lineage |
| Metadata freshness | Atlas freshness fields update after streaming job checkpoints |
| Classification | streaming table classification appears in Atlas |
| Ranger policy | new streaming table access is governed through Ranger/Trino |
| Audit | lineage and policy changes are auditable |

### Demonstrated outcome

Governance does not lag behind streaming. Streaming tables are discoverable, classified, traceable, and governed the same way as batch tables.

---

## 10. Increment 13 - Streaming Operations and Production Readiness

### What we are building

The operational acceptance gate for Phase 2.

### Why this comes last

Streaming systems fail differently from batch systems. Operators must be able to reason about lag, checkpoints, replay, offsets, schema changes, backpressure, duplicate handling, table commit cadence, and long-running job upgrades before production onboarding.

### What is delivered

- Kafka runbooks for broker failure, topic expansion, ACL change, consumer lag, and retention change.
- Kafka Connect runbooks for connector restart, offset handling, schema change, and source outage.
- Debezium runbooks for snapshot, incremental snapshot, source failover, and replication slot/log retention.
- Flink runbooks for savepoint, checkpoint failure, restart, scale-out, drain, and job upgrade.
- Iceberg streaming table runbooks for compaction, snapshot expiry, orphan cleanup, and rollback.
- End-to-end replay drill from retained Kafka events into Iceberg.
- Streaming incident drills and readiness evidence bundle.

### Verification

| Test | Pass condition |
|---|---|
| Replay drill | retained Kafka events can rebuild the verification streaming table in a controlled environment |
| Lag alert | artificial consumer lag triggers alert with topic, group, partition, and owner |
| Checkpoint alert | failed Flink checkpoints trigger alert with job, task, and state backend detail |
| Connector failure | failed Debezium connector alerts and can be restarted from stored offset |
| Source outage | connector behavior during source outage matches documented retry and alert policy |
| Schema change | compatible schema change flows through CDC, Flink, Iceberg, Trino, and Atlas |
| Incompatible schema | incompatible change is blocked, quarantined, or routed according to policy |
| Restore drill | Kafka, Connect, Flink, and Iceberg table state can be restored or rebuilt per runbook |

### Demonstrated outcome

Phase 2 streaming is production-ready. Operators can observe, recover, replay, secure, and govern streaming ingestion without weakening the Phase 1 lakehouse foundation.

---

## 11. Phase 2 Cross-Increment Traceability

| Increment | Produces | Consumed by | Cross-check required |
|---|---|---|---|
| 8 - Kafka | secure event backbone, topics, ACLs, retention, metrics | Connect, Debezium, Flink, Atlas | produce/consume, replay, ACLs, broker health |
| 9 - Connect and Debezium | CDC topics and connector state | Flink ingestion, replay drills | snapshot, insert/update/delete capture, offset recovery |
| 10 - Flink | streaming runtime, checkpointing, savepoints | Iceberg streaming writes | checkpoint recovery, Kafka consumption, metrics |
| 11 - Streaming Iceberg | streaming-owned bronze/silver tables | Trino, Atlas, Ranger, operations | Polaris resolution, snapshot visibility, small-file control |
| 12 - Atlas and lineage | streaming metadata, lineage, classification | governance and security operations | lineage graph, classification, Ranger policy alignment |
| 13 - Readiness | runbooks, drills, acceptance evidence | production streaming onboarding | replay, restore, lag/checkpoint/connector incident drills |

This traceability keeps Phase 2 from becoming a local tool installation exercise. Kafka is not complete because brokers start. Flink is not complete because a job can run. Streaming is complete only when governed events become governed Iceberg tables with replay, lineage, quality, authorization, and operational evidence.

---

## 12. Phase 2 Completion Gate

Phase 2 is complete when:

- [ ] Every Increment 8-12 developer shortcut has been replaced by its documented production setting and the shared functional suite has been rerun.
- [ ] Kafka is running in KRaft mode with TLS, authentication, ACLs, retention, monitoring, and broker failure recovery.
- [ ] Kafka Connect workers are running and connector state topics are protected.
- [ ] Debezium captures the verification source snapshot and ongoing changes.
- [ ] Flink consumes Kafka events with Ceph RGW-backed checkpoints/savepoints, metrics, and recovery; no production state path uses `file://`.
- [ ] Flink writes streaming-owned Iceberg tables through Polaris and Ceph RGW.
- [ ] Trino can query committed streaming table snapshots.
- [ ] Ranger policies govern streaming-created tables.
- [ ] Atlas shows streaming table metadata, classifications, and lineage.
- [ ] Streaming quality checks write to `platform.quality_check_results`.
- [ ] Replay from Kafka retention into Iceberg has been drilled.
- [ ] Connector failure, source outage, lag, checkpoint failure, schema change, and restore drills have evidence.
- [ ] Phase 1 batch workflows still pass after Phase 2 components are added.
- [ ] Phase 2 runbooks and ownership are signed off by platform operations, data engineering, security, and governance.

---

## 13. Phase 3 Handoff

After Phase 2 is accepted, the next implementation plan is [stratus_implementation_plan_phase3.md](stratus_implementation_plan_phase3.md). Phase 3 owns query acceleration, curated business marts, semantic serving views, and domain data product rollout.

Phase 2 should hand off:

- accepted streaming and CDC readiness evidence
- streaming-owned Iceberg table examples
- Trino query baselines for streaming-created tables
- Atlas lineage and classification evidence for streaming data
- Ranger policy evidence for streaming-created tables
- any measured serving gaps that may justify Phase 3 acceleration evaluation

---

## 14. Design Documents

- [stratus_on_prem_data_fabric_architecture.md](../architecture/stratus_on_prem_data_fabric_architecture.md) - full architecture specification and component decisions
- [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md) - Phase 1 foundation implementation plan
- [stratus_implementation_plan_phase3.md](stratus_implementation_plan_phase3.md) - Phase 3 query acceleration and data products implementation plan
- [stratus_phase1_operational_readiness.md](../operations/stratus_phase1_operational_readiness.md) - Phase 1 operational acceptance gate
- [kafka_event_backbone.md](kafka_event_backbone.md) - Increment 8 Kafka event backbone implementation plan
- [kafka_connect_debezium_cdc.md](kafka_connect_debezium_cdc.md) - Increment 9 Kafka Connect and Debezium CDC implementation plan
- [flink_streaming_compute.md](flink_streaming_compute.md) - Increment 10 Flink streaming compute implementation plan
- [flink_streaming_iceberg.md](flink_streaming_iceberg.md) - Increment 11 streaming writes to Iceberg implementation plan
- [atlas_streaming_lineage.md](atlas_streaming_lineage.md) - Increment 12 Atlas event bus and lineage automation implementation plan
- [streaming_production_readiness.md](streaming_production_readiness.md) - Increment 13 streaming operations and production readiness checklist
- [ceph_storage.md](ceph_storage.md) - Phase 1 storage foundation (Ceph RGW)
- [iceberg_polaris_catalog.md](iceberg_polaris_catalog.md) - Phase 1 table and catalog foundation
- [spark_compute.md](spark_compute.md) - Phase 1 batch compute
- [airflow_orchestration.md](airflow_orchestration.md) - Phase 1 orchestration
- [trino_query.md](trino_query.md) - Phase 1 interactive query
- [atlas_ranger_governance.md](atlas_ranger_governance.md) - Phase 1 governance
- [freeipa_keycloak_identity.md](freeipa_keycloak_identity.md) - Phase 1 identity and security hardening

---

## 15. Source References

- Apache Kafka downloads: https://downloads.apache.org/kafka/
- Apache Kafka documentation: https://kafka.apache.org/documentation/
- Apache Flink downloads: https://flink.apache.org/downloads/
- Apache Flink documentation: https://nightlies.apache.org/flink/flink-docs-stable/
- Apache Flink CDC documentation: https://nightlies.apache.org/flink/flink-cdc-docs-stable/
- Debezium releases: https://debezium.io/releases/
- Debezium documentation: https://debezium.io/documentation/
- Apache Iceberg releases: https://iceberg.apache.org/releases/
- Apache Iceberg Flink integration: https://iceberg.apache.org/docs/latest/flink/
- Apache Polaris documentation: https://polaris.apache.org/
- Apache Atlas: https://atlas.apache.org/
- Apache Ranger: https://ranger.apache.org/
