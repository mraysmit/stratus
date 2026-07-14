# Stratus Increment 12 - Atlas Event Bus and Lineage Automation

## 1. Purpose

This document is the technical implementation plan for Increment 12 of the Stratus platform as defined in [stratus_implementation_plan_phase2.md](stratus_implementation_plan_phase2.md).

Increment 12 connects Phase 2 streaming work back into the governance plane. Atlas is migrated from its Phase 1 production notification service to the shared Kafka backbone, and streaming jobs publish standardized lineage and metadata for Kafka topics, CDC sources, Flink jobs, and streaming-owned Iceberg tables. A developer profile that used Atlas's embedded notifier is migrated here too, but that profile was never production accepted. Ranger policy alignment is verified for streaming-created tables through Trino.

The developer profile may use reduced topic replication and test entities in isolated namespaces. The production profile uses the Increment 8 three-node Kafka contract, managed `svc-atlas` credentials, trusted TLS, retained/replayable topics, Atlas external graph/search services, production Ranger policies, and notification/lineage recovery drills. Entity types and lineage payloads are identical in both tracks.

When this increment is complete, streaming data is not invisible side traffic. It is discoverable, classified, traceable, auditable, and governed using the same Atlas/Ranger expectations as the Phase 1 batch foundation.

**Prerequisites:**
- Increment 8 complete - Kafka event backbone operational
- Increment 9 complete - Kafka Connect and Debezium CDC operational
- Increment 10 complete - Flink streaming compute operational
- Increment 11 complete - streaming-owned Iceberg tables visible through Trino
- Increment 6 complete - Atlas and Ranger governance operational
- Increment 7 complete - FreeIPA and Keycloak identity hardening operational
- `svc-atlas` Kafka identity and ACLs available
- `svc-flink` can publish lineage metadata to Atlas over HTTPS

**Track rule:** Developer work requires the developer gates of Increments 8-11. Increment 12 production acceptance requires their production gates and the Phase 1 production Atlas/Ranger/identity topology.

---

## 2. Assumptions and Prerequisites

- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- Podman 5.8.2 installed on the governance host, or a newer approved stable patch after regression testing
- JDK 25 and Maven 3.9.16 on the approved build worker; the verification host requires only the approved container runtime and verifier runtime inputs
- Atlas endpoint is reachable at `https://atlas.stratus.local`
- Ranger endpoint is reachable at `https://ranger.stratus.local`
- Kafka brokers are reachable on `9092` with TLS and SASL/SCRAM
- Trino can query the streaming-owned verification tables from Increment 11
- Atlas uses FreeIPA-backed authentication from Increment 7
- Ranger policies reference FreeIPA groups

Streaming governance scope:

| Asset | Governance treatment |
|---|---|
| PostgreSQL verification source | Atlas source entity |
| Debezium connector | Atlas process entity |
| Kafka CDC topic | Atlas topic entity |
| Flink streaming job | Atlas process entity |
| `bronze.verification_customer_account_cdc` | Atlas Iceberg table entity |
| `silver.verification_customer_account_current` | Atlas Iceberg table entity if implemented |
| Ranger Trino policy | allow/deny policy for streaming-created tables |

### Reference documentation audit

Reference baseline: 2026-07-10.

This increment continues the Apache Atlas and Apache Ranger versions approved in Increment 6 and hardened in Increment 7. It also uses the Kafka 4.3.1 event backbone from Increment 8. Before implementation, confirm the selected Atlas notification properties, Kafka client security properties supported by the Atlas image, and Ranger policy/tag service compatibility with the selected Trino and Ranger releases.

Do not migrate Atlas notifications to Kafka until Increment 8 authentication, TLS, ACLs, metrics, and recovery checks are complete.

---

## 3. Governance Topology

```text
Flink streaming job
      │ HTTPS REST lineage event
      ▼
Atlas REST API
      │ metadata and lineage state
      ▼
Atlas graph/search store
      │ entity change notification
      ▼
Kafka platform.atlas.entity.v1
      │ governance consumers
      ▼
metadata automation / audit checks

Trino query over streaming table
      │ user and group context
      ▼
Ranger policy plugin
      │ policy and audit
      ▼
Ranger Admin
```

Atlas remains the metadata and lineage plane. Ranger remains the policy enforcement and audit plane. Kafka carries Atlas notification events after the migration, but Kafka does not become the metadata store.

---

## 4. Kafka Topics and ACLs

Create Atlas notification topics:

```bash
/opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --create \
  --topic platform.atlas.entity.v1 \
  --partitions 3 \
  --replication-factor 3 \
  --config min.insync.replicas=2 \
  --config retention.ms=2592000000 \
  --config cleanup.policy=delete

/opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --create \
  --topic platform.atlas.hook.v1 \
  --partitions 3 \
  --replication-factor 3 \
  --config min.insync.replicas=2 \
  --config retention.ms=2592000000 \
  --config cleanup.policy=delete
```

Grant `svc-atlas` producer and consumer access:

```bash
/opt/kafka/bin/kafka-acls.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --add \
  --allow-principal User:svc-atlas \
  --operation Read \
  --operation Write \
  --operation Describe \
  --topic platform.atlas. \
  --resource-pattern-type prefixed

/opt/kafka/bin/kafka-acls.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --add \
  --allow-principal User:svc-atlas \
  --operation Read \
  --group atlas.notification.consumer
```

Approved governance consumers may receive read access to `platform.atlas.entity.v1`. Do not grant broad wildcard read access.

---

## 5. Atlas Kafka Notification Configuration

Update the Atlas runtime configuration to use the platform Kafka backbone. Exact property names should be confirmed against the selected Atlas release, but the configuration contract is:

```properties
# Atlas notification producer/consumer
atlas.notification.embedded=false
atlas.notification.create.topics=false
atlas.kafka.bootstrap.servers=kafka1.stratus.local:9092,kafka2.stratus.local:9092,kafka3.stratus.local:9092
atlas.kafka.security.protocol=SASL_SSL
atlas.kafka.sasl.mechanism=SCRAM-SHA-512
atlas.kafka.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="svc-atlas" password="<svc-atlas password>";
atlas.kafka.ssl.truststore.location=/etc/atlas/certs/kafka.truststore.p12
atlas.kafka.ssl.truststore.password=<truststore password>
atlas.kafka.ssl.truststore.type=PKCS12
atlas.kafka.ssl.endpoint.identification.algorithm=https
atlas.notification.topics=platform.atlas.entity.v1,platform.atlas.hook.v1
```

Operational notes:

- topic creation remains platform automation-owned, not Atlas-owned
- Atlas must validate broker certificates
- Atlas must not use a plaintext Kafka listener
- any password values must be injected through the approved secret mechanism
- restart Atlas during a maintenance window because notification behavior changes process-wide

---

## 6. Streaming Lineage Contract

Flink jobs must publish lineage after successful checkpoint-backed commits to Iceberg.

Minimum lineage payload:

| Field | Purpose |
|---|---|
| `runId` | Flink job id plus checkpoint id |
| `jobName` | Flink job name |
| `jobVersion` | deployed job artifact version |
| `sourceSystem` | source system name, e.g. PostgreSQL |
| `sourceConnector` | Debezium connector name |
| `sourceTopic` | Kafka topic |
| `sourceOffsets` | committed topic/partition offset range |
| `targetCatalog` | Polaris catalog |
| `targetTable` | Iceberg table name |
| `targetSnapshotId` | committed Iceberg snapshot id |
| `qualityStatus` | PASS/WARN/FAIL summary |
| `eventTimeMin` | lower event-time bound |
| `eventTimeMax` | upper event-time bound |
| `publishedAt` | lineage publication timestamp |

Publication timing:

1. Flink checkpoint completes.
2. Iceberg snapshot is committed.
3. Quality result summary is available.
4. Flink metadata publisher sends lineage to Atlas.

Lineage must not be published for uncommitted data.

---

## 7. Atlas Entity Model

Increment 12 extends the Phase 1 Atlas model with streaming-aware entities.

| Entity | Required attributes |
|---|---|
| `stratus_source_system` | name, owner, system type, domain |
| `stratus_kafka_topic` | topic name, topic class, owner, retention, source system |
| `stratus_cdc_connector` | connector name, connector class, source system, topic prefix |
| `stratus_flink_job` | job name, job version, owner, checkpoint policy |
| `stratus_iceberg_table` | catalog, namespace, table, zone, owner, steward, quality status, latest snapshot id |

Relationships:

```text
source database table
  -> Debezium connector
  -> Kafka CDC topic
  -> Flink streaming job
  -> Iceberg bronze table
  -> optional Iceberg silver table
```

The existing `stratus_iceberg_table` type from Increment 6 should be reused where possible. Add attributes only when streaming requires them; do not create a parallel table type unless the existing model cannot represent streaming metadata.

---

## 8. Ranger Policy Alignment

Streaming-created tables must be governed through the same Trino/Ranger path as batch-created tables.

Required policy checks:

| Policy | Expected behavior |
|---|---|
| streaming bronze restricted | only platform engineering and approved data engineers can query bronze CDC rows |
| streaming silver consumer access | approved analyst group can query approved silver/current-state table |
| PII column restriction | restricted users cannot query sensitive columns such as `email` |
| Ranger audit | allow and deny events are recorded for Trino queries |
| FreeIPA group mapping | policies reference FreeIPA groups, not local users |

If Atlas classifications drive Ranger tag policy, the classification applied to streaming tables must match the Ranger tag policy name exactly.

---

## 9. Operational Checks

### Atlas notification topic receives events

```bash
/opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --consumer.config /etc/stratus/kafka/client-governance.properties \
  --topic platform.atlas.entity.v1 \
  --from-beginning \
  --max-messages 1
```

Expected: Atlas entity change event appears after a streaming entity is created or updated.

### Atlas search for streaming table

```bash
curl -s \
  -u "$ATLAS_USER:$ATLAS_PASSWORD" \
  "https://atlas.stratus.local/api/atlas/v2/search/basic?typeName=stratus_iceberg_table&query=verification_customer_account_cdc"
```

Expected: bronze streaming table entity is returned.

### Lineage graph

```bash
curl -s \
  -u "$ATLAS_USER:$ATLAS_PASSWORD" \
  "https://atlas.stratus.local/api/atlas/v2/lineage/<streaming-table-guid>?direction=BOTH&depth=5"
```

Expected: lineage graph includes source, Debezium connector, Kafka topic, Flink job, and Iceberg table.

### Ranger deny check

```sql
SELECT email
FROM stratus.bronze.verification_customer_account_cdc
LIMIT 10;
```

Expected: restricted user is denied and Ranger audit records the decision.

---

## 10. Java Verification Suite

The Java source and Maven dependencies in this section are build inputs only. The approved build system publishes the executable verifier as a pinned container image. Operators execute that image and do not build on the verification host or inside the verification container.

Required tests:

| Test class | Required assertions |
|---|---|
| `AtlasKafkaNotificationVerificationTest` | Atlas entity changes appear on `platform.atlas.entity.v1` |
| `StreamingLineageVerificationTest` | Atlas lineage connects source database, Kafka topic, Flink job, and Iceberg table |
| `StreamingMetadataFreshnessVerificationTest` | latest snapshot id and quality status update after streaming checkpoint |
| `StreamingClassificationVerificationTest` | streaming table has expected classification |
| `StreamingRangerPolicyVerificationTest` | Trino allow/deny behavior matches Ranger policies |
| `GovernanceAuditVerificationTest` | Ranger audit and Atlas update evidence exists |

Environment variables:

| Variable | Example |
|---|---|
| `STRATUS_ATLAS_BASE_URL` | `https://atlas.stratus.local` |
| `STRATUS_RANGER_BASE_URL` | `https://ranger.stratus.local` |
| `STRATUS_KAFKA_BOOTSTRAP` | `kafka1.stratus.local:9092,kafka2.stratus.local:9092,kafka3.stratus.local:9092` |
| `STRATUS_TRINO_JDBC_URL` | `jdbc:trino://trino-coordinator.stratus.local:8443/stratus` |
| `STRATUS_STREAMING_TABLE` | `bronze.verification_customer_account_cdc` |

---

## 11. Observability

Minimum dashboard signals:

| Signal | Why it matters |
|---|---|
| Atlas notification event rate | metadata bus health |
| Atlas notification publish errors | governance event loss risk |
| Atlas REST update latency | lineage publication health |
| Flink lineage publish success/failure | streaming metadata freshness |
| latest streaming snapshot id in Atlas | metadata freshness |
| Ranger policy deny/allow counts | access-control validation |
| Ranger plugin policy refresh age | enforcement freshness |
| Kafka lag for governance consumers | notification backlog |

Minimum alerts:

- Atlas notification publish failure
- no Atlas streaming metadata update within freshness window
- Flink lineage publisher failure
- governance consumer lag above threshold
- Ranger policy refresh stale
- unexpected Ranger deny spike on streaming tables

---

## 12. Implementation Task Track

Evidence for these stable tasks belongs under `evidence/phase2/increment12/<task-id>/`.

| ID | Track | Task and definition of done | Owner | Depends on | Deliverable/path | Verification/evidence | Gate | Accepted by | Blocker/risk | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| `P2-12.S1` | Shared | Lock event schemas, Atlas model, publisher image, retry/DLQ contracts, and verifier. | Governance owner | P2-11 developer gate | schema/model/service modules | build, schema compatibility, smoke | D1, P1-P3 | Data owner | Model evolution | Not started |
| `P2-12.D1` | Developer | Deploy publisher and developer topics; publish idempotent lineage for streaming runs. | Governance owner | `P2-12.S1` | `applications/lineage-publisher/`; dev config | entity/lineage creation and duplicate suppression | D1 | Data owner | Atlas fixture | Not started |
| `P2-12.D2` | Developer | Prove retry, DLQ, replay, reconciliation, quality status, and malformed-event handling. | QA owner | `P2-12.D1` | verifier/tests | JUnit, DLQ and reconciliation reports | D1-D2 | Governance owner | Deterministic retries | Not started |
| `P2-12.P1` | Production | Deploy redundant publisher with TLS/auth, managed secrets, ACLs, schema controls, and Atlas production dependencies. | Platform/governance owners | `P2-12.S1`, P2-11 production gate | production service/config | auth negative tests, failover and publish | P1-P8 | Security owner | Atlas capacity | Not started |
| `P2-12.P2` | Production | Apply retention, audit, replay boundaries, reconciliation schedule, ownership, alerts, and change controls. | Governance owner | `P2-12.P1` | governance operations config | audit, scheduled reconciliation, alert exercise | P7-P11 | Data owner | Replay duplication | Not started |
| `P2-12.R1` | Production | Execute Kafka/Atlas/publisher outage, DLQ recovery, rebuild, rollback, and model migration drills. | Operations owner | `P2-12.P2` | `operations/runbooks/atlas-lineage/` | timed drills, defects/reruns, restored lineage | P10-P12 | Platform owner | Maintenance window | Not started |
| `P2-12.V1` | Production | Run full event-to-lineage correctness, lag, throughput, idempotency, and observability regression. | QA owner | `P2-12.R1` | production reports | JUnit, lineage graph, metrics | P12-P13 | Governance owner | Representative event rate | Not started |
| `P2-12.G-D` | Developer | Accept D1-D2. | Governance owner | `P2-12.D2` | developer gate record | gate/evidence matrix | D1-D2 | Data owner | Open defect | Not started |
| `P2-12.G-P` | Production | Accept P1-P13. | Platform owner | `P2-12.V1` | production gate record | evidence/promotion index | P1-P13 | Governance/operations owners | Open production defect | Not started |

## 13. Completion Gates

### Developer gate

- [ ] **D1** - Isolated Atlas notification topics and test entities prove notification delivery, lineage publication, classification, and Ranger allow/deny behavior.
- [ ] **D2** - Reduced replication, test identities, and embedded-notifier source configuration are recorded in the promotion manifest.

### Production gate

Increment 12 is accepted when:

- [ ] **P1** - Atlas uses platform Kafka topics for notifications
- [ ] **P2** - `svc-atlas` Kafka ACLs are least-privilege
- [ ] **P3** - Atlas notification events are visible on approved Kafka topics
- [ ] **P4** - streaming source, connector, topic, Flink job, and Iceberg table entities exist in Atlas
- [ ] **P5** - lineage connects source database to Kafka topic to Flink job to Iceberg table
- [ ] **P6** - latest snapshot id and quality status update after streaming commits
- [ ] **P7** - streaming table classifications are applied
- [ ] **P8** - Ranger policies govern streaming-created tables through Trino
- [ ] **P9** - Ranger audit records allow and deny decisions
- [ ] **P10** - Java verification suite passes
- [ ] **P11** - governance dashboards and alerts are configured
- [ ] **P12** - Atlas no longer depends on an embedded notifier or an Atlas-dedicated Kafka service unless a documented production exception retains that service
- [ ] **P13** - notification replay, Atlas restart, broker failure, lineage republish, and Ranger policy refresh drills have current evidence

The developer gate completes engineering integration. Only the production gate allows Increment 13 to begin production-readiness signoff.

---

## 14. Troubleshooting

### Atlas does not publish Kafka events

- Confirm Atlas notification embedded mode is disabled.
- Confirm Atlas can authenticate as `svc-atlas`.
- Confirm `svc-atlas` has write access to `platform.atlas.*` topics.
- Confirm Atlas trusts Kafka broker certificates.

### Lineage graph is incomplete

- Confirm Flink publishes lineage only after committed Iceberg snapshots.
- Confirm source topic and target table names match Atlas entity qualified names.
- Confirm the lineage publisher uses stable qualified names, not display names.

### Ranger policy does not apply to streaming table

- Confirm Trino sees the table through the governed Polaris catalog.
- Confirm Ranger service definition includes the schema/table.
- Confirm policy references FreeIPA group names.
- Confirm tag/classification names match exactly if tag policy is used.

---

## 15. References

- Stratus Phase 2 implementation plan: [stratus_implementation_plan_phase2.md](stratus_implementation_plan_phase2.md)
- Increment 6 - Atlas and Ranger: [increment6_atlas_ranger.md](increment6_atlas_ranger.md)
- Increment 8 - Kafka Event Backbone: [increment8_kafka_event_backbone.md](increment8_kafka_event_backbone.md)
- Increment 11 - Streaming Writes to Iceberg: [increment11_streaming_iceberg.md](increment11_streaming_iceberg.md)
- Apache Atlas: https://atlas.apache.org/
- Apache Atlas REST API: https://atlas.apache.org/api/v2/index.html
- Apache Ranger: https://ranger.apache.org/
- Apache Kafka documentation: https://kafka.apache.org/documentation/
