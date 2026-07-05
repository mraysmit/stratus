# Stratus Increment 8 - Kafka Event Backbone

## 1. Purpose

This document is the technical implementation plan for Increment 8 of the Stratus platform as defined in [stratus_implementation_plan_phase2.md](stratus_implementation_plan_phase2.md).

Increment 8 delivers Apache Kafka as the durable, replayable event backbone for Phase 2. Kafka is used by Kafka Connect, Debezium CDC connectors, Flink streaming jobs, application event producers, and Atlas entity change notifications after Atlas is migrated from the Phase 1 embedded notifier posture. When this increment is complete, the platform has a secure KRaft-mode Kafka cluster with TLS, authentication, ACLs, topic standards, retention policy, observability, and a Java verification suite.

Kafka is not the data lake, the catalog, the governance system, or the scheduler. It is the event backbone. Governed analytical state remains in Iceberg tables managed through Polaris and stored in MinIO.

**Prerequisites:**
- Phase 1 operational readiness complete
- Increment 7 complete - FreeIPA, Keycloak, TLS, and service identity hardening operational
- DNS and certificates available for Kafka hosts
- Platform observability stack available or ready to receive Kafka metrics
- Initial Phase 2 topic classes and owners identified

---

## 2. Assumptions and Prerequisites

- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- Podman 4.x installed on each Kafka host
- JDK 21+ and Maven 3.9+ on the verification host
- FreeIPA manages or delegates DNS for the Kafka hostnames
- FreeIPA Dogtag PKI issues Kafka TLS certificates
- Kafka brokers can reach each other on the broker and controller ports
- Prometheus can scrape Kafka metrics endpoints
- The platform can store protected Kafka configuration files outside source control
- Kafka is deployed in KRaft mode; ZooKeeper is not introduced

DNS names used in this increment:

| Host | Role |
|---|---|
| `kafka1.stratus.local` | broker and controller |
| `kafka2.stratus.local` | broker and controller |
| `kafka3.stratus.local` | broker and controller |

Service identities introduced:

| Identity | Purpose |
|---|---|
| `svc-kafka-admin` | Kafka administrative automation and verification |
| `svc-connect` | Kafka Connect internal topics and connector traffic |
| `svc-debezium` | Debezium connector producer identity where separated from Connect |
| `svc-flink` | Flink consumer/producer identity, activated in Increment 10 |
| `svc-atlas` | Atlas notification producer/consumer identity, used in Increment 12 |
| `svc-app-producer` | verification application event producer |

Kafka authentication is initially implemented with SASL/SCRAM over TLS. FreeIPA remains the identity-of-record for service ownership, group assignment, certificate lifecycle, and operational approval, but Kafka ACL principals are Kafka SCRAM users. If the platform later standardizes on OAuth/OIDC for Kafka clients, that migration must be handled as a dedicated security increment with compatibility checks for Kafka Connect, Debezium, Flink, Atlas, and Java clients.

### Reference documentation audit

Reference baseline: 2026-07-05.

The Phase 2 plan targets Apache Kafka 4.3.1 as the current ASF release artifact visible in the Apache download index. Kafka Connect is bundled with the selected Kafka release. This increment uses Kafka in KRaft mode and does not use ZooKeeper-era deployment patterns.

Before implementation, confirm the current Kafka release, container image availability, KRaft configuration names, security configuration examples, and JMX/metrics exporter compatibility. If a newer Kafka release is selected, update this document, the verification dependency, the image tag, and any configuration property changes together.

---

## 3. Cluster Topology

Increment 8 uses a three-node Kafka cluster with combined broker/controller nodes for the initial Phase 2 deployment.

```text
kafka1.stratus.local
┌──────────────────────────────────────────────┐
│  Podman: kafka                               │
│  Broker listener      :9092 TLS/SASL         │
│  Controller listener  :9093 TLS              │
│  Metrics endpoint     :9404                  │
└──────────────────────────────────────────────┘
        ▲                 ▲
        │ Raft quorum     │ replication
        ▼                 ▼
kafka2.stratus.local  kafka3.stratus.local
┌─────────────────┐   ┌─────────────────┐
│ broker+control  │   │ broker+control  │
│ :9092 / :9093   │   │ :9092 / :9093   │
└─────────────────┘   └─────────────────┘
```

Combined broker/controller nodes are acceptable for the first Phase 2 footprint because the initial workload is platform CDC, verification event streams, Atlas notifications, and Flink pilot jobs. If Kafka becomes a high-volume shared enterprise event backbone, controller-only nodes and broker-only nodes can be separated in a later scale increment.

---

## 4. Ports

| Port | Purpose | Protocol |
|---|---|---|
| 9092 | client and inter-broker listener | TLS + SASL/SCRAM |
| 9093 | KRaft controller listener | TLS |
| 9404 | Prometheus metrics endpoint | HTTP inside monitoring network |

Firewall rules:

- Kafka hosts must reach each other on `9092` and `9093`.
- Kafka clients must reach brokers on `9092`.
- Prometheus must reach each broker metrics endpoint on `9404`.
- No plaintext listener should be exposed in production-like environments.

---

## 5. Kafka Image and Artifact Policy

Use a pinned Kafka image or internally built image from the approved Apache Kafka binary artifact. Do not use `latest`.

The image must include:

- Apache Kafka 4.3.1 or the approved replacement release
- JDK 21 runtime where supported by the selected image
- JMX exporter Java agent compatible with the selected Kafka release
- no embedded ZooKeeper dependency or startup path

Example internal build tag:

```bash
podman build -t stratus/kafka:4.3.1 docker/kafka
```

For a lab using a vetted Kafka image from an internal registry:

```bash
podman pull registry.stratus.local/platform/kafka:4.3.1
```

The implementation runbook must record:

- image registry
- image tag
- image digest
- Kafka release
- JDK version
- JMX exporter version
- build source

---

## 6. Directory Layout

Create persistent directories on each Kafka host:

```bash
sudo mkdir -p /etc/stratus/kafka
sudo mkdir -p /etc/stratus/kafka/secrets
sudo mkdir -p /etc/stratus/kafka/certs
sudo mkdir -p /data/kafka/logs
sudo mkdir -p /data/kafka/meta
sudo chown -R $USER:$USER /etc/stratus/kafka /data/kafka
chmod 750 /etc/stratus/kafka /etc/stratus/kafka/secrets /data/kafka
```

Mounted container layout:

```text
/etc/stratus/kafka/
├── server.properties
├── client-admin.properties
├── jmx-exporter.yml
├── certs/
│   ├── kafka.truststore.p12
│   └── kafka.keystore.p12
└── secrets/
    ├── kafka-keystore.pass
    ├── kafka-truststore.pass
    └── kafka-scram-admin.pass

/data/kafka/
├── logs/
└── meta/
```

Do not store keystore passwords, SCRAM passwords, or private keys in source control.

---

## 7. Certificates and Trust

FreeIPA Dogtag PKI issues one server certificate per Kafka host.

Certificate requirements:

| Host | Required SANs |
|---|---|
| `kafka1.stratus.local` | `DNS:kafka1.stratus.local` |
| `kafka2.stratus.local` | `DNS:kafka2.stratus.local` |
| `kafka3.stratus.local` | `DNS:kafka3.stratus.local` |

The implementation runbook must record:

- certificate subject
- issuer
- SANs
- expiry date
- renewal command or process
- truststore distribution process

Kafka clients must trust the FreeIPA CA. Normal Kafka client commands should not disable hostname verification.

---

## 8. KRaft Cluster Identity

Generate one cluster ID before formatting storage:

```bash
podman run --rm \
  registry.stratus.local/platform/kafka:4.3.1 \
  /opt/kafka/bin/kafka-storage.sh random-uuid
```

Record the cluster ID in the protected implementation runbook. Use the same cluster ID when formatting storage on all three nodes:

```bash
podman run --rm \
  -v /etc/stratus/kafka/server.properties:/etc/kafka/server.properties:ro,z \
  -v /data/kafka:/data/kafka:z \
  registry.stratus.local/platform/kafka:4.3.1 \
  /opt/kafka/bin/kafka-storage.sh format \
    --config /etc/kafka/server.properties \
    --cluster-id <cluster-id>
```

Storage formatting is a one-time initialization step. Do not reformat an existing broker unless rebuilding the cluster from an approved recovery plan.

---

## 9. Broker Configuration

Create `/etc/stratus/kafka/server.properties` on each host. The values below show `kafka1`; change `node.id`, advertised hostname, and quorum entry as needed for each node.

```properties
# /etc/stratus/kafka/server.properties

process.roles=broker,controller
node.id=1

controller.quorum.voters=1@kafka1.stratus.local:9093,2@kafka2.stratus.local:9093,3@kafka3.stratus.local:9093

listeners=BROKER://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
advertised.listeners=BROKER://kafka1.stratus.local:9092
listener.security.protocol.map=BROKER:SASL_SSL,CONTROLLER:SSL
inter.broker.listener.name=BROKER
controller.listener.names=CONTROLLER

log.dirs=/data/kafka/logs
metadata.log.dir=/data/kafka/meta

num.partitions=3
default.replication.factor=3
min.insync.replicas=2
auto.create.topics.enable=false
delete.topic.enable=false

offsets.topic.replication.factor=3
transaction.state.log.replication.factor=3
transaction.state.log.min.isr=2

group.initial.rebalance.delay.ms=3000

ssl.keystore.location=/etc/kafka/certs/kafka.keystore.p12
ssl.keystore.password=${file:/etc/kafka/secrets/kafka-keystore.pass:password}
ssl.keystore.type=PKCS12
ssl.truststore.location=/etc/kafka/certs/kafka.truststore.p12
ssl.truststore.password=${file:/etc/kafka/secrets/kafka-truststore.pass:password}
ssl.truststore.type=PKCS12
ssl.client.auth=required
ssl.endpoint.identification.algorithm=https

sasl.enabled.mechanisms=SCRAM-SHA-512
sasl.mechanism.inter.broker.protocol=SCRAM-SHA-512

authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer
super.users=User:svc-kafka-admin
allow.everyone.if.no.acl.found=false

listener.name.broker.scram-sha-512.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="svc-kafka-admin" password="${file:/etc/kafka/secrets/kafka-scram-admin.pass:password}";
```

Notes:

- `auto.create.topics.enable=false` prevents accidental topic sprawl.
- `allow.everyone.if.no.acl.found=false` makes missing ACLs fail closed.
- SCRAM credentials must be created before ordinary clients can authenticate.
- For production, validate whether file-based config providers are supported by the selected Kafka image and startup classpath; otherwise inject secrets through the approved protected runtime mechanism.

---

## 10. Podman Container Setup

Start Kafka on each node after storage formatting.

```bash
podman run -d \
  --name kafka \
  --hostname kafka1.stratus.local \
  --network host \
  -v /etc/stratus/kafka/server.properties:/etc/kafka/server.properties:ro,z \
  -v /etc/stratus/kafka/certs:/etc/kafka/certs:ro,z \
  -v /etc/stratus/kafka/secrets:/etc/kafka/secrets:ro,z \
  -v /etc/stratus/kafka/jmx-exporter.yml:/etc/kafka/jmx-exporter.yml:ro,z \
  -v /data/kafka:/data/kafka:z \
  -e KAFKA_OPTS="-javaagent:/opt/jmx-exporter/jmx_prometheus_javaagent.jar=9404:/etc/kafka/jmx-exporter.yml" \
  --restart unless-stopped \
  registry.stratus.local/platform/kafka:4.3.1 \
  /opt/kafka/bin/kafka-server-start.sh /etc/kafka/server.properties
```

Verify the container:

```bash
podman ps | grep kafka
podman logs kafka | tail -50
```

Expected:

- broker starts without ZooKeeper messages
- KRaft quorum forms
- broker listener starts on `9092`
- controller listener starts on `9093`
- metrics endpoint listens on `9404`

### Auto-start with systemd

```bash
podman generate systemd --new --name kafka \
  | sudo tee /etc/systemd/system/stratus-kafka.service

sudo systemctl daemon-reload
sudo systemctl enable --now stratus-kafka.service
```

Repeat on each Kafka host.

---

## 11. Admin Client Configuration

Create `/etc/stratus/kafka/client-admin.properties` on the verification/admin host:

```properties
bootstrap.servers=kafka1.stratus.local:9092,kafka2.stratus.local:9092,kafka3.stratus.local:9092
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="svc-kafka-admin" password="<secret>";
ssl.truststore.location=/etc/stratus/kafka/certs/kafka.truststore.p12
ssl.truststore.password=<truststore password>
ssl.truststore.type=PKCS12
ssl.endpoint.identification.algorithm=https
```

This file contains secrets. Store it in a protected runtime path, not in the repository.

Verify the cluster:

```bash
/opt/kafka/bin/kafka-metadata-quorum.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  describe --status
```

Expected: the quorum reports a leader and all three voters.

---

## 12. SCRAM Users and ACLs

Create SCRAM credentials for platform service users:

```bash
/opt/kafka/bin/kafka-configs.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --alter \
  --add-config 'SCRAM-SHA-512=[password=<svc-connect password>]' \
  --entity-type users \
  --entity-name svc-connect

/opt/kafka/bin/kafka-configs.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --alter \
  --add-config 'SCRAM-SHA-512=[password=<svc-flink password>]' \
  --entity-type users \
  --entity-name svc-flink
```

Create equivalent credentials for:

- `svc-debezium`
- `svc-atlas`
- `svc-app-producer`
- verification users

ACL principles:

- `svc-connect` owns Connect internal topics and connector-created topics where approved.
- `svc-debezium` produces to CDC topics if Debezium credentials are separated from Connect worker credentials.
- `svc-flink` consumes CDC and event topics and may produce derived topics only where approved.
- `svc-atlas` produces and consumes Atlas notification topics in Increment 12.
- no service account receives cluster-wide access except `svc-kafka-admin`.

Example ACL for a verification producer:

```bash
/opt/kafka/bin/kafka-acls.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --add \
  --allow-principal User:svc-app-producer \
  --operation Write \
  --operation Describe \
  --topic app.verification.events.v1
```

Example ACL for a Flink consumer group:

```bash
/opt/kafka/bin/kafka-acls.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --add \
  --allow-principal User:svc-flink \
  --operation Read \
  --operation Describe \
  --topic app.verification.events.v1

/opt/kafka/bin/kafka-acls.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --add \
  --allow-principal User:svc-flink \
  --operation Read \
  --group flink.verification.consumer
```

---

## 13. Topic Standards

Kafka topics are platform contracts. They must be named, owned, retained, and governed.

### Topic classes

| Class | Pattern | Purpose | Default retention |
|---|---|---|---|
| Application events | `app.<domain>.<event>.v<version>` | business events produced by applications | 7 to 30 days |
| CDC | `cdc.<source>.<schema>.<table>.v<version>` | Debezium source table changes | 7 to 30 days |
| Dead-letter | `dlq.<source-topic>` | records rejected by Connect, Debezium, or Flink | 30 to 90 days |
| Connect internal | `_connect.<cluster>.<configs|offsets|status>` | Kafka Connect worker state | compacted |
| Atlas notifications | `platform.atlas.<entity|hook>.v<version>` | Atlas metadata event bus | 7 to 30 days or compacted by event type |
| Verification | `app.verification.events.v1` | Increment 8 verification | 1 to 7 days |

### Topic creation

Create the verification topic:

```bash
/opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --create \
  --topic app.verification.events.v1 \
  --partitions 3 \
  --replication-factor 3 \
  --config min.insync.replicas=2 \
  --config retention.ms=604800000 \
  --config cleanup.policy=delete
```

Create a dead-letter topic:

```bash
/opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --create \
  --topic dlq.app.verification.events.v1 \
  --partitions 3 \
  --replication-factor 3 \
  --config min.insync.replicas=2 \
  --config retention.ms=2592000000 \
  --config cleanup.policy=delete
```

Topic changes must be managed through source-controlled topic definitions and applied by platform automation. Ad hoc console creation is not accepted for production topics.

---

## 14. Observability

Kafka metrics must be scraped by Prometheus and visible in Grafana before Increment 8 is complete.

Minimum dashboard signals:

| Signal | Why it matters |
|---|---|
| broker online status | confirms cluster health |
| controller quorum leader and voters | confirms KRaft health |
| under-replicated partitions | detects broker or replication failure |
| offline partitions | detects unavailable topics |
| produce request rate and latency | producer health |
| fetch request rate and latency | consumer health |
| request error rate | client or broker failure |
| consumer lag by group and topic | downstream processing health |
| bytes in/out | capacity planning |
| disk usage by broker | retention and capacity management |
| active controller count | should be one |
| failed authentication count | security signal |
| ACL authorization failures | policy signal |

Minimum alerts:

- broker down
- controller quorum unhealthy
- under-replicated partitions greater than zero
- offline partitions greater than zero
- active controller count not equal to one
- disk usage above warning threshold
- failed authentication spike
- authorization denial spike
- consumer lag above topic-specific threshold
- metrics scrape failure

---

## 15. Java Verification Suite

The verification suite proves Kafka behavior through the same client protocols that Phase 2 services will use.

### Maven dependencies

Add to the verification module `pom.xml`:

```xml
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-clients</artifactId>
    <version>4.3.1</version>
</dependency>
<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <version>3.27.3</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.13.4</version>
    <scope>test</scope>
</dependency>
```

If the selected Kafka release changes, update `kafka-clients` to the same release line unless the Kafka compatibility notes explicitly recommend otherwise.

### Configuration

Environment variables:

| Variable | Example |
|---|---|
| `STRATUS_KAFKA_BOOTSTRAP` | `kafka1.stratus.local:9092,kafka2.stratus.local:9092,kafka3.stratus.local:9092` |
| `STRATUS_KAFKA_TRUSTSTORE` | `/etc/stratus/kafka/certs/kafka.truststore.p12` |
| `STRATUS_KAFKA_TRUSTSTORE_PASSWORD` | truststore password |
| `STRATUS_KAFKA_PRODUCER_USER` | `svc-app-producer` |
| `STRATUS_KAFKA_PRODUCER_PASSWORD` | producer password |
| `STRATUS_KAFKA_CONSUMER_USER` | `svc-flink` |
| `STRATUS_KAFKA_CONSUMER_PASSWORD` | consumer password |
| `STRATUS_KAFKA_DENIED_USER` | `svc-denied-verification` |
| `STRATUS_KAFKA_DENIED_PASSWORD` | denied user password |

### Verification test class

```java
package com.stratus.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class KafkaEventBackboneVerificationTest {

    private static final String TOPIC = "app.verification.events.v1";
    private static final String GROUP = "flink.verification.consumer";

    @Test
    void clusterHasThreeNodes() throws Exception {
        try (AdminClient admin = AdminClient.create(adminProperties(
                env("STRATUS_KAFKA_PRODUCER_USER"),
                env("STRATUS_KAFKA_PRODUCER_PASSWORD")))) {
            DescribeClusterResult cluster = admin.describeCluster();
            assertThat(cluster.nodes().get()).hasSize(3);
            assertThat(cluster.controller().get()).isNotNull();
        }
    }

    @Test
    void verificationTopicHasExpectedShape() throws Exception {
        try (AdminClient admin = AdminClient.create(adminProperties(
                env("STRATUS_KAFKA_PRODUCER_USER"),
                env("STRATUS_KAFKA_PRODUCER_PASSWORD")))) {
            TopicDescription topic = admin.describeTopics(List.of(TOPIC))
                .allTopicNames()
                .get()
                .get(TOPIC);

            assertThat(topic.partitions()).hasSize(3);
            assertThat(topic.partitions())
                .allSatisfy(partition -> assertThat(partition.replicas()).hasSize(3));
        }
    }

    @Test
    void producerAndConsumerCanRoundTripEvent() throws Exception {
        String key = UUID.randomUUID().toString();
        String value = "{\"event\":\"verification\",\"id\":\"" + key + "\"}";

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties());
             KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties())) {

            consumer.subscribe(List.of(TOPIC));
            producer.send(new ProducerRecord<>(TOPIC, key, value)).get();
            producer.flush();

            boolean found = consumer.poll(Duration.ofSeconds(15)).records(TOPIC).stream()
                .anyMatch(record -> key.equals(record.key()) && value.equals(record.value()));

            assertThat(found).isTrue();
        }
    }

    @Test
    void deniedUserCannotProduce() {
        Properties props = producerProperties(
            env("STRATUS_KAFKA_DENIED_USER"),
            env("STRATUS_KAFKA_DENIED_PASSWORD"));

        assertThatThrownBy(() -> {
            try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
                producer.send(new ProducerRecord<>(TOPIC, "denied", "denied")).get();
            }
        }).isInstanceOf(Exception.class);
    }

    @Test
    void consumerGroupCanReadCommittedOffsets() throws Exception {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties())) {
            TopicPartition partition = new TopicPartition(TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            assertThat(consumer.position(partition)).isGreaterThanOrEqualTo(0L);
        }
    }

    private static Properties producerProperties() {
        return producerProperties(env("STRATUS_KAFKA_PRODUCER_USER"), env("STRATUS_KAFKA_PRODUCER_PASSWORD"));
    }

    private static Properties producerProperties(String user, String password) {
        Properties props = commonProperties(user, password);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "30000");
        return props;
    }

    private static Properties consumerProperties() {
        Properties props = commonProperties(env("STRATUS_KAFKA_CONSUMER_USER"), env("STRATUS_KAFKA_CONSUMER_PASSWORD"));
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP + "." + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return props;
    }

    private static Properties adminProperties(String user, String password) {
        return commonProperties(user, password);
    }

    private static Properties commonProperties(String user, String password) {
        Properties props = new Properties();
        props.put("bootstrap.servers", env("STRATUS_KAFKA_BOOTSTRAP"));
        props.put("security.protocol", "SASL_SSL");
        props.put("sasl.mechanism", "SCRAM-SHA-512");
        props.put("sasl.jaas.config",
            "org.apache.kafka.common.security.scram.ScramLoginModule required username=\""
                + user + "\" password=\"" + password + "\";");
        props.put("ssl.truststore.location", env("STRATUS_KAFKA_TRUSTSTORE"));
        props.put("ssl.truststore.password", env("STRATUS_KAFKA_TRUSTSTORE_PASSWORD"));
        props.put("ssl.truststore.type", "PKCS12");
        props.put("ssl.endpoint.identification.algorithm", "https");
        return props;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new KafkaException("Missing required environment variable: " + name);
        }
        return value;
    }
}
```

### Running the verification suite

```bash
export STRATUS_KAFKA_BOOTSTRAP=kafka1.stratus.local:9092,kafka2.stratus.local:9092,kafka3.stratus.local:9092
export STRATUS_KAFKA_TRUSTSTORE=/etc/stratus/kafka/certs/kafka.truststore.p12
export STRATUS_KAFKA_TRUSTSTORE_PASSWORD=<truststore password>
export STRATUS_KAFKA_PRODUCER_USER=svc-app-producer
export STRATUS_KAFKA_PRODUCER_PASSWORD=<producer password>
export STRATUS_KAFKA_CONSUMER_USER=svc-flink
export STRATUS_KAFKA_CONSUMER_PASSWORD=<consumer password>
export STRATUS_KAFKA_DENIED_USER=svc-denied-verification
export STRATUS_KAFKA_DENIED_PASSWORD=<denied password>

mvn test -Dtest=KafkaEventBackboneVerificationTest
```

Expected: all tests pass, and Kafka broker logs show successful authenticated client connections with no unexpected authorization grants.

---

## 16. Operational Checks

### KRaft quorum

```bash
/opt/kafka/bin/kafka-metadata-quorum.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  describe --status
```

Expected: one leader and three voters.

### Broker API versions

```bash
/opt/kafka/bin/kafka-broker-api-versions.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties
```

Expected: all three brokers respond.

### Topic listing

```bash
/opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --list
```

Expected: only approved platform and verification topics exist. No accidental topics should appear.

### ACL listing

```bash
/opt/kafka/bin/kafka-acls.sh \
  --bootstrap-server kafka1.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --list
```

Expected: ACLs are scoped to approved topics, groups, and service users.

### Broker failure simulation

Stop one broker:

```bash
sudo systemctl stop stratus-kafka
```

From another host:

```bash
/opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka2.stratus.local:9092 \
  --command-config /etc/stratus/kafka/client-admin.properties \
  --describe --topic app.verification.events.v1
```

Expected:

- topic remains available
- partitions may show reduced ISR while the broker is stopped
- under-replicated partition alert fires

Restart the broker:

```bash
sudo systemctl start stratus-kafka
```

Expected: ISR recovers and alert clears.

---

## 17. Completion Gate

Increment 8 is complete when:

- [ ] Kafka 4.3.1 or the approved current release is pinned by image tag and digest
- [ ] Kafka runs in KRaft mode with no ZooKeeper dependency
- [ ] all three brokers/controllers are running and managed by systemd
- [ ] KRaft quorum reports one leader and three voters
- [ ] TLS is enabled on broker and controller traffic
- [ ] normal client commands validate certificates without insecure overrides
- [ ] SASL/SCRAM authentication is enabled for client traffic
- [ ] `allow.everyone.if.no.acl.found=false`
- [ ] `auto.create.topics.enable=false`
- [ ] service users exist for admin, Connect, Debezium, Flink, Atlas, and verification
- [ ] ACLs enforce least-privilege topic and group access
- [ ] topic naming standards and retention classes are documented
- [ ] verification and dead-letter topics are created with replication factor 3
- [ ] Java verification suite passes
- [ ] unauthorized produce or consume attempt fails
- [ ] one-broker failure simulation behaves as expected
- [ ] Prometheus scrapes Kafka metrics
- [ ] Grafana dashboard shows broker, quorum, partition, request, auth, and lag signals
- [ ] Kafka runbook covers startup, shutdown, broker failure, topic creation, ACL change, retention change, and credential rotation

When all gates are checked, Increment 9 (Kafka Connect and Debezium CDC) can begin.

---

## 18. Troubleshooting

### Broker starts but quorum does not form

- Confirm all hosts use the same KRaft cluster ID.
- Confirm `node.id` is unique per host.
- Confirm `controller.quorum.voters` has the correct hostnames and ports.
- Confirm port `9093` is reachable between all Kafka hosts.
- Check broker logs for certificate or listener-name mismatches.

### Client fails TLS handshake

- Confirm the client truststore contains the FreeIPA CA.
- Confirm the broker certificate SAN matches the broker hostname.
- Confirm the client connects to the advertised hostname, not an IP address.
- Confirm `ssl.endpoint.identification.algorithm=https` is not disabled.

### Client authenticates but cannot access topic

- Confirm the Kafka principal name matches the SCRAM username.
- Confirm the ACL uses `User:<name>`.
- Confirm both topic ACL and consumer group ACL exist for consumers.
- Confirm the client is not relying on auto-created topics.

### Topic creation fails

- Confirm the admin user has `Create`, `Describe`, and `Alter` permissions where required.
- Confirm the replication factor does not exceed the number of live brokers.
- Confirm the cluster is not below `min.insync.replicas`.

### Produce fails during one-broker outage

- Confirm producer uses `acks=all`.
- Confirm topic replication factor is 3 and `min.insync.replicas=2`.
- Confirm at least two replicas remain in sync.
- Check under-replicated partitions and ISR state.

### Metrics endpoint is blank or missing

- Confirm the JMX exporter Java agent path exists in the image.
- Confirm `KAFKA_OPTS` includes the exporter config.
- Confirm port `9404` is reachable from Prometheus.
- Check container logs for Java agent startup errors.

---

## 19. References

- Stratus Phase 2 implementation plan: [stratus_implementation_plan_phase2.md](stratus_implementation_plan_phase2.md)
- Stratus Phase 1 implementation plan: [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)
- Phase 1 operational readiness: [phase1_operational_readiness.md](phase1_operational_readiness.md)
- Increment 7 - Identity and Security: [increment7_identity_security.md](increment7_identity_security.md)
- Apache Kafka downloads: https://downloads.apache.org/kafka/
- Apache Kafka documentation: https://kafka.apache.org/documentation/
- Kafka KRaft documentation: https://kafka.apache.org/documentation/#kraft
- Kafka security documentation: https://kafka.apache.org/documentation/#security
- Kafka operations documentation: https://kafka.apache.org/documentation/#operations
