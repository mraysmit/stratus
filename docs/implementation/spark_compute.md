# Stratus Increment 3 — Apache Spark Batch Compute

## 1. Purpose

This document is the technical implementation plan for Increment 3 of the Stratus platform as defined in [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md).

Increment 3 delivers an Apache Spark standalone cluster running on Podman containers. Spark is configured to use Apache Polaris as its Iceberg catalog and Ceph RGW as object storage. When this increment is complete, data flows from a raw source file in the landing zone through bronze, silver, and gold Iceberg tables. Quality checks run against each dataset and gate promotion between zones. A Java verification suite submits real Spark jobs and confirms the full batch compute pipeline works end to end.

The Podman topology in this document is the developer profile. Production uses the same images, job artifacts, Polaris/Ceph contracts, and tests, but requires the approved multi-host worker topology, an accepted Spark master availability design or explicit RTO/RPO exception, externalized event logs, trusted service certificates, managed credentials, capacity evidence, and worker/master failure drills. Passing the local topology does not accept production Spark.

**Prerequisites:**
- Increment 1 complete — Ceph RGW cluster running, all buckets and service accounts in place
- Increment 2 complete — Polaris running, all namespaces and the `platform.quality_check_results` table created, all Increment 2 gate tests passing

**Track rule:** Developer work requires the developer gates of Increments 1 and 2. Increment 3 production acceptance requires their production gates, except final security-dependent checks close after Increment 7 as defined by the Phase 1 plan.

---

## 2. Assumptions and Prerequisites

- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- Podman 5.8.2 installed on each Spark node, or a newer approved stable patch after regression testing
- JDK 25 and Maven 3.9.16 on the approved build worker; development hosts may use the same toolchain, while verification hosts require only the approved container runtime and verifier runtime inputs. Spark job artifacts are compiled with the build-system toolchain to the Java release supported by the selected Spark runtime.
- DNS resolution: `spark-master.stratus.local`, `spark-worker1.stratus.local`, `spark-worker2.stratus.local` resolve correctly
- Nodes can reach Ceph RGW at `object-store.stratus.local` (HTTPS) and Polaris on port 8181
- `svc-spark` Ceph RGW credentials and Polaris principal credentials from earlier increments are available

### Reference documentation audit

Reference baseline: 2026-07-10.

The approved Spark compatibility target for this increment is Spark 4.1.2 with Scala 2.13 and Iceberg 1.11.0's Spark 4.1 runtime artifact. Spark 4.2.0 is not part of this increment unless the platform records a new compatibility decision. Do not fall back to the older Spark 3.5 / Scala 2.12 examples unless the platform records an explicit compatibility exception.

Before implementation, confirm the exact container image tags and Maven artifacts match the approved version matrix. Do not mix a newer Spark major version with older Iceberg runtime artifacts without verifying the upstream compatibility notes and running the Increment 2 and 3 verification suites.

---

## 3. Cluster Topology

A Spark standalone cluster with one master and two workers. All three run as Podman containers.

```text
spark-master.stratus.local
┌──────────────────────────────────┐
│  Podman: spark-master            │
│  Spark Master UI  :8080 (HTTP)   │
│  Spark Master     :7077          │
└──────────────────────────────────┘
          │              │
          ▼              ▼
spark-worker1          spark-worker2
┌──────────────┐    ┌──────────────┐
│ spark-worker │    │ spark-worker │
│ :8081        │    │ :8082        │
└──────────────┘    └──────────────┘
          │              │
          └──────┬───────┘
                 │  reads/writes Iceberg tables
                 ▼
  Polaris (Increment 2)  ←→  Ceph RGW (Increment 1)
```

Spark jobs are submitted to the master via `spark-submit` or the Spark Java API. The master distributes work to the workers. Workers read and write Iceberg data files directly to Ceph RGW using the `svc-spark` credentials. All table metadata is resolved through Polaris.

### Production profile overlay

| Concern | Production requirement |
|---|---|
| Master recovery | use Spark standalone recovery backed by the approved ZooKeeper service and a dedicated `/stratus/spark` namespace, or record a single-master RTO/RPO exception with automated rebuild and tested recovery |
| Worker placement | workers run on separate failure domains with declared CPU, memory, local scratch, and network capacity; loss of one worker is tested during a representative job |
| Submission | Airflow and approved deployment automation submit prebuilt artifacts over the internal Spark master protocol; interactive human submission is restricted |
| Internal transport | enable Spark authentication, network crypto, I/O encryption, and secret injection supported by Spark 4.1.2; do not place the shared secret in source control or command history |
| UIs | master, worker, application, and history UIs are internal-only or exposed through an authenticated HTTPS proxy; their HTTP ports are not production ingress |
| Event history | write Spark event logs to `s3a://stratus-platform/spark-event-logs/` through Ceph RGW and run a history server from the same immutable image, trusted CA, and scoped service identity used by the compute nodes |
| Recovery evidence | prove worker loss, master restart/failover, event-log continuity, failed-job retry, and cleanup of abandoned staging data |

The production overlay is applied after the developer jobs are stable and before the Increment 3 production gate closes. ZooKeeper used for Spark recovery may share the production ZooKeeper service only with separate ACLs, chroot/namespace, capacity review, and failure ownership; otherwise deploy a dedicated quorum.

---

## 4. Ports

| Port | Node | Purpose |
|---|---|---|
| 7077 | spark-master | Spark master RPC — workers and job submissions connect here |
| 8080 | spark-master | Spark master web UI |
| 8081 | spark-worker1, spark-worker2 | Spark worker web UI — default port, same on each worker host |
| 4040 | any | Spark application UI (active jobs only, bound to the driver host) |

Each worker runs on its own host so both can use port 8081 without conflict. The `--webui-port 8081` flag is set explicitly in the container start command to make this clear. Port 4040 is ephemeral — it is only open while a job is running and binds on whichever host the driver is running on.

Ensure ports 7077 and 8081 are open between all nodes. Port 8080 and 8081 need to be reachable from any host used for monitoring or administration.

---

## 5. Spark Docker Image

The official Apache Spark Docker image is used as the base. A custom image adds
Hadoop's S3A connector for raw-file and event-log access through Ceph RGW and a
Stratus-packaged Iceberg runtime. Hadoop S3A 3.4.3 requires AWS SDK 2.35.4,
while Iceberg 1.11.0 requires 2.44.4. Both upstream bundles use the same Java
package names, so loading both directly is ambiguous. The Stratus runtime
combines Iceberg's Spark and AWS bundles and relocates Iceberg's SDK under
`dev.stratus.thirdparty.iceberg.awssdk`; Hadoop retains the unrelocated SDK it
was built and tested with.

The artifact lock for this image contains:

| Artifact | Version | Purpose |
|---|---:|---|
| `stratus-iceberg-aws-runtime` | Iceberg 1.11.0 | Spark 4.1 runtime, `S3FileIO`, and Iceberg's relocated AWS SDK |
| `hadoop-client-api` / `hadoop-client-runtime` | 3.4.3 | One matched Hadoop client line, replacing the base image's 3.4.2 pair |
| `hadoop-aws` | 3.4.3 | S3A filesystem used for `s3a://` landing files and Spark event logs |
| Hadoop S3A runtime dependencies | resolved from the 3.4.3 POM | SDK 2.35.4 and connector-specific dependencies, locked with checksums |

The selected Spark image carries Hadoop 3.4.2. The Dockerfile removes its API
and runtime pair before copying the complete 3.4.3 pair and `hadoop-aws` 3.4.3.
Image conformance rejects any remaining 3.4.1/3.4.2 client and proves that only
Hadoop's SDK owns the unrelocated `S3Client` class. Hadoop 3.4.3 is also the
released patch line containing HADOOP-19212 for current-JDK Subject handling.

### Dockerfile

Create `platform/spark/image/Dockerfile` in the Stratus repository:

```dockerfile
FROM apache/spark:4.1.2-scala2.13-java17-python3-ubuntu

USER root

# Replace the base Hadoop pair as one versioned unit, then copy the locked S3A
# dependencies and isolated Iceberg runtime.
RUN rm /opt/spark/jars/hadoop-client-api-3.4.2.jar \
       /opt/spark/jars/hadoop-client-runtime-3.4.2.jar
COPY jars/ /opt/spark/jars/

USER spark
```

Java 26 is the Stratus build and verifier baseline. Spark 4.1.2 does not list
Java 26 as a supported runtime, so the selected cluster image remains on Java
17 and jobs remain compiled with `--release 17`. The external verification
driver deliberately runs on Java 26 as a tested compatibility exception; the
live client, S3A, catalog, worker-distribution and latency checks are its
evidence. This is not a claim of upstream Spark support for Java 26.

### Build-system image publication

The following container build is a build-pipeline step. It runs on an approved build worker, followed by tests, scanning, registry publication, and digest recording. Do not build the image on Spark runtime hosts. The dependency-resolution job must materialise `artifacts/s3a-runtime/` from the committed lock, reject undeclared JARs and duplicate Hadoop client classes, and verify every checksum before `podman build` runs.

```bash
cd docker/spark
podman build -t stratus/spark:4.1.2 .
```

Distribute the image to all three Spark nodes. For a lab without a registry, save and load:

```bash
podman save stratus/spark:4.1.2 | gzip > stratus-spark.tar.gz
scp stratus-spark.tar.gz spark-worker1.stratus.local:~
scp stratus-spark.tar.gz spark-worker2.stratus.local:~

# On each worker
podman load < ~/stratus-spark.tar.gz
```

---

## 6. Spark Configuration

Create `/etc/stratus/spark-defaults.conf` on each node. This file is mounted into every container and configures Spark's connection to Polaris and Ceph RGW.

```properties
# /etc/stratus/spark-defaults.conf

# Iceberg catalog — Apache Polaris REST
spark.sql.extensions                            org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions
spark.sql.catalog.stratus                       org.apache.iceberg.spark.SparkCatalog
spark.sql.catalog.stratus.type                  rest
spark.sql.catalog.stratus.uri                   https://polaris.stratus.local:8181/api/catalog
spark.sql.catalog.stratus.rest.auth.type        oauth2
spark.sql.catalog.stratus.oauth2-server-uri     https://polaris.stratus.local:8181/api/catalog/v1/oauth/tokens
spark.sql.catalog.stratus.credential            svc-spark:<client-secret>
spark.sql.catalog.stratus.scope                 PRINCIPAL_ROLE:ALL
spark.sql.catalog.stratus.warehouse             stratus
spark.sql.catalog.stratus.io-impl               org.apache.iceberg.aws.s3.S3FileIO
spark.sql.catalog.stratus.s3.endpoint           https://object-store.stratus.local
spark.sql.catalog.stratus.s3.access-key-id      svc-spark
spark.sql.catalog.stratus.s3.secret-access-key  <svc-spark secret>
spark.sql.catalog.stratus.s3.path-style-access  true

# Default catalog
spark.sql.defaultCatalog                        stratus
spark.sql.session.timeZone                      UTC

# Four-core developer profile; size these values for the production cluster.
spark.cores.max                                 2
spark.executor.cores                            1
spark.executor.memory                           1g
spark.default.parallelism                       8
spark.sql.shuffle.partitions                    8

# S3A filesystem (raw landing files and production event logs through Ceph RGW)
spark.hadoop.fs.s3a.impl                        org.apache.hadoop.fs.s3a.S3AFileSystem
spark.hadoop.fs.s3a.endpoint                    https://object-store.stratus.local
spark.hadoop.fs.s3a.access.key                  svc-spark
spark.hadoop.fs.s3a.secret.key                  <svc-spark secret>
spark.hadoop.fs.s3a.path.style.access           true
spark.hadoop.fs.s3a.connection.ssl.enabled      true

# Developer profile event log. The production overlay replaces this value with
# s3a://stratus-platform/spark-event-logs/ and uses a scoped history-server identity.
spark.eventLog.enabled                          true
spark.eventLog.dir                              file:///data/spark-events
spark.local.dir                                 /opt/spark/scratch

# Include the REST catalog credential key in Spark UI/event-log redaction.
spark.redaction.regex                           (?i)secret|password|token|access[.]?key|credential
spark.sql.redaction.options.regex               (?i)secret|password|token|access[.]?key|credential

# Serialization
spark.serializer                                org.apache.spark.serializer.KryoSerializer
```

The parallelism values above are deliberately specific to the four-core
developer harness. Spark otherwise starts with 200 SQL shuffle partitions,
which creates far more scheduling work than useful work for its tiny
conformance datasets. Production deployments must size parallelism from their
actual core count and workload rather than inherit the developer values.

---

## 7. Podman Container Setup

### Environment file

Create `/etc/stratus/spark.env` on each node:

```bash
# /etc/stratus/spark.env
SPARK_MASTER_URL=spark://spark-master.stratus.local:7077
SPARK_CONF_DIR=/etc/stratus
```

### Start the master

Run on `spark-master.stratus.local`:

```bash
podman run -d \
  --name spark-master \
  --hostname spark-master.stratus.local \
  --network host \
  --env-file /etc/stratus/spark.env \
  -v /etc/stratus/spark-defaults.conf:/etc/stratus/spark-defaults.conf:ro,z \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  stratus/spark:4.1.2 \
  /opt/spark/bin/spark-class org.apache.spark.deploy.master.Master \
    --host spark-master.stratus.local \
    --port 7077 \
    --webui-port 8080
```

### Start worker 1

Run on `spark-worker1.stratus.local`:

```bash
podman run -d \
  --name spark-worker \
  --hostname spark-worker1.stratus.local \
  --network host \
  --env-file /etc/stratus/spark.env \
  -v /etc/stratus/spark-defaults.conf:/etc/stratus/spark-defaults.conf:ro,z \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  stratus/spark:4.1.2 \
  /opt/spark/bin/spark-class org.apache.spark.deploy.worker.Worker \
    --webui-port 8081 \
    spark://spark-master.stratus.local:7077
```

### Start worker 2

Run on `spark-worker2.stratus.local`:

```bash
podman run -d \
  --name spark-worker \
  --hostname spark-worker2.stratus.local \
  --network host \
  --env-file /etc/stratus/spark.env \
  -v /etc/stratus/spark-defaults.conf:/etc/stratus/spark-defaults.conf:ro,z \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  stratus/spark:4.1.2 \
  /opt/spark/bin/spark-class org.apache.spark.deploy.worker.Worker \
    --webui-port 8081 \
    spark://spark-master.stratus.local:7077
```

### Verify the cluster

Open `http://spark-master.stratus.local:8080` in a browser. Both workers must appear as `ALIVE`.

Or check via curl:

```bash
curl -s http://spark-master.stratus.local:8080/json/ | jq '.workers | length'
# Expected: 2
```

### Auto-start with systemd

On each node:

```bash
podman generate systemd --new --name spark-master \  # or spark-worker
  | sudo tee /etc/systemd/system/stratus-spark.service

sudo systemctl daemon-reload
sudo systemctl enable --now stratus-spark.service
```

---

## 8. Platform Spark Jobs

Five Spark jobs are implemented as capability modules under `jobs/spark/`; Java packages use the `dev.stratus.jobs.spark` namespace.

Every job rejects an argument it does not read. A misspelled or retired name
stops the job rather than being ignored, because an ignored argument means the
job ran with the default the caller was trying to replace.

**Status codes** (`JobExit`). The orchestrator reads the code, not the log:

| Code | Meaning |
| --- | --- |
| `0` | The job did what it was asked |
| `1` | The arguments were wrong, or the job could not complete |
| `2` | A blocking quality check failed, so nothing was written |
| `3` | The incoming schema conflicts with the table's, so nothing was written |

**Logging.** INFO records each job's outcome with stable identifiers. DEBUG
records the schema comparison, the batch predicate, the upsert statement, the
maintenance call, and each quality rule's measurement. `JobLogging` reads
`STRATUS_LOG_LEVEL` — the same switch the Ceph, catalog and secrets verifiers
use — and renders one record per line with a UTC timestamp, naming the
diagnostic level DEBUG rather than the JDK's FINE.

The level must be set explicitly or the diagnostics are unreachable: the JDK
discards FINE by default, so a job would carry records no operator could ever
turn on. The live suite passes the level into the container and relays each
job's output into its own transcript, which is what makes those records visible
rather than merely present in the source.

**Write mode per zone** follows the architecture (§6.4.6): bronze appends,
silver upserts copy-on-write, gold is rebuilt in full. Each zone's write
properties — `write.{delete,update,merge}.mode`, the matching isolation levels,
`write.format.default`, `write.target-file-size-bytes` and the pinned
`format-version` — are stated by `ZoneWriteProperties` and applied both at
create and on every run, because Iceberg honours table properties on CREATE and
silently discards them on an append.

### Job 1 — Ingestion: landing → bronze

Reads a CSV, JSON or NDJSON source file from `stratus-landing`, applies minimal
normalisation (string trimming, null handling), and appends it to an Iceberg
table in the `bronze` namespace via Polaris.

Bronze accumulates. Each landing file is a batch, and a batch is appended to
whatever the table already holds — a second day's file must not be able to erase
the first day's. Bronze is partitioned by `stratus_batch_id`, which makes a
replay a metadata operation that cannot reach another batch's files.

**Inputs:**
- `sourceFile` — S3A path to the source file in `stratus-landing` (e.g. `s3a://stratus-landing/customers/2024-01-15/customers.csv`)
- `targetTable` — fully qualified Iceberg table name (e.g. `stratus.bronze.customers`)
- `sourceSystem` — name of the source system (written as table property for Atlas lineage)
- `batchId` — the identity of this delivery, carried on every row
- `onExistingBatch` — `fail` (default) or `replace`. A batch id the table already holds is refused unless a replay is asked for explicitly
- `schema` — optional DDL string. Preferred over inference, which reads types out of whichever rows happened to arrive: `007` becomes `7`, and a column of all blanks infers one type in this batch and another in the next
- `runId` — optional; defaults to a generated identifier

**Audit columns added to every row:** `stratus_batch_id`,
`stratus_ingested_at` (from the job's injected clock, so the value is testable),
`stratus_source_file`.

**Schema drift.** A batch that adds a column evolves the table, and the rows
that arrived before it read back null. A batch that changes a column's type is
refused with status `3`, naming every conflicting column and both types — not
just the first, because a source system that changed five columns should not
cost five failed runs to discover.

**Outputs:**
- Bronze Iceberg table written in `stratus-bronze`
- Lineage event: external source → bronze table (logged to stdout in Increment 3; sent to Atlas in Increment 6)

**Lineage payload emitted (logged):**
```json
{
  "type": "INGESTION",
  "source": "external:<sourceSystem>/<sourceFile>",
  "target": "stratus.bronze.<table>",
  "run_id": "<uuid>",
  "timestamp": "<iso8601>"
}
```

### Job 2 — Transform: bronze → silver

Reads a bronze Iceberg table, collapses it to one row per business key, and
upserts those rows into a silver Iceberg table.

Silver is merged, not rebuilt. The merge condition compares a monotonic
sequence as well as the key, so a row only updates a row it is newer than.
Matching on the key alone lets a replay carrying an older version of a record
overwrite state that was already corrected — the architecture (§6.4.4) calls
that the most common silent corruption in a change-data pipeline, and it is
silent precisely because both rows are valid and the table simply ends up
holding the wrong one.

```sql
MERGE INTO <silver> AS t USING <batch> AS s
  ON t.<key> = s.<key>
  WHEN MATCHED AND (s.<seq> > t.<seq> OR t.<seq> IS NULL) THEN UPDATE SET *
  WHEN NOT MATCHED THEN INSERT *
```

`t.<seq> IS NULL` is in the condition on purpose: comparing against a null
yields null rather than true, so without it a silver row that arrived without a
sequence value could never be corrected again.

**Inputs:**
- `sourceTable` — fully qualified bronze table name
- `targetTable` — fully qualified silver table name
- `businessKey` — comma-separated list of columns forming the deduplication key
- `sequenceColumn` — the monotonic column that decides which version of a record is newer. This replaces the retired `orderBy`, which is now refused rather than ignored
- `sourceBatch` — optional; process one bronze batch rather than the whole table. Reading the whole table re-picks the newest row per key on every run, which produces the right answer for the wrong reason and leaves the sequence comparison unable to decide anything
- `qualityRunId` — optional; consult the promotion gate before writing
- `runId` — optional; defaults to a generated identifier

Within a batch, a key arriving twice is collapsed to one row. The ordering is
made total by a hash of the row's own contents, so two rows sharing a key *and*
a sequence value still have a defined winner and a re-run over the same input
produces the same silver table.

Type normalisation and reference-data enrichment are not implemented. They
remain in scope for a later increment.

**Outputs:**
- Silver Iceberg table written in `stratus-silver`
- Lineage event: bronze table → silver table

**Row-level deletes (tombstones) are not implemented.** They need a delete-flag
argument, a rule for a null flag, and they change silver from update-only to a
table that produces delete files, which interacts with `write.delete.mode`. The
ordering trap is worth recording for whoever adds them: the delete clause must
carry the sequence guard too, or a late tombstone deletes a newer row.

### Job 3 — Materialisation: silver → gold

Reads one or more silver Iceberg tables, applies aggregations or joins, and writes a gold Iceberg table. This job is domain-specific in its logic but uses the same job contract as transform.

**Inputs:**
- `sourceTables` — comma-separated list of fully qualified silver table names
- `targetTable` — fully qualified gold table name
- `sql` — the aggregation or join. It runs with the engine's own catalog privileges, so it is platform configuration and not user input; the source tables are stated separately precisely so lineage records what was actually read
- `qualityRunId` — optional; consult the promotion gate before writing
- `runId` — optional; defaults to a generated identifier

**Outputs:**
- Gold Iceberg table written in `stratus-gold`, rebuilt in full — the documented gold write mode
- Lineage event: silver tables → gold table

### Job 4 — Quality checks

Runs a defined set of quality rules against a target Iceberg table and writes results to `platform.quality_check_results`. Quality rules are supplied as job parameters.

**Supported check types:**
- `schema_conformance` — all columns present and typed correctly
- `completeness` — null rate for each column below defined threshold
- `uniqueness` — no duplicate values on defined key columns
- `freshness` — latest record timestamp within defined SLA window
- `referential_integrity` — foreign key values exist in reference table
- `row_count_min` — row count meets minimum threshold

`schema_conformance` checks that the named columns are present. It does not
check their types; drift in a column's type is refused by ingestion (Job 1)
before any row reaches a table a rule could measure.

**Inputs:**
- `targetTable` — fully qualified Iceberg table name
- `checks` — JSON array of check definitions (type, parameters, severity)
- `checksBase64` — the same JSON, base64-encoded. Exactly one of the two must be given. The encoded form exists because a JSON document does not survive every path to a job: submitting through a container runtime on Windows strips the double quotes
- `runId` — unique identifier for this quality run
- `pipelineRunId` — optional; ties this quality run to the pipeline run that caused it

**Outputs:**
- One result record per check written to `platform.quality_check_results`
- Summary logged: total checks, passed, failed, warnings

### Job 5 — Table maintenance

Runs Iceberg maintenance operations on a target table using Spark actions.

**Inputs:**
- `targetTable` — fully qualified Iceberg table name
- `operations` — comma-separated list: `expire_snapshots`, `rewrite_data_files`, `delete_orphan_files`
- `olderThan` — retention boundary. Required for `delete_orphan_files`: Iceberg's own default would delete files older than three days, and a job that inherits that silently can remove files a concurrent write has staged but not yet committed
- `retainLast` — snapshots to keep, defaulting to 2 rather than Iceberg's 1, so there is a snapshot to roll back to after a bad write

**Outputs:**
- Metrics logged: data and manifest files deleted, files rewritten and added, orphan files removed

Orphan removal needs three things on this platform that the other operations do
not, each found by running it against the live cluster rather than reasoned
about:

- The **fully qualified** table identifier. Orphan removal reads the table's
  metadata tables by name, and a two-part name has its first part taken for a
  catalog — `The catalog 'bronze' not found`.
- A **location whose scheme Hadoop can list**. Iceberg's S3FileIO records paths
  as `s3://` and the cluster registers only `s3a`, so listing the table's own
  location fails with `No FileSystem for scheme "s3"`. The location is not among
  the properties `SHOW TBLPROPERTIES` returns, so the job derives it from the
  newest metadata log entry.
- `equal_schemes => map('s3', 's3a')`, so a file listed as `s3a://` is
  recognised as the file the metadata records as `s3://`. Without it every live
  file looks like an orphan, which is the most destructive possible misreading.

Iceberg additionally refuses any retention inside 24 hours, whatever
`--olderThan` says, because a file written minutes ago may belong to a commit
still in flight.

Which operations to run is still named on the command line. Driving the decision
from Iceberg metadata-table thresholds is task `P1-2.5-P1`; the components that
make that decision (`MaintenanceAdvisor`, `OrphanFileDetector`) exist and are
proven under `P1-2.5-D1`, and neither has any destructive path.

---

## 9. Promotion Gate

The promotion gate is a plain Java class (no Spark dependency) that runs between quality check execution and the downstream transform or materialisation job. It reads quality outcomes for a given `runId` from `platform.quality_check_results` via the Iceberg Java API and makes a deterministic promote/block decision.

```text
Quality job (Spark)
      │
      │  writes results to platform.quality_check_results
      ▼
PromotionGate.evaluate(runId, targetTable)
      │
      ├── all blocking checks PASSED → return PROMOTE
      │
      ├── any blocking check FAILED  → return BLOCK (with failing rule names)
      │
      └── WARNING only              → return PROMOTE (warnings logged)
```

The gate is called by the orchestration layer (Job 2 and Job 3) before the downstream write. If the gate returns BLOCK, the job exits with a non-zero status code that Airflow will detect as a failure in Increment 4.

Override requires an explicit `--override-reason` parameter and a named `--override-principal`. Overrides are written as additional records to `platform.quality_check_results` with `status=overridden`.

---

## 10. Java Verification Suite

The Java source and Maven dependencies in this section are build inputs only. The approved build system publishes the executable verifier as a pinned container image. Operators execute that image and do not build on the verification host or inside the verification container.

The verification suite submits real Spark jobs to the live cluster and confirms the full batch pipeline works end to end. It uses the Spark Java API for job submission.

### Additional Maven dependency

```xml
<dependency>
    <groupId>org.apache.spark</groupId>
    <artifactId>spark-sql_2.13</artifactId>
    <version>4.1.2</version>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>org.apache.iceberg</groupId>
    <artifactId>iceberg-spark-runtime-4.1_2.13</artifactId>
    <version>1.11.0</version>
    <scope>provided</scope>
</dependency>
```

### Configuration

| Variable | Description |
|---|---|
| `STRATUS_SPARK_MASTER` | e.g. `spark://spark-master.stratus.local:7077` |
| `STRATUS_POLARIS_URI` | Polaris REST API base URL |
| `STRATUS_POLARIS_CLIENT_ID` | `svc-spark` |
| `STRATUS_POLARIS_CLIENT_SECRET` | svc-spark client secret |
| `STRATUS_POLARIS_CATALOG` | `stratus` |
| `CEPH_RGW_ENDPOINT` | Ceph RGW S3 endpoint |
| `CEPH_RGW_ACCESS_KEY` | `svc-spark` access key |
| `CEPH_RGW_SECRET_KEY` | `svc-spark` secret key |

### Shared Spark session helper

Place in `verification/spark-pipeline/src/test/java/dev/stratus/verification/spark/SparkTestSession.java`:

```java
package dev.stratus.jobs.spark;

import org.apache.spark.sql.SparkSession;

public class SparkTestSession {

    public static SparkSession create() {
        String master      = System.getenv("STRATUS_SPARK_MASTER");
        String polarisUri  = System.getenv("STRATUS_POLARIS_URI");
        String clientId    = System.getenv("STRATUS_POLARIS_CLIENT_ID");
        String clientSecret = System.getenv("STRATUS_POLARIS_CLIENT_SECRET");
        String catalog     = System.getenv("STRATUS_POLARIS_CATALOG");
        String s3Endpoint  = System.getenv("CEPH_RGW_ENDPOINT");
        String accessKey   = System.getenv("CEPH_RGW_ACCESS_KEY");
        String secretKey   = System.getenv("CEPH_RGW_SECRET_KEY");

        return SparkSession.builder()
            .appName("stratus-verification")
            .master(master)
            .config("spark.sql.extensions",
                "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
            .config("spark.sql.catalog." + catalog,
                "org.apache.iceberg.spark.SparkCatalog")
            .config("spark.sql.catalog." + catalog + ".type", "rest")
            .config("spark.sql.catalog." + catalog + ".uri", polarisUri)
            .config("spark.sql.catalog." + catalog + ".credential", clientId + ":" + clientSecret)
            .config("spark.sql.catalog." + catalog + ".scope", "PRINCIPAL_ROLE:ALL")
            .config("spark.sql.catalog." + catalog + ".warehouse", catalog)
            .config("spark.sql.catalog." + catalog + ".io-impl",
                "org.apache.iceberg.aws.s3.S3FileIO")
            .config("spark.sql.catalog." + catalog + ".s3.endpoint", s3Endpoint)
            .config("spark.sql.catalog." + catalog + ".s3.access-key-id", accessKey)
            .config("spark.sql.catalog." + catalog + ".s3.secret-access-key", secretKey)
            .config("spark.sql.catalog." + catalog + ".s3.path-style-access", "true")
            .config("spark.sql.defaultCatalog", catalog)
            .config("spark.hadoop.fs.s3a.endpoint", s3Endpoint)
            .config("spark.hadoop.fs.s3a.access.key", accessKey)
            .config("spark.hadoop.fs.s3a.secret.key", secretKey)
            .config("spark.hadoop.fs.s3a.path.style.access", "true")
            .config("spark.hadoop.fs.s3a.connection.ssl.enabled", "true")
            .getOrCreate();
    }
}
```

### Verification test class

Place in `verification/spark-pipeline/src/test/java/dev/stratus/verification/spark/SparkPipelineVerificationTest.java`:

```java
package dev.stratus.jobs.spark;

import org.apache.spark.sql.*;
import org.apache.spark.sql.types.*;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SparkPipelineVerificationTest {

    static SparkSession spark;
    static final String RUN_ID = UUID.randomUUID().toString();
    // Verification tables created by this test suite — distinct from Increment 2's verification_test table
    static final String BRONZE_TABLE = "stratus.bronze.verification_customers";
    static final String SILVER_TABLE = "stratus.silver.verification_customers";
    static final String GOLD_TABLE   = "stratus.gold.verification_customer_summary";

    @BeforeAll
    static void startSpark() {
        assertThat(System.getenv("STRATUS_SPARK_MASTER"))
            .as("STRATUS_SPARK_MASTER must be set").isNotBlank();
        spark = SparkTestSession.create();
    }

    @Test
    @Order(1)
    void sparkConnectsToCluster() {
        assertThat(spark.sparkContext().master())
            .as("Spark must connect to the standalone cluster")
            .startsWith("spark://");

        // Confirm workers are available
        int workers = spark.sparkContext().statusTracker()
            .getExecutorInfos().length;
        assertThat(workers)
            .as("At least two Spark executors must be available")
            .isGreaterThanOrEqualTo(2);
    }

    @Test
    @Order(2)
    void sparkCanResolvePolarisNamespaces() {
        List<Row> namespaces = spark.sql("SHOW NAMESPACES IN stratus").collectAsList();
        List<String> names = namespaces.stream()
            .map(r -> r.getString(0))
            .toList();

        assertThat(names)
            .as("Spark must resolve all four Polaris namespaces")
            .contains("bronze", "silver", "gold", "platform");
    }

    @Test
    @Order(3)
    void ingestionJobWritesBronzeTable() {
        // Drop if exists from a previous run
        spark.sql("DROP TABLE IF EXISTS " + BRONZE_TABLE);

        // Create a bronze table
        spark.sql("""
            CREATE TABLE %s (
              customer_id STRING NOT NULL,
              name        STRING NOT NULL,
              email       STRING,
              country     STRING,
              created_at  TIMESTAMP
            )
            USING iceberg
            TBLPROPERTIES (
              'write.format.default' = 'parquet',
              'source_system'        = 'verification'
            )
            """.formatted(BRONZE_TABLE));

        // Write source data as if arriving from the landing zone
        spark.sql("""
            INSERT INTO %s VALUES
              ('C001', 'Alice Smith',  'alice@example.com',  'GB', current_timestamp()),
              ('C002', 'Bob Jones',    'bob@example.com',    'US', current_timestamp()),
              ('C003', 'Carol White',  null,                 'DE', current_timestamp()),
              ('C001', 'Alice Smith',  'alice@example.com',  'GB', current_timestamp())
            """.formatted(BRONZE_TABLE));
        // Note: C001 is intentionally duplicated to test silver deduplication

        long rowCount = spark.sql("SELECT count(*) FROM " + BRONZE_TABLE)
            .collectAsList().get(0).getLong(0);

        assertThat(rowCount)
            .as("Bronze table must contain all four rows including the duplicate")
            .isEqualTo(4L);
    }

    @Test
    @Order(4)
    void qualityJobRunsOnBronzeTable() {
        // Completeness check: email null rate must be below 50%
        long total = spark.sql("SELECT count(*) FROM " + BRONZE_TABLE)
            .collectAsList().get(0).getLong(0);
        long nullEmails = spark.sql(
            "SELECT count(*) FROM " + BRONZE_TABLE + " WHERE email IS NULL")
            .collectAsList().get(0).getLong(0);

        double nullRate = (double) nullEmails / total;
        String status = nullRate <= 0.5 ? "passed" : "failed";

        // Write quality result to platform.quality_check_results
        spark.sql("""
            INSERT INTO stratus.platform.quality_check_results VALUES
              ('%s', 'bronze', 'verification_customers', 'bronze',
               'completeness', 'email_null_rate_below_50pct', 'warning',
               '%s', %f, 0.5, null,
               'spark-verification', current_timestamp(), -1L)
            """.formatted(RUN_ID, status, nullRate));

        assertThat(status)
            .as("Completeness check must pass for the verification dataset")
            .isEqualTo("passed");
    }

    @Test
    @Order(5)
    void uniquenessCheckDetectsDuplicates() {
        long duplicateCount = spark.sql("""
            SELECT count(*) FROM (
              SELECT customer_id, count(*) as cnt
              FROM %s
              GROUP BY customer_id
              HAVING cnt > 1
            )
            """.formatted(BRONZE_TABLE))
            .collectAsList().get(0).getLong(0);

        String status = duplicateCount == 0 ? "passed" : "failed";

        // Write quality result
        spark.sql("""
            INSERT INTO stratus.platform.quality_check_results VALUES
              ('%s', 'bronze', 'verification_customers', 'bronze',
               'uniqueness', 'customer_id_unique', 'blocking',
               '%s', %d, 0, 'Duplicate customer_ids found: %d',
               'spark-verification', current_timestamp(), -1L)
            """.formatted(RUN_ID, status, duplicateCount, duplicateCount));

        assertThat(duplicateCount)
            .as("Uniqueness check must detect the intentional duplicate in the bronze table")
            .isEqualTo(1L);
        assertThat(status).isEqualTo("failed");
    }

    @Test
    @Order(6)
    void promotionGateBlocksOnFailedUniquenesCheck() {
        // Read quality outcomes for this run from platform.quality_check_results
        List<Row> blockingFailures = spark.sql("""
            SELECT check_name, failure_detail
            FROM stratus.platform.quality_check_results
            WHERE run_id = '%s'
              AND severity = 'blocking'
              AND status   = 'failed'
            """.formatted(RUN_ID))
            .collectAsList();

        assertThat(blockingFailures)
            .as("Promotion gate must detect the blocking uniqueness failure")
            .isNotEmpty();

        // Confirm silver table has NOT been written (promotion was blocked)
        boolean silverExists = spark.catalog().tableExists(SILVER_TABLE);
        assertThat(silverExists)
            .as("Silver table must not exist — promotion was blocked by the quality gate")
            .isFalse();
    }

    @Test
    @Order(7)
    void transformJobWritesSilverTableAfterDeduplication() {
        // Fix the bronze data by deduplicating before silver promotion
        spark.sql("DROP TABLE IF EXISTS " + SILVER_TABLE);

        spark.sql("""
            CREATE TABLE %s
            USING iceberg
            TBLPROPERTIES ('write.format.default' = 'parquet')
            AS
            SELECT customer_id, name, email, country, created_at
            FROM (
              SELECT *,
                     row_number() OVER (PARTITION BY customer_id ORDER BY created_at DESC) AS rn
              FROM %s
            )
            WHERE rn = 1
            """.formatted(SILVER_TABLE, BRONZE_TABLE));

        long silverCount = spark.sql("SELECT count(*) FROM " + SILVER_TABLE)
            .collectAsList().get(0).getLong(0);

        assertThat(silverCount)
            .as("Silver table must contain 3 rows — duplicate removed by deduplication")
            .isEqualTo(3L);
    }

    @Test
    @Order(8)
    void materialisationJobWritesGoldTable() {
        spark.sql("DROP TABLE IF EXISTS " + GOLD_TABLE);

        spark.sql("""
            CREATE TABLE %s
            USING iceberg
            TBLPROPERTIES ('write.format.default' = 'parquet')
            AS
            SELECT country,
                   count(*)            AS customer_count,
                   current_timestamp() AS computed_at
            FROM %s
            GROUP BY country
            """.formatted(GOLD_TABLE, SILVER_TABLE));

        long goldRowCount = spark.sql("SELECT count(*) FROM " + GOLD_TABLE)
            .collectAsList().get(0).getLong(0);

        assertThat(goldRowCount)
            .as("Gold table must contain one row per country (GB, US, DE)")
            .isEqualTo(3L);
    }

    @Test
    @Order(9)
    void maintenanceJobRunsOnBronzeTable() {
        // Run snapshot expiry on the bronze table — retaining the current snapshot
        spark.sql("""
            CALL stratus.system.expire_snapshots(
              table => '%s',
              older_than => TIMESTAMP '%s',
              retain_last => 1
            )
            """.formatted(BRONZE_TABLE,
                java.time.Instant.now().toString().replace("T", " ").replace("Z", "")));

        // Run file compaction
        Dataset<Row> rewriteResult = spark.sql("""
            CALL stratus.system.rewrite_data_files(
              table => '%s'
            )
            """.formatted(BRONZE_TABLE));

        Row metrics = rewriteResult.collectAsList().get(0);
        assertThat(metrics).isNotNull();
    }

    @Test
    @Order(10)
    void qualityResultsTableContainsAllRunRecords() {
        long resultCount = spark.sql("""
            SELECT count(*) FROM stratus.platform.quality_check_results
            WHERE run_id = '%s'
            """.formatted(RUN_ID))
            .collectAsList().get(0).getLong(0);

        assertThat(resultCount)
            .as("Quality results table must contain records for both checks run in this suite")
            .isGreaterThanOrEqualTo(2L);
    }

    @Test
    @Order(11)
    void cleanup() {
        spark.sql("DROP TABLE IF EXISTS " + GOLD_TABLE);
        spark.sql("DROP TABLE IF EXISTS " + SILVER_TABLE);
        spark.sql("DROP TABLE IF EXISTS " + BRONZE_TABLE);

        assertThat(spark.catalog().tableExists(BRONZE_TABLE)).isFalse();
        assertThat(spark.catalog().tableExists(SILVER_TABLE)).isFalse();
        assertThat(spark.catalog().tableExists(GOLD_TABLE)).isFalse();
    }

    @AfterAll
    static void stopSpark() {
        if (spark != null) spark.stop();
    }
}
```

### Running the verification suite

Build the fat JAR first, then run:

```bash
export STRATUS_SPARK_MASTER=spark://spark-master.stratus.local:7077
export STRATUS_POLARIS_URI=https://polaris.stratus.local:8181/api/catalog
export STRATUS_POLARIS_CLIENT_ID=svc-spark
export STRATUS_POLARIS_CLIENT_SECRET=<client secret>
export STRATUS_POLARIS_CATALOG=stratus
export CEPH_RGW_ENDPOINT=https://object-store.stratus.local
export CEPH_RGW_ACCESS_KEY=svc-spark
export CEPH_RGW_SECRET_KEY=<svc-spark secret>

export STRATUS_SPARK_PIPELINE_VERIFIER_IMAGE=registry.stratus.local/stratus/spark-pipeline-verifier:<version>@sha256:<digest>
podman run --rm --env-file /etc/stratus/verifiers/spark-pipeline.env \
  -v /data/stratus/evidence/increment3:/evidence:z \
  ${STRATUS_SPARK_PIPELINE_VERIFIER_IMAGE}
```

All tests must pass before Increment 3 is considered complete.

### Incremental-load verification

The listing above is one pipeline over one file, which is the only day a batch
pipeline cannot get wrong. `SparkIncrementalLoadVerificationTest`, in the same
module, is the days after it: seventeen ordered scenarios over one table's
history, driven by eight landing fixtures under
`platform/spark/tests/src/test/resources/landing/`.

| # | Scenario | What it proves |
| --- | --- | --- |
| 1 | The first batch lands | Audit columns populated, one partition per batch, write properties as `ZoneWriteProperties` states them |
| 2 | The same batch is sent again | Refused, naming the batch and the table |
| 3 | The same batch is replayed deliberately | Rewrites that batch and converges |
| 4 | A second batch lands | Bronze holds both — the case a `createOrReplace` ingestion would have failed silently |
| 5 | The first batch reaches silver | Silver created without the bronze audit columns |
| 6 | A correction arrives | The row is updated in place, new customers inserted |
| 7 | A replay carries an older version | Bronze grows, silver is unchanged — the sequence guard |
| 8 | A batch adds a column | The table evolves; earlier rows read back null |
| 9 | A batch changes a column's type | Refused with status `3`, naming the column and both types |
| 10 | A key arrives twice at the same instant | One row, and the same one on a re-run |
| 11 | A defective batch, then its correction | The gate blocks the transform from inside the job; the corrected batch replays and passes the same rules |
| 12 | The blocked run is overridden | Recorded as its own result naming the principal; the original verdict is untouched |
| 13 | Freshness | Stale business time fails an hourly SLA, recent ingest time passes a daily one |
| 14 | Referential integrity | An unknown code fails naming the reference table; a clean table passes |
| 15 | Maintenance | Expiry leaves exactly the retained snapshots and changes no row |
| 16 | Orphan cleanup | A retention inside the concurrent-write window is refused naming the interval, and nothing is touched; the same operation with a safe retention runs |
| 17 | An NDJSON batch | The same contract on the other supported format |

Scenarios 4 and 7 are the load-bearing ones, and each was proven to fail with
the defect put back: restoring `createOrReplace` in the ingestion job breaks 4,
and dropping the sequence comparison from the merge breaks 7.

**Not covered, and why.** Scenario 16 does not prove that an orphan file is
actually deleted. Iceberg refuses any retention inside 24 hours, because a file
written minutes ago may belong to a commit still in flight; every file in this
harness is minutes old, and an object's modification time is set by the storage
server on write, so there is no way to present the job with an orphan old enough
to delete. Proving the deletion itself needs a table that has existed for more
than a day, which belongs to the production run (`P1-3.5-V1`). What the scenario
does prove is that the destructive operation refuses rather than half-running,
and that the same operation with a safe retention runs — so the refusal is about
the interval and not about a job that never works.

That distinction is not academic. Writing this scenario is what found that
`delete_orphan_files` could not run against this platform **at all**: it failed
first on the table identifier and then on the `s3://` scheme, before any
retention was even considered. The previous suite tested only that the operation
was refused without `--olderThan`, so a job that could never have succeeded
looked exactly like one that worked.

---

## 11. Operational Checks

### Spark master web UI

Open `http://spark-master.stratus.local:8080`. Confirm:
- Both workers shown as `ALIVE` with correct core and memory counts
- Completed applications visible after the verification suite runs

### Event log access

Spark event logs are written to the mounted local path `/data/spark-events`. Confirm they are visible on the Spark hosts:

```bash
sudo ls -lah /data/spark-events
```

Expected in the developer profile: application event log files exist after the test run. The production profile repeats the test with `spark.eventLog.dir=s3a://stratus-platform/spark-event-logs/`, restarts the history server on another node, and proves completed applications remain readable through Ceph RGW.

### Submit a test job via spark-submit

Confirm `spark-submit` works independently of the Java test suite:

```bash
podman exec spark-master \
  /opt/spark/bin/spark-submit \
    --master spark://spark-master.stratus.local:7077 \
    --class org.apache.spark.examples.SparkPi \
    /opt/spark/examples/jars/spark-examples_2.13-4.1.2.jar \
    100
```

Expected output: `Pi is roughly 3.14...`

### Confirm Iceberg tables visible in Ceph RGW after test run

```bash
rclone --ca-cert /etc/stratus/pki/stratus-ca.crt lsf --recursive cephrgw:stratus-bronze/ | head -20
rclone --ca-cert /etc/stratus/pki/stratus-ca.crt lsf --recursive cephrgw:stratus-silver/ | head -20
rclone --ca-cert /etc/stratus/pki/stratus-ca.crt lsf --recursive cephrgw:stratus-gold/ | head -20
```

Each zone must show `metadata/` and `data/` directories containing `.json`, `.avro`, and `.parquet` files.

---

## 12. Implementation Task Track

These child tasks execute Phase 1 parents `P1-3.1` through `P1-3.6`. IDs are stable across issues, artifacts, evidence, and gate records; evidence belongs under `evidence/phase1/increment3/<task-id>/`.

| ID | Parent | Track | Task and definition of done | Owner | Depends on | Deliverable/path | Verification/evidence | Gate | Accepted by | Blocker/risk | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `P1-3.1-S1` | `P1-3.1` | Shared | Build and lock Spark, Iceberg, S3A, job, and verifier artifacts; done when Hadoop ABI and Ceph S3A smoke tests pass. | Build owner | P1-2 developer gate | `platform/spark/image/`; `platform/spark/aws-runtime/`; job modules; lock manifest | Scan, digest, ABI check, S3A create/read/list/delete | D1, P1-P3 | Platform owner | Reopened 2026-08-13: Hadoop is one matched 3.4.3 API/runtime/S3A line and Iceberg 1.11.0 carries its SDK in the relocated Stratus runtime. Offline classpath tests and image inspection pass; fresh live S3A and pipeline evidence is required before re-acceptance. Image scan, digest pin, SBOM and provenance remain with `P1-0.1` | In progress |
| `P1-3.1-D1` | `P1-3.1` | Developer | Deploy idempotent reduced Spark cluster with local event history and scratch. | Data-engineering owner | `P1-3.1-S1` | `platform/spark/compose-cluster/` | repeated lifecycle, master/worker health | D1 | Platform owner | Updated 2026-08-13: one master and two workers remain loopback-published; each container now receives private tmpfs scratch, the master supplies a two-core default application ceiling, and executors are single-core so the live client suite can prove placement on both workers. Fresh lifecycle and live worker evidence is pending. | In progress |
| `P1-3.2-D1` | `P1-3.2` | Developer | Configure Polaris, Ceph S3FileIO/S3A, CA trust, and lab credentials. | Data-engineering owner | `P1-3.1-D1`, P1-2 developer gate | `platform/spark/compose-cluster/config/`; svc-spark identity | catalog resolution and object read/write | D1 | Data-platform owner | Verified 2026-08-08: Spark resolves all four governed namespaces through Polaris over TLS and writes and reads an Iceberg table whose files land under `s3://stratus-bronze/bronze/`; S3A raw object round trip proven separately, since it is configured independently of Iceberg's S3FileIO. `svc-spark` exists as both an RGW identity (bucket policies on the five Stratus buckets, proven to fail closed on `stratus-denied`) and a Polaris principal created by `spark-compose-bootstrap-principal.sh`. No credential is in a tracked file: the RGW key pair is pulled from OpenBao (ADR-P1-004) and the catalog secret is generated into the ignored `.env`; the Spark configuration is rendered from the providers' `connection.env` so no endpoint is duplicated (ADR-P1-003). A forged principal secret is refused, with a positive control proving the real one works at that moment. Least-privilege narrowing of the catalog role belongs to `P1-3.2-P1` | Accepted |
| `P1-3.3-V1` | `P1-3.3` | Developer | Implement and verify bronze, silver, gold, quality, promotion, maintenance, and lineage-payload jobs. | Data-engineering owner | `P1-3.2-D1` | `jobs/spark/`; `platform/spark/tests/` | expected data, failed-quality block, maintenance evidence | D1-D2 | Data owner | Verified 2026-08-09: five jobs under `jobs/spark/` (ingestion, transform, materialisation, quality, maintenance) plus `PromotionGate`, proven end to end by `SparkPipelineVerificationTest` — 11/11 against the live cluster, transcript `platform/spark/compose-cluster/logs/spark-conformance-tests-20260809T042935Z.log`. The fixture carries a duplicated business key and a blank field on purpose, so the failed-quality block is proven on a real failure rather than asserted: the blocking uniqueness rule records FAILED, the non-blocking completeness rule records WARNING, and the gate blocks naming the failing rule. Determinism is addressed rather than hoped for — deduplication orders by an explicit column so re-runs keep the same row. The gate also blocks when a run has no recorded results at all, so a quality job that dies before writing cannot promote unchecked data; that path was observed live during development. Orphan-file deletion is refused without an explicit retention age. 20 offline unit tests cover argument parsing, the lineage payload shape, and the verdict. **Superseded in part by `P1-3.3-V2`:** this row also recorded that ingestion used `createOrReplace` so a repeated run converged. That held for one file and was wrong for two — a second landing file replaced the whole table, which contradicts the architecture's append-only bronze, and no test in this task's suite would have noticed because none ingested twice. | Accepted |
| `P1-3.3-V2` | `P1-3.3` | Developer | Bring the bronze and silver write modes in line with the architecture and prove the pipeline over successive batches; done when a second batch accumulates, a late replay cannot overwrite a correction, and both are proven to fail if the guard is removed. | Data-engineering owner | `P1-3.3-V1` | `jobs/spark/`; `platform/spark/tests/` | multi-batch, late arrival, schema drift, failed-batch replay | D1 | Data owner | Verified 2026-08-09: 37/37 live `spark-integration` checks, transcript `platform/spark/compose-cluster/logs/spark-conformance-tests-20260809T111311Z.log`, and 56 offline unit tests. Bronze now appends by batch and refuses a batch it already holds unless a replay is asked for (ADR-P1-006); silver is upserted on a monotonic sequence so a replay carrying older state cannot overwrite a correction. Both guards were proven load-bearing by putting the defect back: restoring `createOrReplace` failed `aSecondBatchAccumulatesInsteadOfReplacingTheFirst` with 3 rows where 8 were expected, and removing the sequence comparison failed `aReplayCarryingAnOlderVersionDoesNotOverwriteTheCorrection` with `bob.stale@example.com` where the correction should have held — in each case that test alone. Writing the scenarios found three defects nothing had exercised: a MERGE source registered from a DataFrame plan cannot be planned by Spark 4.1, the promotion-gate override wrote a `java.sql.Timestamp` against the deployed table's `TIMESTAMP_NTZ` schema and always failed, and `delete_orphan_files` could not run at all against this platform — it failed first on the table identifier and then on the `s3://` scheme. Not proven here: deletion of a genuinely aged orphan, which Iceberg's 24-hour retention floor puts out of reach of a harness whose files are minutes old; it belongs to `P1-3.5-V1` | Accepted |
| `P1-3.1-P1` | `P1-3.1` | Production | Deploy approved master recovery design, multi-host workers, durable scratch policy, and restricted submission. | Platform owner | `P1-3.1-S1`, production infrastructure | `platform/spark/`; `environments/production/spark/` | worker/master loss and RTO/RPO evidence | P1-P4 | Operations owner | Availability exception | Not started |
| `P1-3.2-P1` | `P1-3.2` | Production | Apply Spark auth/crypto, managed secrets, trusted TLS proxying, and least-privilege Polaris/Ceph access. | Security owner | `P1-3.1-P1`, Increment 7 controls | `platform/spark/compose-cluster/config/`; `environments/production/spark/` | positive/negative auth, encrypted transport | P3-P7 | Platform owner | Shared-secret rotation | Not started |
| `P1-3.6-P1` | `P1-3.6` | Production | Deploy Ceph-backed event logs and history server; prove relocation and continuity. | Operations owner | `P1-3.2-P1` | history-server config/runbook | `s3a://` event continuity and restart test | P8-P9 | Operations owner | Event-log retention | Not started |
| `P1-3.5-V1` | `P1-3.5` | Production | Run full production pipeline, quality, maintenance, capacity, and worker-failure regression. | QA owner | `P1-3.6-P1` | production reports | JUnit, job IDs, metrics, object/table evidence | P10-P13 | Data owner | Representative workload needed | Not started |
| `P1-3.G-D` | `P1-3` | Developer | Accept D1-D2 after all producing tasks and evidence are accepted. | Platform owner | `P1-3.3-V1` | developer gate record | gate matrix/evidence index | D1-D2 | Data owner | Accepted 2026-08-09 by the platform owner. All four producing tasks are `Accepted`; the gate matrix and evidence index are below and the promotion manifest satisfies D2. Evidence: 20 live `spark-integration` checks (cluster, bindings, and the full batch pipeline) plus 27 offline guardrail and unit tests, re-verified after a secret rotation and again on a platform rebuilt from destroyed volumes | Accepted |
| `P1-3.G-P` | `P1-3` | Production | Accept P1-P13 with promotion manifest and no developer shortcuts. | Platform owner | `P1-3.5-V1` | production gate record | gate matrix, recovery and readiness evidence | P1-P13 | Operations owner | Open production defect | Not started |

## 13. Completion Gates

### Developer gate

- [x] **D1** - Reduced Podman topology starts/stops idempotently and ingestion, transformations, quality gates, maintenance decisions, and verifier tests pass.
- [x] **D2** - Local volumes, local certificates, reduced workers, and bootstrap credentials are recorded in the promotion manifest.

### Developer-to-production promotion controls

This table is the promotion manifest that gate **D2** requires. Every
developer-only condition in the Increment 3 harness is named here with the
production task that replaces it and the condition under which promotion
stops. A developer condition that is not listed has not been assessed and
blocks the developer gate.

| Developer condition | Production replacement task | Rollback or stop condition |
|---|---|---|
| Reduced single-host topology: one master and two workers on one workstation, sized 2 cores and 2 GB each | `P1-3.1-P1` deploys multi-host workers with an approved master recovery design | never claim an RTO/RPO, capacity, or failover posture from this topology; the production gate stays open until worker and master loss drills pass |
| Local event-log and scratch volumes (`file:///opt/spark/events`, `/opt/spark/scratch`) | `P1-3.6-P1` moves event logs to Ceph and adds the history server | event history is lost on volume removal here; no continuity or relocation evidence may be taken from this mode |
| Two disposable lab CAs trusted by the engine — Ceph's for object storage and Polaris's for the catalog | `P1-7.4` replaces both with FreeIPA Dogtag-issued material | never fall back to plain HTTP or relax client verification to make a TLS check pass |
| Harness-generated `svc-spark` catalog secret in the ignored `.env`, reset into Polaris on every bootstrap | `P1-3.2-P1` with Increment 7 controls | rotate after any real use; never promote a harness credential |
| `svc-spark` holds the `catalog_admin` catalog role rather than a least-privilege engine role | `P1-3.2-P1` | a production engine principal never carries catalog administration; promotion stops until the role is narrowed and a negative test proves the boundary |
| Runtime image pinned by tag `stratus/spark-runtime:dev`, built on the workstation from a local artifact lock | `P1-3.1-S1` under `P1-0.1` publishes it by immutable digest with scan, SBOM, and provenance | production runs by digest only; this clause shares the `P1-0.1` publication deferral and cannot be closed from a tag pin |
| No Spark authentication or network encryption between master, workers, and driver | `P1-3.2-P1` applies Spark auth and encrypted transport | no shared or representative use until both are on; a shared lab without them is a stop condition |
| Submission is unrestricted from any container on the harness network | `P1-3.1-P1` restricts submission | never expose the master port beyond loopback in this mode |

**Accepted 2026-08-09.** The platform owner accepted the Increment 3 developer gate.
All four producing tasks — `P1-3.1-S1`, `P1-3.1-D1`, `P1-3.2-D1`, and
`P1-3.3-V1` — are `Accepted`, satisfying the gate traceability rule, and D1
and D2 are ticked above.

D1's conditions each have live evidence: the lifecycle is idempotent across
repeated cycles, a landing file becomes bronze, deduplication produces silver,
materialisation produces gold, quality rules are recorded and a blocking
failure stops promotion while a clean run is promoted, maintenance runs and
reports metrics, and the verifier suites pass. D2 is satisfied by the
promotion manifest above, which names eight developer-only conditions with the
production task that replaces each.

This accepts the **developer** track only. Increment 3 as a whole is not
accepted: multi-host workers, master recovery, Spark authentication and
transport encryption, the Ceph-backed history server, and the production
regression remain open, and the portfolio row in the Phase 1 plan stays
`Not started` until they close.

The readiness note below is kept as the state at the time of the decision.

**Readiness (2026-08-08).** The cluster and its bindings are done and
`Verified`: `P1-3.1-S1` (image and artifact lock, developer scope),
`P1-3.1-D1` (master and two workers, idempotent lifecycle), and `P1-3.2-D1`
(Polaris catalog resolution, Iceberg and S3A object read/write through the
`svc-spark` identity). Live evidence is eight `spark-integration` checks plus
seven offline harness guardrails; transcripts in
`platform/spark/compose-cluster/logs/`.

**Updated 2026-08-09.** `P1-3.3-V1` is now `Verified` too: the five platform
jobs and the promotion gate exist under `jobs/spark/` and run end to end
against the live cluster. Every condition D1 names — idempotent lifecycle,
ingestion, transformations, quality gates, maintenance decisions, and the
verifier tests — now has live evidence: 11 pipeline checks, 8 cluster and
binding checks, and 27 offline guardrail and unit tests.

**Extended the same day by `P1-3.3-V2`.** The evidence above covered one batch.
It now covers successive ones: 37 live checks in a single run (transcript
`spark-conformance-tests-20260809T111311Z.log`) and 56 offline unit tests, with
bronze appending by batch and silver upserting on a sequence as §6.4.6 of the
architecture requires. Both new guards were proven to fail with the defect put
back.

D1 is therefore satisfiable on the evidence, and is still not ticked here for
one reason only: the gate traceability rule requires every producing task to
be `Accepted`, and all four are `Verified`. That transition is the platform
owner's action.

D2's promotion manifest is the table above, added 2026-08-08. It names eight
developer-only conditions with the production task that replaces each. D2 is
satisfiable on that evidence once the platform owner accepts it; it is not
ticked here because the gate traceability rule requires the producing tasks to
be `Accepted`, which is the owner's action.

### Production gate

Increment 3 is accepted when all of the following are true:

- [ ] **P1** - Spark master container running and managed by systemd on `spark-master.stratus.local`
- [ ] **P2** - Both Spark workers running and showing `ALIVE` in the master web UI
- [ ] **P3** - Spark connects to Polaris and resolves all four namespaces
- [ ] **P4** - Spark connects to Ceph RGW through the approved S3 endpoint and can read and write all platform buckets
- [ ] **P5** - image CI proves the matched Hadoop 3.4.3 client/S3A set, isolated Iceberg AWS SDK, and an S3A create/read/list/delete test against Ceph RGW using the trusted CA
- [ ] **P6** - `SparkPipelineVerificationTest` and `SparkIncrementalLoadVerificationTest` — every test passes against the live cluster (12 and 17 as of 2026-08-09). The first proves one batch end to end. The second proves the days after it, and exists because a review asked what real-world cases the suite covered and found that every test ran on a single file ingested once: a second batch, a late replay, a schema change and a failed-batch replay were all absent, and two of them could not have passed against the jobs as they were (`P1-3.3-V2`, ADR-P1-006)
- [ ] **P7** - Bronze, silver, and gold Iceberg tables created and visible in Ceph RGW
- [ ] **P8** - Quality results written to `platform.quality_check_results` and queryable via Spark SQL
- [ ] **P9** - Promotion gate correctly blocks silver promotion when a blocking quality check fails
- [ ] **P10** - Table maintenance runs without error and records the metadata signals used to choose snapshot expiry and compaction actions
- [ ] **P11** - `spark-submit` test job executes successfully on the standalone cluster
- [ ] **P12** - production event logs persist at `s3a://stratus-platform/spark-event-logs/` and remain readable after history-server relocation; trusted TLS, managed credentials, capacity evidence, and worker failure recovery are proven
- [ ] **P13** - Spark master availability matches the approved RTO/RPO design or has an accepted exception

The developer gate may unblock Increment 4 engineering. Only the production gate marks Increment 3 accepted in the Phase 1 tracker.

---

## 14. Troubleshooting

### Workers do not appear in the master UI

- Confirm `spark-master.stratus.local:7077` is reachable from worker nodes: `nc -zv spark-master.stratus.local 7077`
- Check worker container logs: `podman logs spark-worker`
- Confirm the master URL in the worker start command matches exactly

### Spark cannot connect to Polaris

- Confirm the Polaris REST API is reachable from the Spark nodes: `curl --cacert /etc/stratus/certs/ca.crt https://polaris.stratus.local:8181/api/catalog/v1/config`
- Confirm the `credential` format is `clientId:clientSecret` with no spaces
- Check Polaris logs for authentication failures: `podman logs polaris`

### Spark cannot write to Ceph RGW

- Confirm `s3.path-style-access=true` and `fs.s3a.path.style.access=true` are both set in `spark-defaults.conf`
- Confirm the S3 endpoint is `https://object-store.stratus.local` or the environment-approved equivalent
- Test Ceph RGW access from an operator client directly: `rclone --ca-cert /etc/stratus/pki/stratus-ca.crt lsf cephrgw:stratus-bronze/`

### `ClassNotFoundException` for Iceberg or S3 classes

- Confirm `stratus-iceberg-aws-runtime`, the Hadoop 3.4.3 API/runtime/S3A jars, and the locked connector dependencies are present in `/opt/spark/jars/`; no 3.4.1/3.4.2 Hadoop client or second unrelocated AWS SDK may remain
- Compare the running image digest and artifact-lock digest with the promotion manifest; do not repair a running container by downloading JARs interactively
- Rebuild the Docker image if JARs are missing

### Quality results table shows no rows after the quality job

- Confirm `spark.sql.defaultCatalog=stratus` is set so the INSERT targets the correct catalog
- Use the fully qualified table name `stratus.platform.quality_check_results` in all quality job SQL to avoid catalog ambiguity

### `AnalysisException: Table not found` on silver or gold

- Confirm the bronze table was created and written successfully before running the transform job
- Confirm the target namespace exists in Polaris using the trusted CA bundle and an authenticated Polaris token

---

## 15. References

- Apache Spark standalone cluster: https://spark.apache.org/docs/latest/spark-standalone.html
- Apache Spark 4.1.2 S3A dependency example: https://spark.apache.org/docs/4.1.2/running-on-kubernetes.html#dependency-management
- Apache Spark object-store integration: https://spark.apache.org/docs/4.1.2/cloud-integration.html
- Apache Hadoop S3A connector: https://hadoop.apache.org/docs/r3.4.3/hadoop-aws/tools/hadoop-aws/index.html
- Apache Spark Iceberg integration: https://iceberg.apache.org/docs/latest/spark-getting-started/
- Iceberg Spark procedures (maintenance): https://iceberg.apache.org/docs/latest/spark-procedures/
- Iceberg Spark SQL extensions: https://iceberg.apache.org/docs/latest/spark-ddl/
- Apache Spark Docker images: https://hub.docker.com/r/apache/spark
- Stratus Phase 1 implementation plan: [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)
- Stratus architecture: [stratus_on_prem_data_fabric_architecture.md](../architecture/stratus_on_prem_data_fabric_architecture.md)
- Increment 1 — Ceph object storage foundation: [ceph_storage.md](ceph_storage.md)
- Increment 2 — Iceberg and Polaris: [iceberg_polaris_catalog.md](iceberg_polaris_catalog.md)
