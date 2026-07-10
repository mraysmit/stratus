# Stratus Increment 10 - Flink Streaming Compute

## 1. Purpose

This document is the technical implementation plan for Increment 10 of the Stratus platform as defined in [stratus_implementation_plan_phase2.md](stratus_implementation_plan_phase2.md).

Increment 10 delivers Apache Flink as the stateful streaming compute runtime for Phase 2. Flink consumes Kafka topics created by Increment 8 and populated by Increment 9, applies event-time and stateful processing, and prepares data for governed Iceberg writes in Increment 11. When this increment is complete, the platform has a secure Flink cluster with checkpointing, savepoints, Kafka consumption, metrics, and a verification streaming job.

Flink is not the batch ETL engine and it is not the scheduler. Spark remains the batch engine. Airflow remains the orchestrator for bounded workflows and operational control-plane jobs. Flink owns long-running streaming jobs.

**Prerequisites:**
- Increment 8 complete - Kafka event backbone operational
- Increment 9 complete - Kafka Connect and Debezium CDC operational
- Phase 1 identity and TLS hardening complete
- `svc-flink` service identity exists in FreeIPA and Kafka SCRAM/ACLs
- Checkpoint storage path approved
- Observability stack can scrape Flink metrics

---

## 2. Assumptions and Prerequisites

- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- Podman 4.x installed on each Flink host
- JDK 25 and Maven 3.9+ on the approved build worker; the verification host requires only the approved container runtime and verifier runtime inputs. Flink job artifacts are compiled with the build-system toolchain to the Java release supported by the selected Flink runtime.
- Kafka client truststore from Increment 8 is available
- `svc-flink` can read the verification event and CDC topics
- `svc-flink` can write to the approved checkpoint and savepoint storage path
- Prometheus can scrape Flink metrics
- Flink jobs use Kafka as the source in this increment; Iceberg writes begin in Increment 11

DNS names used in this increment:

| Host | Role |
|---|---|
| `flink-jobmanager.stratus.local` | Flink JobManager |
| `flink-taskmanager1.stratus.local` | Flink TaskManager |
| `flink-taskmanager2.stratus.local` | Flink TaskManager |

### Reference documentation audit

Reference baseline: 2026-07-05.

The approved Flink compatibility target for this increment is Flink 2.1.1 with Flink Kafka Connector 5.0.0. Connector compatibility matters more than release recency: Flink Kafka Connector 5.0.0 is listed as compatible with Flink 2.1.x and 2.2.x, and Iceberg 1.11.0 publishes a Flink 2.1 runtime artifact for streaming table writes in Increment 11.

Do not target Flink 2.3.0 for this increment until the Kafka connector, Iceberg runtime, and verification jobs are confirmed compatible with that line.

---

## 3. Cluster Topology

Increment 10 uses one JobManager and two TaskManagers.

```text
flink-jobmanager.stratus.local
┌──────────────────────────────────────────────┐
│  Podman: flink-jobmanager                    │
│  Web UI / REST API :8081                     │
│  RPC / coordination                          │
│  Metrics :9249                               │
└──────────────────────────────────────────────┘
          │
          │ job coordination
          ▼
flink-taskmanager1       flink-taskmanager2
┌─────────────────┐      ┌─────────────────┐
│ TaskManager     │      │ TaskManager     │
│ slots / state   │      │ slots / state   │
│ metrics :9249   │      │ metrics :9249   │
└─────────────────┘      └─────────────────┘
          │                       │
          └──────────┬────────────┘
                     │ consumes Kafka topics
                     ▼
           Kafka event backbone
```

This increment validates Flink runtime behavior and Kafka consumption. It writes verification output to logs or a simple validation sink. Iceberg table writes are deliberately deferred to Increment 11 so checkpointing and job lifecycle are proven first.

---

## 4. Ports

| Port | Service | Purpose |
|---|---|---|
| 8081 | JobManager | Web UI and REST API |
| 6123 | JobManager | RPC endpoint |
| 6124 | TaskManager | Data exchange |
| 9249 | JobManager and TaskManagers | Prometheus metrics |
| 9092 | Kafka brokers | outbound Kafka client traffic |

Restrict JobManager UI and REST API to platform operators and automation.

---

## 5. Flink Image and Artifact Policy

The approved build system builds a pinned internal image for Flink and the connector set. Flink runtime hosts never download connectors or build the image.

Target artifacts:

| Artifact | Target |
|---|---|
| Apache Flink | 2.1.1 |
| Flink Kafka Connector | 5.0.0 |
| JDK | Java 17 runtime for Flink 2.1; Java 25 build toolchain with Flink job modules compiled using `--release 17` |
| Prometheus metrics reporter | bundled or pinned compatible reporter |

Example image tag:

```bash
podman build -t stratus/flink:2.1.1-kafka5.0.0 docker/flink
```

This is a build-pipeline command. The pipeline tests, scans, publishes, and records the image digest before deployment.

The image must include:

- Flink runtime
- Flink Kafka connector
- Kafka client libraries compatible with the connector
- platform CA trust bundle
- no plugin downloads at container startup

The implementation runbook must record image tag, digest, Flink version, connector version, JDK version, and artifact checksums.

Java 25 is the Stratus build and verifier baseline. Flink 2.1 recommends Java 17 and supports Java 21, but does not document Java 25 support; its runtime therefore remains Java 17 until an approved Flink release supports Java 25. This exception must be retested when the Flink version changes.

---

## 6. Directory Layout

Create directories on Flink hosts:

```bash
sudo mkdir -p /etc/stratus/flink
sudo mkdir -p /etc/stratus/flink/secrets
sudo mkdir -p /etc/stratus/flink/certs
sudo mkdir -p /data/flink/checkpoints
sudo mkdir -p /data/flink/savepoints
sudo mkdir -p /data/flink/logs
sudo chown -R $USER:$USER /etc/stratus/flink /data/flink
chmod 750 /etc/stratus/flink /etc/stratus/flink/secrets /data/flink
```

Mounted layout:

```text
/etc/stratus/flink/
├── flink-conf.yaml
├── log4j-console.properties
├── kafka-client.properties
├── certs/
│   └── kafka.truststore.p12
└── secrets/
    ├── flink-scram.pass
    └── kafka-truststore.pass

/data/flink/
├── checkpoints/
├── savepoints/
└── logs/
```

For production-like deployment, checkpoint and savepoint paths should use durable shared storage. A local path is acceptable only for the first lab verification and must be replaced before production streaming jobs.

---

## 7. Flink Configuration

Create `/etc/stratus/flink/flink-conf.yaml`:

```yaml
jobmanager.rpc.address: flink-jobmanager.stratus.local
jobmanager.rpc.port: 6123
rest.address: flink-jobmanager.stratus.local
rest.port: 8081

taskmanager.numberOfTaskSlots: 4
parallelism.default: 2

state.backend.type: rocksdb
state.checkpoints.dir: file:///data/flink/checkpoints
state.savepoints.dir: file:///data/flink/savepoints
execution.checkpointing.interval: 30s
execution.checkpointing.mode: EXACTLY_ONCE
execution.checkpointing.timeout: 10min
execution.checkpointing.min-pause: 10s
execution.checkpointing.tolerable-failed-checkpoints: 3

restart-strategy.type: fixed-delay
restart-strategy.fixed-delay.attempts: 3
restart-strategy.fixed-delay.delay: 10s

metrics.reporter.prom.factory.class: org.apache.flink.metrics.prometheus.PrometheusReporterFactory
metrics.reporter.prom.port: 9249
```

When Ceph RGW-backed durable checkpoints are enabled, update `state.checkpoints.dir` and `state.savepoints.dir` to the approved object-storage path and add the required filesystem dependencies to the Flink image.

### Kafka client properties

Create `/etc/stratus/flink/kafka-client.properties`:

```properties
bootstrap.servers=kafka1.stratus.local:9092,kafka2.stratus.local:9092,kafka3.stratus.local:9092
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="svc-flink" password="${file:/etc/flink/secrets/flink-scram.pass:password}";
ssl.truststore.location=/etc/flink/certs/kafka.truststore.p12
ssl.truststore.password=${file:/etc/flink/secrets/kafka-truststore.pass:password}
ssl.truststore.type=PKCS12
ssl.endpoint.identification.algorithm=https
```

If the Flink Kafka connector does not support file expansion for JAAS configuration in the selected runtime, inject the rendered client properties file through the approved secret process at deployment time.

---

## 8. Podman Container Setup

Start the JobManager:

```bash
podman run -d \
  --name flink-jobmanager \
  --hostname flink-jobmanager.stratus.local \
  --network host \
  -v /etc/stratus/flink:/etc/flink:ro,z \
  -v /data/flink:/data/flink:z \
  --restart unless-stopped \
  stratus/flink:2.1.1-kafka5.0.0 \
  jobmanager
```

Start each TaskManager:

```bash
podman run -d \
  --name flink-taskmanager \
  --hostname flink-taskmanager1.stratus.local \
  --network host \
  -v /etc/stratus/flink:/etc/flink:ro,z \
  -v /data/flink:/data/flink:z \
  --restart unless-stopped \
  stratus/flink:2.1.1-kafka5.0.0 \
  taskmanager
```

Verify:

```bash
curl -s http://flink-jobmanager.stratus.local:8081/overview
curl -s http://flink-jobmanager.stratus.local:8081/taskmanagers
```

Expected: JobManager reports two TaskManagers and available task slots.

### Auto-start with systemd

```bash
podman generate systemd --new --name flink-jobmanager \
  | sudo tee /etc/systemd/system/stratus-flink-jobmanager.service

sudo systemctl daemon-reload
sudo systemctl enable --now stratus-flink-jobmanager.service
```

Generate equivalent units for each TaskManager.

---

## 9. Verification Streaming Job

The first Flink job consumes `app.verification.events.v1` from Increment 8, applies event-time parsing, counts events by key, and writes validation output to the job log. This avoids introducing Iceberg write semantics before Increment 11.

Job requirements:

- consume from Kafka with `svc-flink`
- use event timestamps from the message payload
- define a bounded out-of-order watermark
- checkpoint every 30 seconds
- maintain keyed count state
- expose processed-record and late-record metrics
- fail if Kafka authentication or TLS validation fails

Maven dependencies for the verification job:

```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-streaming-java</artifactId>
    <version>2.1.1</version>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-connector-kafka</artifactId>
    <version>5.0.0</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.20.1</version>
</dependency>
```

Submit the job:

```bash
/opt/flink/bin/flink run \
  -Dpipeline.name=stratus-verification-kafka-consumer \
  -c com.stratus.flink.VerificationKafkaConsumerJob \
  /opt/stratus/jobs/stratus-flink-verification.jar \
  --topic app.verification.events.v1 \
  --group flink.verification.consumer \
  --kafka-client-config /etc/flink/kafka-client.properties
```

Expected:

- job reaches `RUNNING`
- records produced to `app.verification.events.v1` are consumed
- checkpoints complete
- metrics show nonzero records in

---

## 10. Savepoint and Recovery Procedure

Trigger a savepoint:

```bash
/opt/flink/bin/flink savepoint <job-id> file:///data/flink/savepoints
```

Stop with savepoint:

```bash
/opt/flink/bin/flink stop --savepointPath file:///data/flink/savepoints <job-id>
```

Restart from savepoint:

```bash
/opt/flink/bin/flink run \
  -s file:///data/flink/savepoints/<savepoint-id> \
  -c com.stratus.flink.VerificationKafkaConsumerJob \
  /opt/stratus/jobs/stratus-flink-verification.jar \
  --topic app.verification.events.v1 \
  --group flink.verification.consumer \
  --kafka-client-config /etc/flink/kafka-client.properties
```

Expected: the job resumes from saved state and does not start over from the earliest Kafka offsets unless explicitly configured.

---

## 11. Operational Checks

### Cluster health

```bash
curl -s http://flink-jobmanager.stratus.local:8081/overview | jq
curl -s http://flink-jobmanager.stratus.local:8081/taskmanagers | jq
```

Expected: JobManager and both TaskManagers are visible.

### Job status

```bash
curl -s http://flink-jobmanager.stratus.local:8081/jobs | jq
```

Expected: verification job is `RUNNING`.

### Checkpoints

```bash
curl -s http://flink-jobmanager.stratus.local:8081/jobs/<job-id>/checkpoints | jq
```

Expected: completed checkpoints are present and failed checkpoints are within tolerance.

### Kafka group lag

```bash
/opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --describe \
  --group flink.verification.consumer
```

Expected: lag is low or zero after the job catches up.

---

## 12. Java Verification Suite

The Java source and Maven dependencies in this section are build inputs only. The approved build system publishes the executable verifier as a pinned container image. Operators execute that image and do not build on the verification host or inside the verification container.

The verification suite uses Flink REST, Kafka admin/producer APIs, and consumer group checks.

### Required tests

| Test class | Required assertions |
|---|---|
| `FlinkClusterHealthVerificationTest` | JobManager responds; two TaskManagers are registered |
| `FlinkKafkaJobVerificationTest` | verification job is running and consumes records produced to Kafka |
| `FlinkCheckpointVerificationTest` | at least one checkpoint completes successfully |
| `FlinkSavepointVerificationTest` | savepoint can be created and job can restart from it |
| `FlinkSecurityVerificationTest` | job fails or cannot consume when Kafka credentials are invalid |

### Environment variables

| Variable | Example |
|---|---|
| `STRATUS_FLINK_REST_URL` | `http://flink-jobmanager.stratus.local:8081` |
| `STRATUS_KAFKA_BOOTSTRAP` | `kafka1.stratus.local:9092,kafka2.stratus.local:9092,kafka3.stratus.local:9092` |
| `STRATUS_KAFKA_TRUSTSTORE` | `/etc/stratus/kafka/certs/kafka.truststore.p12` |
| `STRATUS_KAFKA_TRUSTSTORE_PASSWORD` | truststore password |
| `STRATUS_KAFKA_PRODUCER_USER` | `svc-app-producer` |
| `STRATUS_KAFKA_PRODUCER_PASSWORD` | producer password |
| `STRATUS_FLINK_JOB_NAME` | `stratus-verification-kafka-consumer` |

---

## 13. Observability

Minimum dashboard signals:

| Signal | Why it matters |
|---|---|
| JobManager health | cluster control plane |
| TaskManager count | worker capacity |
| running job count | lifecycle health |
| Kafka records consumed | source activity |
| Kafka consumer lag | freshness |
| checkpoint duration | state backend health |
| failed checkpoints | recovery risk |
| restart count | job stability |
| backpressure | throughput bottleneck |
| JVM heap and GC | runtime health |

Minimum alerts:

- JobManager unavailable
- TaskManager count below expected
- verification job not running
- Kafka consumer lag above threshold
- checkpoint failures above tolerance
- checkpoint duration above threshold
- repeated restarts
- backpressure sustained above threshold
- metrics scrape failure

---

## 14. Completion Gate

Increment 10 is complete when:

- [ ] Flink version and connector versions are pinned by image tag and digest
- [ ] Flink 2.1.1 compatibility decision is recorded against Kafka Connector 5.0.0 and Iceberg 1.11.0 Flink 2.1 runtime
- [ ] JobManager and two TaskManagers are running and managed by systemd
- [ ] Flink REST API is restricted to platform operators
- [ ] `svc-flink` can consume only approved Kafka topics and groups
- [ ] verification streaming job consumes Kafka events
- [ ] checkpointing completes successfully
- [ ] savepoint creation and restore are verified
- [ ] job recovers from TaskManager failure
- [ ] Kafka consumer lag is visible
- [ ] Prometheus and Grafana expose Flink cluster and job metrics
- [ ] Java verification suite passes
- [ ] operational runbook covers submit, stop, drain, savepoint, restore, restart, and upgrade

When all gates are checked, Increment 11 (Streaming Writes to Iceberg) can begin.

---

## 15. Troubleshooting

### TaskManagers do not register

- Confirm `jobmanager.rpc.address` resolves from TaskManager hosts.
- Confirm ports `6123` and `6124` are reachable.
- Confirm all containers use the same Flink configuration.

### Job cannot consume Kafka

- Confirm `svc-flink` SCRAM credentials.
- Confirm Kafka ACLs include topic read and group read.
- Confirm truststore contains the FreeIPA CA.
- Confirm Kafka broker hostnames match certificate SANs.

### Checkpoints fail

- Confirm checkpoint path exists and is writable by the Flink container.
- Confirm state backend dependencies are present.
- Confirm checkpoint timeout is not too low for the workload.
- Check logs for serialization or state backend errors.

### Savepoint restore fails

- Confirm the job JAR and operator UIDs are compatible with the savepoint.
- Confirm savepoint path is reachable from JobManager and TaskManagers.
- Confirm the job was stopped cleanly or the savepoint completed successfully.

### Consumer lag grows

- Check TaskManager CPU, heap, GC, and backpressure.
- Increase parallelism only after confirming partitions and task slots support it.
- Confirm downstream sink or logging output is not the bottleneck.

---

## 16. References

- Stratus Phase 2 implementation plan: [stratus_implementation_plan_phase2.md](stratus_implementation_plan_phase2.md)
- Increment 8 - Kafka Event Backbone: [increment8_kafka_event_backbone.md](increment8_kafka_event_backbone.md)
- Increment 9 - Kafka Connect and Debezium CDC: [increment9_kafka_connect_debezium.md](increment9_kafka_connect_debezium.md)
- Apache Flink downloads: https://flink.apache.org/downloads/
- Apache Flink documentation: https://nightlies.apache.org/flink/flink-docs-stable/
- Apache Flink Kafka connector: https://nightlies.apache.org/flink/flink-docs-stable/docs/connectors/datastream/kafka/
- Apache Flink operations: https://nightlies.apache.org/flink/flink-docs-stable/docs/deployment/overview/
- Apache Kafka documentation: https://kafka.apache.org/documentation/
