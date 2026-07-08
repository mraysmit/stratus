# Stratus Increment 3 — Apache Spark Batch Compute

## 1. Purpose

This document is the technical implementation plan for Increment 3 of the Stratus platform as defined in [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md).

Increment 3 delivers an Apache Spark standalone cluster running on Podman containers. Spark is configured to use Apache Polaris as its Iceberg catalog and Ceph RGW as object storage. When this increment is complete, data flows from a raw source file in the landing zone through bronze, silver, and gold Iceberg tables. Quality checks run against each dataset and gate promotion between zones. A Java verification suite submits real Spark jobs and confirms the full batch compute pipeline works end to end.

**Prerequisites:**
- Increment 1 complete — Ceph RGW cluster running, all buckets and service accounts in place
- Increment 2 complete — Polaris running, all namespaces and the `platform.quality_check_results` table created, all Increment 2 gate tests passing

---

## 2. Assumptions and Prerequisites

- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- Podman 4.x installed on each Spark node
- JDK 21+ and Maven 3.9+ on the development and verification host
- DNS resolution: `spark-master.stratus.local`, `spark-worker1.stratus.local`, `spark-worker2.stratus.local` resolve correctly
- Nodes can reach Ceph RGW on port 9000 and Polaris on port 8181
- `svc-spark` S3 credentials and Polaris principal credentials from earlier increments are available

### Reference documentation audit

Reference baseline: 2026-07-05.

The current Apache Spark release line includes Spark 4.1.2 as the latest stable 4.1 maintenance release, while Spark 4.2.0 is still a preview release. This increment therefore targets Spark 4.1.2 with Scala 2.13 and Iceberg 1.11.0's Spark 4.1 runtime artifact. Do not fall back to the older Spark 3.5 / Scala 2.12 examples unless the platform records an explicit compatibility exception.

Before implementation, confirm the exact container image tags and Maven artifacts still match the latest stable upstream releases. Do not mix a newer Spark major version with older Iceberg runtime artifacts without verifying the upstream compatibility notes and running the Increment 2 and 3 verification suites.

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

The official Apache Spark Docker image is used as the base. A custom image adds the Iceberg runtime JAR, the AWS S3 connector, and the Polaris REST catalog client.

### Dockerfile

Create `docker/spark/Dockerfile` in the Stratus repository:

```dockerfile
FROM apache/spark:4.1.2-scala2.13-java17-python3-ubuntu

USER root

# Iceberg Spark runtime — includes Iceberg core, Spark integration, and REST catalog client
ADD https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-spark-runtime-4.1_2.13/1.11.0/iceberg-spark-runtime-4.1_2.13-1.11.0.jar \
    /opt/spark/jars/

# AWS bundle — provides S3FileIO for Ceph RGW connectivity
ADD https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-aws-bundle/1.11.0/iceberg-aws-bundle-1.11.0.jar \
    /opt/spark/jars/

# Hadoop AWS is required only if Spark internals use s3a:// paths outside Iceberg S3FileIO.
# If enabled, pin hadoop-aws and its AWS SDK dependencies to the Hadoop version bundled in
# the selected Spark image; do not copy Hadoop 3.3.x jars into a Spark 4.1 image.

USER spark
```

### Build and tag the image

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

# S3A filesystem (used by Spark internals for staging)
spark.hadoop.fs.s3a.endpoint                    https://object-store.stratus.local
spark.hadoop.fs.s3a.access.key                  svc-spark
spark.hadoop.fs.s3a.secret.key                  <svc-spark secret>
spark.hadoop.fs.s3a.path.style.access           true
spark.hadoop.fs.s3a.connection.ssl.enabled      true

# Event log — write to a mounted local path for job history.
# If this is changed to s3a://, add hadoop-aws dependencies that match the Spark-bundled Hadoop version.
spark.eventLog.enabled                          true
spark.eventLog.dir                              file:///data/spark-events

# Serialization
spark.serializer                                org.apache.spark.serializer.KryoSerializer
```

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

Five Spark jobs are implemented for Increment 3. Each is a self-contained class in the Stratus Java module under `src/main/java/dev/mars/stratus/jobs/`.

### Job 1 — Ingestion: landing → bronze

Reads a CSV or JSON source file from `stratus-landing`, applies minimal normalisation (string trimming, null handling), and writes an Iceberg table in the `bronze` namespace via Polaris.

**Inputs:**
- `sourceFile` — S3A path to the source file in `stratus-landing` (e.g. `s3a://stratus-landing/customers/2024-01-15/customers.csv`)
- `targetTable` — fully qualified Iceberg table name (e.g. `stratus.bronze.customers`)
- `sourceSystem` — name of the source system (written as table property for Atlas lineage)

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

Reads a bronze Iceberg table, applies deduplication on the business key, type normalisation, and reference data enrichment where applicable. Writes a silver Iceberg table.

**Inputs:**
- `sourceTable` — fully qualified bronze table name
- `targetTable` — fully qualified silver table name
- `businessKey` — comma-separated list of columns forming the deduplication key

**Outputs:**
- Silver Iceberg table written in `stratus-silver`
- Lineage event: bronze table → silver table

### Job 3 — Materialisation: silver → gold

Reads one or more silver Iceberg tables, applies aggregations or joins, and writes a gold Iceberg table. This job is domain-specific in its logic but uses the same job contract as transform.

**Inputs:**
- `sourceTables` — comma-separated list of fully qualified silver table names
- `targetTable` — fully qualified gold table name

**Outputs:**
- Gold Iceberg table written in `stratus-gold`
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

**Inputs:**
- `targetTable` — fully qualified Iceberg table name
- `checks` — JSON array of check definitions (type, parameters, severity)
- `runId` — unique identifier for this quality run

**Outputs:**
- One result record per check written to `platform.quality_check_results`
- Summary logged: total checks, passed, failed, warnings

### Job 5 — Table maintenance

Runs Iceberg maintenance operations on a target table using Spark actions.

**Inputs:**
- `targetTable` — fully qualified Iceberg table name
- `operations` — comma-separated list: `expire_snapshots`, `rewrite_data_files`, `delete_orphan_files`

**Outputs:**
- Metrics logged: snapshots expired, files rewritten, bytes reclaimed, orphan files removed

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
| `STRATUS_S3_ENDPOINT` | Ceph RGW S3 endpoint |
| `STRATUS_S3_ACCESS_KEY` | `svc-spark` access key |
| `STRATUS_S3_SECRET_KEY` | `svc-spark` secret key |

### Shared Spark session helper

Place in `src/test/java/dev/mars/stratus/jobs/SparkTestSession.java`:

```java
package dev.mars.stratus.jobs;

import org.apache.spark.sql.SparkSession;

public class SparkTestSession {

    public static SparkSession create() {
        String master      = System.getenv("STRATUS_SPARK_MASTER");
        String polarisUri  = System.getenv("STRATUS_POLARIS_URI");
        String clientId    = System.getenv("STRATUS_POLARIS_CLIENT_ID");
        String clientSecret = System.getenv("STRATUS_POLARIS_CLIENT_SECRET");
        String catalog     = System.getenv("STRATUS_POLARIS_CATALOG");
        String s3Endpoint = System.getenv("STRATUS_S3_ENDPOINT");
        String accessKey   = System.getenv("STRATUS_S3_ACCESS_KEY");
        String secretKey   = System.getenv("STRATUS_S3_SECRET_KEY");

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

Place in `src/test/java/dev/mars/stratus/jobs/SparkPipelineVerificationTest.java`:

```java
package dev.mars.stratus.jobs;

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
export STRATUS_S3_ENDPOINT=https://object-store.stratus.local
export STRATUS_S3_ACCESS_KEY=svc-spark
export STRATUS_S3_SECRET_KEY=<svc-spark secret>

mvn test -pl . -Dtest=SparkPipelineVerificationTest
```

All eleven tests must pass before Increment 3 is considered complete.

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

Expected: application event log files exist after the test run. If the environment changes this path to `s3a://`, the Spark image must include `hadoop-aws` and AWS SDK dependencies that match the Hadoop version bundled in the selected Spark image.

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
aws --endpoint-url "$STRATUS_S3_ENDPOINT" s3 ls --recursive s3://stratus-bronze/ | head -20
aws --endpoint-url "$STRATUS_S3_ENDPOINT" s3 ls --recursive s3://stratus-silver/ | head -20
aws --endpoint-url "$STRATUS_S3_ENDPOINT" s3 ls --recursive s3://stratus-gold/   | head -20
```

Each zone must show `metadata/` and `data/` directories containing `.json`, `.avro`, and `.parquet` files.

---

## 12. Completion Gate

Increment 3 is complete when all of the following are true:

- [ ] Spark master container running and managed by systemd on `spark-master.stratus.local`
- [ ] Both Spark workers running and showing `ALIVE` in the master web UI
- [ ] Spark connects to Polaris and resolves all four namespaces
- [ ] Spark connects to Ceph RGW through the approved S3 endpoint and can read and write all platform buckets
- [ ] `SparkPipelineVerificationTest` — all eleven tests pass against the live cluster
- [ ] Bronze, silver, and gold Iceberg tables created and visible in Ceph RGW
- [ ] Quality results written to `platform.quality_check_results` and queryable via Spark SQL
- [ ] Promotion gate correctly blocks silver promotion when a blocking quality check fails
- [ ] Table maintenance runs without error and records the metadata signals used to choose snapshot expiry and compaction actions
- [ ] `spark-submit` test job executes successfully on the standalone cluster

When all gates are checked, Increment 4 (Apache Airflow) can begin.

---

## 13. Troubleshooting

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
- Test Ceph RGW access from a Spark node directly: `aws --endpoint-url "$STRATUS_S3_ENDPOINT" s3 ls s3://stratus-bronze/`

### `ClassNotFoundException` for Iceberg or S3 classes

- Confirm the Iceberg runtime JAR and AWS bundle JAR are present in `/opt/spark/jars/` inside the container: `podman exec spark-master ls /opt/spark/jars/ | grep iceberg`
- Rebuild the Docker image if JARs are missing

### Quality results table shows no rows after the quality job

- Confirm `spark.sql.defaultCatalog=stratus` is set so the INSERT targets the correct catalog
- Use the fully qualified table name `stratus.platform.quality_check_results` in all quality job SQL to avoid catalog ambiguity

### `AnalysisException: Table not found` on silver or gold

- Confirm the bronze table was created and written successfully before running the transform job
- Confirm the target namespace exists in Polaris using the trusted CA bundle and an authenticated Polaris token

---

## 14. References

- Apache Spark standalone cluster: https://spark.apache.org/docs/latest/spark-standalone.html
- Apache Spark Iceberg integration: https://iceberg.apache.org/docs/latest/spark-getting-started/
- Iceberg Spark procedures (maintenance): https://iceberg.apache.org/docs/latest/spark-procedures/
- Iceberg Spark SQL extensions: https://iceberg.apache.org/docs/latest/spark-ddl/
- Apache Spark Docker images: https://hub.docker.com/r/apache/spark
- Stratus Phase 1 implementation plan: [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)
- Stratus architecture: [on_prem_data_fabric_architecture.md](on_prem_data_fabric_architecture.md)
- Increment 1 — Ceph RGW: [increment1_ceph.md](increment1_ceph.md)
- Increment 2 — Iceberg and Polaris: [increment2_iceberg_polaris.md](increment2_iceberg_polaris.md)
