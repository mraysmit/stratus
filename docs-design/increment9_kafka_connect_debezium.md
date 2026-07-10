# Stratus Increment 9 - Kafka Connect and Debezium CDC

## 1. Purpose

This document is the technical implementation plan for Increment 9 of the Stratus platform as defined in [stratus_implementation_plan_phase2.md](stratus_implementation_plan_phase2.md).

Increment 9 delivers Kafka Connect in distributed mode and Debezium CDC connectors. Kafka Connect provides the managed connector runtime. Debezium captures row-level source database changes and publishes them into the Kafka event backbone created in Increment 8. When this increment is complete, a PostgreSQL verification source can be snapshotted, inserts/updates/deletes are emitted to Kafka topics, connector offsets survive worker restarts, and operators can monitor connector health, lag, errors, and dead-letter routing.

Kafka Connect and Debezium do not replace Spark, Flink, Polaris, Iceberg, or Airflow. They move source-system changes into Kafka. Flink consumes those topics in Increment 10 and writes governed Iceberg tables in Increment 11.

**Prerequisites:**
- Increment 8 complete - Kafka KRaft cluster running with TLS, SASL/SCRAM, ACLs, topic standards, and monitoring
- Phase 1 identity, TLS, and operational readiness complete
- PostgreSQL verification source database available
- Source database owner has approved CDC permissions
- Kafka topics and ACL patterns from Increment 8 are available

---

## 2. Assumptions and Prerequisites

- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- Podman 4.x installed on each Kafka Connect host
- JDK 25 and Maven 3.9+ on the approved build worker; the verification host requires only the approved container runtime and verifier runtime inputs. Kafka Connect uses the Java 25 runtime inherited from the approved Kafka 4.3 image; connector compatibility is verified in the image pipeline.
- Kafka client truststore from Increment 8 is available
- `svc-connect` SCRAM user exists in Kafka
- `svc-connect` has access to Connect internal topics and connector-created CDC topics
- Source database credentials are stored in protected runtime configuration, not source control
- PostgreSQL logical replication is enabled for the verification source
- Prometheus can scrape Kafka Connect metrics

DNS names used in this increment:

| Host | Role |
|---|---|
| `connect1.stratus.local` | Kafka Connect worker |
| `connect2.stratus.local` | Kafka Connect worker |
| `connect3.stratus.local` | Kafka Connect worker |

### Reference documentation audit

Reference baseline: 2026-07-05.

The approved Debezium compatibility target for this increment is the Debezium 3.6 series with Kafka Connect bundled from the selected Kafka 4.3.1 runtime. The exact Debezium patch artifact must be pinned in the Phase 2 version matrix.

Before implementation, confirm the exact Debezium 3.6 patch artifact, Kafka Connect compatibility notes, PostgreSQL connector properties, source database versions, and connector image build path. If a newer Debezium stable series is selected, update this document, plugin artifacts, test expectations, and connector templates together.

---

## 3. Cluster Topology

Kafka Connect runs as a three-worker distributed cluster.

```text
connect1.stratus.local
┌──────────────────────────────────────────────┐
│  Podman: kafka-connect                       │
│  REST API :8083                              │
│  Debezium PostgreSQL connector plugin        │
│  Kafka client TLS/SASL to brokers :9092      │
└──────────────────────────────────────────────┘
       ▲                 ▲
       │ group rebalance │ connector tasks
       ▼                 ▼
connect2.stratus.local  connect3.stratus.local
┌─────────────────┐     ┌─────────────────┐
│ Connect worker  │     │ Connect worker  │
│ :8083           │     │ :8083           │
└─────────────────┘     └─────────────────┘

Source PostgreSQL -> Debezium task -> Kafka CDC topics
```

Connect workers coordinate through Kafka. Connector configuration, offsets, and status are stored in Kafka internal topics. There is no external Connect database.

---

## 4. Ports

| Port | Purpose | Access |
|---|---|---|
| 8083 | Kafka Connect REST API | platform operators and automation only |
| 9405 | Prometheus metrics endpoint | monitoring network only |
| 9092 | Kafka broker access | outbound from Connect workers |
| 5432 | PostgreSQL verification source | outbound from Connect workers |

The Connect REST API should not be exposed to analyst or general user networks.

---

## 5. Kafka Connect Image

The approved build system builds a pinned internal Kafka Connect image from the approved Kafka runtime and Debezium plugin artifacts. Kafka Connect runtime hosts never assemble plugins or build this image.

The image must include:

- Kafka Connect from Kafka 4.3.1 or the approved Kafka release
- Debezium connector plugin 3.6 for PostgreSQL
- JMX exporter Java agent
- trusted platform CA bundle
- no floating plugin downloads at container startup

Example image build target:

```bash
podman build -t stratus/kafka-connect-debezium:4.3.1-3.6 docker/kafka-connect
```

This is a build-pipeline command. The pipeline tests, scans, publishes, and records the image digest before deployment.

The implementation runbook must record:

- Kafka Connect version
- Debezium version and connector artifacts
- PostgreSQL JDBC driver version
- JDK version
- image tag and digest
- plugin checksums

---

## 6. Directory Layout

Create persistent directories on each Connect host:

```bash
sudo mkdir -p /etc/stratus/connect
sudo mkdir -p /etc/stratus/connect/secrets
sudo mkdir -p /etc/stratus/connect/certs
sudo mkdir -p /data/connect
sudo chown -R $USER:$USER /etc/stratus/connect /data/connect
chmod 750 /etc/stratus/connect /etc/stratus/connect/secrets /data/connect
```

Mounted layout:

```text
/etc/stratus/connect/
├── connect-distributed.properties
├── client-connect.properties
├── jmx-exporter.yml
├── connectors/
│   └── verification-postgres-source.json
├── certs/
│   └── kafka.truststore.p12
└── secrets/
    ├── connect-scram.pass
    ├── kafka-truststore.pass
    └── verification-postgres.properties
```

Connector JSON files may live in source control only if they contain no passwords. Source database credentials must be injected from protected runtime files or the approved secret mechanism.

---

## 7. Connect Internal Topics and ACLs

Create Connect internal topics before starting workers:

```bash
/opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --create \
  --topic _connect.phase2.configs \
  --partitions 1 \
  --replication-factor 3 \
  --config cleanup.policy=compact \
  --config min.insync.replicas=2

/opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --create \
  --topic _connect.phase2.offsets \
  --partitions 25 \
  --replication-factor 3 \
  --config cleanup.policy=compact \
  --config min.insync.replicas=2

/opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --create \
  --topic _connect.phase2.status \
  --partitions 5 \
  --replication-factor 3 \
  --config cleanup.policy=compact \
  --config min.insync.replicas=2
```

Grant `svc-connect` read/write/describe access to these topics and to connector-managed CDC topics:

```bash
/opt/kafka/bin/kafka-acls.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --add \
  --allow-principal User:svc-connect \
  --operation Read \
  --operation Write \
  --operation Describe \
  --topic _connect.phase2. \
  --resource-pattern-type prefixed

/opt/kafka/bin/kafka-acls.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --add \
  --allow-principal User:svc-connect \
  --operation Read \
  --operation Write \
  --operation Describe \
  --operation Create \
  --topic cdc. \
  --resource-pattern-type prefixed
```

If topic creation is restricted to platform automation, remove `Create` and pre-create CDC topics with approved definitions.

---

## 8. Worker Configuration

Create `/etc/stratus/connect/connect-distributed.properties` on each worker:

```properties
bootstrap.servers=kafka1.stratus.local:9092,kafka2.stratus.local:9092,kafka3.stratus.local:9092
group.id=stratus-connect-phase2

config.storage.topic=_connect.phase2.configs
offset.storage.topic=_connect.phase2.offsets
status.storage.topic=_connect.phase2.status

config.storage.replication.factor=3
offset.storage.replication.factor=3
status.storage.replication.factor=3

key.converter=org.apache.kafka.connect.json.JsonConverter
value.converter=org.apache.kafka.connect.json.JsonConverter
key.converter.schemas.enable=true
value.converter.schemas.enable=true

plugin.path=/opt/kafka/plugins

rest.host.name=connect1.stratus.local
rest.port=8083
rest.advertised.host.name=connect1.stratus.local
rest.advertised.port=8083

security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="svc-connect" password="${file:/etc/connect/secrets/connect-scram.pass:password}";
ssl.truststore.location=/etc/connect/certs/kafka.truststore.p12
ssl.truststore.password=${file:/etc/connect/secrets/kafka-truststore.pass:password}
ssl.truststore.type=PKCS12
ssl.endpoint.identification.algorithm=https

producer.security.protocol=SASL_SSL
producer.sasl.mechanism=SCRAM-SHA-512
producer.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="svc-connect" password="${file:/etc/connect/secrets/connect-scram.pass:password}";
producer.ssl.truststore.location=/etc/connect/certs/kafka.truststore.p12
producer.ssl.truststore.password=${file:/etc/connect/secrets/kafka-truststore.pass:password}
producer.ssl.truststore.type=PKCS12
producer.ssl.endpoint.identification.algorithm=https
producer.acks=all
producer.enable.idempotence=true

consumer.security.protocol=SASL_SSL
consumer.sasl.mechanism=SCRAM-SHA-512
consumer.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="svc-connect" password="${file:/etc/connect/secrets/connect-scram.pass:password}";
consumer.ssl.truststore.location=/etc/connect/certs/kafka.truststore.p12
consumer.ssl.truststore.password=${file:/etc/connect/secrets/kafka-truststore.pass:password}
consumer.ssl.truststore.type=PKCS12
consumer.ssl.endpoint.identification.algorithm=https

errors.tolerance=none
errors.log.enable=true
errors.log.include.messages=false
```

Change `rest.host.name` and `rest.advertised.host.name` per worker.

For production connectors, dead-letter behavior is configured per connector after the product owner decides whether malformed records should fail fast or route to a DLQ.

---

## 9. Podman Container Setup

Start each worker:

```bash
podman run -d \
  --name kafka-connect \
  --hostname connect1.stratus.local \
  --network host \
  -v /etc/stratus/connect/connect-distributed.properties:/etc/connect/connect-distributed.properties:ro,z \
  -v /etc/stratus/connect/certs:/etc/connect/certs:ro,z \
  -v /etc/stratus/connect/secrets:/etc/connect/secrets:ro,z \
  -v /etc/stratus/connect/jmx-exporter.yml:/etc/connect/jmx-exporter.yml:ro,z \
  -v /data/connect:/data/connect:z \
  -e KAFKA_OPTS="-javaagent:/opt/jmx-exporter/jmx_prometheus_javaagent.jar=9405:/etc/connect/jmx-exporter.yml" \
  --restart unless-stopped \
  stratus/kafka-connect-debezium:4.3.1-3.6 \
  /opt/kafka/bin/connect-distributed.sh /etc/connect/connect-distributed.properties
```

Verify:

```bash
curl -s http://connect1.stratus.local:8083/
curl -s http://connect1.stratus.local:8083/connector-plugins
```

Expected:

- worker REST API responds
- Debezium PostgreSQL connector plugin is listed
- no connector task failures appear in logs

### Auto-start with systemd

```bash
podman generate systemd --new --name kafka-connect \
  | sudo tee /etc/systemd/system/stratus-kafka-connect.service

sudo systemctl daemon-reload
sudo systemctl enable --now stratus-kafka-connect.service
```

Repeat on each Connect host.

---

## 10. Verification PostgreSQL Source

Configure the PostgreSQL verification database for logical replication.

Minimum PostgreSQL settings:

```properties
wal_level=logical
max_wal_senders=10
max_replication_slots=10
```

Create a CDC user:

```sql
CREATE ROLE svc_debezium WITH LOGIN REPLICATION PASSWORD '<protected password>';
GRANT CONNECT ON DATABASE stratus_cdc_verification TO svc_debezium;
GRANT USAGE ON SCHEMA public TO svc_debezium;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO svc_debezium;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO svc_debezium;
```

Create the verification table:

```sql
CREATE TABLE public.customer_account (
    customer_id bigint PRIMARY KEY,
    email text NOT NULL,
    status text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
```

Create publication:

```sql
CREATE PUBLICATION stratus_verification_pub FOR TABLE public.customer_account;
```

---

## 11. Debezium PostgreSQL Connector

Create `/etc/stratus/connect/connectors/verification-postgres-source.json`:

```json
{
  "name": "verification-postgres-cdc",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "tasks.max": "1",
    "database.hostname": "postgres-source.stratus.local",
    "database.port": "5432",
    "database.user": "svc_debezium",
    "database.password": "${file:/etc/connect/secrets/verification-postgres.properties:password}",
    "database.dbname": "stratus_cdc_verification",
    "topic.prefix": "cdc.verification.postgres",
    "plugin.name": "pgoutput",
    "publication.name": "stratus_verification_pub",
    "slot.name": "stratus_verification_slot",
    "schema.include.list": "public",
    "table.include.list": "public.customer_account",
    "snapshot.mode": "initial",
    "tombstones.on.delete": "true",
    "decimal.handling.mode": "string",
    "time.precision.mode": "adaptive_time_microseconds",
    "provide.transaction.metadata": "true",
    "heartbeat.interval.ms": "10000",
    "errors.tolerance": "all",
    "errors.deadletterqueue.topic.name": "dlq.cdc.verification.postgres.public.customer_account.v1",
    "errors.deadletterqueue.context.headers.enable": "true",
    "errors.log.enable": "true",
    "errors.log.include.messages": "false"
  }
}
```

Register the connector:

```bash
curl -s -X PUT \
  http://connect1.stratus.local:8083/connectors/verification-postgres-cdc/config \
  -H "Content-Type: application/json" \
  --data @/etc/stratus/connect/connectors/verification-postgres-source.json
```

The resulting topic is:

```text
cdc.verification.postgres.public.customer_account
```

If the platform requires the `cdc.<source>.<schema>.<table>.v<version>` pattern exactly, add a single-message transform to route the Debezium topic to the approved topic name and document that mapping in the connector runbook.

---

## 12. Operational Checks

### Worker health

```bash
curl -s http://connect1.stratus.local:8083/ | jq
curl -s http://connect1.stratus.local:8083/connectors | jq
```

Expected: Connect responds and lists `verification-postgres-cdc`.

### Connector status

```bash
curl -s http://connect1.stratus.local:8083/connectors/verification-postgres-cdc/status | jq
```

Expected: connector and task state are `RUNNING`.

### Internal topics

```bash
/opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --describe --topic _connect.phase2.offsets
```

Expected: replication factor 3, compacted cleanup, and healthy ISR.

### CDC topic

```bash
/opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --consumer.config /etc/stratus/kafka/client-flink.properties \
  --topic cdc.verification.postgres.public.customer_account \
  --from-beginning \
  --max-messages 1
```

Expected: at least one snapshot or change event appears after the connector starts.

---

## 13. Java Verification Suite

The Java source and Maven dependencies in this section are build inputs only. The approved build system publishes the executable verifier as a pinned container image. Operators execute that image and do not build on the verification host or inside the verification container.

The verification suite uses Kafka Connect REST and Kafka consumer APIs to prove CDC behavior.

### Maven dependencies

```xml
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-clients</artifactId>
    <version>4.3.1</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.20.1</version>
</dependency>
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.13.4</version>
    <scope>test</scope>
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
| `KafkaConnectHealthVerificationTest` | worker REST API responds; Debezium PostgreSQL plugin is listed; connector task is `RUNNING` |
| `DebeziumPostgresCdcVerificationTest` | insert, update, and delete in PostgreSQL produce expected Kafka events |
| `ConnectOffsetRecoveryVerificationTest` | worker restart does not replay from the beginning after committed offsets |
| `ConnectErrorHandlingVerificationTest` | malformed or rejected records follow the configured fail/DLQ policy |

### Environment variables

| Variable | Example |
|---|---|
| `STRATUS_CONNECT_URL` | `http://connect1.stratus.local:8083` |
| `STRATUS_KAFKA_BOOTSTRAP` | `kafka1.stratus.local:9092,kafka2.stratus.local:9092,kafka3.stratus.local:9092` |
| `STRATUS_KAFKA_TRUSTSTORE` | `/etc/stratus/kafka/certs/kafka.truststore.p12` |
| `STRATUS_KAFKA_TRUSTSTORE_PASSWORD` | truststore password |
| `STRATUS_KAFKA_CONSUMER_USER` | `svc-flink` |
| `STRATUS_KAFKA_CONSUMER_PASSWORD` | consumer password |
| `STRATUS_CDC_TOPIC` | `cdc.verification.postgres.public.customer_account` |
| `STRATUS_POSTGRES_JDBC_URL` | `jdbc:postgresql://postgres-source.stratus.local:5432/stratus_cdc_verification` |
| `STRATUS_POSTGRES_USER` | verification writer user |
| `STRATUS_POSTGRES_PASSWORD` | verification writer password |

---

## 14. Observability

Minimum dashboard signals:

| Signal | Why it matters |
|---|---|
| worker count | confirms cluster membership |
| connector state | identifies stopped or failed connectors |
| task state | identifies failed task instances |
| task error count | connector health |
| source lag | CDC freshness |
| records produced per connector | change volume |
| DLQ rate | data quality or schema problem |
| rebalance count | worker stability |
| REST API latency and errors | operational automation health |

Minimum alerts:

- Connect worker down
- connector state not `RUNNING`
- task state not `RUNNING`
- source lag above threshold
- DLQ records above threshold
- repeated task restarts
- internal topic under-replicated
- connector cannot authenticate to Kafka
- connector cannot connect to source database

---

## 15. Completion Gate

Increment 9 is complete when:

- [ ] Kafka Connect distributed cluster has three workers
- [ ] workers are managed by systemd
- [ ] Kafka Connect uses SASL_SSL to the Kafka backbone
- [ ] Debezium 3.6 PostgreSQL connector plugin is installed and visible
- [ ] Connect internal topics exist with replication factor 3 and compacted cleanup
- [ ] `svc-connect` ACLs are least-privilege
- [ ] PostgreSQL verification source is configured for logical replication
- [ ] verification connector performs initial snapshot
- [ ] insert, update, and delete source changes appear in Kafka
- [ ] delete and tombstone behavior is documented
- [ ] connector resumes from stored offsets after worker restart
- [ ] DLQ or fail-fast behavior is tested and documented
- [ ] connector credentials are not stored in source control
- [ ] Prometheus and Grafana expose worker, connector, task, lag, and error signals
- [ ] Java verification suite passes

When all gates are checked, Increment 10 (Flink Streaming Compute) can begin.

---

## 16. Troubleshooting

### Debezium plugin does not appear

- Confirm the plugin JARs are under the configured `plugin.path`.
- Confirm the connector image includes the Debezium PostgreSQL connector.
- Restart all workers after changing plugin contents.

### Connector fails to start

- Check `/connectors/<name>/status`.
- Confirm source database hostname, port, user, password, publication, and slot.
- Confirm PostgreSQL `wal_level=logical`.
- Confirm connector can authenticate to Kafka.

### No events appear in Kafka

- Confirm the table is included by `schema.include.list` and `table.include.list`.
- Confirm the source table has a primary key.
- Confirm publication includes the table.
- Check connector logs for snapshot progress and replication slot errors.

### Connector replays too much after restart

- Confirm offset topic is compacted and healthy.
- Confirm the connector name did not change.
- Confirm `topic.prefix`, slot name, and database identity did not change unexpectedly.

### DLQ grows unexpectedly

- Inspect DLQ headers for connector and exception details.
- Confirm converter settings match downstream expectations.
- Confirm source schema changes are compatible with the connector and consumers.

---

## 17. References

- Stratus Phase 2 implementation plan: [stratus_implementation_plan_phase2.md](stratus_implementation_plan_phase2.md)
- Increment 8 - Kafka Event Backbone: [increment8_kafka_event_backbone.md](increment8_kafka_event_backbone.md)
- Apache Kafka Connect documentation: https://kafka.apache.org/documentation/#connect
- Debezium releases: https://debezium.io/releases/
- Debezium PostgreSQL connector: https://debezium.io/documentation/reference/stable/connectors/postgresql.html
- PostgreSQL logical replication: https://www.postgresql.org/docs/current/logical-replication.html
