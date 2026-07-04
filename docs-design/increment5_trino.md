# Stratus Increment 5 — Trino Interactive Query

## 1. Purpose

This document is the technical implementation plan for Increment 5 of the Stratus platform as defined in [stratus_implementation_plan.md](stratus_implementation_plan.md).

Increment 5 delivers Trino as the shared interactive SQL query plane over Polaris-managed Apache Iceberg tables stored in MinIO. When this increment is complete, users and platform operators can discover bronze, silver, gold, and platform tables through Trino, query Spark-produced datasets without touching Spark or MinIO paths, inspect `platform.quality_check_results`, and verify SQL results against the outputs produced and orchestrated by Increments 3 and 4. A Java JDBC verification suite confirms Trino works as an independent query surface over the same table contracts.

**Prerequisites:**
- Increment 1 complete — MinIO cluster running, all buckets and service accounts in place
- Increment 2 complete — Polaris running, all namespaces and the `platform.quality_check_results` table created, all Increment 2 gate tests passing
- Increment 3 complete — Spark cluster running, bronze/silver/gold verification tables created by Spark, all Increment 3 gate tests passing
- Increment 4 complete — Airflow running, DAGs able to orchestrate Spark jobs and quality gates, all Increment 4 gate tests passing

---

## 2. Assumptions and Prerequisites

- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- Podman 4.x installed on each Trino node
- JDK 21+ and Maven 3.9+ on the development and verification host
- DNS resolution:
  - `trino-coordinator.stratus.local`
  - `trino-worker1.stratus.local`
  - `trino-worker2.stratus.local`
- Trino nodes can reach:
  - Polaris on port 8181
  - MinIO on port 9000
  - Airflow on port 8088 for operational cross-checks
- `svc-trino` MinIO credentials from Increment 1 are available
- `svc-trino` Polaris principal from Increment 2 exists and has read access to silver, gold, and platform namespaces
- Verification datasets from Increment 3 or Airflow DAG outputs from Increment 4 are available

---

## 3. Cluster Topology

Trino runs as a small distributed cluster with one coordinator and two workers. All three run as Podman containers.

```text
trino-coordinator.stratus.local
┌──────────────────────────────────────────────┐
│  Podman: trino-coordinator                   │
│  Trino coordinator / UI / JDBC :8080         │
│  Discovery service                           │
└──────────────────────────────────────────────┘
          │              │
          ▼              ▼
trino-worker1         trino-worker2
┌──────────────┐    ┌──────────────┐
│ trino-worker │    │ trino-worker │
│ query tasks  │    │ query tasks  │
└──────────────┘    └──────────────┘
          │              │
          └──────┬───────┘
                 │ resolves Iceberg metadata
                 ▼
        Polaris REST catalog
                 │ reads Iceberg data files
                 ▼
        MinIO object storage
```

Trino is the consumer-facing query plane. It is not the primary ETL engine. Spark remains responsible for heavy batch transformation, quality checks, and table maintenance unless a later design deliberately assigns a specific SQL workload to Trino.

---

## 4. Ports

| Port | Node | Purpose |
|---|---|---|
| 8080 | trino-coordinator | Trino web UI, REST API, JDBC endpoint |

The Trino workers connect to the coordinator on port 8080. The coordinator and workers must also reach MinIO and Polaris:

| Port | Service | Purpose |
|---|---|---|
| 8181 | Polaris | Iceberg REST catalog |
| 9000 | MinIO | Iceberg metadata and data file access |

For Increment 5, Trino may run with internal lab access only. OIDC client authentication, Kerberos internal authentication, and Ranger-backed policy enforcement are hardened in later increments.

---

## 5. Trino Image

Use the official Trino image. Pin the version rather than using `latest`.

This plan uses `trinodb/trino:482`, matching the current Trino documentation referenced in §15. If the platform standardizes on a different approved Trino version, use that version consistently across all coordinator and worker nodes and update the JDBC dependency in §10.

```bash
podman pull docker.io/trinodb/trino:482
```

For an air-gapped lab, save and distribute the image:

```bash
podman save docker.io/trinodb/trino:482 | gzip > trino-482.tar.gz
scp trino-482.tar.gz trino-worker1.stratus.local:~
scp trino-482.tar.gz trino-worker2.stratus.local:~

# On each worker
podman load < ~/trino-482.tar.gz
```

---

## 6. Trino Directory Layout

Create persistent configuration directories on each Trino node:

```bash
sudo mkdir -p /etc/stratus/trino/catalog
sudo mkdir -p /data/trino
sudo chown -R $USER:$USER /etc/stratus/trino /data/trino
```

The mounted directory layout is:

```text
/etc/stratus/trino/
├── config.properties
├── jvm.config
├── node.properties
├── log.properties
└── catalog/
    └── stratus.properties
```

---

## 7. Trino Configuration

### Common JVM configuration

Create `/etc/stratus/trino/jvm.config` on all Trino nodes:

```properties
-server
-Xmx8G
-XX:+UseG1GC
-XX:G1HeapRegionSize=32M
-XX:+ExplicitGCInvokesConcurrent
-XX:+HeapDumpOnOutOfMemoryError
-XX:+ExitOnOutOfMemoryError
```

Adjust heap size to match the host. For a small lab, 8 GB is adequate. Production sizing must be based on query concurrency, data size, and worker count.

### Common logging configuration

Create `/etc/stratus/trino/log.properties` on all Trino nodes:

```properties
io.trino=INFO
```

### Coordinator node properties

Create `/etc/stratus/trino/node.properties` on `trino-coordinator.stratus.local`:

```properties
node.environment=stratus-lab
node.id=trino-coordinator
node.data-dir=/data/trino
```

### Coordinator config

Create `/etc/stratus/trino/config.properties` on `trino-coordinator.stratus.local`:

```properties
coordinator=true
node-scheduler.include-coordinator=false
http-server.http.port=8080
discovery.uri=http://trino-coordinator.stratus.local:8080
discovery-server.enabled=true

query.max-memory=4GB
query.max-memory-per-node=2GB
query.max-total-memory-per-node=3GB
```

### Worker node properties

Create `/etc/stratus/trino/node.properties` on each worker with a unique `node.id`:

```properties
node.environment=stratus-lab
node.id=trino-worker1
node.data-dir=/data/trino
```

For `trino-worker2`, set:

```properties
node.id=trino-worker2
```

### Worker config

Create `/etc/stratus/trino/config.properties` on each worker:

```properties
coordinator=false
http-server.http.port=8080
discovery.uri=http://trino-coordinator.stratus.local:8080

query.max-memory-per-node=2GB
query.max-total-memory-per-node=3GB
```

---

## 8. Trino Iceberg Catalog Configuration

Create `/etc/stratus/trino/catalog/stratus.properties` on every Trino node.

This catalog points Trino at Apache Polaris using the Iceberg REST catalog interface and enables native S3-compatible access to MinIO. Trino must resolve tables through Polaris. It must not be configured as a path-based reader over raw MinIO directories.

```properties
# /etc/stratus/trino/catalog/stratus.properties

connector.name=iceberg

# Apache Polaris REST catalog
iceberg.catalog.type=rest
iceberg.rest-catalog.uri=https://polaris.stratus.local:8181/api/catalog
iceberg.rest-catalog.warehouse=stratus
iceberg.rest-catalog.security=OAUTH2
iceberg.rest-catalog.oauth2.credential=svc-trino:<svc-trino Polaris client secret>
iceberg.rest-catalog.oauth2.scope=PRINCIPAL_ROLE:ALL

# Increment 5 is a read/query increment.
iceberg.security=read_only

# MinIO object storage
fs.s3.enabled=true
s3.endpoint=https://minio1.stratus.local:9000
s3.region=us-east-1
s3.path-style-access=true
s3.aws-access-key=svc-trino
s3.aws-secret-key=<svc-trino MinIO secret>

# Query behavior
iceberg.file-format=PARQUET
iceberg.table-statistics-enabled=true
iceberg.metadata-cache.enabled=true
```

If Polaris uses a self-signed CA from Increment 1, the CA must be trusted by the JVM inside the Trino container. For a lab-only shortcut, a temporary truststore can be added to the image or mounted and referenced through JVM options. The target state is to replace lab certificates with FreeIPA Dogtag-issued certificates in Increment 7.

### Access scope

For Increment 5, `svc-trino` should be able to:

| Namespace | Access |
|---|---|
| `bronze` | read for verification only; normally restricted later |
| `silver` | read |
| `gold` | read |
| `platform` | read `quality_check_results` |

If the platform chooses to keep bronze hidden from analysts even during Increment 5, the verification suite may run with an internal platform principal while the user-facing Trino principal is limited to silver, gold, and platform.

---

## 9. Podman Container Setup

### Start the coordinator

Run on `trino-coordinator.stratus.local`:

```bash
podman run -d \
  --name trino-coordinator \
  --hostname trino-coordinator.stratus.local \
  --network host \
  -v /etc/stratus/trino:/etc/trino:ro,z \
  -v /data/trino:/data/trino:z \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  docker.io/trinodb/trino:482
```

### Start worker 1

Run on `trino-worker1.stratus.local`:

```bash
podman run -d \
  --name trino-worker \
  --hostname trino-worker1.stratus.local \
  --network host \
  -v /etc/stratus/trino:/etc/trino:ro,z \
  -v /data/trino:/data/trino:z \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  docker.io/trinodb/trino:482
```

### Start worker 2

Run on `trino-worker2.stratus.local`:

```bash
podman run -d \
  --name trino-worker \
  --hostname trino-worker2.stratus.local \
  --network host \
  -v /etc/stratus/trino:/etc/trino:ro,z \
  -v /data/trino:/data/trino:z \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  docker.io/trinodb/trino:482
```

### Verify the cluster

```bash
podman ps | grep trino
podman logs trino-coordinator | tail -50
```

Open `http://trino-coordinator.stratus.local:8080` in a browser. Confirm both workers appear as active nodes.

Or check via the REST API:

```bash
curl -s http://trino-coordinator.stratus.local:8080/v1/node | jq '. | length'
# Expected: 3
```

### Auto-start with systemd

On each Trino node:

```bash
podman generate systemd --new --name trino-coordinator \
  | sudo tee /etc/systemd/system/stratus-trino.service

# On worker nodes use --name trino-worker

sudo systemctl daemon-reload
sudo systemctl enable --now stratus-trino.service
```

---

## 10. SQL Query Contract

Trino must prove that it can query the same Iceberg tables produced by Spark and orchestrated by Airflow.

### Discovery checks

```sql
SHOW CATALOGS;
SHOW SCHEMAS FROM stratus;
SHOW TABLES FROM stratus.bronze;
SHOW TABLES FROM stratus.silver;
SHOW TABLES FROM stratus.gold;
SHOW TABLES FROM stratus.platform;
```

Expected schemas:

```text
bronze
silver
gold
platform
```

### Bronze validation

```sql
SELECT count(*) AS bronze_count
FROM stratus.bronze.verification_customers;
```

Expected: `4`, matching the Increment 3 verification dataset, including the intentional duplicate customer.

### Silver validation

```sql
SELECT count(*) AS silver_count
FROM stratus.silver.verification_customers;
```

Expected: `3`, matching the deduplicated Spark output.

### Gold validation

```sql
SELECT country, customer_count
FROM stratus.gold.verification_customer_summary
ORDER BY country;
```

Expected: one row for each country created by the Increment 3 verification dataset.

### Quality result visibility

```sql
SELECT run_id, dataset_namespace, dataset_name, check_type, severity, status
FROM stratus.platform.quality_check_results
ORDER BY checked_at DESC
LIMIT 20;
```

Expected: quality records from Spark and Airflow verification runs are visible.

### Cross-zone query

```sql
SELECT b.customer_id, b.name, s.country
FROM stratus.bronze.verification_customers b
JOIN stratus.silver.verification_customers s
  ON b.customer_id = s.customer_id
ORDER BY b.customer_id;
```

Expected: query succeeds where policy permits bronze access.

### Schema enforcement

```sql
SELECT does_not_exist
FROM stratus.silver.verification_customers;
```

Expected: query fails clearly with a column-not-found error.

### Metadata table visibility

```sql
SELECT snapshot_id, committed_at, operation
FROM stratus.silver."verification_customers$snapshots"
ORDER BY committed_at DESC
LIMIT 5;
```

Expected: current Iceberg snapshot metadata is visible through Trino metadata tables.

---

## 11. Java Verification Suite

The verification suite uses Trino JDBC to connect to the live Trino coordinator and validate query behavior. It checks discovery, row counts, aggregate correctness, quality table visibility, schema errors, and metadata table access.

### Maven dependencies

Add to `pom.xml`:

```xml
<dependency>
    <groupId>io.trino</groupId>
    <artifactId>trino-jdbc</artifactId>
    <version>482</version>
    <scope>test</scope>
</dependency>
```

### Configuration

| Variable | Description |
|---|---|
| `STRATUS_TRINO_JDBC_URL` | e.g. `jdbc:trino://trino-coordinator.stratus.local:8080/stratus` |
| `STRATUS_TRINO_USER` | Trino username for Increment 5 verification |

If basic authentication is enabled for the Trino endpoint, also set:

| Variable | Description |
|---|---|
| `STRATUS_TRINO_PASSWORD` | Trino password |

### Shared JDBC helper

Place in `src/test/java/dev/mars/stratus/query/TrinoTestClient.java`:

```java
package dev.mars.stratus.query;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class TrinoTestClient {

    public static Connection connect() throws SQLException {
        String url = System.getenv("STRATUS_TRINO_JDBC_URL");
        String user = System.getenv("STRATUS_TRINO_USER");
        String password = System.getenv("STRATUS_TRINO_PASSWORD");

        Properties properties = new Properties();
        properties.setProperty("user", user == null || user.isBlank() ? "stratus-verifier" : user);
        if (password != null && !password.isBlank()) {
            properties.setProperty("password", password);
        }

        return DriverManager.getConnection(url, properties);
    }
}
```

### Verification test class

Place in `src/test/java/dev/mars/stratus/query/TrinoQueryVerificationTest.java`:

```java
package dev.mars.stratus.query;

import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrinoQueryVerificationTest {

    static Connection trino;

    @BeforeAll
    static void connect() throws Exception {
        assertThat(System.getenv("STRATUS_TRINO_JDBC_URL"))
            .as("STRATUS_TRINO_JDBC_URL must be set")
            .isNotBlank();
        trino = TrinoTestClient.connect();
    }

    @Test
    @Order(1)
    void trinoReachable() throws Exception {
        assertThat(querySingleLong("SELECT 1"))
            .as("Trino must respond to a simple query")
            .isEqualTo(1L);
    }

    @Test
    @Order(2)
    void allRequiredSchemasVisible() throws Exception {
        Set<String> schemas = new HashSet<>();
        try (Statement statement = trino.createStatement();
             ResultSet rs = statement.executeQuery("SHOW SCHEMAS FROM stratus")) {
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
        }

        assertThat(schemas)
            .as("Trino must expose all Polaris namespaces through the stratus catalog")
            .contains("bronze", "silver", "gold", "platform");
    }

    @Test
    @Order(3)
    void bronzeRowCountMatchesSparkVerificationOutput() throws Exception {
        long count = querySingleLong(
            "SELECT count(*) FROM stratus.bronze.verification_customers");

        assertThat(count)
            .as("Bronze table must include the intentional duplicate from Spark verification")
            .isEqualTo(4L);
    }

    @Test
    @Order(4)
    void silverRowCountMatchesDeduplicatedSparkOutput() throws Exception {
        long count = querySingleLong(
            "SELECT count(*) FROM stratus.silver.verification_customers");

        assertThat(count)
            .as("Silver table must contain the deduplicated Spark output")
            .isEqualTo(3L);
    }

    @Test
    @Order(5)
    void goldAggregateMatchesExpectedOutput() throws Exception {
        long countryCount = querySingleLong(
            "SELECT count(*) FROM stratus.gold.verification_customer_summary");

        assertThat(countryCount)
            .as("Gold table must contain one row per verification country")
            .isEqualTo(3L);
    }

    @Test
    @Order(6)
    void qualityResultsQueryable() throws Exception {
        long resultCount = querySingleLong(
            "SELECT count(*) FROM stratus.platform.quality_check_results");

        assertThat(resultCount)
            .as("Quality results table must be queryable through Trino")
            .isGreaterThanOrEqualTo(1L);
    }

    @Test
    @Order(7)
    void crossZoneJoinWorksWherePolicyPermits() throws Exception {
        long joinedRows = querySingleLong("""
            SELECT count(*)
            FROM stratus.bronze.verification_customers b
            JOIN stratus.silver.verification_customers s
              ON b.customer_id = s.customer_id
            """);

        assertThat(joinedRows)
            .as("Cross-zone join must work for the verification principal")
            .isGreaterThanOrEqualTo(3L);
    }

    @Test
    @Order(8)
    void invalidColumnFailsClearly() {
        assertThatExceptionOfType(SQLException.class)
            .as("Invalid column queries must fail clearly")
            .isThrownBy(() -> querySingleLong(
                "SELECT does_not_exist FROM stratus.silver.verification_customers"))
            .withMessageContaining("does_not_exist");
    }

    @Test
    @Order(9)
    void icebergSnapshotMetadataVisible() throws Exception {
        long snapshotCount = querySingleLong(
            "SELECT count(*) FROM stratus.silver.\"verification_customers$snapshots\"");

        assertThat(snapshotCount)
            .as("Trino must expose Iceberg metadata tables")
            .isGreaterThanOrEqualTo(1L);
    }

    @AfterAll
    static void close() throws Exception {
        if (trino != null) trino.close();
    }

    private static long querySingleLong(String sql) throws SQLException {
        try (Statement statement = trino.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).as("Query must return one row: %s", sql).isTrue();
            return rs.getLong(1);
        }
    }
}
```

### Running the verification suite

```bash
export STRATUS_TRINO_JDBC_URL=jdbc:trino://trino-coordinator.stratus.local:8080/stratus
export STRATUS_TRINO_USER=stratus-verifier

mvn test -pl . -Dtest=TrinoQueryVerificationTest
```

All nine tests must pass before Increment 5 is considered complete.

---

## 12. Operational Checks

Once the verification suite passes, perform these additional checks before signing off Increment 5.

### Trino web UI

Open `http://trino-coordinator.stratus.local:8080`. Confirm:
- coordinator is running
- both workers are active
- completed queries are visible
- failed query messages are visible and useful

### Node health

```bash
curl -s http://trino-coordinator.stratus.local:8080/v1/node | jq .
```

Expected: one coordinator and two worker nodes.

### Catalog loaded

Run from the coordinator container:

```bash
podman exec trino-coordinator trino --execute "SHOW CATALOGS"
podman exec trino-coordinator trino --execute "SHOW SCHEMAS FROM stratus"
```

Expected: `stratus` catalog is listed and contains `bronze`, `silver`, `gold`, and `platform`.

### Query known verification outputs

```bash
podman exec trino-coordinator trino --catalog stratus --schema silver \
  --execute "SELECT count(*) FROM verification_customers"

podman exec trino-coordinator trino --catalog stratus --schema gold \
  --execute "SELECT * FROM verification_customer_summary ORDER BY country"
```

Counts and aggregates must match the Spark verification suite from Increment 3.

### Confirm quality visibility

```bash
podman exec trino-coordinator trino --catalog stratus --schema platform \
  --execute "SELECT status, count(*) FROM quality_check_results GROUP BY status"
```

Expected: quality records from Spark or Airflow verification runs.

### Confirm Trino does not bypass Polaris

Stop or block access to Polaris in a controlled test window. Queries against `stratus.silver.verification_customers` should fail because Trino cannot resolve table metadata. Restore Polaris immediately after the test.

Do not sign off Increment 5 if Trino can still query governed Iceberg tables while Polaris is unavailable through an alternate unmanaged catalog path.

---

## 13. Completion Gate

Increment 5 is complete when all of the following are true:

- [ ] Trino coordinator container running and managed by systemd on `trino-coordinator.stratus.local`
- [ ] Both Trino worker containers running and managed by systemd
- [ ] Trino web UI reachable on port 8080
- [ ] Trino reports one coordinator and two active workers
- [ ] `stratus` catalog configured with the Iceberg connector and Apache Polaris REST catalog
- [ ] Trino uses native S3 access to MinIO with path-style access enabled
- [ ] Bronze, silver, gold, and platform schemas visible through Trino
- [ ] Trino can query Spark-produced bronze, silver, and gold verification tables
- [ ] Bronze row count matches Spark ingestion output
- [ ] Silver row count matches Spark deduplication output
- [ ] Gold aggregate results match Spark materialisation output
- [ ] `stratus.platform.quality_check_results` is queryable
- [ ] Cross-zone join works for the verification principal where policy permits
- [ ] Invalid column query fails with a clear SQL error
- [ ] Iceberg metadata tables, such as `$snapshots`, are queryable through Trino
- [ ] `TrinoQueryVerificationTest` passes against the live Trino cluster
- [ ] Trino does not expose an unmanaged catalog path that bypasses Polaris

When all gates are checked, Increment 6 (Apache Atlas and Apache Ranger governance) can begin.

---

## 14. Troubleshooting

### Coordinator starts but workers do not appear

```bash
podman logs trino-worker
curl -s http://trino-coordinator.stratus.local:8080/v1/node | jq .
```

Common causes:
- worker `discovery.uri` points to the wrong coordinator hostname
- port 8080 is blocked between worker and coordinator nodes
- `node.environment` differs between coordinator and workers
- duplicate `node.id` values across workers

### `stratus` catalog does not appear

- Confirm `/etc/stratus/trino/catalog/stratus.properties` exists on every node
- Check coordinator logs for catalog loading errors
- Confirm the file is mounted into the container at `/etc/trino/catalog/stratus.properties`
- Confirm `connector.name=iceberg` is spelled correctly

### Trino cannot connect to Polaris

```bash
podman exec trino-coordinator curl -k https://polaris.stratus.local:8181/api/catalog/v1/config
```

Common causes:
- Polaris hostname does not resolve from Trino nodes
- TLS truststore does not trust the lab CA
- `iceberg.rest-catalog.uri` is missing `/api/catalog`
- OAuth2 credential or scope is incorrect
- `svc-trino` Polaris principal does not have the required catalog role

### Trino cannot read MinIO data

- Confirm `fs.s3.enabled=true`
- Confirm `s3.path-style-access=true`
- Confirm MinIO endpoint includes port 9000
- Confirm `svc-trino` MinIO credentials can read the target buckets
- Check whether the table location uses `s3://` or `s3a://` and verify Trino can resolve it

### Table exists in Spark but not in Trino

- Confirm Spark and Trino are using the same Polaris REST catalog
- Confirm Spark table name is in the expected namespace
- Run `SHOW TABLES FROM stratus.<namespace>` in Trino
- Check Polaris for table visibility and role grants for `svc-trino`

### Query returns different row counts than Spark

- Confirm the Spark verification tables were not cleaned up after Increment 3 tests
- Confirm Airflow did not overwrite or drop the verification tables
- Check the Iceberg snapshot visible to Trino using the `$snapshots` metadata table
- Compare with Spark SQL against the same fully qualified table name

### `Access Denied` from MinIO

- Confirm the `svc-trino` MinIO policy includes read and list access for silver, gold, and platform buckets
- If bronze verification is enabled, confirm temporary read access to `stratus-bronze`
- Confirm the secret in `stratus.properties` matches the active MinIO service account secret

### Invalid column test does not fail

- Confirm the query references a truly nonexistent column
- Confirm the table being queried is the intended verification table
- Check whether a stale table from a previous run has a different schema

---

## 15. References

- Trino Iceberg connector: https://trino.io/docs/current/connector/iceberg.html
- Trino REST catalog properties: https://trino.io/docs/current/object-storage/metastores.html#rest-catalog
- Trino S3 file system support: https://trino.io/docs/current/object-storage/file-system-s3.html
- Trino JDBC driver: https://trino.io/docs/current/client/jdbc.html
- Apache Iceberg REST Catalog spec: https://iceberg.apache.org/docs/latest/rest-catalog/
- Apache Polaris: https://polaris.apache.org/
- Stratus implementation plan: [stratus_implementation_plan.md](stratus_implementation_plan.md)
- Stratus architecture: [on_prem_data_fabric_architecture.md](on_prem_data_fabric_architecture.md)
- Increment 1 — MinIO: [increment1_minio.md](increment1_minio.md)
- Increment 2 — Iceberg and Polaris: [increment2_iceberg_polaris.md](increment2_iceberg_polaris.md)
- Increment 3 — Spark: [increment3_spark.md](increment3_spark.md)
- Increment 4 — Airflow: [increment4_airflow.md](increment4_airflow.md)
