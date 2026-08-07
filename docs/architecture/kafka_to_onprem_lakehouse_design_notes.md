# Kafka ingestion to an on-premises lakehouse

## Design assessment

This document assesses a streaming ingestion architecture in which Kafka data is
written to an on-premises S3-compatible object store, and compares it with a
scheduled batch alternative. It records the constraints identified, the recommended
target architecture, and the measurements required to size it.

---

## 1. Context and terminology

**Current position**

| Item | Detail |
| --- | --- |
| Source | Kafka |
| Target | Lakehouse tables on an on-premises S3-compatible appliance, not a managed cloud object store |
| Current write path | One HTTP write per message, each incurring a network round trip and a metadata operation |

**Concerns raised for assessment**

1. Request-rate limits will be reached well before storage capacity becomes a
   constraint.
2. Window size is determined by consumer read behaviour and target file size, and
   should therefore not be owned by the producer service.
3. Writing directly from Kafka couples Kafka to storage availability.

All three are assessed as valid in section 11.

**Terminology**

All three roles in this architecture write data somewhere, so the terms are defined
here to avoid ambiguity.

| Term | Definition |
| --- | --- |
| Producer | An application writing events into Kafka |
| Writer tier | A Kafka consumer that reads a topic and writes objects to the appliance. It is a consumer in Kafka terminology and a client from the appliance's perspective. |
| Reader | A query engine reading tables from the appliance |

---

## 2. Ingestion patterns

Streaming and batch pipelines converge on the same final operation: write Parquet
objects, then atomically commit a manifest that makes them visible to readers.

**Batch capture patterns**

| Pattern | Assessment |
| --- | --- |
| Full snapshot extract | Always accurate and always expensive. Appropriate for dimension tables. |
| Watermark / incremental extract | Inexpensive and widely used. Does not capture hard deletes, and is unreliable under clock skew or backdated updates. |
| File drop / landing zone | The event-driven form (notification to queue to worker) is preferred. Polling a bucket listing does not scale. |
| Bootstrap snapshot with log tail | The standard method for migrating from batch extraction to change data capture. |

**Streaming capture patterns**

| Pattern | Assessment |
| --- | --- |
| Log-based CDC | Reads the write-ahead log or binlog. Captures deletes and preserves transaction order. Requires a sequence number (LSN or SCN) to be emitted. |
| Application event streams | Append-only and high volume, with schema governed by a registry. |
| Outbox pattern | Business state and event record are written in a single local transaction, which prevents the divergence caused by dual writes. |

**Write patterns into the table**

| Pattern | Assessment |
| --- | --- |
| Append-only | The simplest case: facts, events and logs. |
| Upsert / MERGE | Copy-on-write favours read performance at the cost of write performance; merge-on-read reverses that trade until compaction runs. |
| Buffer-then-merge | Stream into an append-only staging table and MERGE periodically. Separates ingest latency from merge cost. |
| Micro-batch | A trigger interval of one to five minutes satisfies most stated streaming requirements at considerably lower operational cost. |

---

## 3. Principal finding

**Streaming and batch transfer the same volume of data. The difference between them
is the number of separate objects that data is divided into.**

Increasing stored volume is straightforward, since capacity can be added by
installing drives. Increasing object count is not, because each object requires a
request to write it and an entry in the metadata index. Both are constrained by the
gateway nodes and index hardware already installed.

The governing figure is therefore objects created per second. That figure is
determined by how long the writer buffers before flushing, which is a configuration
setting rather than an inherent property of streaming.

---

## 4. What changes on-premises

On a managed cloud object store this concern would be misplaced, as the service
absorbs the load and the only material costs are request billing and slower query
planning.

An on-premises deployment has no such elasticity. The number of drives and gateway
nodes is fixed, the metadata subsystem has finite throughput, and the cluster is
likely shared with other workloads.

A single logical PUT does not correspond to a single write:

```
client PUT → S3 gateway → bucket index update → erasure split (k+m) → k+m drive ops
```

Under an 8+3 erasure profile, each object results in eleven fragment writes plus a
metadata update. At four objects per second this is negligible. At four hundred
objects per second it represents approximately 4,400 sustained small random writes.

### Failure modes, in order of likelihood

1. **Metadata layer.** This component has no direct cloud equivalent.
   - *Ceph RGW:* each object occupies an entry in a sharded bucket index held in
     RocksDB omap. Planning guidance is approximately 100,000 objects per shard.
     Beyond that threshold, resharding operations and RocksDB compaction cycles
     produce latency spikes indistinguishable from storage saturation.
   - *MinIO:* metadata is stored inline with the object, so there is no central
     shard to saturate. The equivalent cost appears in listing and read operations.
2. **Write amplification.** As described above.
3. **Burst replay.** The writer restarts following an outage and drains Kafka at
   maximum rate into a cluster with no headroom. The effect is compounded if a
   drive is rebuilding concurrently. This scenario is commonly untested.
4. **Read-side degradation.** Where a partition holds hundreds of thousands of
   small Parquet files, query planning time is dominated by footer reads and
   listing calls.

### Mitigations

| Mitigation | Detail |
| --- | --- |
| Rotate at 5–15 minutes | Rather than 30–60 seconds. The objective is reducing object count, not reducing cost. |
| Serve sub-minute freshness from Kafka | Where genuinely required, use Flink state, ksqlDB or a query service reading the topic, and treat the lakehouse as the minutes-latency tier. |
| Bound bucket size structurally | One bucket per topic, or per topic per month. Pre-shard at creation rather than relying on dynamic resharding. |
| Increase multipart part size | So that large objects are not decomposed into many small writes. |
| Rate-limit the writer | So that replay cannot saturate the cluster. Implement back-off on 503 responses. |
| Budget for compaction | Compaction reads every small file and writes larger ones back, consuming read and write capacity on the cluster that is simultaneously handling ingest. Schedule off-peak and throttle. |
| Isolate where possible | A dedicated storage pool, or at minimum SSD-backed index devices on Ceph. |

---

## 5. Comparison with scheduled batch ingestion

The question raised was whether batch ingestion is safer for the storage layer. On
fixed hardware the opposite is generally true, for the reasons set out below.

| | Scheduled batch | Streaming with a writer tier |
| --- | --- | --- |
| Object count | Runs per day × partitions | Windows per day × partitions |
| Load profile | Concentrated in the run window | Distributed across the day |
| Peak request rate | High; the day's writes occur in one window | Low and steady |
| Data freshness | Hours | Minutes |
| Failure recovery | Rerun the job | Replay from the last committed offset |
| Load on source system | Heavy during extraction | None; the log already holds the data |
| Dependency | Source system retaining history | Kafka retention |
| Always-on infrastructure | Not required | Required |

### Advantages of batch ingestion

**A known load window.** The timing of storage load is chosen rather than
continuous, allowing it to be scheduled around backups, compaction and other
tenants. On a shared on-premises cluster this is a material operational advantage
and the strongest argument for the batch model.

**A simpler failure model.** A failed run is repeated, overwriting the partition.
There is no offset ordering to implement, no rebalance behaviour or in-flight
buffer to reason about, and no orphaned files arising from partial commits.

**No always-on writer tier** to operate, patch and monitor.

### Costs of batch ingestion on fixed hardware

**Load is concentrated rather than reduced.** The same volume of data and
approximately the same object count arrive within a twenty-minute window instead of
being distributed across twenty-four hours. On elastic cloud storage this is not
observable. On a fixed-capacity appliance the hardware must be sized for the peak,
so the batch model can require more capacity than steady-state streaming rather
than less.

**Recovery is slower.** A failed overnight run results in a full day of staleness,
and the rerun constitutes a second complete load cycle in addition to the next
scheduled one.

**Watermark extracts do not capture hard deletes**, and bulk extracts impose heavy
periodic load on the source database.

### The distinction is largely a configuration choice

Where the source is already a Kafka topic, batch ingestion means reading from Kafka
on a schedule. That is the writer tier described in section 7 operating with a
longer window. The buffering, rotation and commit protocol are identical; only the
interval differs.

The decision can therefore be deferred. The writer tier should be built first, with
the window set subsequently: approximately five minutes for near-real-time
delivery, or several hours to approximate batch behaviour and leave the cluster
quiet during working hours. The setting can be revised later without redesign.

Batch ingestion does not address the per-message write problem. If each message
remains a separate HTTP round trip, moving to an overnight schedule leaves the
request count unchanged while compressing it into a shorter window, which is worse.

---

## 6. Ownership boundaries

### Per-message writes

This should be addressed before any other change. Where every message incurs an
HTTP round trip and a metadata operation, request rate equals message rate, and the
appliance limits ingest throughput irrespective of available capacity. Batching is
the only mechanism that separates the two figures, and it does so by several orders
of magnitude.

### Window size

Window size is determined by target file size, consumer read patterns and the
appliance's available request capacity. The producer has visibility of none of
these, and all of them change independently of the producer. Window size should
therefore be per-topic configuration held by the writer tier.

> Requiring a producer redeployment to correct file sizes indicates the boundary
> has been drawn in the wrong place.

### Coupling to storage

Kafka already decouples producers from storage. If the writer tier fails, producers
continue and consumer lag increases. This is the intended failure behaviour.

The genuine risk is narrower and consists of two items:

- **Retention sizing.** Retention must exceed the longest tolerable storage outage
  plus the subsequent catch-up period. If the appliance can be unavailable for six
  hours during a firmware upgrade, six hours of retention is insufficient, and lag
  becomes data loss.
- **Offset commit ordering.** Offsets must be committed only after the object is
  durably written and the catalog commit has succeeded. Committing on read means a
  storage failure silently discards a window. Custom-built writers frequently
  implement this incorrectly.

---

## 7. Target architecture

A dedicated writer tier consuming from Kafka, which:

1. Buffers to local disk rather than memory, so that window size is not constrained
   by heap and a restart does not lose the in-flight window
2. Rotates on size or elapsed time, whichever is reached first
3. Writes one object per window per partition using multipart upload
4. Commits to the catalog
5. Commits offsets last
6. On storage unavailability, stops committing and allows lag to accumulate,
   pausing its Kafka consumer when the local buffer fills rather than failing or
   discarding data

Kafka Connect with an Iceberg sink, or Apache Flink, provides this without custom
development.

**Catalog.** An external catalog (REST, JDBC, Nessie or Hive) should hold commit
authority. The on-premises gateway should not be relied upon for commit atomicity,
as conditional write support varies between S3-compatible implementations.

---

## 8. Recovery semantics

The principle that keeps recovery simple is that Kafka is the source of truth and
the local buffer is disposable. No data in the buffer has had its offsets
committed, so on restart the partial buffer is discarded and reading resumes from
the last committed offset.

| Point of failure | Outcome |
| --- | --- |
| During buffering | Buffer discarded, replay from last committed offset. No data loss or duplication. |
| After object written, before catalog commit | The object exists but is absent from the table, so readers never see it. Replay rewrites the data and the original object becomes an orphan. |
| After catalog commit | Restart resumes from the recorded position. |

**Orphaned file cleanup is required**, since on-premises capacity is finite.
Orphan file removal should be scheduled with an age threshold exceeding the longest
possible in-flight write.

**Rebalance behaviour.** When a writer fails, every remaining writer has its
partitions revoked and reassigned. If all of them discard in-flight windows, each
partition replays its last window simultaneously, producing a substantial burst
against the appliance. This is mitigated by static group membership, an increased
session timeout, a rate-limited replay path, and flushing on partition revocation
where this can complete within the rebalance timeout.

**Kafka broker restarts** are largely uneventful. The item to verify is upstream:
without `acks=all` and `min.insync.replicas=2`, a broker restart can lose messages
before the writer tier receives them, and no downstream mechanism can recover them.

---

## 9. Kafka Connect Iceberg sink

Sources: the [Apache Iceberg Kafka Connect documentation](https://iceberg.apache.org/docs/nightly/kafka-connect/)
and the [connector design document](https://github.com/databricks/iceberg-kafka-connect/blob/main/docs/design.md).

### Offset storage

The connector maintains two distinct sets of offsets.

- **Source topic offsets** are managed by the workers and committed to a
  sink-managed consumer group within a Kafka transaction, together with the data
  files event.
- **Control topic offsets** are stored in the Iceberg snapshot as a summary
  property, and are read back before each commit so that only later events are
  committed.

On restart, offsets are initialised from the sink-managed group; the Kafka Connect
consumer group serves only as a fallback. Resetting offsets requires both groups to
be reset.

### Commit coordination

One worker is elected coordinator, specifically the worker holding partition 0 of
the first configured topic, so the role fails over on partition reassignment.
Workers communicate with the coordinator over a control topic. This design avoids
producing n × m snapshots (n tasks by m commit intervals), which would inflate
metadata and cause commit contention.

### Recovery behaviour

| Failure | Behaviour |
| --- | --- |
| Worker fails during processing | Restarts from the last sink-managed group offsets. Data written since that point remains uncommitted. The documentation states that table maintenance should be run regularly to remove the resulting orphaned files. |
| Coordinator fails before committing | On startup it re-reads all data file events since the last table commit to reconstruct the pending file list. |
| Iceberg commit fails | The buffer is retained and committed at the next interval. |

### Items requiring attention

**Duplicates remain possible.** A garbage collection pause exceeding the consumer
session timeout, which defaults to 45 seconds, can cause partition reassignment
while the original task is still running. That task then completes its commit.
Zombie fencing is listed as a future enhancement, so the session timeout should be
treated as a correctness setting.

**Default failure handling is strict.**
`iceberg.control.commit.max-consecutive-failures` defaults to 1, so the coordinator
terminates after a single failed commit. This should be increased for on-premises
deployment, where transient appliance failures are expected. All errors within the
connector are non-retryable, and Kafka Connect fails a task on a non-retryable
error by default.

### Configuration

| Property | Note |
| --- | --- |
| `iceberg.control.commit.interval-ms` | Default 300,000 (5 minutes), which is already appropriate for an on-premises appliance |
| `iceberg.control.commit.timeout-ms` | Default 30,000 (30 seconds) |
| `iceberg.control.commit.max-consecutive-failures` | Default 1; increase |
| `iceberg.catalog.s3.endpoint` | Required for a self-hosted endpoint |
| `iceberg.catalog.s3.path-style-access` | Frequently required for a self-hosted endpoint |
| `iceberg.catalog.io-impl` | Use `S3FileIO` rather than the Hadoop default |
| `iceberg.control.topic` | One control topic per connector |

**Configuration note.** If the Kafka Connect consumer group ID and the Iceberg
control topic group ID do not match, no coordinator is elected and no commits
occur. Data is consumed but never written, and no clear error is reported.

The connector requires Kafka 2.5 or later, as it relies on KIP-447 for exactly-once
semantics.

---

## 10. What to measure and test

### 10.1 Identify the object store product

The applicable tuning parameter depends on the product in use. The product should
be identified and the corresponding constraint on small-object ingest assessed.

| Product | Governing constraint | Action |
| --- | --- | --- |
| Ceph RGW | Bucket index shards held in RocksDB omap | Run `radosgw-admin bucket limit check`. Pre-shard at creation using `rgw_override_bucket_index_max_shards`, budgeting approximately 100,000 objects per shard. Place the index pool on NVMe. Disable dynamic resharding on high-ingest buckets and shard manually. |
| MinIO | Erasure set width; no central index | Run `mc admin info` to establish drive and erasure set layout. Cost appears in listing operations, so measure listing latency on a loaded bucket rather than write latency. Objects below approximately 128 KiB are stored inline in metadata. |
| Dell ECS / StorageGRID / Cloudian | Vendor metadata store | Request documented operations per second per node and objects-per-bucket guidance from the vendor. These figures are published and should not be inferred. |

### 10.2 Measure the operations-per-second ceiling

Benchmarking should be performed against the installed hardware rather than
referencing published cloud provider limits.

1. Use `warp` or an equivalent S3 benchmark, with object sizes matching those the
   intended window will produce.
2. Increase concurrency until p99 latency rises sharply or 503 responses appear.
   Record the PUT operations per second at that point.
3. Repeat against a bucket pre-loaded with approximately ten million objects. The
   difference between the two results indicates how far the metadata layer degrades
   as the bucket fills, which is the figure the design must accommodate.
4. Set the operating budget at 50% of that figure, reserving the remainder as
   headroom for compaction, replay and erasure recovery following a drive failure.

The minimum rotation interval follows from that budget:
`partitions ÷ operating budget = minimum seconds per window`.

### 10.3 Test the replay burst

This should be executed as a planned exercise rather than encountered during an
incident.

1. In a non-production environment or during an off-peak window, stop the writer
   for at least the target outage tolerance.
2. Restart and record peak PUT rate, 503 response rate, latency of other workloads
   on the cluster, and total time to drain.
3. Set the replay throttle such that the peak remains within the operating budget
   measured in section 10.2.
4. Where feasible, repeat with a drive removed, since recovery traffic competing
   with replay represents the realistic worst case.

### 10.4 Size retention

Retention must cover both the outage and the subsequent catch-up period, and the
catch-up period is typically the longer of the two.

Given an ingest rate `R`, an outage duration `T`, and throttled replay at `k × R`:

```
backlog     = R × T
drain rate  = (k − 1) × R        (ingest continues during catch-up)
drain time  = T / (k − 1)
retention  ≥ T × k / (k − 1)
```

| Replay throttle | Minimum retention |
| --- | --- |
| 3 × ingest | 1.5 × outage |
| 2 × ingest | 2 × outage |
| 1.5 × ingest | 3 × outage |

A six-hour firmware maintenance window, with replay throttled to 1.5 × ingest,
therefore requires eighteen hours of retention rather than six. This figure is a
minimum and should carry additional margin.

These two settings are in tension: throttling replay more aggressively to protect
the appliance increases the retention required. The throttle should be set first,
from the measured operating budget, and retention sized accordingly.

---

## 11. The three concerns assessed

### 11.1 Request-rate limits before capacity

Assessed as valid. Request rate and stored volume scale differently, and batching
changes request rate by orders of magnitude while leaving stored volume unaffected.

One qualification: batching addresses only one of two figures. It reduces requests
per second, which is what the appliance rate-limits. It does not reduce the total
number of objects held in the bucket, which only increases, and that total is what
eventually degrades the metadata layer. Compaction and snapshot expiry are
therefore still required. Both figures should be monitored: requests per second
against the gateway ceiling, and total objects per bucket against the index shard
budget.

### 11.2 Producers should not own window size

Assessed as valid. The producer cannot determine the correct value because it does
not have the necessary inputs. Two further arguments are stronger.

**Consumers have different requirements.** A single producer may feed several
sinks with different targets, such as a lakehouse table requiring 256 MB files and
a search index requiring second-level latency. Producer-side windowing imposes one
policy on all of them.

**Window size would also control how much data is lost on failure.** Window size is
chosen to hit a target file size, which is a storage concern. Inside a producer,
the only way to achieve it is to hold unacknowledged events in memory, so the same
number determines how much data is lost when an instance fails. Tuning for 256 MB
files would mean accepting a proportionally larger loss window, with no way to
separate the two. Behind Kafka the same setting costs lag rather than loss, because
the buffered data is already durable.

**Multiple producers make this materially worse.** The two arguments above assume a
single producer. With N producers on one topic, each buffers independently, so a
window produces N objects rather than one, each approximately 1/N of the intended
size. Nothing coordinates the window boundaries, so a low-volume producer emits
undersized files regardless of the configured window.

Producer count is also elastic. Scaling from three instances to thirty under load
multiplies object count tenfold at precisely the point at which the appliance is
busiest: higher traffic causes more producers, more producers cause more objects,
and more objects reduce appliance throughput. The effect is self-reinforcing. A
rolling deployment produces a smaller version of the same behaviour, flushing N
partial windows simultaneously. There is also no single point at which the policy
can be changed, so retuning file size would require a coordinated redeployment of
every producer.

By contrast, write parallelism on the consumer side is bounded by partition count,
which is set deliberately and can be planned around. Producer count is determined
by the autoscaler.

### 11.3 Direct write couples Kafka to storage

Assessed as valid, where "direct" means a Kafka consumer calling the appliance from
within its poll loop with no writer tier in between.

Without that tier there is no destination for backpressure. When the appliance
returns 503 or times out, the consumer either retries within the poll loop —
blocking, missing the poll interval, being evicted from the consumer group, and
triggering a rebalance across every other consumer, which in turn produces the
replay burst that increases load on the appliance further — or it fails fast and
enters a crash loop, or it discards data. None of these outcomes is acceptable,
because the only available backpressure mechanism is to stop consuming, and
stopping is what breaks group membership.

The function of the writer tier is to own a buffer, so that backpressure has a
destination. This is what converts storage unavailability into consumer lag rather
than consumer group instability. Kafka Connect provides this together with the
commit protocol.

Three parameters bound the residual coupling:

- Retention must cover the outage plus drain time at the throttled replay rate; the
  second term is frequently longer than the first.
- Offsets must be committed after the catalog commit, never before.
- **Shared failure domain.** If Kafka tiered storage is configured against the same
  appliance, an outage affects both systems and the decoupling fails at the point
  it is most needed. This is an infrastructure question rather than a design one.

### Summary

All three concerns are assessed as valid. The per-message write path should be
addressed first, as it represents the binding constraint, and the writer tier that
resolves it also resolves the second and third concerns.
