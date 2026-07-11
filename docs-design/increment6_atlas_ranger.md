# Stratus Increment 6 — Apache Atlas and Apache Ranger Governance

## 1. Purpose

This document is the technical implementation plan for Increment 6 of the Stratus platform as defined in [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md).

Increment 6 delivers Apache Atlas as the metadata, lineage, glossary, and classification plane, and Apache Ranger as the access policy and audit plane. When this increment is complete, Iceberg datasets created by Spark and queried through Trino have Atlas entities with ownership, schema, zone, quality status, and lineage. Ranger policies enforce zone and classification-based access through Trino. A Java verification suite confirms that governance is not decorative: metadata is searchable, lineage exists, classifications can be applied, Trino allow/deny behavior follows Ranger policy, and Ranger audit logs record the decisions.

This increment has two explicit tracks. The developer profile uses the disposable Atlas dependencies and local Ranger identities shown in the runnable examples so engineers can prove metadata and policy behavior quickly. The production profile replaces them with supported external Atlas graph, search, and notification services, durable Ranger state and audit storage, managed identity, trusted TLS, availability, backup/restore, and failure drills. Developer completion may unblock Increment 7 engineering; it cannot satisfy Increment 6 production acceptance or Phase 1 readiness.

**Prerequisites:**
- Increment 1 complete — Ceph RGW cluster running, all buckets and service accounts in place
- Increment 2 complete — Polaris running, all namespaces and the `platform.quality_check_results` table created, all Increment 2 gate tests passing
- Increment 3 complete — Spark jobs create and maintain bronze, silver, and gold Iceberg tables
- Increment 4 complete — Airflow orchestrates Spark jobs, quality checks, promotion gates, and maintenance
- Increment 5 complete — Trino queries Polaris-managed Iceberg tables and all Increment 5 gate tests pass

**Track rule:** Developer work requires the developer gates of Increments 1-5. Increment 6 production preparation may proceed in parallel, but its identity, TLS, and group-sync checks close only after Increment 7; formal acceptance then requires the production gates of its dependencies.

---

## 2. Assumptions and Prerequisites

- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- Podman 5.8.2 installed on the governance host and Trino coordinator, or a newer approved stable patch after regression testing
- JDK 25 and Maven 3.9.16 on the approved build worker; development hosts may use the same toolchain, while verification hosts require only the approved container runtime and verifier runtime inputs. Atlas and Ranger runtime Java versions remain pinned to the versions supported by the selected releases and are recorded as component-runtime exceptions where they differ from Java 25.
- DNS resolution:
  - `atlas.stratus.local`
  - `ranger.stratus.local`
  - `trino-coordinator.stratus.local`
- Governance host can reach:
  - Trino coordinator on port 8080
  - Airflow on port 8088
  - Polaris on port 8181
- Trino coordinator can reach Ranger Admin on port 6080
- Verification tables from Increment 5 are available:
  - `stratus.bronze.verification_customers`
  - `stratus.silver.verification_customers`
  - `stratus.gold.verification_customer_summary`
  - `stratus.platform.quality_check_results`
- Initial lab users and groups exist in Ranger's local user store:
  - `platform_admin`
  - `platform_engineer`
  - `analyst_crm`
  - `analyst_restricted`

FreeIPA-backed LDAP usersync is introduced in Increment 7. Increment 6 may use local users and groups to prove the governance behavior before identity hardening.

---

## 3. Topology

The runnable topology below is the developer profile on a dedicated governance host. Ranger enforces access through the Trino Ranger access-control plugin. Atlas records metadata and lineage; Ranger enforces access and writes audit records.

```text
governance host
┌──────────────────────────────────────────────┐
│  Podman: atlas                               │
│  Atlas UI / REST API :21000                  │
│  embedded JanusGraph BerkeleyDB + Solr       │
├──────────────────────────────────────────────┤
│  Podman: ranger-admin                        │
│  Ranger Admin UI / REST API :6080            │
├──────────────────────────────────────────────┤
│  Podman: ranger-postgres                     │
│  Ranger policy database :5432                │
├──────────────────────────────────────────────┤
│  Podman: ranger-usersync                     │
│  local usersync now; FreeIPA LDAP later      │
└──────────────────────────────────────────────┘
          │
          │ policy download + audit
          ▼
trino-coordinator.stratus.local
┌──────────────────────────────────────────────┐
│  Trino Ranger access-control plugin          │
│  enforces catalog / schema / table / column  │
└──────────────────────────────────────────────┘
          │
          ▼
Polaris REST catalog → Ceph RGW Iceberg data
```

Atlas is not an enforcement layer. Ranger is the enforcement layer. Increment 6 must prove the loop:

```text
dataset exists → Atlas entity exists → classification applied
      → Ranger policy evaluates → Trino query allowed or denied
```

The production profile adds external HBase, SolrCloud with ZooKeeper, and an external Kafka notification service for Atlas; durable PostgreSQL and audit storage for Ranger; at least two Atlas application instances or an approved availability exception; and production load-balancing, TLS, monitoring, backup, and recovery. The external notification service may be a small Atlas-dedicated Kafka deployment or the Increment 8 platform backbone brought forward solely as an Atlas dependency. CDC and Flink remain Phase 2 capabilities either way.

---

## 4. Ports

| Port | Service | Purpose |
|---|---|---|
| 21000 | Atlas | Atlas UI and REST API |
| 6080 | Ranger Admin | Ranger UI, REST API, and policy download endpoint |
| 5432 | PostgreSQL | Ranger policy database, local host access only where possible |

Trino must be able to reach Ranger Admin on port 6080 to download policies. The verification host must be able to reach Atlas, Ranger, and Trino APIs.

For Increment 6, Atlas and Ranger may use HTTP inside the lab network. TLS and FreeIPA/Keycloak-backed authentication are hardened in Increment 7.

---

## 5. Governance Images

Apache Atlas and Apache Ranger deployments are sensitive to Java, Python, database, and packaging versions. For reproducibility, Stratus uses platform-maintained container images built from Apache release artifacts rather than unpinned community images.

Target image names:

| Image | Purpose |
|---|---|
| `stratus/atlas:2.5.0` | Atlas server; developer image includes disposable dependencies, production image uses external services |
| `stratus/ranger-admin:2.8.0` | Ranger Admin server |
| `stratus/ranger-usersync:2.8.0` | Ranger usersync service |
| `${RANGER_POSTGRES_IMAGE}` | exact latest Ranger-compatible PostgreSQL patch, pinned by tag and digest in the environment matrix |

Before running the examples, export the exact image reference approved by the Ranger 2.8.0 database compatibility test:

```bash
export RANGER_POSTGRES_IMAGE=registry.stratus.local/mirror/postgres:<approved-patch>@sha256:<digest>
```

There is deliberately no implicit PostgreSQL major-version default. The environment matrix records the newest supported major and latest patch that passes Ranger schema bootstrap, migration, backup, and restore tests.

Create image build directories:

```bash
mkdir -p docker/atlas
mkdir -p docker/ranger-admin
mkdir -p docker/ranger-usersync
```

The exact image build should be pinned to approved Apache release artifacts and checked into the repository before implementation begins. Do not use `latest` tags for governance services.

### Reference documentation audit

Reference baseline: 2026-07-10.

Apache Atlas and Apache Ranger do not provide the same single, turnkey official container path as Trino or Airflow. Stratus therefore treats the Atlas and Ranger images as platform-maintained artifacts built from approved Apache releases. The build scripts, base images, Java versions, database drivers, and plugin versions must be versioned in the repository before implementation.

The Trino Ranger access-control properties in §12 are aligned with the current Trino Ranger documentation for release 482. Keep the Ranger plugin configuration aligned with the selected Trino release, and rerun the Increment 5 and 6 verification suites after any Trino or Ranger upgrade.

---

## 6. Persistent Directory Layout

Create persistent directories on `governance.stratus.local`:

```bash
sudo mkdir -p /data/atlas
sudo mkdir -p /data/ranger/admin
sudo mkdir -p /data/ranger/postgres
sudo mkdir -p /data/ranger/audit
sudo mkdir -p /etc/stratus/atlas
sudo mkdir -p /etc/stratus/ranger
sudo chown -R $USER:$USER /data/atlas /data/ranger /etc/stratus/atlas /etc/stratus/ranger
```

The mounted directory layout is:

```text
/etc/stratus/
├── atlas/
│   ├── atlas-application.properties
│   └── atlas-log4j.xml
└── ranger/
    ├── ranger-admin-install.properties
    ├── usersync-install.properties
    ├── trino/
    │   ├── access-control.properties
    │   ├── ranger-trino-security.xml
    │   ├── ranger-trino-audit.xml
    │   └── ranger-policymgr-ssl.xml
    └── policies/
        └── stratus-trino-policies.json
```

```text
/data/
├── atlas/             Atlas graph/search/runtime state
└── ranger/
    ├── admin/         Ranger admin runtime state
    ├── postgres/      Ranger policy database
    └── audit/         local audit file destination for Increment 6
```

---

## 7. Atlas Configuration

Atlas stores metadata entities, classifications, glossary terms, relationships, and lineage. The following configuration is **developer profile only**: a disposable embedded graph/search backend and embedded notifier keep local work focused on metadata behavior. These settings are prohibited by the production gate.

Create `/etc/stratus/atlas/atlas-application.properties`:

```properties
# /etc/stratus/atlas/atlas-application.properties

atlas.server.http.port=21000
atlas.server.run.setup.on.start=false

# Embedded graph and search backend for Increment 6
atlas.graph.storage.backend=berkeleyje
atlas.graph.storage.directory=/data/atlas/graph
atlas.graph.index.search.backend=solr
atlas.graph.index.search.solr.mode=embedded
atlas.graph.index.search.solr.embedded.data-dir=/data/atlas/solr

# Authentication: lab-local for Increment 6; FreeIPA/Keycloak hardening follows in Increment 7
atlas.authentication.method.file=true
atlas.authentication.method.kerberos=false
atlas.authentication.method.ldap=false

# Notification bus: embedded for Increment 6
atlas.notification.embedded=true

# Server identity
atlas.cluster.name=stratus-lab
```

### Production Atlas dependency configuration

Production uses a separate configuration overlay. The selected Atlas 2.5.0 build must validate the exact property names and dependency versions against the release documentation before deployment; the required design is:

```properties
# External graph store
atlas.graph.storage.backend=hbase2
atlas.graph.storage.hostname=zk1.stratus.local,zk2.stratus.local,zk3.stratus.local

# External search index
atlas.graph.index.search.backend=solr
atlas.graph.index.search.solr.mode=cloud
atlas.graph.index.search.solr.zookeeper-url=zk1.stratus.local:2181,zk2.stratus.local:2181,zk3.stratus.local:2181/solr

# External notification service
atlas.notification.embedded=false
atlas.kafka.bootstrap.servers=atlas-kafka1.stratus.local:9093,atlas-kafka2.stratus.local:9093,atlas-kafka3.stratus.local:9093
atlas.kafka.security.protocol=SASL_SSL
```

The production overlay also supplies service truststores/keystores, SASL credentials through the approved secret mechanism, HBase and Solr service identities, collection/bootstrap automation, and non-local data directories. Do not place passwords in `atlas-application.properties`. Production acceptance proves HBase recovery, SolrCloud collection recovery, notification delivery, Atlas application failover, and a coordinated metadata restore. If these dependencies are unavailable, Atlas remains developer-profile only and governed production onboarding is blocked.

### Start Atlas

```bash
export STRATUS_RANGER_DB_PASSWORD=<ranger-db secret from approved secret store>

podman run -d \
  --name atlas \
  --hostname atlas.stratus.local \
  --network host \
  -v /etc/stratus/atlas:/etc/atlas/conf:ro,z \
  -v /data/atlas:/data/atlas:z \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  stratus/atlas:2.5.0
```

### Verify Atlas

```bash
curl -s http://atlas.stratus.local:21000/api/atlas/admin/status
```

Expected: Atlas reports active or ready status.

Open `http://atlas.stratus.local:21000` and confirm the UI is reachable.

---

## 8. Ranger Configuration

Ranger stores policy definitions, users/groups, service definitions, and audit configuration. Increment 6 configures Ranger Admin and usersync, then connects Trino to Ranger for enforcement.

### Start Ranger PostgreSQL

Run on `governance.stratus.local`:

```bash
podman run -d \
  --name ranger-postgres \
  --hostname ranger-postgres \
  --network host \
  -e POSTGRES_USER=ranger \
  -e POSTGRES_PASSWORD="$STRATUS_RANGER_DB_PASSWORD" \
  -e POSTGRES_DB=ranger \
  -v /data/ranger/postgres:/var/lib/postgresql/data:z \
  --restart unless-stopped \
  ${RANGER_POSTGRES_IMAGE}
```

### Ranger Admin environment

Create `/etc/stratus/ranger/ranger-admin.env`:

```bash
# /etc/stratus/ranger/ranger-admin.env

RANGER_DB_HOST=localhost
RANGER_DB_PORT=5432
RANGER_DB_NAME=ranger
RANGER_DB_USER=ranger
RANGER_DB_PASSWORD=<ranger-db secret from approved secret store>

RANGER_ADMIN_USER=admin
RANGER_ADMIN_PASSWORD=<bootstrap secret from approved secret store>
RANGER_HTTP_PORT=6080
```

### Start Ranger Admin

```bash
podman run -d \
  --name ranger-admin \
  --hostname ranger.stratus.local \
  --network host \
  --env-file /etc/stratus/ranger/ranger-admin.env \
  -v /etc/stratus/ranger:/etc/ranger/conf:ro,z \
  -v /data/ranger/admin:/data/ranger/admin:z \
  -v /data/ranger/audit:/data/ranger/audit:z \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  stratus/ranger-admin:2.8.0
```

### Start Ranger usersync

For Increment 6, usersync may load local users and groups from a static file. Increment 7 replaces this with FreeIPA LDAP usersync.

Create `/etc/stratus/ranger/usersync-users.csv`:

```csv
user,groups
platform_admin,platform-admins
platform_engineer,platform-engineers
analyst_crm,analysts-crm
analyst_restricted,restricted-data-users
```

Start usersync:

```bash
podman run -d \
  --name ranger-usersync \
  --hostname ranger-usersync.stratus.local \
  --network host \
  --env-file /etc/stratus/ranger/ranger-admin.env \
  -v /etc/stratus/ranger:/etc/ranger/conf:ro,z \
  -v /data/ranger/audit:/data/ranger/audit:z \
  --restart unless-stopped \
  stratus/ranger-usersync:2.8.0
```

### Verify Ranger

```bash
curl -s -u "${RANGER_ADMIN_USER}:${RANGER_ADMIN_PASSWORD}" \
  http://ranger.stratus.local:6080/service/public/v2/api/service
```

Expected: a JSON response. Open `http://ranger.stratus.local:6080` and log in with the bootstrap admin user. Increment 7 replaces local/bootstrap authentication with FreeIPA-backed identity.

---

## 9. Atlas Type Model

Atlas needs a small Stratus-specific type model so Iceberg tables, namespaces, pipeline runs, and quality runs can be represented consistently.

### Entity types

| Type | Description |
|---|---|
| `stratus_iceberg_catalog` | Polaris catalog, normally `stratus` |
| `stratus_iceberg_namespace` | Iceberg namespace such as `bronze`, `silver`, `gold`, `platform` |
| `stratus_iceberg_table` | Iceberg table entity |
| `stratus_pipeline_run` | Airflow DAG run or platform pipeline run |
| `stratus_quality_check_run` | quality check execution for a dataset |

### Required table attributes

| Attribute | Description |
|---|---|
| `qualifiedName` | globally unique table name, e.g. `stratus.silver.verification_customers` |
| `name` | table name |
| `catalog` | Polaris catalog |
| `namespace` | Iceberg namespace |
| `zone` | bronze / silver / gold / platform |
| `domain` | data domain |
| `owner` | owning team or service |
| `steward` | accountable data steward |
| `sourceSystem` | source system name |
| `schemaVersion` | logical schema version |
| `qualityStatus` | passed / failed / warning / unknown |
| `qualityRunId` | latest quality run id |
| `icebergSnapshotId` | latest observed Iceberg snapshot id |

### Classification types

Create these classifications:

| Classification | Purpose |
|---|---|
| `PII` | personally identifiable information |
| `CONFIDENTIAL` | confidential business data |
| `RESTRICTED` | restricted operational or regulated data |
| `QUALITY_FAILED` | dataset currently failing blocking quality checks |

### Register type definitions

Create a type definition file in the repository:

```text
governance/atlas/stratus-atlas-types.json
```

Register it:

```bash
curl -s -u "${STRATUS_ATLAS_USERNAME}:${STRATUS_ATLAS_PASSWORD}" \
  -X POST http://atlas.stratus.local:21000/api/atlas/v2/types/typedefs \
  -H "Content-Type: application/json" \
  --data @governance/atlas/stratus-atlas-types.json
```

Atlas exposes REST endpoints for types, entities, classification, discovery, and lineage. The verification suite in §13 uses those APIs directly.

---

## 10. Metadata Publication Contract

Increment 6 introduces a metadata publication contract used by Spark jobs, Airflow DAGs, and verification utilities. The publisher may be implemented as a Java library, a small command-line tool, or an Airflow task. The contract is what matters.

### Dataset registration

Every governed Iceberg table must be registered in Atlas with:

- table entity
- namespace entity
- catalog entity
- schema attributes
- owner
- steward
- domain
- lifecycle zone
- current quality status
- latest Iceberg snapshot id

### Lineage publication

Lineage must cover:

| Edge | Example |
|---|---|
| source to bronze | `external:verification/customers.csv` → `stratus.bronze.verification_customers` |
| bronze to silver | `stratus.bronze.verification_customers` → `stratus.silver.verification_customers` |
| silver to gold | `stratus.silver.verification_customers` → `stratus.gold.verification_customer_summary` |
| quality run to dataset | `quality_check_run` → checked table |
| pipeline run to output | `pipeline_run` → produced table |

### Quality metadata update

After each quality run, Atlas must reflect:

| Atlas attribute | Source |
|---|---|
| `qualityStatus` | aggregate result from `platform.quality_check_results` |
| `qualityRunId` | Airflow or Spark run id |
| `qualityCheckedAt` | latest check timestamp |
| `qualityBlockingFailures` | count of blocking failed checks |

### Failure rule

Metadata publication failure should fail the pipeline after data write only when the dataset is being promoted to a governed consumer-facing zone. For bronze replay or internal validation runs, publication failure may alert and retry, but it must not be silent.

---

## 11. Ranger Policy Model

Ranger policies are enforced first through Trino because Trino is the shared query plane introduced in Increment 5. Spark and Flink policy enforcement can be added later after identity hardening.

### Ranger service

Create a Ranger service named:

```text
stratus_trino
```

The service points to the Trino coordinator and is used by the Trino Ranger plugin to download and evaluate policies.

### Baseline policies

| Policy | Resource | Group/User | Access |
|---|---|---|---|
| Trino query execution | query `*` | all verified users | execute |
| Trino self impersonation | user `{USER}` | all verified users | impersonate |
| Platform admins | catalog `stratus`, all schemas/tables | `platform-admins` | all |
| Platform engineers | catalog `stratus`, all schemas/tables | `platform-engineers` | select, show, create where needed |
| Analysts CRM | `stratus.silver.crm_*`, `stratus.gold.crm_*` | `analysts-crm` | select, show |
| Quality visibility | `stratus.platform.quality_check_results` | `platform-engineers`, `analysts-crm` | select, show |
| Bronze restricted | `stratus.bronze.*` | analysts | deny select |
| PII deny | tagged/classified resources with `PII` | users not in `restricted-data-users` | deny select |
| PII allow | tagged/classified resources with `PII` | `restricted-data-users` | select, show |

The exact Ranger UI resource names depend on the Trino service definition. The policy intent above is the contract that verification must prove.

### Audit requirements

Ranger audit must record:

- allowed Trino query for a permitted gold or silver table
- denied Trino query for a restricted bronze table
- denied Trino query for a PII-classified table by an unauthorized user
- allowed Trino query for the same PII-classified table by an authorized user

Audit records should include user, resource, access type, result, timestamp, and policy id.

---

## 12. Trino Ranger Plugin Configuration

Configure the Trino coordinator to use Ranger access control.

### Access control properties

Create `/etc/stratus/ranger/trino/access-control.properties`:

```properties
access-control.name=ranger
ranger.service.name=stratus_trino
ranger.plugin.config.resource=ranger-trino-security.xml,ranger-trino-audit.xml,ranger-policymgr-ssl.xml
```

Mount this file into the Trino coordinator at:

```text
/etc/trino/access-control.properties
```

### Ranger security configuration

Create `/etc/stratus/ranger/trino/ranger-trino-security.xml`:

```xml
<?xml version="1.0"?>
<configuration>
  <property>
    <name>ranger.plugin.trino.policy.rest.url</name>
    <value>http://ranger.stratus.local:6080</value>
  </property>
  <property>
    <name>ranger.plugin.trino.service.name</name>
    <value>stratus_trino</value>
  </property>
  <property>
    <name>ranger.plugin.trino.access.cluster.name</name>
    <value>stratus-lab</value>
  </property>
  <property>
    <name>ranger.plugin.trino.use.rangerGroups</name>
    <value>true</value>
  </property>
  <property>
    <name>ranger.plugin.trino.use.only.rangerGroups</name>
    <value>true</value>
  </property>
</configuration>
```

### Ranger audit configuration

Create `/etc/stratus/ranger/trino/ranger-trino-audit.xml`:

```xml
<?xml version="1.0"?>
<configuration>
  <property>
    <name>xasecure.audit.is.enabled</name>
    <value>true</value>
  </property>
  <property>
    <name>xasecure.audit.destination.file</name>
    <value>true</value>
  </property>
  <property>
    <name>xasecure.audit.destination.file.filename</name>
    <value>/data/ranger/audit/trino-ranger-audit.log</value>
  </property>
</configuration>
```

### Ranger SSL configuration

Create `/etc/stratus/ranger/trino/ranger-policymgr-ssl.xml`:

```xml
<?xml version="1.0"?>
<configuration>
  <!-- Increment 6 uses lab HTTP. Increment 7 replaces this with TLS. -->
</configuration>
```

### Restart Trino coordinator

After mounting the Ranger plugin configuration into the Trino coordinator, restart the coordinator:

```bash
sudo systemctl restart stratus-trino.service
```

Verify policy download in coordinator logs:

```bash
podman logs trino-coordinator | grep -i ranger
```

---

## 13. Java Verification Suite

The Java source and Maven dependencies in this section are build inputs only. The approved build system publishes the executable verifier as a pinned container image. Operators execute that image and do not build on the verification host or inside the verification container.

The verification suite uses Atlas REST APIs, Ranger REST APIs, and Trino JDBC to validate governance behavior.

### Maven dependencies

Add to `pom.xml` if they are not already present:

```xml
<dependency>
    <groupId>io.trino</groupId>
    <artifactId>trino-jdbc</artifactId>
    <version>482</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.17.1</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <version>4.2.1</version>
    <scope>test</scope>
</dependency>
```

### Configuration

| Variable | Description |
|---|---|
| `STRATUS_ATLAS_BASE_URL` | e.g. `http://atlas.stratus.local:21000` |
| `STRATUS_ATLAS_USERNAME` | Atlas API username |
| `STRATUS_ATLAS_PASSWORD` | Atlas API password |
| `STRATUS_RANGER_BASE_URL` | e.g. `http://ranger.stratus.local:6080` |
| `STRATUS_RANGER_USERNAME` | Ranger API username |
| `STRATUS_RANGER_PASSWORD` | Ranger API password |
| `STRATUS_TRINO_JDBC_URL` | e.g. `jdbc:trino://trino-coordinator.stratus.local:8080/stratus` |
| `STRATUS_TRINO_ALLOWED_USER` | user expected to query non-sensitive gold data |
| `STRATUS_TRINO_DENIED_USER` | user expected to be denied PII data |
| `STRATUS_TRINO_RESTRICTED_USER` | user expected to access PII data |

### Verification test class

Place in `src/test/java/dev/mars/stratus/governance/GovernanceVerificationTest.java`:

```java
package dev.mars.stratus.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Properties;

import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GovernanceVerificationTest {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @Order(1)
    void atlasReachable() throws Exception {
        JsonNode response = atlasGet("/api/atlas/admin/status");
        assertThat(response.toString())
            .as("Atlas must return status JSON")
            .isNotBlank();
    }

    @Test
    @Order(2)
    void rangerReachable() throws Exception {
        JsonNode response = rangerGet("/service/public/v2/api/service");
        assertThat(response.isArray())
            .as("Ranger service API must return a service array")
            .isTrue();
    }

    @Test
    @Order(3)
    void verificationTablesRegisteredInAtlas() throws Exception {
        JsonNode response = atlasGet(
            "/api/atlas/v2/search/basic?typeName=stratus_iceberg_table&query=verification_customers");

        assertThat(response.toString())
            .as("Atlas search must find verification Iceberg tables")
            .contains("verification_customers");
    }

    @Test
    @Order(4)
    void atlasLineageExistsForGoldSummary() throws Exception {
        JsonNode search = atlasGet(
            "/api/atlas/v2/search/basic?typeName=stratus_iceberg_table&query=verification_customer_summary");

        String guid = search.at("/entities/0/guid").asText();
        assertThat(guid).as("Gold table entity guid must exist").isNotBlank();

        JsonNode lineage = atlasGet("/api/atlas/v2/lineage/" + guid + "?direction=BOTH&depth=3");
        assertThat(lineage.toString())
            .as("Lineage graph must include upstream verification customer tables")
            .contains("verification_customers");
    }

    @Test
    @Order(5)
    void piiClassificationApplied() throws Exception {
        JsonNode response = atlasGet(
            "/api/atlas/v2/search/basic?typeName=stratus_iceberg_table&query=verification_customers");

        assertThat(response.toString())
            .as("PII classification should be visible on the verification table or columns")
            .contains("PII");
    }

    @Test
    @Order(6)
    void allowedUserCanQueryGoldTable() throws Exception {
        long count = queryAs(
            System.getenv("STRATUS_TRINO_ALLOWED_USER"),
            "SELECT count(*) FROM stratus.gold.verification_customer_summary");

        assertThat(count)
            .as("Allowed user must be able to query non-sensitive gold data")
            .isGreaterThanOrEqualTo(1L);
    }

    @Test
    @Order(7)
    void deniedUserCannotQueryPiiTable() {
        assertThatExceptionOfType(SQLException.class)
            .as("Unauthorized user must be denied access to PII data")
            .isThrownBy(() -> queryAs(
                System.getenv("STRATUS_TRINO_DENIED_USER"),
                "SELECT email FROM stratus.silver.verification_customers"));
    }

    @Test
    @Order(8)
    void restrictedUserCanQueryPiiTable() throws Exception {
        long count = queryAs(
            System.getenv("STRATUS_TRINO_RESTRICTED_USER"),
            "SELECT count(email) FROM stratus.silver.verification_customers");

        assertThat(count)
            .as("Restricted user must be able to query PII data")
            .isGreaterThanOrEqualTo(1L);
    }

    @Test
    @Order(9)
    void rangerAuditContainsAllowAndDeny() throws Exception {
        JsonNode response = rangerGet("/service/assets/accessAudit");
        assertThat(response.toString())
            .as("Ranger audit must contain Trino allow and deny events")
            .contains("trino")
            .contains("DENIED");
    }

    static JsonNode atlasGet(String path) throws Exception {
        return get(System.getenv("STRATUS_ATLAS_BASE_URL"), path,
            System.getenv("STRATUS_ATLAS_USERNAME"),
            System.getenv("STRATUS_ATLAS_PASSWORD"));
    }

    static JsonNode rangerGet(String path) throws Exception {
        return get(System.getenv("STRATUS_RANGER_BASE_URL"), path,
            System.getenv("STRATUS_RANGER_USERNAME"),
            System.getenv("STRATUS_RANGER_PASSWORD"));
    }

    static JsonNode get(String baseUrl, String path, String username, String password) throws Exception {
        String token = Base64.getEncoder().encodeToString(
            (username + ":" + password).getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Authorization", "Basic " + token)
            .GET()
            .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
            .as("GET %s%s must succeed: %s", baseUrl, path, response.body())
            .isBetween(200, 299);
        return JSON.readTree(response.body());
    }

    static long queryAs(String user, String sql) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        try (var connection = DriverManager.getConnection(
                 System.getenv("STRATUS_TRINO_JDBC_URL"), properties);
             var statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).as("Query must return a row").isTrue();
            return rs.getLong(1);
        }
    }
}
```

### Running the verification suite

```bash
export STRATUS_ATLAS_BASE_URL=http://atlas.stratus.local:21000
export STRATUS_ATLAS_USERNAME=admin
export STRATUS_ATLAS_PASSWORD=<bootstrap secret from approved secret store>
export STRATUS_RANGER_BASE_URL=http://ranger.stratus.local:6080
export STRATUS_RANGER_USERNAME=admin
export STRATUS_RANGER_PASSWORD=<bootstrap secret from approved secret store>
export STRATUS_TRINO_JDBC_URL=jdbc:trino://trino-coordinator.stratus.local:8080/stratus
export STRATUS_TRINO_ALLOWED_USER=analyst_crm
export STRATUS_TRINO_DENIED_USER=analyst_crm
export STRATUS_TRINO_RESTRICTED_USER=analyst_restricted

export STRATUS_GOVERNANCE_VERIFIER_IMAGE=registry.stratus.local/stratus/governance-verifier:<version>@sha256:<digest>
podman run --rm --env-file /etc/stratus/verifiers/governance.env \
  -v /data/stratus/evidence/increment6:/evidence:z \
  ${STRATUS_GOVERNANCE_VERIFIER_IMAGE}
```

All nine tests must pass before Increment 6 is considered complete.

---

## 14. Operational Checks

Once the verification suite passes, perform these additional checks before signing off Increment 6.

### Atlas UI

Open `http://atlas.stratus.local:21000`. Confirm:
- verification Iceberg tables are searchable
- table entities include owner, steward, domain, zone, and quality status
- `PII` classification is visible where applied
- lineage graph shows source → bronze → silver → gold

### Ranger UI

Open `http://ranger.stratus.local:6080`. Confirm:
- `stratus_trino` service exists
- users and groups are visible
- baseline policies exist
- audit tab shows Trino allow and deny events

### Trino policy enforcement

Allowed query:

```bash
trino --server http://trino-coordinator.stratus.local:8080 \
  --user analyst_crm \
  --execute "SELECT count(*) FROM stratus.gold.verification_customer_summary"
```

Denied query:

```bash
trino --server http://trino-coordinator.stratus.local:8080 \
  --user analyst_crm \
  --execute "SELECT email FROM stratus.silver.verification_customers"
```

Expected: first query succeeds; second query fails because `email` is classified as PII or the table is tagged PII.

### Ranger audit log

```bash
podman exec ranger-admin tail -50 /data/ranger/audit/trino-ranger-audit.log
```

Expected: recent allow and deny records for the test queries.

### Metadata publication after Airflow run

Trigger a small Airflow verification DAG and confirm Atlas updates:
- latest `qualityStatus`
- latest `qualityRunId`
- latest `icebergSnapshotId`
- lineage edge from pipeline run to output table

---

## 15. Implementation Task Track

These tasks execute `P1-6.1` through `P1-6.5`; evidence belongs under `evidence/phase1/increment6/<task-id>/`.

| ID | Parent | Track | Task and definition of done | Owner | Depends on | Deliverable/path | Verification/evidence | Gate | Accepted by | Blocker/risk | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `P1-6.1-S1` | `P1-6.1` | Shared | Build, lock, scan, and publish Atlas/Ranger images, plugins, models, and verifiers. | Build owner | P1-5 developer gate | `docker/atlas/`; `docker/ranger/`; model modules | digests, SBOMs, startup smoke | D1-D4, P1 | Platform owner | Non-official image build | Not started |
| `P1-6.1-D1` | `P1-6.1` | Developer | Deploy idempotent Atlas developer dependencies and service. | Governance owner | `P1-6.1-S1` | `deploy/dev/atlas/` | lifecycle, health, entity CRUD | D1-D6 | Platform owner | Resource footprint | Not started |
| `P1-6.2-D1` | `P1-6.2` | Developer | Deploy Ranger, usersync fixture, Trino integration, and baseline policies. | Security owner | `P1-6.1-S1` | `deploy/dev/ranger/`; policies | allow/deny and audit tests | D7-D11 | Security owner | Plugin compatibility | Not started |
| `P1-6.3-D1` | `P1-6.3` | Developer | Implement dataset registration, lineage publication, quality status, classifications, retry/idempotency, and reconciliation. | Governance owner | `P1-6.1-D1`, `P1-6.2-D1` | `services/governance/`; type definitions | entity/lineage/quality test reports | D12-D18 | Data owner | Event ordering | Not started |
| `P1-6.5-V1` | `P1-6.5` | Developer | Run integrated Atlas/Ranger positive, negative, and failure regression. | QA owner | `P1-6.3-D1` | verifier reports | JUnit, audit, retry and policy evidence | D19-D20 | Governance owner | Test identity setup | Not started |
| `P1-6.1-P1` | `P1-6.1` | Production | Deploy production HBase, SolrCloud/ZooKeeper, notification Kafka, and redundant Atlas with backup/restore. | Governance/operations owners | `P1-6.1-S1` | `deploy/prod/atlas/`; runbooks | dependency failure, Atlas recovery, restore | P1-P3 | Operations owner | Multi-service capacity | Not started |
| `P1-6.2-P1` | `P1-6.2` | Production | Deploy production Ranger/Admin/usersync with external DB, TLS, identity, policy backup, and HA posture. | Security owner | `P1-6.1-S1`, Increment 7 controls | `deploy/prod/ranger/` | auth, sync, policy export/restore | P2-P4 | Security owner | Identity source availability | Not started |
| `P1-6.3-P1` | `P1-6.3` | Production | Migrate developer governance state/contracts to external dependencies and rerun lineage/policy regression. | Governance owner | `P1-6.1-P1`, `P1-6.2-P1` | migration/promotion runbook | before/after reconciliation, rollback rehearsal | P3-P5 | Data owner | Migration data loss | Not started |
| `P1-6.5-R1` | `P1-6.5` | Production | Exercise dependency, publisher, policy, backup/restore, observability, and reconciliation failures. | Operations owner | `P1-6.3-P1` | `runbooks/governance/` | timed drills, alerts, defects/reruns | P4-P6 | Platform owner | Maintenance window | Not started |
| `P1-6.G-D` | `P1-6` | Developer | Accept D1-D20. | Governance owner | `P1-6.5-V1` | developer gate record | gate/evidence matrix | D1-D20 | Platform owner | Open defect | Not started |
| `P1-6.G-P` | `P1-6` | Production | Accept P1-P6 with production dependencies and promotion evidence. | Platform owner | `P1-6.5-R1` | production gate record | gate/evidence matrix | P1-P6 | Security/operations owners | Open production defect | Not started |

## 16. Completion Gates

### Developer gate

The developer gate is complete when the functional checklist below passes against the disposable topology. Embedded Atlas dependencies and local Ranger users are permitted only here.

- [ ] **D1** - Atlas container running and managed by systemd on `atlas.stratus.local`
- [ ] **D2** - Ranger PostgreSQL container running and managed by systemd on `ranger.stratus.local`
- [ ] **D3** - Ranger Admin container running and managed by systemd on `ranger.stratus.local`
- [ ] **D4** - Ranger usersync container running and managed by systemd
- [ ] **D5** - Atlas UI and REST API reachable on port 21000
- [ ] **D6** - Ranger UI and REST API reachable on port 6080
- [ ] **D7** - Stratus Atlas type definitions registered
- [ ] **D8** - Atlas contains entities for verification bronze, silver, gold, and platform Iceberg tables
- [ ] **D9** - Atlas table entities include owner, steward, domain, zone, schema, quality status, and latest snapshot id
- [ ] **D10** - Atlas lineage shows source → bronze → silver → gold for the verification pipeline
- [ ] **D11** - `PII`, `CONFIDENTIAL`, `RESTRICTED`, and `QUALITY_FAILED` classifications exist
- [ ] **D12** - A verification dataset or column is classified as `PII`
- [ ] **D13** - Ranger `stratus_trino` service exists
- [ ] **D14** - Ranger baseline Trino policies exist for platform admins, engineers, analysts, quality visibility, bronze restriction, and PII access
- [ ] **D15** - Trino coordinator is configured with Ranger access control
- [ ] **D16** - Authorized Trino query against a non-sensitive gold table succeeds
- [ ] **D17** - Unauthorized Trino query against a PII-classified table or column is denied
- [ ] **D18** - Authorized restricted user can query the same PII-classified resource
- [ ] **D19** - Ranger audit records show both allow and deny events
- [ ] **D20** - `GovernanceVerificationTest` passes against the live platform

When the developer gate is checked, Increment 7 engineering can begin.

### Production gate

- [ ] **P1** - Atlas 2.5.0 uses external HBase, SolrCloud/ZooKeeper, and an external Kafka notification service; no `berkeleyje`, embedded Solr, or embedded notification setting is active.
- [ ] **P2** - Atlas application availability matches the approved RTO/RPO design and failover has been exercised.
- [ ] **P3** - HBase, SolrCloud, Atlas types/entities/glossary/classifications, and notification configuration have coordinated backup and restore evidence.
- [ ] **P4** - Ranger 2.8.0 uses a durable, backed-up PostgreSQL service and durable audit destination.
- [ ] **P5** - FreeIPA/Keycloak identities, LDAPS, trusted HTTPS, managed secrets, and service-specific authorization replace local users and bootstrap credentials.
- [ ] **P6** - The developer functional checklist passes unchanged against production, followed by failure, restore, capacity, and security-negative tests.

Only this production gate can mark Increment 6 accepted in the Phase 1 gate tracker.

---

## 17. Troubleshooting

### Atlas starts but search returns no entities

- Confirm Stratus type definitions were registered
- Confirm the metadata publisher ran after the Spark or Airflow verification pipeline
- Check Atlas logs for type validation or entity write failures
- Search by fully qualified name, for example `stratus.silver.verification_customers`

### Atlas lineage graph is empty

- Confirm process or pipeline-run entities were created
- Confirm relationships connect input and output table entities
- Confirm the lineage API is called with the output table GUID and sufficient depth
- Check whether metadata publication failed after the Spark job completed

### Ranger UI is reachable but Trino ignores policies

- Confirm `/etc/trino/access-control.properties` exists inside the Trino coordinator container
- Confirm `access-control.name=ranger`
- Confirm `ranger.service.name` matches the Ranger service name exactly
- Check Trino coordinator logs for Ranger policy download errors
- Confirm the Trino coordinator can reach `http://ranger.stratus.local:6080`

### All Trino queries are denied

- Confirm required query execution and self-impersonation policies exist
- Confirm the user exists in Ranger
- Confirm usersync assigned the expected groups
- Confirm the policy applies to catalog `stratus`

### PII query is allowed when it should be denied

- Confirm the Atlas classification was applied to the table or column
- Confirm Ranger has a tag/classification policy for `PII`
- Confirm policy priority and deny conditions
- Confirm the query user is not a member of `restricted-data-users`
- Check Ranger audit records to see which policy allowed the request

### Ranger audit logs are missing

- Confirm `xasecure.audit.is.enabled=true`
- Confirm audit destination is configured
- Confirm the audit path is writable by the Trino or Ranger plugin process
- Check Trino coordinator logs for audit sink errors

### Metadata publisher leaks secrets into Atlas

- Stop the publisher and inspect the payload mapping
- Atlas entities must not contain Ceph RGW secrets, Polaris client secrets, keytabs, or JDBC passwords
- Rotate any leaked credential and remove the attribute from Atlas

---

## 18. References

- Apache Atlas: https://atlas.apache.org/
- Apache Atlas REST API: https://atlas.apache.org/api/v2/index.html
- Apache Ranger: https://ranger.apache.org/
- Trino Ranger access control: https://trino.io/docs/current/security/ranger-access-control.html
- Trino Iceberg connector: https://trino.io/docs/current/connector/iceberg.html
- Trino documentation: https://trino.io/docs/current/
- Stratus Phase 1 implementation plan: [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)
- Stratus architecture: [stratus_on_prem_data_fabric_architecture.md](stratus_on_prem_data_fabric_architecture.md)
- Increment 1 — Ceph object storage foundation: [increment1_ceph.md](increment1_ceph.md)
- Increment 2 — Iceberg and Polaris: [increment2_iceberg_polaris.md](increment2_iceberg_polaris.md)
- Increment 3 — Spark: [increment3_spark.md](increment3_spark.md)
- Increment 4 — Airflow: [increment4_airflow.md](increment4_airflow.md)
- Increment 5 — Trino: [increment5_trino.md](increment5_trino.md)
