# Stratus Increment 11 - Streaming Writes to Iceberg

## 1. Purpose

This document is the technical implementation plan for Increment 11 of the Stratus platform as defined in [stratus_implementation_plan_phase2.md](stratus_implementation_plan_phase2.md).

Increment 11 connects the Phase 2 streaming runtime to the Phase 1 lakehouse foundation. Flink consumes Kafka and Debezium CDC topics, resolves Iceberg tables through Apache Polaris, writes data files to MinIO, and commits Iceberg snapshots that Trino can query. When this increment is complete, Kafka events and CDC records are continuously written into governed Iceberg tables without bypassing Polaris, weakening Ranger/Atlas governance expectations, or creating uncontrolled small-file debt.

This increment is not a general Flink deployment and not an Iceberg maintenance increment. Increment 10 proves the Flink runtime. Increment 11 proves streaming table writes, checkpoint-aligned commits, table ownership, reader visibility, and operational guardrails.

**Prerequisites:**
- Increment 8 complete - Kafka event backbone operational
- Increment 9 complete - Kafka Connect and Debezium CDC operational
- Increment 10 complete - Flink streaming compute operational on the approved Iceberg-compatible Flink line
- Increment 2 complete - Polaris and Iceberg namespaces operational
- Increment 5 complete - Trino can query Polaris-managed Iceberg tables
- Increment 6 and 7 complete - Atlas/Ranger identity and governance model operational
- `svc-flink` has approved Kafka, Polaris, and MinIO credentials

---

## 2. Assumptions and Prerequisites

- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- Podman 4.x installed on each Flink host
- JDK 21+ and Maven 3.9+ on the verification host
- Flink target is `2.1.1` for this increment because Iceberg 1.11.0 publishes a Flink 2.1 runtime artifact
- Iceberg target is `1.11.0`
- Flink Kafka Connector target is `5.0.0`
- Polaris endpoint is reachable at `https://polaris.stratus.local:8181/api/catalog`
- MinIO endpoint is reachable at `https://minio1.stratus.local:9000`
- Kafka verification CDC topic from Increment 9 is populated
- Trino can query `bronze`, `silver`, and `platform` namespaces
- Flink checkpoint and savepoint paths are durable enough for restart tests

Streaming-owned tables introduced in this increment:

| Table | Purpose | Writer |
|---|---|---|
| `bronze.verification_customer_account_cdc` | raw Debezium CDC events normalized into append-friendly rows | Flink only |
| `silver.verification_customer_account_current` | current-state view materialized by Flink from CDC events | Flink only |

These are verification tables. Production streaming tables must follow the same ownership and maintenance rules, but with domain-specific names.

### Reference documentation audit

Reference baseline: 2026-07-05.

Apache Iceberg 1.11.0 is the current Iceberg line used by the Stratus Phase 1 baseline. Its Flink documentation includes REST catalog configuration, writing, reading, Flink writes, table maintenance, and configuration sections. Iceberg 1.11.0 publishes runtime artifacts for Flink 2.1, 2.0, and 1.20. This increment therefore uses:

| Component | Target |
|---|---|
| Apache Flink | 2.1.1 |
| Apache Iceberg | 1.11.0 |
| Iceberg Flink runtime | `iceberg-flink-runtime-2.1` |
| Iceberg AWS bundle | `iceberg-aws-bundle` 1.11.0 |
| Flink Kafka Connector | 5.0.0 |
| Apache Polaris | 1.5.0 or approved newer release |
| Trino | 482 or approved newer release |

Do not move this increment to Flink 2.2.x or 2.3.x until Iceberg publishes or documents a compatible Flink runtime or the platform approves a tested custom connector build.

---

## 3. Streaming Write Topology

```text
PostgreSQL source
      │ logical replication
      ▼
Debezium connector
      │ Kafka topic
      ▼
cdc.verification.postgres.public.customer_account
      │ consume with svc-flink
      ▼
Flink streaming job
      │ checkpoint-aligned Iceberg commits
      ▼
Polaris REST catalog ──► Iceberg metadata
      │
      ▼
MinIO data files
      │
      ├── Trino validation queries
      ├── Atlas lineage publication in Increment 12
      └── Ranger policy enforcement through Trino
```

Flink must use Polaris as the Iceberg catalog. Direct table paths, local Hadoop catalogs, ad hoc metadata locations, and engine-local catalogs are not accepted for governed tables.

---

## 4. Ownership and Write Rules

Streaming writes create new multi-engine risk. Increment 11 uses a strict ownership model:

| Rule | Requirement |
|---|---|
| One writer per table | Flink owns writes to streaming-created verification tables |
| Catalog discipline | all table resolution goes through Polaris |
| Batch maintenance boundary | Spark/Airflow maintenance must not rewrite files while Flink is actively committing unless the maintenance procedure is explicitly streaming-safe |
| Snapshot cadence | commits are tied to Flink checkpoint completion |
| Reader contract | Trino reads only committed snapshots |
| Quality visibility | streaming checks write to `platform.quality_check_results` |
| Governance continuity | Atlas and Ranger expectations from Phase 1 still apply; automation is completed in Increment 12 |

If a production table needs both batch and streaming writers, it must be split into separate owned tables or have a specific concurrency design reviewed before implementation.

---

## 5. Flink Image Update

Extend the Increment 10 Flink image with Iceberg runtime artifacts.

Target image tag:

```bash
podman build -t stratus/flink:2.1.1-kafka5.0.0-iceberg1.11.0 docker/flink-iceberg
```

Required artifacts:

| Artifact | Version |
|---|---|
| Apache Flink | 2.1.1 |
| Flink Kafka Connector | 5.0.0 |
| `iceberg-flink-runtime-2.1` | 1.11.0 |
| `iceberg-aws-bundle` | 1.11.0 |

Example Dockerfile fragment:

```dockerfile
FROM stratus/flink:2.1.1-kafka5.0.0

USER root

ADD https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-flink-runtime-2.1/1.11.0/iceberg-flink-runtime-2.1-1.11.0.jar \
    /opt/flink/lib/

ADD https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-aws-bundle/1.11.0/iceberg-aws-bundle-1.11.0.jar \
    /opt/flink/lib/

USER flink
```

The implementation runbook must record image digest, artifact checksums, and any dependency overrides.

---

## 6. Flink Configuration

Add Iceberg and Polaris settings to the Flink configuration used by the streaming write jobs.

Create `/etc/stratus/flink/iceberg.properties`:

```properties
catalog-name=stratus
catalog-impl=org.apache.iceberg.rest.RESTCatalog
uri=https://polaris.stratus.local:8181/api/catalog
warehouse=stratus
credential=svc-flink:<polaris-client-secret>
scope=PRINCIPAL_ROLE:ALL
io-impl=org.apache.iceberg.aws.s3.S3FileIO
s3.endpoint=https://minio1.stratus.local:9000
s3.access-key-id=svc-flink
s3.secret-access-key=<svc-flink-minio-secret>
s3.path-style-access=true
```

This file contains secrets unless rendered through the approved secret mechanism. Store it in a protected runtime path, not source control.

Update `/etc/stratus/flink/flink-conf.yaml`:

```yaml
execution.checkpointing.interval: 30s
execution.checkpointing.mode: EXACTLY_ONCE
execution.checkpointing.timeout: 10min
state.backend.type: rocksdb
state.checkpoints.dir: file:///data/flink/checkpoints
state.savepoints.dir: file:///data/flink/savepoints
```

For production, checkpoint and savepoint paths should be moved to approved durable shared storage. The verification lab may use local paths only if restart tests are scoped to the same host set.

---

## 7. Iceberg Table Provisioning

Create streaming-owned verification tables through Polaris before starting the Flink job.

### `bronze.verification_customer_account_cdc`

Purpose: append one row per Debezium source change.

Required columns:

| Column | Type | Notes |
|---|---|---|
| `event_id` | string | deterministic id from topic, partition, offset |
| `source_topic` | string | Kafka topic |
| `source_partition` | int | Kafka partition |
| `source_offset` | long | Kafka offset |
| `operation` | string | Debezium operation code |
| `customer_id` | long | source primary key |
| `email` | string | current email value where present |
| `status` | string | current status where present |
| `event_time` | timestamp | source event timestamp |
| `ingested_at` | timestamp | Flink processing timestamp |
| `pipeline_run_id` | string | Flink job/checkpoint identifier |

Recommended partitioning:

```text
days(event_time)
```

### `silver.verification_customer_account_current`

Purpose: current-state table keyed by `customer_id`.

Required columns:

| Column | Type | Notes |
|---|---|---|
| `customer_id` | long | primary business key |
| `email` | string | current email |
| `status` | string | current status |
| `last_operation` | string | latest CDC operation |
| `last_event_time` | timestamp | latest event timestamp |
| `updated_at` | timestamp | Flink update timestamp |

Silver current-state writes require an explicit upsert/merge strategy. If the selected Flink/Iceberg writer cannot safely update the current-state table for the approved runtime, Increment 11 should accept the bronze append table first and defer silver current-state materialization to a Spark or Trino batch job until the write mode is proven.

---

## 8. Streaming Job Design

The verification job has two stages:

1. Consume Debezium events from `cdc.verification.postgres.public.customer_account`.
2. Write normalized append records to `bronze.verification_customer_account_cdc`.

Optional second stage:

3. Maintain `silver.verification_customer_account_current` if the approved Flink/Iceberg runtime supports the required update semantics.

Job requirements:

- read Kafka with `svc-flink`
- use Debezium source timestamp where available
- assign deterministic `event_id` from topic/partition/offset
- write through Polaris REST catalog
- commit Iceberg snapshots on successful checkpoints
- expose processed, late, failed, and committed record metrics
- fail closed if Polaris, MinIO, Kafka, or TLS trust fails
- write quality results for accepted and rejected records

Pseudocode:

```text
KafkaSource<DebeziumEvent>
  -> parse and validate
  -> map to CustomerAccountCdcRecord
  -> write rejected records to quality result path
  -> IcebergSink.forRowData(bronze.verification_customer_account_cdc)
```

---

## 9. Quality Checks

Streaming quality checks are lightweight and continuous.

Required checks:

| Check | Severity | Action |
|---|---|---|
| `customer_id` present | blocking | reject record and write FAIL result |
| operation in allowed set | blocking | reject record and write FAIL result |
| event timestamp present | warning | use processing time and write WARN result |
| email present for active customer | warning | write WARN result |
| duplicate topic/partition/offset | blocking | reject duplicate and write FAIL result |

Quality results must be written to `platform.quality_check_results` with:

- dataset name
- Flink job id
- checkpoint id where available
- Kafka topic/partition/offset
- Iceberg snapshot id after commit where available
- severity
- status
- detail

If continuous writes to `platform.quality_check_results` create too many small files, buffer quality results and write them at checkpoint cadence.

---

## 10. Operational Checks

### Polaris table resolution

```bash
/opt/flink/bin/sql-client.sh embedded
```

Then:

```sql
CREATE CATALOG stratus WITH (
  'type'='iceberg',
  'catalog-impl'='org.apache.iceberg.rest.RESTCatalog',
  'uri'='https://polaris.stratus.local:8181/api/catalog',
  'warehouse'='stratus',
  'credential'='svc-flink:<polaris-client-secret>',
  'scope'='PRINCIPAL_ROLE:ALL',
  'io-impl'='org.apache.iceberg.aws.s3.S3FileIO',
  's3.endpoint'='https://minio1.stratus.local:9000',
  's3.path-style-access'='true'
);

USE CATALOG stratus;
SHOW DATABASES;
```

Expected: `bronze`, `silver`, `gold`, and `platform` namespaces are visible according to `svc-flink` permissions.

### Run streaming write job

```bash
/opt/flink/bin/flink run \
  -Dpipeline.name=stratus-verification-cdc-to-iceberg \
  -c com.stratus.flink.VerificationCdcToIcebergJob \
  /opt/stratus/jobs/stratus-flink-iceberg-verification.jar \
  --source-topic cdc.verification.postgres.public.customer_account \
  --bronze-table bronze.verification_customer_account_cdc \
  --iceberg-config /etc/flink/iceberg.properties \
  --kafka-client-config /etc/flink/kafka-client.properties
```

Expected: job reaches `RUNNING`, consumes CDC events, and completes checkpoints.

### Trino visibility

```sql
SELECT count(*)
FROM stratus.bronze.verification_customer_account_cdc;

SELECT customer_id, status, event_time
FROM stratus.bronze.verification_customer_account_cdc
ORDER BY event_time DESC
LIMIT 10;
```

Expected: Trino sees only committed records after Iceberg snapshot publication.

### Snapshot check

```sql
SELECT committed_at, snapshot_id, operation
FROM stratus.bronze."verification_customer_account_cdc$snapshots"
ORDER BY committed_at DESC
LIMIT 5;
```

Expected: new snapshots appear after successful Flink checkpoints.

---

## 11. Java Verification Suite

The verification suite validates behavior across Kafka, Flink, Iceberg, Polaris, MinIO, and Trino.

### Maven dependencies

```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-streaming-java</artifactId>
    <version>2.1.1</version>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>org.apache.iceberg</groupId>
    <artifactId>iceberg-flink-runtime-2.1</artifactId>
    <version>1.11.0</version>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-clients</artifactId>
    <version>4.3.1</version>
</dependency>
<dependency>
    <groupId>io.trino</groupId>
    <artifactId>trino-jdbc</artifactId>
    <version>482</version>
</dependency>
<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <version>3.27.3</version>
    <scope>test</scope>
</dependency>
```

### Required tests

| Test class | Required assertions |
|---|---|
| `FlinkIcebergCatalogVerificationTest` | Flink resolves Polaris catalog and target tables |
| `StreamingBronzeWriteVerificationTest` | inserted CDC event appears in bronze Iceberg table |
| `IcebergSnapshotCommitVerificationTest` | new snapshot appears only after checkpoint completion |
| `TrinoStreamingVisibilityVerificationTest` | Trino reads committed streaming records |
| `FlinkRestartSafetyVerificationTest` | restart from checkpoint/savepoint does not duplicate source offsets beyond documented semantics |
| `StreamingQualityResultVerificationTest` | bad record produces quality result and does not corrupt bronze table |
| `SmallFileControlVerificationTest` | file count and average file size stay within verification thresholds |

### Environment variables

| Variable | Example |
|---|---|
| `STRATUS_FLINK_REST_URL` | `http://flink-jobmanager.stratus.local:8081` |
| `STRATUS_KAFKA_BOOTSTRAP` | `kafka1.stratus.local:9092,kafka2.stratus.local:9092,kafka3.stratus.local:9092` |
| `STRATUS_CDC_TOPIC` | `cdc.verification.postgres.public.customer_account` |
| `STRATUS_POLARIS_URI` | `https://polaris.stratus.local:8181/api/catalog` |
| `STRATUS_POLARIS_CLIENT_ID` | `svc-flink` |
| `STRATUS_POLARIS_CLIENT_SECRET` | client secret |
| `STRATUS_TRINO_JDBC_URL` | `jdbc:trino://trino-coordinator.stratus.local:8443/stratus` |
| `STRATUS_TRINO_USER` | verification user |
| `STRATUS_TRINO_PASSWORD` | verification password |

---

## 12. Small-File and Commit Controls

Streaming writes can create file and metadata pressure. Increment 11 must define thresholds before signoff.

Minimum controls:

| Control | Requirement |
|---|---|
| checkpoint interval | starts at 30 seconds for verification; tune per workload |
| target file size | record target in table properties or writer config |
| max files per checkpoint | define threshold for verification workload |
| snapshot retention | coordinated with Phase 1 maintenance policy |
| compaction owner | Airflow/Spark maintenance owns compaction unless a Flink maintenance job is explicitly approved |
| active writer protection | maintenance jobs must not rewrite files in a way that conflicts with active Flink commits |

Operational metrics:

- snapshots per hour
- data files per snapshot
- average file size
- manifest count
- commit duration
- failed commit count
- checkpoint duration

---

## 13. Observability

Minimum dashboard signals:

| Signal | Why it matters |
|---|---|
| Flink job state | streaming pipeline health |
| Kafka consumer lag | source freshness |
| checkpoint completion | exactly-once progress |
| checkpoint duration | state pressure |
| Iceberg commit duration | catalog/object-store pressure |
| failed commits | table write reliability |
| snapshots per hour | metadata growth |
| files per snapshot | small-file risk |
| Trino freshness query | reader-visible lag |
| quality failures | rejected or malformed records |

Minimum alerts:

- Flink job not running
- Kafka lag above threshold
- checkpoint failures above tolerance
- Iceberg commit failures
- snapshot creation stalled
- file count above threshold
- Trino freshness lag above threshold
- quality failure spike
- Polaris unavailable
- MinIO write failures

---

## 14. Completion Gate

Increment 11 is complete when:

- [ ] Flink runtime, Kafka connector, and Iceberg runtime versions are pinned and compatible
- [ ] Flink Iceberg image includes `iceberg-flink-runtime-2.1` 1.11.0 and `iceberg-aws-bundle` 1.11.0
- [ ] `svc-flink` resolves tables through Polaris REST catalog
- [ ] `svc-flink` writes data files only to approved MinIO locations
- [ ] streaming-owned bronze table exists and is documented
- [ ] optional silver current-state table is either implemented safely or explicitly deferred
- [ ] Flink job consumes Debezium CDC topic and writes bronze Iceberg records
- [ ] Iceberg snapshots are committed after successful checkpoints
- [ ] Trino sees committed streaming records
- [ ] restart from checkpoint/savepoint does not create unacceptable duplicates
- [ ] streaming quality results are written to `platform.quality_check_results`
- [ ] small-file thresholds are defined and verification workload stays within them
- [ ] Spark/Airflow maintenance conflict rules are documented
- [ ] Java verification suite passes
- [ ] operational dashboards and alerts exist

When all gates are checked, Increment 12 (Atlas Event Bus and Lineage Automation) can begin.

---

## 15. Troubleshooting

### Flink cannot resolve the Polaris catalog

- Confirm `iceberg-flink-runtime-2.1` is present in `/opt/flink/lib`.
- Confirm Polaris URI uses the REST catalog endpoint.
- Confirm `svc-flink` Polaris credentials and scope.
- Confirm the Flink container trusts the Polaris TLS certificate.

### Flink writes files but table is not visible in Trino

- Confirm Flink is committing through the Polaris catalog, not writing direct paths.
- Confirm checkpoints are completing.
- Confirm Trino uses the same Polaris catalog and warehouse.
- Check Iceberg snapshots metadata for committed snapshots.

### Duplicates appear after restart

- Confirm the job restarted from a checkpoint or savepoint.
- Confirm Kafka offsets are part of Flink checkpoint state.
- Confirm `event_id` is deterministic from topic, partition, and offset.
- Confirm downstream current-state logic handles repeated source events idempotently.

### File count grows too quickly

- Increase checkpoint interval for low-volume streams.
- Tune writer target file size where supported.
- Confirm compaction schedule is configured and streaming-safe.
- Avoid one table per tiny source unless there is a real governance or ownership reason.

### Quality results create small files

- Buffer quality writes until checkpoint cadence.
- Write quality summaries instead of one row per accepted event.
- Keep full rejected payloads in Kafka DLQ and write compact quality references to Iceberg.

---

## 16. References

- Stratus Phase 2 implementation plan: [stratus_implementation_plan_phase2.md](stratus_implementation_plan_phase2.md)
- Increment 8 - Kafka Event Backbone: [increment8_kafka_event_backbone.md](increment8_kafka_event_backbone.md)
- Increment 9 - Kafka Connect and Debezium CDC: [increment9_kafka_connect_debezium.md](increment9_kafka_connect_debezium.md)
- Increment 10 - Flink Streaming Compute: [increment10_flink_streaming_compute.md](increment10_flink_streaming_compute.md)
- Increment 2 - Iceberg and Polaris: [increment2_iceberg_polaris.md](increment2_iceberg_polaris.md)
- Increment 5 - Trino: [increment5_trino.md](increment5_trino.md)
- Apache Iceberg Flink integration: https://iceberg.apache.org/docs/latest/flink/
- Apache Iceberg Flink writes: https://iceberg.apache.org/docs/latest/flink-writes/
- Apache Iceberg Flink configuration: https://iceberg.apache.org/docs/latest/flink-configuration/
- Apache Iceberg table maintenance: https://iceberg.apache.org/docs/latest/maintenance/
- Apache Polaris documentation: https://polaris.apache.org/
