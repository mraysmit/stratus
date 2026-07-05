# Stratus Increment 13 - Streaming Operations and Production Readiness

## 1. Purpose

This document is the operational acceptance and production readiness plan for Stratus Phase 2.

Increment 13 does not introduce another streaming component. It verifies that Increments 8 through 12 are operationally safe: Kafka can be recovered, CDC connectors can be restarted without data loss surprises, Flink jobs can be saved and restored, streaming Iceberg tables can be replayed or rebuilt, schema changes are controlled, alerts are actionable, and governance remains intact.

Phase 2 is not complete until this readiness gate passes.

**Prerequisites:**
- Increment 8 complete - Kafka event backbone
- Increment 9 complete - Kafka Connect and Debezium CDC
- Increment 10 complete - Flink streaming compute
- Increment 11 complete - streaming writes to Iceberg
- Increment 12 complete - Atlas event bus and lineage automation
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

## 8. Phase 2 Completion Gate

Phase 2 is production-ready when:

- [ ] Increment 8 through Increment 12 completion gates are complete
- [ ] all Phase 2 verification suites pass
- [ ] replay drill rebuilds the verification streaming table
- [ ] broker failure drill passes
- [ ] connector failure and source outage drills pass
- [ ] Flink savepoint, restore, and checkpoint failure drills pass
- [ ] schema change drill passes for compatible and incompatible changes
- [ ] streaming table freshness is observable through Trino and dashboards
- [ ] Atlas lineage and classifications are current for streaming tables
- [ ] Ranger allow/deny behavior works for streaming tables and is audited
- [ ] runbooks have owners and are executable by operators
- [ ] open risks have owner, severity, mitigation, and acceptance
- [ ] platform operations, data engineering, security, and governance sign off

After this gate passes, controlled production streaming sources may onboard, and Phase 3 data product/query acceleration planning can proceed.

---

## 9. Troubleshooting

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

## 10. References

- Stratus Phase 2 implementation plan: [stratus_implementation_plan_phase2.md](stratus_implementation_plan_phase2.md)
- Increment 8 - Kafka Event Backbone: [increment8_kafka_event_backbone.md](increment8_kafka_event_backbone.md)
- Increment 9 - Kafka Connect and Debezium CDC: [increment9_kafka_connect_debezium.md](increment9_kafka_connect_debezium.md)
- Increment 10 - Flink Streaming Compute: [increment10_flink_streaming_compute.md](increment10_flink_streaming_compute.md)
- Increment 11 - Streaming Writes to Iceberg: [increment11_streaming_iceberg.md](increment11_streaming_iceberg.md)
- Increment 12 - Atlas Event Bus and Lineage Automation: [increment12_atlas_event_lineage.md](increment12_atlas_event_lineage.md)
- Phase 1 operational readiness: [phase1_operational_readiness.md](phase1_operational_readiness.md)
