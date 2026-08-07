# ADR-P1-005: Event Backbone Selection — Kafka Retained, Pulsar Qualified

- Status: Accepted
- Date: 2026-08-06
- Decision owners: Platform architect and architecture owner
- Relates to: the architecture event-backbone position
  ([stratus_on_prem_data_fabric_architecture.md](../architecture/stratus_on_prem_data_fabric_architecture.md)
  §4.5 and §4.5.1)
- Affects: Increment 8 (event backbone), Increment 9 (CDC), Increment 12
  (Atlas event bus and lineage automation)

## Context

The architecture names Apache Kafka as the shared event backbone, delivered
by Increment 8 with Kafka Connect and Debezium in Increment 9. No Phase 2
increment has been built, so the selection was reopened deliberately while
changing it is still cheap.

Apache Pulsar was evaluated as the alternative. The comparison was not run
on throughput, where neither product decides it for a platform of this size,
but on fit with three properties of the Stratus deployment context: it is on
premises, so storage growth is a procurement event rather than a billing
line; it already operates a Ceph RGW object store sized for the lakehouse;
and it is a multi-domain platform, so tenant isolation recurs.

## Decision

**Apache Kafka is retained as the platform event backbone.** Apache Pulsar
is recorded as a **qualified alternative** — evaluated, documented with its
trade-offs, and available for reconsideration under the named triggers below
— rather than dismissed.

The deciding factor is Apache Atlas. Verified against Atlas sources on
2026-08-06:

- Atlas notification transport is Kafka. Metadata changes publish to the
  `ATLAS_ENTITIES` topic and hook events arrive on `ATLAS_HOOK`; the
  notification documentation names no alternative broker.
- The server's default configuration carries `atlas.notification.topics`,
  `atlas.notification.embedded`, and the `atlas.kafka.*` client properties,
  with no property naming an alternative notification implementation.
- The hook-side factory (`NotificationProvider`) selects between exactly two
  implementations, `KafkaNotification` and `RestNotification`. It is a
  two-way switch, not a pluggable provider interface, so no Pulsar
  implementation can be supplied by configuration.
- The REST path added by ATLAS-4335
  (`atlas.hook.rest.notification.enabled`) is not a Kafka replacement. It
  makes *hooks* POST to `/api/atlas/v2/notification/topic/<topicName>`, and
  the Atlas server then hands the message to its Kafka notifier. Kafka
  remains behind the endpoint, and the outbound `ATLAS_ENTITIES` stream that
  governance consumers read is unchanged.

Because Atlas requires Kafka regardless, adopting Pulsar would **add** a
messaging system rather than replace one: the platform would operate Pulsar
for data events and Kafka for Atlas notifications, permanently. Retaining
Kafka keeps one messaging system and preserves the Increment 12 plan in
which Atlas notification traffic consolidates onto the platform backbone.

## Consequences

### What retaining Kafka preserves

- One messaging system in production, one operational skill set, one set of
  runbooks, and the larger pool of on-premises operating experience.
- The Increment 12 consolidation of Atlas notification traffic onto the
  platform backbone remains achievable as planned.
- Kafka Connect runs Debezium connectors for all five supported source
  systems — PostgreSQL, MySQL, Oracle, SQL Server, and MongoDB — on one
  connector framework, so the platform operates a single CDC runtime.

### What retaining Kafka forgoes

These are real and are accepted, not disputed:

- **Storage and serving scale together.** Broker capacity and retention
  capacity are one procurement decision rather than two, and rebalancing
  moves data between brokers.
- **No upstream tiered-storage path onto the existing object store.** Long
  retention is provisioned on broker storage rather than offloaded to the
  Ceph RGW cluster already deployed. Tiered storage for Kafka is
  distribution-dependent rather than an upstream Apache feature.
- **Multi-tenancy is a convention over topic prefixes and ACLs** rather than
  a first-class construct with per-namespace quotas and retention.
- **Per-key ordering under parallel consumption** is bounded by partition
  count rather than provided by a subscription mode.

The CDC correctness rule in architecture §6.4.4 — merge on a sequence
number, never on the key alone — remains mandatory either way; with Kafka it
is satisfied by keying topics on the entity primary key so all changes to
one row land in one partition and stay ordered.

### Pulsar's costs, recorded so a future comparison need not re-derive them

- Three moving parts in production — brokers, BookKeeper bookies, and a
  metadata store — against Kafka's single broker role in KRaft mode.
- Pulsar IO packages Debezium connectors for MySQL, PostgreSQL, and MongoDB
  only; Oracle and SQL Server capture requires the separate Debezium Server
  runtime with its Pulsar sink, so those platforms operate two CDC runtimes.
- Smaller operational talent pool and fewer worked on-premises examples.
- Metadata-store churn upstream: ZooKeeper supported, Oxia recommended for
  new clusters from Pulsar 5.0, etcd backend removed in 5.0.

### Reconsideration triggers

Reopen this decision if any of the following becomes true:

1. Event retention grows large enough that offloading the backlog to the
   existing Ceph RGW cluster is materially cheaper than provisioning broker
   storage for it.
2. Domain isolation requirements outgrow topic-prefix conventions and ACL
   management, and per-namespace quotas and retention would materially
   reduce operational load.
3. Apache Atlas gains support for a second notification transport, which
   removes the permanent-two-systems consequence that decides this ADR.

Reconsideration before Increment 8 is inexpensive. After Increment 8 the
migration cost is real, so a trigger observed later should be weighed
against that cost rather than treated as automatic.

## References

- Apache Kafka: https://kafka.apache.org/
- Apache Kafka Connect: https://kafka.apache.org/documentation/#connect
- Apache Pulsar: https://pulsar.apache.org/
- Pulsar architecture overview: https://pulsar.apache.org/docs/concepts-architecture-overview/
- Pulsar tiered storage: https://pulsar.apache.org/docs/tiered-storage-overview/
- Pulsar IO Debezium connectors: https://pulsar.apache.org/docs/io-cdc-debezium/
- Debezium Server, including the Pulsar sink: https://debezium.io/documentation/reference/stable/operations/debezium-server.html
- Apache Atlas notifications, naming the `ATLAS_HOOK` and `ATLAS_ENTITIES` Kafka topics: https://atlas.apache.org/2.0.0/Notifications.html
- Atlas default configuration, showing the `atlas.notification.*` and `atlas.kafka.*` properties: https://github.com/apache/atlas/blob/master/distro/src/conf/atlas-application.properties
- Atlas `NotificationProvider`, the two-implementation hook-side switch: https://github.com/apache/atlas/blob/master/notification/src/main/java/org/apache/atlas/kafka/NotificationProvider.java
- ATLAS-4335, hook notifications through the REST interface: https://mail-archive.com/dev@atlas.apache.org/msg26395.html
