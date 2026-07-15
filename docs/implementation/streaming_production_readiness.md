# Stratus Increment 13 - Streaming Operations and Production Readiness

## 1. Purpose

This document is the operational acceptance and production readiness plan for Stratus Phase 2.

Increment 13 does not introduce another streaming component. It verifies that Increments 8 through 12 are operationally safe: Kafka can be recovered, CDC connectors can be restarted without data loss surprises, Flink jobs can be saved and restored, streaming Iceberg tables can be replayed or rebuilt, schema changes are controlled, alerts are actionable, and governance remains intact.

Phase 2 is not complete until this readiness gate passes.

This document accepts only the production profile. Developer-profile results are retained as regression evidence, but reduced replicas, local-only volumes, `file://` Flink state, plaintext Connect/Flink control APIs, generated identities, local CA certificates, and disposable source or governance dependencies are automatic failures here.

**Prerequisites:**
- Increment 8 production gate complete - Kafka event backbone
- Increment 9 production gate complete - Kafka Connect and Debezium CDC
- Increment 10 production gate complete - Flink streaming compute
- Increment 11 production gate complete - streaming writes to Iceberg
- Increment 12 production gate complete - Atlas event bus and lineage automation
- Phase 1 operational readiness evidence is still valid

---

## 2. Acceptance Scope

Increment 13 covers:

- Kafka broker, topic, ACL, retention, and replay readiness
- Kafka Connect worker, connector, offset, and DLQ readiness
- Debezium source outage, snapshot, replication slot, and schema change readiness
- Flink checkpoint, savepoint, restart, scale, and upgrade readiness
- Iceberg streaming table commit, compaction, rollback, and small-file readiness
- Trino reader visibility and freshness readiness
- Atlas/Ranger lineage, classification, policy, and audit readiness
- dashboards, alerts, runbooks, ownership, and evidence

Out of scope:

- Firebolt/query acceleration, covered by Phase 3
- broad data product rollout, covered by Phase 3
- self-service governance marketplace workflows, covered by Phase 4

---

## 3. Readiness Evidence Bundle

The Phase 2 evidence bundle must include:

- passing completion gates for Increments 8 through 12
- passing Java verification suites for Increments 8 through 12
- version matrix for Kafka, Debezium, Flink, Iceberg, Polaris, Trino, Atlas, and Ranger
- deployed topic inventory with owner, retention, partitions, replication, and ACLs
- connector inventory with owner, source database, publication/slot, status, and DLQ policy
- Flink job inventory with owner, checkpoint path, savepoint path, parallelism, and restart policy
- Iceberg streaming table inventory with writer owner, maintenance owner, and freshness target
- Atlas/Ranger governance evidence for streaming tables
- dashboard and alert inventory
- runbook index
- drill results and open risk register
- a promotion manifest showing the production replacement and verification evidence for every developer shortcut used in Increments 8-12

The version matrix must pin at least Kafka 4.3.1, Debezium 3.6.0.Final, Flink 2.1.3, Flink Kafka Connector 5.0.0-2.1, Iceberg 1.11.0, Polaris 1.5.0, Atlas 2.5.0, Ranger 2.8.0, and each deployed image digest, unless a newer compatible set has passed the same suites and is recorded by architecture decision.

---

## 4. Required Runbooks

| Runbook | Required content |
|---|---|
| Kafka broker failure | triage, expected alerts, ISR behavior, restart, replacement |
| Kafka topic creation/change | owner approval, partition change, retention change, ACL update |
| Kafka credential rotation | SCRAM user rotation and client rollout order |
| Kafka Connect worker failure | worker restart, rebalance expectation, task placement |
| Debezium connector restart | connector status, offset behavior, task restart |
| Source database outage | retry behavior, alerting, recovery, slot/log retention risk |
| Debezium snapshot | initial snapshot, incremental snapshot where supported, approval |
| Schema change | compatible/incompatible change handling and consumer notification |
| Flink checkpoint failure | state backend triage, restart policy, escalation |
| Flink savepoint and upgrade | stop with savepoint, deploy, restore, rollback |
| Streaming Iceberg compaction | ownership, schedule, active-writer safety |
| Replay/rebuild | retained Kafka event replay into controlled Iceberg table |
| Atlas/Ranger streaming governance | lineage repair, classification repair, policy repair |

Runbooks are accepted only if an operator other than the implementer can execute them during a readiness review.

---

## 5. Operational Drills

### Replay drill

Goal: prove retained Kafka events can rebuild the verification streaming table in a controlled environment.

Pass condition:

- replay uses retained Kafka events
- target table is isolated from production verification table
- rebuilt row counts and keys match expected source offsets
- Atlas/Ranger metadata is either rebuilt or explicitly marked as test-only
- no production table is overwritten

### Kafka broker failure drill

Pass condition:

- one broker is stopped
- under-replicated partition alert fires
- producers and consumers continue within the approved fault-tolerance target
- broker rejoins and ISR recovers
- no ACL or topic drift occurs

### Connector failure drill

Pass condition:

- Debezium connector task is stopped or forced to fail
- alert identifies connector and task
- task restart resumes from stored offsets
- no duplicate behavior exceeds the documented CDC semantics

### Source outage drill

Pass condition:

- source database becomes unavailable
- connector retries and alerts as documented
- source log/slot retention risk is visible
- connector catches up after source recovery

### Flink checkpoint failure drill

Pass condition:

- checkpoint failure alert fires
- job behavior matches restart policy
- operator can identify state backend or sink cause
- job returns to healthy checkpointing or is stopped safely

### Schema change drill

Pass condition:

- compatible schema change flows from source to Kafka to Flink to Iceberg to Trino to Atlas
- incompatible schema change is blocked, quarantined, or routed according to policy
- consumers receive documented notification

### Governance drill

Pass condition:

- new streaming table classification appears in Atlas
- Ranger policy applies to Trino query
- restricted user deny is audited
- lineage still connects source, topic, job, and table

---

## 6. Alert Acceptance

Minimum alerts that must be tested:

| Alert | Required context |
|---|---|
| Kafka broker down | broker, cluster, severity |
| under-replicated partitions | topic, partition, broker |
| consumer lag high | group, topic, partition, lag, owner |
| Connect worker down | worker, cluster |
| connector task failed | connector, task, error summary |
| Debezium source lag high | connector, source, lag |
| DLQ records present | connector, topic, count |
| Flink job not running | job, owner, last state |
| checkpoint failures | job, checkpoint id, failure reason |
| Iceberg commit failure | job, table, exception |
| streaming table freshness breach | table, last snapshot, owner |
| Atlas lineage stale | table, latest expected update |
| Ranger policy refresh stale | service, plugin, refresh age |

Alerts must point to the relevant runbook and owner.

---

## 7. Backup, Restore, and Rebuild Readiness

| Area | Required evidence |
|---|---|
| Kafka | broker config, topic definitions, ACL definitions, and rebuild procedure |
| Kafka Connect | worker config, connector config, internal topic health |
| Debezium | connector config, source publication/slot, snapshot/restart procedure |
| Flink | job artifacts, config, checkpoint/savepoint paths, restore procedure |
| Iceberg | table metadata retention, snapshot rollback, rebuild-from-Kafka procedure |
| Atlas | streaming entity and lineage restore or repair procedure |
| Ranger | streaming table policy backup and restore |

Kafka is not backed up like a database in this plan. The readiness posture is topic/config/ACL reproducibility plus retention-based replay for the approved recovery window. If a topic becomes the sole durable copy of a source event, that topic needs an explicit retention, replication, and disaster recovery design.

---

## 8. Implementation Task Track

Increment 13 has no developer acceptance gate. Developer results are indexed by `P2-13.E-D` as regression inputs only. Production drill evidence belongs under `evidence/phase2/increment13/<task-id>/`.

| ID | Track | Task and definition of done | Owner | Depends on | Deliverable/path | Verification/evidence | Gate | Accepted by | Blocker/risk | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| `P2-13.E-D` | Developer evidence | Freeze developer regression reports and promotion manifests from Increments 8-12; no production claim is made. | QA owner | P2-8 through P2-12 developer gates | developer evidence index | completeness/hash validation | developer evidence | Platform owner | Missing upstream report | Not started |
| `P2-13.S1` | Production | Freeze versions, topology, identities, risks, exceptions, runbooks, SLOs, and production evidence inventory. | Platform owner | P2-8 through P2-12 production gates | readiness manifest | configuration/evidence review | P1-P3 | Architecture owner | Upstream gate open | Not started |
| `P2-13.R1` | Production | Execute Kafka recovery and Connect/Debezium restart/source-outage drills. | Operations owner | `P2-13.S1` | drill reports | loss/duplicate/RTO/RPO and alert evidence | P4-P6 | Data owner | Maintenance window | Not started |
| `P2-13.R2` | Production | Execute Flink savepoint/restore/upgrade and Ceph-state outage drills. | Operations owner | `P2-13.S1` | drill reports | state consistency, RTO/RPO, rollback | P7-P8 | Data-engineering owner | State compatibility | Not started |
| `P2-13.R3` | Production | Execute Iceberg replay/rebuild/reconciliation and Atlas lineage reconciliation drills. | Data/governance owners | `P2-13.R1`, `P2-13.R2` | drill reports | row/snapshot/lineage reconciliation | P9-P11 | Data owner | Destructive rebuild scope | Not started |
| `P2-13.R4` | Production | Exercise security rotation/expiry, alerts, backup/restore, capacity, and operator runbooks. | Security/operations owners | `P2-13.R3` | drill and capacity reports | negative tests, alerts, restore, load results | P10-P14 | Platform owner | Cross-team scheduling | Not started |
| `P2-13.V1` | Production | Close defects, rerun failed drills, validate evidence links, and record residual-risk decisions. | QA owner | `P2-13.R4` | readiness evidence index | zero unexplained failures; accepted risks | P12-P15 | Architecture owner | Unaccepted high risk | Not started |
| `P2-13.G-P` | Production | Obtain platform, operations, security, governance, and data signoff; done when P1-P16 are accepted. | Platform owner | `P2-13.V1` | Phase 2 acceptance record | signed gate/evidence matrix | P1-P16 | Steering group | Any open blocking defect | Not started |

## 9. Completion Gates

### Developer evidence

Increment 13 has no independent developer acceptance gate. Developer-profile results from Increments 8 through 12 remain useful regression evidence, but they cannot satisfy or weaken any readiness criterion below.

### Production gate

Phase 2 is production-ready when:

- [ ] **P1** - Increment 8 through Increment 12 production gates are complete; developer gates alone are insufficient
- [ ] **P2** - the promotion manifest has no unresolved developer-only topology, state, transport, identity, certificate, or secret setting
- [ ] **P3** - all Phase 2 verification suites pass
- [ ] **P4** - replay drill rebuilds the verification streaming table
- [ ] **P5** - broker failure drill passes
- [ ] **P6** - connector failure and source outage drills pass
- [ ] **P7** - Flink savepoint, restore, and checkpoint failure drills pass
- [ ] **P8** - Flink checkpoints/savepoints use Ceph RGW durable shared paths and no production state path uses `file://`
- [ ] **P9** - Kafka Connect and Flink operator APIs use authenticated HTTPS; unauthenticated and untrusted clients are rejected
- [ ] **P10** - schema change drill passes for compatible and incompatible changes
- [ ] **P11** - streaming table freshness is observable through Trino and dashboards
- [ ] **P12** - Atlas lineage and classifications are current for streaming tables
- [ ] **P13** - Ranger allow/deny behavior works for streaming tables and is audited
- [ ] **P14** - runbooks have owners and are executable by operators
- [ ] **P15** - open risks have owner, severity, mitigation, and acceptance
- [ ] **P16** - platform operations, data engineering, security, and governance sign off

After this gate passes, controlled production streaming sources may onboard, and Phase 3 data product/query acceleration planning can proceed.

---

## 10. Troubleshooting

### Replay does not match expected table state

- Confirm replay offset range.
- Confirm source topic retention covers the full replay window.
- Confirm deterministic event IDs use topic, partition, and offset.
- Confirm current-state materialization handles deletes and out-of-order events.

### Source log retention is at risk

- Stop nonessential connector restarts.
- Increase source log retention where permitted.
- Resume or snapshot connector according to the source-system runbook.
- Notify source owner and platform operations.

### Schema change breaks Flink job

- Stop the job with savepoint if possible.
- Route affected records to DLQ or quarantine according to policy.
- Update schema handling and restart from savepoint.
- Publish consumer impact note.

### Governance evidence is stale

- Confirm Flink lineage publisher is running.
- Confirm Atlas REST API accepts updates.
- Confirm Atlas Kafka notification topic is receiving events.
- Confirm Ranger policy refresh is current.

---

## 11. References

- Stratus Phase 2 implementation plan: [stratus_implementation_plan_phase2.md](stratus_implementation_plan_phase2.md)
- Increment 8 - Kafka Event Backbone: [kafka_event_backbone.md](kafka_event_backbone.md)
- Increment 9 - Kafka Connect and Debezium CDC: [kafka_connect_debezium_cdc.md](kafka_connect_debezium_cdc.md)
- Increment 10 - Flink Streaming Compute: [flink_streaming_compute.md](flink_streaming_compute.md)
- Increment 11 - Streaming Writes to Iceberg: [flink_streaming_iceberg.md](flink_streaming_iceberg.md)
- Increment 12 - Atlas Event Bus and Lineage Automation: [atlas_streaming_lineage.md](atlas_streaming_lineage.md)
- Phase 1 operational readiness: [stratus_phase1_operational_readiness.md](../operations/stratus_phase1_operational_readiness.md)
