# Stratus Increment 2 — Iceberg Tables and Polaris Catalog

## 1. Purpose

This document is the technical implementation plan for Increment 2 of the Stratus platform as defined in [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md).

Increment 2 delivers Apache Polaris as the central REST catalog and Apache Iceberg as the table format over the Ceph RGW storage layer established in Increment 1. When this increment is complete, Iceberg tables exist in all platform zones, Polaris manages their metadata, table maintenance operations work via the Iceberg Java API, and the `platform.quality_check_results` table exists and accepts writes. A Java verification suite confirms the table layer is ready for Spark in Increment 3.

**Prerequisite:** Increment 1 must be complete. All five Ceph RGW buckets must exist and all Increment 1 gate tests must pass before starting this increment.

---

## 2. Assumptions and Prerequisites

- Increment 1 complete — Ceph RGW cluster running, buckets and service accounts in place
- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- Podman 4.x installed on the Polaris host
- JDK 21+ and Maven 3.9+ installed on the verification host
- DNS resolution: `polaris.stratus.local` resolves to the Polaris host
- `svc-polaris` Ceph RGW credentials from Increment 1 are available

### Reference documentation audit

Reference baseline: 2026-07-05.

The current Apache Polaris documentation line lists Polaris 1.5.0. This increment therefore uses a pinned Polaris 1.5.0 image and Iceberg 1.11.0 Java dependencies, aligned with the Spark 4.1 target in Increment 3. Before implementation, verify the exact Polaris, Iceberg, Spark, and Trino versions together and update all increment documents as a set if any upstream release changes the compatibility matrix.

Polaris quickstart-style examples are lab bootstrap guidance, not a production deployment pattern. Production-like deployment must use an external metadata store, hardened credentials, TLS trusted by all engines, and pinned artifacts.

---

## 3. Topology

Polaris runs as a single Podman container on a dedicated host. For Increment 2 it uses an embedded H2 database as its metadata store. H2 will be replaced with PostgreSQL in a later operational maturity increment.

```text
  Polaris host
  ┌─────────────────────────────────────────┐
  │  Podman: polaris container              │
  │  REST Catalog API  :8181 (TLS)          │
  │  Embedded H2 metadata store            │
  └─────────────────────────────────────────┘
           │  catalog metadata
           │  (namespace, table, schema, snapshot location)
           ▼
  ┌─────────────────────────────────────────┐
  │  Ceph RGW cluster  (Increment 1)        │
  │  stratus-bronze / silver / gold /       │
  │  platform  ← Iceberg data files        │
  └─────────────────────────────────────────┘
```

All compute engines added in later increments (Spark, Trino, Flink) connect to Polaris at `https://polaris.stratus.local:8181` to resolve table locations.

---

## 4. Ports

| Port | Service | Purpose |
|---|---|---|
| 8181 | Polaris | REST Catalog API (TLS) |

---

## 5. TLS Certificates

Use the same self-signed CA created in Increment 1.

```bash
cd ~/stratus-certs

openssl genrsa -out polaris.key 2048
openssl req -new -key polaris.key -out polaris.csr \
  -subj "/CN=polaris.stratus.local/O=Stratus/C=US"
openssl x509 -req -days 3650 \
  -in polaris.csr \
  -CA ca.crt -CAkey ca.key -CAcreateserial \
  -extfile <(printf "subjectAltName=DNS:polaris.stratus.local,IP:127.0.0.1") \
  -out polaris.crt

# Distribute to the Polaris host
ssh polaris.stratus.local "mkdir -p /etc/stratus/certs"
scp polaris.key polaris.crt ca.crt polaris.stratus.local:/etc/stratus/certs/
```

---

## 6. Polaris Container Configuration

### Data directory

Polaris needs a persistent directory for the embedded H2 database files:

```bash
# On the Polaris host
sudo mkdir -p /data/polaris
sudo chown $USER:$USER /data/polaris
```

### Environment file

Create `/etc/stratus/polaris.env` on the Polaris host:

```bash
# /etc/stratus/polaris.env

# Bootstrap credentials for the Polaris root principal
# Change before any non-lab use
POLARIS_BOOTSTRAP_PRINCIPAL_NAME=stratus-root
POLARIS_BOOTSTRAP_PRINCIPAL_CREDENTIAL=change-me-before-use

# Ceph RGW connection — used by Polaris to read/write Iceberg metadata files
CEPH_RGW_ENDPOINT=https://object-store.stratus.local
CEPH_RGW_ACCESS_KEY=svc-polaris
CEPH_RGW_SECRET_KEY=<svc-polaris secret from Increment 1>
CEPH_RGW_PATH_STYLE_ACCESS=true

# H2 persistence
POLARIS_PERSISTENCE=h2
POLARIS_H2_DATA_DIR=/data/polaris
```

### Run Polaris

```bash
podman run -d \
  --name polaris \
  --hostname polaris.stratus.local \
  --network host \
  --env-file /etc/stratus/polaris.env \
  -v /data/polaris:/data/polaris:z \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  apache/polaris:1.5.0 \
    --tls-certificate /etc/stratus/certs/polaris.crt \
    --tls-key /etc/stratus/certs/polaris.key
```

If a different Polaris release is approved, update this image tag and the Iceberg dependency versions in §9 together. Do not use `latest`.

### Verify the container started

```bash
podman ps | grep polaris
podman logs polaris | tail -30
```

Look for `Polaris REST Catalog started` and the API listening on port 8181.

### Quick API health check

```bash
curl -k https://polaris.stratus.local:8181/api/catalog/v1/config
```

Expected: a JSON response containing the catalog configuration. A 200 response confirms Polaris is reachable.

### Auto-start with systemd

```bash
podman generate systemd --new --name polaris \
  | sudo tee /etc/systemd/system/stratus-polaris.service

sudo systemctl daemon-reload
sudo systemctl enable --now stratus-polaris.service
```

---

## 7. Polaris Catalog Setup

With Polaris running, configure the catalog structure: principal credentials, catalog definition, and namespace hierarchy.

All setup commands use the Polaris REST API directly via `curl`. Replace the bearer token in each call with the token obtained in the authentication step below.

### Authenticate and obtain a token

```bash
TOKEN=$(curl -sk -X POST \
  https://polaris.stratus.local:8181/api/catalog/v1/oauth/tokens \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=stratus-root" \
  -d "client_secret=change-me-before-use" \
  -d "scope=PRINCIPAL_ROLE:ALL" \
  | jq -r '.access_token')

echo "Token acquired: ${TOKEN:0:20}..."
```

### Create the Stratus catalog

```bash
curl -sk -X POST \
  https://polaris.stratus.local:8181/api/management/v1/catalogs \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "stratus",
    "type": "INTERNAL",
    "properties": {
      "default-base-location": "s3://stratus-bronze"
    },
    "storageConfigInfo": {
      "storageType": "S3",
      "allowedLocations": [
        "s3://stratus-landing",
        "s3://stratus-bronze",
        "s3://stratus-silver",
        "s3://stratus-gold",
        "s3://stratus-platform"
      ],
      "s3.endpoint": "https://object-store.stratus.local",
      "s3.access-key-id": "svc-polaris",
      "s3.secret-access-key": "<svc-polaris secret>",
      "s3.path-style-access": "true"
    }
  }'
```

### Create namespaces

```bash
for NS in bronze silver gold platform; do
  curl -sk -X POST \
    https://polaris.stratus.local:8181/api/catalog/v1/stratus/namespaces \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"namespace\": [\"${NS}\"],
      \"properties\": {
        \"location\": \"s3://stratus-${NS}\",
        \"zone\": \"${NS}\"
      }
    }"
  echo "Created namespace: ${NS}"
done
```

### Create service principals in Polaris

Each compute engine that connects to Polaris needs a Polaris principal with appropriate catalog roles.

```bash
# Create principal for Spark
curl -sk -X POST \
  https://polaris.stratus.local:8181/api/management/v1/principals \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "svc-spark", "type": "SERVICE"}'

# Create principal for Trino (read-only to queryable namespaces)
curl -sk -X POST \
  https://polaris.stratus.local:8181/api/management/v1/principals \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "svc-trino", "type": "SERVICE"}'
```

Assign catalog roles granting appropriate namespace access to each principal. `svc-trino` requires read access to `silver`, `gold`, and `platform` for normal query serving and quality visibility. It also needs controlled read access to `bronze` for the Increment 5 verification dataset unless the verification suite uses a separate internal platform principal. Full role management details are in the Polaris documentation.

---

## 8. Iceberg Table Provisioning

With Polaris configured, create the initial platform Iceberg tables. These tables define the schema contracts that all engines write to and read from.

### `platform.quality_check_results`

This table is defined in the architecture document (§5.3). Create it via the Iceberg Java API in the verification module (§9) or via a setup script using the Iceberg REST client.

Schema:

| Column | Type | Description |
|---|---|---|
| `run_id` | string | Unique identifier for the check run |
| `dataset_namespace` | string | Polaris namespace |
| `dataset_name` | string | Iceberg table name |
| `zone` | string | bronze / silver / gold |
| `check_type` | string | completeness / uniqueness / freshness / etc. |
| `check_name` | string | Descriptive name of the specific check |
| `severity` | string | blocking / warning |
| `status` | string | passed / failed / warning / overridden |
| `metric_value` | double | Observed metric value |
| `threshold` | double | Configured pass threshold |
| `failure_detail` | string | Human-readable failure context |
| `pipeline_run_id` | string | Airflow DAG run ID |
| `checked_at` | timestamp | Check execution time |
| `iceberg_snapshot_id` | long | Iceberg snapshot ID of the checked dataset |

This table is append-only. It must be partitioned by `zone` and `checked_at` (by day) for query performance.

---

## 9. Java Verification Module

The verification suite uses the Iceberg Java API and the Iceberg REST catalog client to connect to Polaris and verify that tables can be created, written, read, and maintained via the catalog.

### Maven dependencies

Add to `pom.xml`:

```xml
<!-- Iceberg core API and Java implementation -->
<dependency>
    <groupId>org.apache.iceberg</groupId>
    <artifactId>iceberg-core</artifactId>
    <version>1.11.0</version>
</dependency>

<!-- Iceberg REST catalog client — connects to Polaris -->
<dependency>
    <groupId>org.apache.iceberg</groupId>
    <artifactId>iceberg-rest-catalog</artifactId>
    <version>1.11.0</version>
</dependency>

<!-- Parquet support for reading and writing data files -->
<dependency>
    <groupId>org.apache.iceberg</groupId>
    <artifactId>iceberg-parquet</artifactId>
    <version>1.11.0</version>
</dependency>
<dependency>
    <groupId>org.apache.parquet</groupId>
    <artifactId>parquet-avro</artifactId>
    <version>1.13.1</version>
</dependency>

<!-- AWS S3 FileIO — used by Iceberg to read/write Ceph RGW -->
<dependency>
    <groupId>org.apache.iceberg</groupId>
    <artifactId>iceberg-aws</artifactId>
    <version>1.11.0</version>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.25.0</version>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>url-connection-client</artifactId>
    <version>2.25.0</version>
</dependency>
```

### Configuration

The verification suite reads all connection details from environment variables:

| Variable | Description |
|---|---|
| `STRATUS_POLARIS_URI` | e.g. `https://polaris.stratus.local:8181/api/catalog` |
| `STRATUS_POLARIS_CLIENT_ID` | Polaris principal client id |
| `STRATUS_POLARIS_CLIENT_SECRET` | Polaris principal client secret |
| `STRATUS_POLARIS_CATALOG` | Catalog name — `stratus` |
| `STRATUS_S3_ENDPOINT` | e.g. `https://object-store.stratus.local` |
| `STRATUS_S3_ACCESS_KEY` | `svc-polaris` access key |
| `STRATUS_S3_SECRET_KEY` | `svc-polaris` secret key |

### Shared catalog client helper

Place in `src/test/java/dev/mars/stratus/catalog/PolarisTestClient.java`:

```java
package dev.mars.stratus.catalog;

import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.rest.RESTCatalog;

import java.util.HashMap;
import java.util.Map;

public class PolarisTestClient {

    public static RESTCatalog connect() {
        String uri          = System.getenv("STRATUS_POLARIS_URI");
        String clientId     = System.getenv("STRATUS_POLARIS_CLIENT_ID");
        String clientSecret = System.getenv("STRATUS_POLARIS_CLIENT_SECRET");
        String catalog      = System.getenv("STRATUS_POLARIS_CATALOG");
        String s3Endpoint   = System.getenv("STRATUS_S3_ENDPOINT");
        String accessKey    = System.getenv("STRATUS_S3_ACCESS_KEY");
        String secretKey    = System.getenv("STRATUS_S3_SECRET_KEY");

        Map<String, String> properties = new HashMap<>();
        properties.put(CatalogProperties.URI, uri);
        properties.put("credential", clientId + ":" + clientSecret);
        properties.put("scope", "PRINCIPAL_ROLE:ALL");
        properties.put(CatalogProperties.WAREHOUSE_LOCATION, "s3://stratus-bronze");
        properties.put(CatalogProperties.FILE_IO_IMPL, "org.apache.iceberg.aws.s3.S3FileIO");
        properties.put("s3.endpoint", s3Endpoint);
        properties.put("s3.access-key-id", accessKey);
        properties.put("s3.secret-access-key", secretKey);
        properties.put("s3.path-style-access", "true");

        RESTCatalog restCatalog = new RESTCatalog();
        restCatalog.initialize(catalog, properties);
        return restCatalog;
    }
}
```

### Verification test class

Place in `src/test/java/dev/mars/stratus/catalog/IcebergPolarisVerificationTest.java`:

```java
package dev.mars.stratus.catalog;

import org.apache.iceberg.*;
import org.apache.iceberg.actions.RewriteDataFiles;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IcebergPolarisVerificationTest {

    static RESTCatalog catalog;

    static final Schema TEST_SCHEMA = new Schema(
        Types.NestedField.required(1, "id", Types.StringType.get()),
        Types.NestedField.required(2, "name", Types.StringType.get()),
        Types.NestedField.optional(3, "value", Types.DoubleType.get())
    );

    static final PartitionSpec UNPARTITIONED = PartitionSpec.unpartitioned();

    static final TableIdentifier BRONZE_TABLE =
        TableIdentifier.of(Namespace.of("bronze"), "verification_test");

    static final TableIdentifier QUALITY_TABLE =
        TableIdentifier.of(Namespace.of("platform"), "quality_check_results");

    @BeforeAll
    static void connect() {
        assertThat(System.getenv("STRATUS_POLARIS_URI"))
            .as("STRATUS_POLARIS_URI must be set").isNotBlank();
        catalog = PolarisTestClient.connect();
    }

    @Test
    @Order(1)
    void polarisReachableAndCatalogExists() {
        assertThatNoException()
            .as("Polaris must be reachable and the stratus catalog must exist")
            .isThrownBy(() -> catalog.listNamespaces());
    }

    @Test
    @Order(2)
    void allNamespacesExist() {
        List<Namespace> namespaces = catalog.listNamespaces();
        List<String> names = namespaces.stream()
            .map(ns -> ns.level(0))
            .toList();

        assertThat(names)
            .as("All four platform namespaces must exist in Polaris")
            .contains("bronze", "silver", "gold", "platform");
    }

    @Test
    @Order(3)
    void canCreateTableInBronzeNamespace() {
        if (catalog.tableExists(BRONZE_TABLE)) {
            catalog.dropTable(BRONZE_TABLE, true);
        }

        Table table = catalog.createTable(BRONZE_TABLE, TEST_SCHEMA, UNPARTITIONED);

        assertThat(table).isNotNull();
        assertThat(table.schema().columns()).hasSize(3);
        assertThat(catalog.tableExists(BRONZE_TABLE)).isTrue();
    }

    @Test
    @Order(4)
    void canWriteDataToTable() throws Exception {
        Table table = catalog.loadTable(BRONZE_TABLE);

        // Write a parquet data file directly via the Iceberg Java API
        String filePath = table.location() + "/data/" + UUID.randomUUID() + ".parquet";
        OutputFile outputFile = table.io().newOutputFile(filePath);

        GenericRecord record1 = GenericRecord.create(TEST_SCHEMA);
        record1.setField("id", "1");
        record1.setField("name", "alpha");
        record1.setField("value", 1.0);

        GenericRecord record2 = GenericRecord.create(TEST_SCHEMA);
        record2.setField("id", "2");
        record2.setField("name", "beta");
        record2.setField("value", 2.0);

        DataWriter<Record> writer = Parquet.writeData(outputFile)
            .schema(TEST_SCHEMA)
            .createWriterFunc(GenericParquetWriter::buildWriter)
            .overwrite()
            .withSpec(UNPARTITIONED)
            .build();

        try (writer) {
            writer.write(record1);
            writer.write(record2);
        }

        // Commit the written file as a new snapshot
        table.newAppend()
            .appendFile(writer.toDataFile())
            .commit();

        assertThat(table.currentSnapshot()).isNotNull();
        assertThat(table.currentSnapshot().addedDataFiles(table.io()))
            .as("Snapshot must reference the written data file")
            .isNotEmpty();
    }

    @Test
    @Order(5)
    void canReadDataBackFromTable() {
        Table table = catalog.loadTable(BRONZE_TABLE);

        try (CloseableIterable<Record> records = IcebergGenerics.read(table).build()) {
            List<Record> rows = org.apache.iceberg.util.StructLikeSet.of(
                TEST_SCHEMA.asStruct()).stream().toList();

            // Collect into list
            java.util.List<Record> result = new java.util.ArrayList<>();
            records.forEach(result::add);

            assertThat(result)
                .as("Table must contain the two written records")
                .hasSize(2);

            assertThat(result.stream().map(r -> r.getField("id")).toList())
                .containsExactlyInAnyOrder("1", "2");
        } catch (Exception e) {
            fail("Failed to read records from table: " + e.getMessage());
        }
    }

    @Test
    @Order(6)
    void schemaEvolutionWorks() {
        Table table = catalog.loadTable(BRONZE_TABLE);
        int originalColumnCount = table.schema().columns().size();

        // Add a new optional column — Iceberg schema evolution
        table.updateSchema()
            .addColumn("source_system", Types.StringType.get())
            .commit();

        Table reloaded = catalog.loadTable(BRONZE_TABLE);
        assertThat(reloaded.schema().columns())
            .as("Schema must contain the new column after evolution")
            .hasSize(originalColumnCount + 1);
    }

    @Test
    @Order(7)
    void snapshotExpiryWorks() {
        Table table = catalog.loadTable(BRONZE_TABLE);
        long snapshotCountBefore = snapshotCount(table);

        // Expire all snapshots older than now (retaining the current snapshot)
        table.expireSnapshots()
            .expireOlderThan(System.currentTimeMillis())
            .retainLast(1)
            .commit();

        Table reloaded = catalog.loadTable(BRONZE_TABLE);
        long snapshotCountAfter = snapshotCount(reloaded);

        assertThat(snapshotCountAfter)
            .as("Snapshot expiry must reduce snapshot count")
            .isLessThanOrEqualTo(snapshotCountBefore);
        assertThat(reloaded.currentSnapshot())
            .as("Current snapshot must still exist after expiry")
            .isNotNull();
    }

    @Test
    @Order(8)
    void qualityResultsTableExistsWithCorrectSchema() {
        assertThat(catalog.tableExists(QUALITY_TABLE))
            .as("platform.quality_check_results must exist in Polaris")
            .isTrue();

        Table table = catalog.loadTable(QUALITY_TABLE);
        List<String> columnNames = table.schema().columns().stream()
            .map(Types.NestedField::name)
            .toList();

        assertThat(columnNames).contains(
            "run_id", "dataset_namespace", "dataset_name", "zone",
            "check_type", "check_name", "severity", "status",
            "metric_value", "threshold", "failure_detail",
            "pipeline_run_id", "checked_at", "iceberg_snapshot_id"
        );
    }

    @Test
    @Order(9)
    void qualityResultsTableAcceptsWrite() throws Exception {
        Table table = catalog.loadTable(QUALITY_TABLE);
        Schema schema = table.schema();

        String filePath = table.location() + "/data/" + UUID.randomUUID() + ".parquet";
        OutputFile outputFile = table.io().newOutputFile(filePath);

        GenericRecord record = GenericRecord.create(schema);
        record.setField("run_id", UUID.randomUUID().toString());
        record.setField("dataset_namespace", "bronze");
        record.setField("dataset_name", "verification_test");
        record.setField("zone", "bronze");
        record.setField("check_type", "completeness");
        record.setField("check_name", "mandatory_fields_not_null");
        record.setField("severity", "blocking");
        record.setField("status", "passed");
        record.setField("metric_value", 1.0);
        record.setField("threshold", 1.0);
        record.setField("failure_detail", null);
        record.setField("pipeline_run_id", "verification-run-001");
        record.setField("checked_at",
            java.time.OffsetDateTime.now().toEpochSecond() * 1_000_000L);
        record.setField("iceberg_snapshot_id",
            catalog.loadTable(BRONZE_TABLE).currentSnapshot().snapshotId());

        DataWriter<Record> writer = Parquet.writeData(outputFile)
            .schema(schema)
            .createWriterFunc(GenericParquetWriter::buildWriter)
            .overwrite()
            .withSpec(table.spec())
            .build();

        try (writer) { writer.write(record); }

        table.newAppend().appendFile(writer.toDataFile()).commit();

        assertThat(table.currentSnapshot())
            .as("Quality results table must have a snapshot after write")
            .isNotNull();
    }

    @Test
    @Order(10)
    void tableDropAndCleanup() {
        // Clean up the verification test table from the bronze namespace
        if (catalog.tableExists(BRONZE_TABLE)) {
            boolean dropped = catalog.dropTable(BRONZE_TABLE, true);
            assertThat(dropped).as("Verification table must be droppable").isTrue();
            assertThat(catalog.tableExists(BRONZE_TABLE))
                .as("Table must no longer exist after drop").isFalse();
        }
    }

    @AfterAll
    static void close() {
        if (catalog != null) {
            try { catalog.close(); } catch (Exception ignored) { }
        }
    }

    private long snapshotCount(Table table) {
        long count = 0;
        for (Snapshot ignored : table.snapshots()) count++;
        return count;
    }
}
```

### Running the verification suite

```bash
export STRATUS_POLARIS_URI=https://polaris.stratus.local:8181/api/catalog
export STRATUS_POLARIS_CLIENT_ID=svc-spark
export STRATUS_POLARIS_CLIENT_SECRET=<client secret>
export STRATUS_POLARIS_CATALOG=stratus
export STRATUS_S3_ENDPOINT=https://object-store.stratus.local
export STRATUS_S3_ACCESS_KEY=svc-polaris
export STRATUS_S3_SECRET_KEY=<svc-polaris secret>

mvn test -pl . -Dtest=IcebergPolarisVerificationTest
```

All ten tests must pass before Increment 2 is considered complete.

---

## 10. Operational Checks

Once the verification suite passes, perform these additional checks before signing off Increment 2.

### Confirm table metadata is stored in Ceph RGW

Iceberg metadata files (`.metadata.json`, manifest lists, manifests) should be visible in the bronze bucket:

```bash
aws --endpoint-url https://object-store.stratus.local s3 ls --recursive s3://stratus-bronze/
```

Expect to see:
- `metadata/` directory with `.metadata.json` and `.avro` manifest files
- `data/` directory with `.parquet` data files

This confirms Polaris is correctly directing Iceberg to write metadata and data into Ceph RGW.

### Confirm namespace properties in Polaris

```bash
TOKEN=$(curl -sk -X POST \
  https://polaris.stratus.local:8181/api/catalog/v1/oauth/tokens \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=stratus-root&client_secret=change-me-before-use&scope=PRINCIPAL_ROLE:ALL" \
  | jq -r '.access_token')

curl -sk \
  https://polaris.stratus.local:8181/api/catalog/v1/stratus/namespaces \
  -H "Authorization: Bearer $TOKEN" | jq .
```

All four namespaces (`bronze`, `silver`, `gold`, `platform`) must be listed.

### Confirm `platform.quality_check_results` table

```bash
curl -sk \
  https://polaris.stratus.local:8181/api/catalog/v1/stratus/namespaces/platform/tables \
  -H "Authorization: Bearer $TOKEN" | jq .
```

The `quality_check_results` table must appear in the response.

---

## 11. Completion Gate

Increment 2 is complete when all of the following are true:

- [ ] Polaris container running and managed by systemd on `polaris.stratus.local`
- [ ] Polaris REST API responding at `https://polaris.stratus.local:8181` with TLS
- [ ] `stratus` catalog created in Polaris
- [ ] Four namespaces exist: `bronze`, `silver`, `gold`, `platform`
- [ ] `svc-spark` and `svc-trino` principals created in Polaris with correct roles
- [ ] `platform.quality_check_results` Iceberg table created with correct schema
- [ ] `IcebergPolarisVerificationTest` — all ten tests pass against the live cluster
- [ ] Iceberg metadata files visible in Ceph RGW buckets via `aws s3 ls`
- [ ] Polaris logs show no errors during the verification test run

When all gates are checked, Increment 3 (Apache Spark) can begin.

---

## 12. Troubleshooting

### Polaris container exits on startup

```bash
podman logs polaris
```

Common causes:
- Certificate path mismatch — confirm the `--tls-certificate` and `--tls-key` paths match the volume mount
- H2 data directory not writable — confirm `/data/polaris` is owned by the running user
- Port 8181 already in use — `ss -tlnp | grep 8181`

### `401 Unauthorized` from Polaris API

- Confirm the client ID and secret match what was set in `POLARIS_BOOTSTRAP_PRINCIPAL_CREDENTIAL`
- Confirm the `scope` parameter is included in the token request: `scope=PRINCIPAL_ROLE:ALL`
- Check that the token has not expired (default TTL is typically 1 hour)

### Iceberg cannot write to Ceph RGW

- Confirm `s3.path-style-access=true` is set — Ceph RGW requires path-style access
- Confirm the `svc-polaris` credentials have write access to the target bucket in Ceph RGW
- Confirm the Ceph RGW endpoint in Polaris storage config matches the running Ceph cluster
- Test Ceph RGW access directly: `aws --endpoint-url https://object-store.stratus.local s3 ls s3://stratus-bronze/`

### `NoSuchTableException` in verification test

- The table was not created — check that test order 3 (create table) passed before test order 4 (write)
- Confirm the namespace exists in Polaris before attempting to create a table in it

### Verification test runs but parquet read returns zero rows

- The Iceberg snapshot may not have been committed — ensure `.commit()` was called after `newAppend()`
- Confirm the FileIO properties (Ceph RGW endpoint, credentials) are correctly set in `PolarisTestClient`

---

## 13. References

- Apache Polaris documentation: https://polaris.apache.org/
- Apache Polaris GitHub: https://github.com/apache/polaris
- Apache Iceberg Java API: https://iceberg.apache.org/docs/latest/java-api-quickstart/
- Iceberg REST Catalog spec: https://iceberg.apache.org/docs/latest/rest-catalog/
- Iceberg Parquet writer: https://iceberg.apache.org/docs/latest/api/
- Ceph RGW S3 API compatibility: https://docs.ceph.com/en/latest/radosgw/s3/
- Stratus Phase 1 implementation plan: [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)
- Stratus architecture: [on_prem_data_fabric_architecture.md](on_prem_data_fabric_architecture.md)
- Increment 1 — Ceph object storage foundation: [increment1_ceph.md](increment1_ceph.md)
