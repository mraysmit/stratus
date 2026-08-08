# Stratus Increment 2 — Iceberg Tables and Polaris Catalog

## 1. Purpose

This document is the technical implementation plan for Increment 2 of the Stratus platform as defined in [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md).

Increment 2 delivers Apache Polaris as the central REST catalog and Apache Iceberg as the table format over the Ceph RGW storage layer established in Increment 1. When this increment is complete, Iceberg tables exist in all platform zones, Polaris manages their metadata, table maintenance operations work via the Iceberg Java API, and the `platform.quality_check_results` table exists and accepts writes. A Java verification suite confirms the table layer is ready for Spark in Increment 3.

**Prerequisite:** All five Ceph RGW buckets must exist and the Increment 1 developer/lab gate must pass before developer-track work starts. Production acceptance additionally requires the Increment 1 production gate.

**Track rule:** A dependency marked `complete` in this document means complete in the same track. A prior developer gate unblocks engineering; a prior production gate is required for production acceptance.

---

## 2. Assumptions and Prerequisites

- Increment 1 complete in the target track — Ceph RGW cluster running, buckets and service accounts in place
- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- Podman 5.8.2 installed on the Polaris host, or a newer approved stable patch after regression testing
- JDK 25 and Maven 3.9.16 on the approved build worker; the verification host requires only the approved container runtime and verifier runtime inputs
- DNS resolution: `polaris.stratus.local` resolves to the Polaris host
- `svc-polaris` S3 credentials from Increment 1 are available

### Reference documentation audit

Reference baseline: 2026-07-10.

The current Apache Polaris documentation line lists Polaris 1.5.0. This increment therefore uses a pinned Polaris 1.5.0 image and Iceberg 1.11.0 Java dependencies, aligned with the Spark 4.1 target in Increment 3. Before implementation, verify the exact Polaris, Iceberg, Spark, and Trino versions together and update all increment documents as a set if any upstream release changes the compatibility matrix.

Polaris quickstart-style examples are developer bootstrap guidance, not the active Stratus deployment pattern. Increment 2 uses a production-ready catalog topology: external catalog metadata store, hardened credentials, TLS trusted by all engines, pinned artifacts, catalog audit logging, and a tested backup/restore path.

### Known upstream incompatibilities (verified 2026-08-04)

Live verification of Polaris 1.5.0 against Ceph Tentacle 20.2.2 surfaced
these; re-test each on any upgrade of either product:

- **Credential vending is blocked by an IAM dialect mismatch.** Polaris's
  AssumeRole session policy includes `kms:DescribeKey`; RGW's policy parser
  rejects any non-S3 action, failing STS with 400. RGW STS itself works
  (enabled in the developer harness with per-identity roles, proven to
  exactly this parse error). The developer catalog therefore uses Polaris's
  S3-compatible `stsUnavailable: true` mode with scoped static credentials;
  the production vending decision remains open until the dialects reconcile.
- **Purge-drops are doubly gated.** `DROP TABLE ... PURGE` requires the
  per-catalog property `polaris.config.drop-with-purge.enabled=true` and an
  explicit `CATALOG_MANAGE_CONTENT` grant — the auto-created `catalog_admin`
  role manages metadata only. The developer bootstrap applies both.
- **Namespace locations are structured.** Custom namespace locations are
  disabled by default; each namespace must live under
  `<allowedLocation>/<namespace>/` (see section 7 — Stratus conforms rather
  than disabling the check).
- **JVM trust pitfalls.** A `javax.net.ssl.trustStore` override replaces the
  default CA set (build tooling then loses Maven Central unless the override
  extends a copy of `cacerts`), and modern `keytool` creates PKCS12 by
  default, which yields zero trust anchors on a passwordless read — lab
  truststores must be explicit JKS. The harness scripts encode both rules.

The developer-harness README
(`platform/polaris/compose-service/README.md`) carries the operator-facing
version of this table.

---

## 3. Topology

Polaris runs as the central Iceberg REST catalog on a dedicated host or approved service placement. The active Increment 2 topology uses an approved external metadata store for catalog state. Embedded H2 is permitted only for disposable developer validation and cannot satisfy the Increment 2 completion gate.

The external metadata store must be one supported by the selected Polaris release and approved for the environment. It must have a named owner, backup schedule, restore procedure, retention policy, monitoring, and an HA/failover posture or explicit RTO/RPO exception before Increment 2 can unblock downstream engines.

```text
  ┌─────────────────────────────────────────┐
  │  Polaris REST Catalog API :8181 (TLS)   │
  │  catalog authn/authz + table commits    │
  └─────────────────────────────────────────┘
           │
           │ catalog state
           │ namespaces, principals, roles,
           │ table identifiers, metadata locations
           ▼
  ┌─────────────────────────────────────────┐
  │  Approved external metadata store       │
  │  backup + restore + monitoring + HA     │
  └─────────────────────────────────────────┘
           │
           │ Iceberg metadata locations point to
           ▼
  ┌─────────────────────────────────────────┐
  │  Ceph RGW endpoint (Increment 1)        │
  │  data files, metadata files, manifests  │
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

Use the approved CA chain established in Increment 1. A local CA is acceptable only for disposable developer validation; representative shared-lab and production runs must use a CA trusted by Polaris clients without `-k` or `--insecure`.

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

## 6. Polaris Production Configuration

### Catalog metadata store

Create or allocate the approved external metadata store before starting Polaris. This may be a PostgreSQL-compatible database or another metadata-store backend explicitly supported by the selected Polaris release and accepted by the platform architecture decision.

The implementation record must capture:

- metadata store product, version, endpoint, database/schema name, and owner
- service account used by Polaris, without recording secret values
- backup schedule, retention period, restore command, and last restore-test result
- HA/failover posture, RTO, RPO, and known operational limits
- monitoring signals for connectivity, latency, storage growth, lock/contention errors, failed commits, and authentication failures
- encryption, TLS, and credential-rotation procedure

Example preparation for a PostgreSQL-compatible metadata store:

```bash
# Example only. Use the approved database host and secret-management process.
create database polaris;
create user svc_polaris with password '<stored outside source control>';
grant all privileges on database polaris to svc_polaris;
```

### Environment file

Create `/etc/stratus/polaris.env` on the Polaris host:

```bash
# /etc/stratus/polaris.env

# Bootstrap credentials for the Polaris root principal
# Rotate immediately after bootstrap and store in the approved secret store
POLARIS_BOOTSTRAP_PRINCIPAL_NAME=stratus-root
POLARIS_BOOTSTRAP_PRINCIPAL_CREDENTIAL=<bootstrap secret from approved secret store>

# External catalog metadata store
# Exact property names must match the selected Polaris release and backend.
POLARIS_PERSISTENCE=external
POLARIS_METADATA_STORE_TYPE=<approved backend type>
POLARIS_METADATA_STORE_URI=<metadata store JDBC/API URI>
POLARIS_METADATA_STORE_USER=svc_polaris
POLARIS_METADATA_STORE_PASSWORD=<svc_polaris secret from approved secret store>

# Ceph RGW connection — used by Polaris to read/write Iceberg metadata files
CEPH_RGW_ENDPOINT=https://object-store.stratus.local
CEPH_RGW_ACCESS_KEY=svc-polaris
CEPH_RGW_SECRET_KEY=<svc-polaris secret from Increment 1>
S3_PATH_STYLE_ACCESS=true
```

### Run Polaris

```bash
podman run -d \
  --name polaris \
  --hostname polaris.stratus.local \
  --network host \
  --env-file /etc/stratus/polaris.env \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  apache/polaris:1.5.0 \
    --tls-certificate /etc/stratus/certs/polaris.crt \
    --tls-key /etc/stratus/certs/polaris.key
```

If a different Polaris release is approved, update this image tag and the Iceberg dependency versions in §9 together. Do not use `latest`.

### Developer-only in-memory persistence

Verified against the live `apache/polaris:1.5.0` image on 2026-08-03: this
release line has no embedded H2 backend. Its test-only metastore is
`in-memory` (Quarkus property `polaris.persistence.type`, environment
variable `POLARIS_PERSISTENCE_TYPE=in-memory`), which loses all catalog
state on restart and is flagged by the server's own production-readiness
check. The persistent backend for this release line is `relational-jdbc`
over PostgreSQL, owned by the production metadata-store task.

In-memory persistence may be used only for local command validation and
disposable developer tests. It is not a representative lab or production
topology. Do not use in-memory evidence to satisfy the Increment 2
production gate, Phase 1 readiness, backup/restore, HA, or recovery
evidence. Any result produced with this mode must be labelled
`developer-only` in the evidence record.

The developer harness at `platform/polaris/compose-service/` configures this
mode explicitly; bootstrap credentials use the verified
`polaris.bootstrap.credentials` property in `realm,client-id,client-secret`
form (environment variable `POLARIS_BOOTSTRAP_CREDENTIALS`). The
`svc-polaris` storage credentials are not copied into the harness: its
scripts pull them from the OpenBao secret store, where the Ceph
provisioning step publishes them (ADR-P1-004).

### Verify the container started

```bash
podman ps | grep polaris
podman logs polaris | tail -30
```

Look for `Polaris REST Catalog started` and the API listening on port 8181.

### Quick API health check

```bash
curl --cacert /etc/stratus/certs/ca.crt \
  https://polaris.stratus.local:8181/api/catalog/v1/config
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
export POLARIS_BOOTSTRAP_PRINCIPAL_CREDENTIAL=<bootstrap secret from approved secret store>

TOKEN=$(curl --cacert /etc/stratus/certs/ca.crt -s -X POST \
  https://polaris.stratus.local:8181/api/catalog/v1/oauth/tokens \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=stratus-root" \
  -d "client_secret=${POLARIS_BOOTSTRAP_PRINCIPAL_CREDENTIAL}" \
  -d "scope=PRINCIPAL_ROLE:ALL" \
  | jq -r '.access_token')

echo "Token acquired: ${TOKEN:0:20}..."
```

### Create the Stratus catalog

The payload shape below is verified against Polaris 1.5.0 (the developer
harness script `platform/polaris/compose-service/scripts/verify/polaris-compose-bootstrap-catalog.sh`
is the executable form): the request wraps a `catalog` object, the S3
endpoint and path-style flags are `storageConfigInfo` fields, and the
`s3.*` FileIO keys go in catalog `properties`. Static S3 credentials are
supplied to the serving layer, never embedded in the catalog record.

```bash
curl --cacert /etc/stratus/certs/ca.crt -s -X POST \
  https://polaris.stratus.local:8181/api/management/v1/catalogs \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "catalog": {
      "name": "stratus",
      "type": "INTERNAL",
      "properties": {
        "default-base-location": "s3://stratus-bronze",
        "s3.endpoint": "https://object-store.stratus.local:8443",
        "s3.path-style-access": "true"
      },
      "storageConfigInfo": {
        "storageType": "S3",
        "endpoint": "https://object-store.stratus.local:8443",
        "pathStyleAccess": true,
        "allowedLocations": [
          "s3://stratus-landing",
          "s3://stratus-bronze",
          "s3://stratus-silver",
          "s3://stratus-gold",
          "s3://stratus-platform"
        ]
      }
    }
  }'
```

### Create namespaces

Polaris disables custom namespace locations by default and requires each
namespace under `<allowedLocation>/<namespace>/` (verified against 1.5.0).
Stratus conforms to that safety rule instead of disabling it, so each zone's
data lives at `s3://stratus-<zone>/<zone>/`:

```bash
for NS in bronze silver gold platform; do
  curl --cacert /etc/stratus/certs/ca.crt -s -X POST \
    https://polaris.stratus.local:8181/api/catalog/v1/stratus/namespaces \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{
      \"namespace\": [\"${NS}\"],
      \"properties\": {
        \"location\": \"s3://stratus-${NS}/${NS}/\",
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
curl --cacert /etc/stratus/certs/ca.crt -s -X POST \
  https://polaris.stratus.local:8181/api/management/v1/principals \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "svc-spark", "type": "SERVICE"}'

# Create principal for Trino (read-only to queryable namespaces)
curl --cacert /etc/stratus/certs/ca.crt -s -X POST \
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

This table is defined in the architecture document (§5.3). In the developer harness it is provisioned idempotently by `platform/polaris/compose-service/scripts/verify/polaris-compose-bootstrap-catalog.sh` through the Iceberg REST create-table endpoint — provisioning lives in the re-runnable bootstrap path because the harness's Polaris 1.5.0 in-memory metastore loses all catalog state on restart. The documented shape is also pinned in Java (`QualityCheckResultsTableDefinition` in `stratus-catalog-verifier`), and the live conformance suite compares the deployed table against it (§9).

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

The Java source and Maven dependencies in this section are build inputs only. The approved build system publishes the executable verifier as a pinned container image. Operators execute that image and do not build on the verification host or inside the verification container.

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

<!-- The Iceberg REST catalog client (RESTCatalog) ships inside
     iceberg-core; there is no separate client artifact (verified against
     Iceberg 1.11.0 on Maven Central). Generic record reads and writes need
     iceberg-data plus iceberg-orc (the generic readers dispatch on ORC even
     for Parquet-only use), and Iceberg's Parquet$WriteBuilder constructor
     requires Hadoop's Configuration class (verified by removal: every write
     fails at Parquet.java:182 without it), supplied by the shaded
     hadoop-client-api/-runtime pair. All versions are owned by
     build-support/stratus-bom. -->
<dependency>
    <groupId>org.apache.iceberg</groupId>
    <artifactId>iceberg-data</artifactId>
    <version>1.11.0</version>
</dependency>
<dependency>
    <groupId>org.apache.iceberg</groupId>
    <artifactId>iceberg-orc</artifactId>
    <version>1.11.0</version>
</dependency>

<!-- Parquet support for reading and writing data files -->
<dependency>
    <groupId>org.apache.iceberg</groupId>
    <artifactId>iceberg-parquet</artifactId>
    <version>1.11.0</version>
</dependency>
<!-- Iceberg upstream S3 FileIO implementation, configured against Ceph RGW -->
<dependency>
    <groupId>org.apache.iceberg</groupId>
    <artifactId>iceberg-aws</artifactId>
    <version>1.11.0</version>
</dependency>
<dependency>
    <groupId>org.apache.iceberg</groupId>
    <artifactId>iceberg-aws-bundle</artifactId>
    <version>1.11.0</version>
</dependency>
```

Do not independently pin older Parquet or S3 SDK transitive dependencies in this verifier. Iceberg 1.11.0 owns that compatibility set through its modules and bundle; any security-driven override is tested with the full Increment 2 suite and recorded in the dependency lock/SBOM.

### Configuration

The verification suite reads all connection details from environment variables. `CatalogVerifierConfig` validates them by name and fails before any network operation, so a missing or malformed value is reported as configuration rather than as a connection error.

| Variable | Required | Description |
|---|---|---|
| `STRATUS_CATALOG_INTEGRATION` | yes | `true` opts the live tests in. Without it the `catalog-integration` tests skip; under the `catalog-integration-tests` profile a missing value fails instead of skipping, so the profile can never pass silently |
| `STRATUS_POLARIS_URI` | yes | e.g. `https://polaris.stratus.local:8181/api/catalog`. Must be absolute HTTPS with no credentials, query, or fragment |
| `STRATUS_POLARIS_CLIENT_ID` | yes | Polaris principal client id |
| `STRATUS_POLARIS_CLIENT_SECRET` | yes | Polaris principal client secret |
| `STRATUS_POLARIS_CATALOG` | yes | Catalog name — `stratus`. Sent as the Iceberg `warehouse` property, which for a Polaris catalog carries the catalog name rather than a storage location |
| `CEPH_RGW_ENDPOINT` | yes | e.g. `https://object-store.stratus.local`. Must be an origin URL with no path, credentials, query, or fragment |
| `CEPH_RGW_ACCESS_KEY` | yes | `svc-polaris` access key |
| `CEPH_RGW_SECRET_KEY` | yes | `svc-polaris` secret key |
| `S3_PATH_STYLE_ACCESS` | no | Defaults to `true`, which Ceph RGW requires without per-bucket DNS |
| `STRATUS_POLARIS_ALLOW_HTTP` | no | Defaults to `false`. Set `true` only for a disposable development endpoint; HTTPS is otherwise mandatory |
| `CEPH_RGW_ALLOW_HTTP` | no | Defaults to `false`, with the same disposable-development meaning for the object-store endpoint |

`RestCatalogProperties` maps this configuration onto the Iceberg REST client. Two auth properties are set explicitly rather than inferred — `rest.auth.type=oauth2` and `oauth2-server-uri` — because inference logs a warning per connection and Iceberg's automatic token-endpoint fallback is deprecated for removal (apache/iceberg#10537). The client also sends `X-Iceberg-Access-Delegation: none`: the verifier supplies its own storage credentials and declines credential vending rather than asking the catalog to subscope.

### Suite structure

The suite is the `stratus-catalog-verifier` Maven module at `verification/catalog/`. Source is not reproduced here: the module is the authoritative form, and a copy in this document would drift from it. See [verification/catalog/README.md](../../verification/catalog/README.md) for the classpath findings behind the dependency set above.

| Class | Tree | Role |
|---|---|---|
| `CatalogVerifierConfig` | main | Immutable configuration record. Validates the environment by name and rejects non-HTTPS endpoints, embedded credentials, and malformed URLs before any network call. Redacts secrets in `toString` |
| `RestCatalogProperties` | main | Maps the configuration onto Iceberg REST client properties. Keeps Iceberg property keys as literals so the main tree carries no Iceberg dependency |
| `QualityCheckResultsTableDefinition` | main | The documented shape of `platform.quality_check_results` — columns, partition fields, and properties — in plain Java with no Iceberg dependency, pinned offline by a unit test and compared against the deployed table live |
| `LiveCatalog` | test | Shared entry point for the live tests. Enforces the opt-in switch, asserts the required environment under the profile, and connects the real `RESTCatalog` |
| `IcebergRestCatalogConformanceTest` | test | The ten live catalog conformance checks listed below |
| `QualityCheckResultsConformanceTest` | test | The four live checks against the permanent quality-results table |
| `CatalogVerificationLogging` | test | Verification-event logging, itself covered by an offline test so INFO/DEBUG behavior is proven rather than assumed |

There is no hand-written test double anywhere in the module. Every live check runs against the real Polaris service and the real Ceph RGW endpoint; the offline tests cover configuration, property mapping, the table definition, and logging only.

### Live conformance coverage

`IcebergRestCatalogConformanceTest` — eleven checks, tagged `catalog-integration`:

1. the four zone namespaces resolve through the catalog
2. zone namespaces carry the governed location and zone properties
3. a probe table is created, written, read back, and dropped inside the governed zone location
4. schema evolution adds a column in place without disturbing existing rows
5. a row leaving a required column null is rejected, and the rejected write neither advances the snapshot nor adds rows
6. the create/write/read/drop cycle holds in every data zone, not only bronze
7. a table with a complete attribute set reloads from the catalog unchanged
8. each partition writes to its own governed storage path
9. sort order survives the catalog round trip
10. superseded snapshots expire while the current snapshot stays readable
11. a forged principal credential is refused

`QualityCheckResultsConformanceTest` — four checks against the table the catalog bootstrap provisions:

1. the table exists with the documented fourteen-column schema in the governed platform location
2. it is partitioned by `zone` and by `checked_at` day
3. it declares the append-only marker in its table properties
4. it accepts a quality result record and serves it back through the catalog

The record written by check 4 is a genuine quality result of the conformance run and is retained — the table is an append-only audit trail, so the suite does not clean up after itself there. Probe tables created by the other suite are purge-dropped.

### Running the verification suite

Against the developer harnesses, use the wrapper. It supplies the environment, the live opt-in switch, and the CA truststore, so no manual export is required:

```bash
bash platform/polaris/compose-service/scripts/verify/polaris-compose-run-catalog-tests.sh
```

The wrapper selects the `catalog-integration-tests` profile, under which a missing opt-in switch or environment variable fails the run instead of skipping it. The offline tests run in the default build (`./mvnw clean verify`) and never touch a live endpoint.

For the production profile the same suite runs from the pinned verifier image, built and published by the approved build system. Operators execute the image and do not build on the verification host:

```bash
export STRATUS_ICEBERG_POLARIS_VERIFIER_IMAGE=registry.stratus.local/stratus/iceberg-polaris-verifier:<version>@sha256:<digest>
podman run --rm --env-file /etc/stratus/verifiers/iceberg-polaris.env \
  -v /data/stratus/evidence/increment2:/evidence:z \
  ${STRATUS_ICEBERG_POLARIS_VERIFIER_IMAGE}
```

Image publication is deferred under `P1-0.1` by owner direction, so the developer track currently runs the suite from the workstation build. That exception is recorded in the Phase 1 plan and does not extend to production acceptance.

All fifteen live checks must pass before Increment 2 is considered complete.

---

## 10. Operational Checks

Once the verification suite passes, perform these additional checks before signing off Increment 2.

### Confirm table metadata is stored in Ceph RGW

Iceberg metadata files (`.metadata.json`, manifest lists, manifests) should be visible in the bronze bucket:

```bash
rclone --ca-cert /etc/stratus/pki/stratus-ca.crt \
  lsf --recursive cephrgw:stratus-bronze/
```

The `cephrgw` rclone remote is the Ceph-specific operator client configured in Increment 1. Iceberg's upstream `S3FileIO` class and `iceberg-aws-bundle` artifact retain their official project names; they are client-library identifiers and do not imply an AWS deployment.

Expect to see:
- `metadata/` directory with `.metadata.json` and `.avro` manifest files
- `data/` directory with `.parquet` data files

This confirms Polaris is correctly directing Iceberg to write metadata and data into the approved Ceph RGW S3 endpoint.

### Confirm namespace properties in Polaris

```bash
TOKEN=$(curl --cacert /etc/stratus/certs/ca.crt -s -X POST \
  https://polaris.stratus.local:8181/api/catalog/v1/oauth/tokens \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=stratus-root&client_secret=${POLARIS_BOOTSTRAP_PRINCIPAL_CREDENTIAL}&scope=PRINCIPAL_ROLE:ALL" \
  | jq -r '.access_token')

curl --cacert /etc/stratus/certs/ca.crt -s \
  https://polaris.stratus.local:8181/api/catalog/v1/stratus/namespaces \
  -H "Authorization: Bearer $TOKEN" | jq .
```

All four namespaces (`bronze`, `silver`, `gold`, `platform`) must be listed.

### Confirm `platform.quality_check_results` table

```bash
curl --cacert /etc/stratus/certs/ca.crt -s \
  https://polaris.stratus.local:8181/api/catalog/v1/stratus/namespaces/platform/tables \
  -H "Authorization: Bearer $TOKEN" | jq .
```

The `quality_check_results` table must appear in the response.

---

## 11. Catalog Production Evidence

Increment 2 must produce production-readiness evidence for the catalog control plane before Increment 3 begins:

- approved external Polaris metadata store, with product/version, endpoint, schema/database, owner, backup schedule, retention, and restore procedure documented
- restore test proving catalog metadata, Iceberg metadata files, manifests, and object data can be recovered to a consistent point
- validation that restored Polaris resolves table identifiers to the expected Iceberg metadata locations in Ceph RGW
- catalog audit logging for namespace, table, principal, role, credential, and metadata-location changes
- metrics and alerts for authentication failure, commit failure, catalog latency, metadata-store connectivity, metadata-store latency, and storage growth
- HA/failover posture for Polaris and the metadata store, or a documented RTO/RPO exception accepted by platform operations
- credential vending if supported and approved, or an explicit service-credential model that prevents engines from bypassing the catalog and object-store policy contract
- rotation test for Polaris bootstrap/root credential, service principal credentials, and metadata-store credentials

---

## 12. Implementation Task Track

These child tasks are the execution source of truth for Phase 1 parents `P1-2.1` through `P1-2.6`. IDs must be used in issues, pull requests, evidence paths, and gate records. Evidence is stored under `evidence/phase1/increment2/<task-id>/`; valid states are `Not started`, `In progress`, `Blocked`, `Built`, `Verified`, and `Accepted`.

| ID | Parent | Track | Task and definition of done | Owner | Depends on | Deliverable/path | Verification/evidence | Gate | Accepted by | Blocker/risk | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `P1-2.2-S1` | `P1-2.2` | Shared | Lock Polaris, Iceberg, database, image, and client artifacts; done when CI publishes immutable artifacts and compatibility evidence. | Build owner | P1-1 developer gate | `platform/polaris/image/`; dependency lock; SBOM | Build, scan, provenance, digest, startup smoke | D1, P1-P2 | Platform owner | Upstream compatibility change | Not started |
| `P1-2.2-D1` | `P1-2.2` | Developer | Implement idempotent developer deployment and reset; done after two start/verify/stop cycles. | Platform owner | `P1-2.2-S1` | `platform/polaris/compose-service/`; scripts | Repeated lifecycle transcripts and health report | D1 | Platform owner | Two start/verify/stop cycles recorded 2026-08-03 against live `apache/polaris:1.5.0` (transcripts in `platform/polaris/compose-service/logs/`): fail-fast provider check per ADR-P1-003, verified `polaris.bootstrap.credentials` consumption without stdout echo, OAuth token issuance (HTTP 200), unauthenticated 401, idempotent shutdown and reset. TLS for `polaris.stratus.local` remains open ahead of any shared or representative use; digest pin belongs to `P1-2.2-S1` | Verified |
| `P1-2.3-D1` | `P1-2.3` | Developer | Bootstrap catalog, namespaces, Ceph locations, and scoped lab credentials; done when positive/negative access matches the documented policy. | Data-platform owner | `P1-2.2-D1`, P1-1 developer gate | `platform/polaris/compose-service/scripts/verify/polaris-compose-bootstrap-catalog.sh`; Ceph harness svc-polaris identity and bucket policies; `environments/developer/polaris/` | Namespace/location inventory and access tests | D1 | Security owner | Verified 2026-08-04: scoped `svc-polaris` RGW identity created by the Ceph harness with bucket policies on the five Stratus buckets only (positive write/list/delete probe passed; denied-bucket listing failed closed); `stratus` catalog and four zone namespaces bootstrapped idempotently over the live API with a forged-token 401 negative. Transcripts in both harness `logs/` directories. Engine principals (svc-spark, svc-trino) belong to their increments; Polaris-to-RGW TLS trust for table IO is wired under `P1-2.4` | Verified |
| `P1-2.4-V1` | `P1-2.4` | Developer | Create verification tables and run Java catalog/storage tests; done when create/read/write/evolution and quality-table checks pass. | QA owner | `P1-2.3-D1` | verifier tests and reports | JUnit, object inventory, metadata inspection | D1-D2 | Data-engineering owner | Verified 2026-08-06: `platform.quality_check_results` provisioned idempotently by the catalog bootstrap (14 columns, identity(zone)+day(checked_at) partitioning, append-only marker); live catalog conformance 9/9 including schema evolution and the quality-table schema/partition/write/read-back checks, proven red (4 failures with the table absent) then green after bootstrap; object inventory confirmed Parquet under `data/zone=platform/checked_at_day=.../` with Iceberg metadata in `stratus-platform`. Transcripts in `platform/polaris/compose-service/logs/`. **Addendum 2026-08-07:** a fifteenth check (`rejectsARowThatLeavesARequiredColumnNull`) was added to close the schema-enforcement row in the Phase 1 plan §5 verification table, which no existing check covered. **Closed 2026-08-08:** the fifteenth check ran live against the Ceph and Polaris harnesses and passed in a 15/15 suite, logging `Negative check confirmed check=required-column-null`; transcript `platform/polaris/compose-service/logs/catalog-conformance-tests-20260808T062350Z.log`. The check was also hardened the same day: its record was built inside the `assertThrows` lambda, so a failure before the write was attempted satisfied the assertion and the snapshot and row-count assertions then held vacuously — demonstrated by injecting an unrelated construction fault, which the original form reported as a PASS with a `Negative check confirmed` evidence line. The record is now built outside the lambda, which also establishes that the record API accepts a null in a required column and enforcement comes from the write path | Verified |
| `P1-2.1-P1` | `P1-2.1` | Production | Provision supported external PostgreSQL with TLS, backup, HA/RTO/RPO, and managed credentials. | Database owner | `P1-2.2-S1`, P1-1 production preparation | `platform/polaris/database/`; `environments/production/polaris/`; runbook | TLS connection, failover, backup/restore evidence | P1-P3 | Operations owner | Database capacity/support | Not started |
| `P1-2.2-P1` | `P1-2.2` | Production | Deploy redundant production Polaris services with trusted TLS, health routing, immutable image, and managed config. | Platform owner | `P1-2.1-P1` | `platform/polaris/`; `environments/production/polaris/` | Endpoint failover, config snapshot, digest check | P1-P5 | Operations owner | Load-balancer ownership | Not started |
| `P1-2.3-P1` | `P1-2.3` | Production | Apply service identities, least-privilege catalog roles, Ceph bindings, secret injection, and rotation. | Security owner | `P1-2.2-P1`, Increment 7 controls | `platform/polaris/config/`; `environments/production/polaris/`; policy records | Positive/negative authorization and rotation tests | P4-P7 | Data-platform owner | Final identity integration | Not started |
| `P1-2.5-D1` | `P1-2.5` | Developer | Verify metadata-driven maintenance decisions against the live catalog; done when the files, snapshots, manifests, delete-files, and orphan-file metadata tables are queried and a threshold decision is proven for each. | Data-platform owner | `P1-2.4-V1` | `verification/catalog/src/test/`; maintenance decision rules | Metadata-table query output, threshold decision per category, before/after object inventory | D1 | Data-engineering owner | Verified 2026-08-08: `MaintenanceAdvisor` reads the `files`, `manifests`, `delete_files`, and `snapshots` metadata tables and decides compaction, manifest rewrite, delete-file compaction, and snapshot expiry; `OrphanFileDetector` decides orphan files. Every threshold is proven in both directions on the same table, varying only the trigger, so a rule that always recommends cannot pass — and the small-file rule is additionally proven to read `file_size_in_bytes` rather than count rows (a one-byte target yields 0, not 3). Live catalog conformance 24/24. **Neither component has any destructive path**: the advisor cannot compact, rewrite, or expire, and the detector cannot delete. Each orphan-detection safeguard was proven load-bearing by disabling it against the live cluster — all-snapshot reachability (a manifest list becomes a false orphan), delete-manifest reading (the equality-delete file becomes a false orphan), and the age threshold (a file still being written is withheld). Transcripts in `platform/polaris/compose-service/logs/`. Wiring any action to these decisions belongs to `P1-2.5-P1` | Verified |
| `P1-2.5-P1` | `P1-2.5` | Production | Verify metadata-driven maintenance thresholds and safe snapshot/orphan behavior. | Data-platform owner | `P1-2.3-P1`, `P1-2.5-D1` | maintenance queries/runbook | Metadata queries, dry-run and applied-action evidence | P8-P9 | Data-engineering owner | Unsafe retention setting | Not started |
| `P1-2.6-R1` | `P1-2.6` | Production | Execute catalog/database/object consistency backup and restore; done when restored tables resolve to valid Ceph objects. | Operations owner | `P1-2.5-P1` | restore runbook and evidence | Timed restore, consistency queries, audit events | P10-P12 | Platform owner | Restore point mismatch | Not started |
| `P1-2.G-D` | `P1-2` | Developer | Accept developer gate after D1-D2 have accepted producing tasks. | Platform owner | `P1-2.4-V1` | developer gate record | Gate matrix and evidence index | D1-D2 | Data-platform owner | Open functional defect | Not started |
| `P1-2.G-P` | `P1-2` | Production | Run production regression and accept P1-P13 with no developer-only setting remaining. | Platform owner | `P1-2.6-R1`, Increment 7 controls | production gate/promotion record | Full verifier, resilience, observability and readiness evidence | P1-P13 | Architecture and operations owners | Open production defect | Not started |

### Developer-to-production promotion controls

This table is the promotion manifest that gate **D2** requires. Every developer-only condition in the Increment 2 harness is named here with the production task that replaces it and the condition under which promotion stops. A developer condition that is not listed has not been assessed and blocks the developer gate.

| Developer condition | Production replacement task | Rollback or stop condition |
|---|---|---|
| In-memory Polaris metastore (`POLARIS_PERSISTENCE_TYPE=in-memory`), which loses all catalog state on restart and is flagged by the server's own production-readiness check | `P1-2.1-P1`, then `P1-2.2-P1`; the persistent backend for this release line is `relational-jdbc` over PostgreSQL | no in-memory result may satisfy the production gate, backup/restore, HA, or recovery evidence; every result produced in this mode is labelled `developer-only` in the evidence record |
| Plain HTTP on the loopback port (`POLARIS_ALLOW_HTTP=true`), TLS wiring still open under `P1-2.2-D1` | `P1-2.2-P1` terminates trusted TLS for `polaris.stratus.local`; `P1-7.4` replaces the certificate with FreeIPA Dogtag-issued material | no shared or representative use until trusted TLS terminates; never satisfy a TLS check by relaxing client verification |
| Single Polaris container with no redundancy or health routing | `P1-2.2-P1` | do not claim an RTO/RPO or failover posture from a single-container topology; the production gate stays open until endpoint failover passes |
| Disposable bootstrap credential generated into `.env` (`stratus-root`, `polaris.bootstrap.credentials`) | `P1-2.3-P1` with Increment 7 controls | rotate after any real catalog bootstrap; never promote a harness credential, and never echo it to stdout |
| `svc-polaris` storage credentials pulled from dev-mode OpenBao, whose secrets are discarded on shutdown | `P1-2.3-P1` against the approved secret store (ADR-P1-004) with rotation | restore the prior approved credential reference if a policy regression appears; never copy a credential by hand into `.env` |
| Local lab CA material trusted by the verifier and the catalog for the Ceph RGW chain | `P1-7.4` | never fall back to an insecure client or a disabled trust check to make a run pass |
| Catalog and namespaces re-bootstrapped by script after every restart, because the metastore is not persistent | `P1-2.1-P1` supplies a persistent metastore; `P1-2.6-R1` supplies the restore path | a production catalog is never re-bootstrapped to recover state — that is a restore, and re-bootstrapping instead of restoring is a stop condition |
| Polaris pinned by tag `apache/polaris:1.5.0` with the observed digest recorded but not enforced | `P1-2.2-S1` publishes the immutable digest with scan, SBOM, and provenance | production runs by digest only; this clause shares the `P1-0.1` publication deferral and cannot be closed from a tag pin |
| Catalog verifier executed from the workstation build rather than a published image | `P1-2.2-S1` under `P1-0.1` | production acceptance requires the digest-qualified verifier image; workstation runs support developer evidence only |
| Engine principals `svc-spark` and `svc-trino` not yet created in Polaris | `P1-2.3-P1`, with each engine's own increment creating its principal | production gate P7 stays open until both exist with least-privilege catalog roles; no engine shares the root principal |

### Gate traceability rule

The gate identifiers below are normative. A gate checkbox may be marked complete only when every mapped task is `Accepted` and its evidence index resolves. `P1-2.G-D` and `P1-2.G-P` own the final checks; they do not create missing evidence on behalf of implementation tasks.

## 13. Completion Gates

### Developer gate

- [ ] **D1** - Disposable in-memory persistence mode starts/stops idempotently and the namespace, table, Iceberg metadata, Ceph RGW, and verifier conformance checks pass.
- [ ] **D2** - In-memory persistence, plain-HTTP loopback, local credentials, local CA material, and reduced topology are labelled developer-only in the promotion manifest.

The Polaris 1.5.0 release line has no embedded H2 backend; its test-only metastore is `in-memory` and its persistent backend is `relational-jdbc`. D1 and D2 name that mode directly — an H2 reading of either gate is not satisfiable against this release.

**Readiness.** Both gates have their producing evidence, and every producing task is `Verified`. This is the gate matrix and evidence index `P1-2.G-D` requires as its artifact.

| Gate | Producing task | Evidence | Verified |
|---|---|---|---|
| D1 | `P1-2.2-D1` | two start/verify/stop cycles, fail-fast provider check, OAuth 200 and unauthenticated 401, idempotent shutdown and reset; transcripts in `platform/polaris/compose-service/logs/` | 2026-08-03 |
| D1 | `P1-2.3-D1` | catalog and four zone namespaces, scoped `svc-polaris` RGW identity with bucket policies on the five Stratus buckets only, forged-token negative | 2026-08-04 |
| D1 | `P1-2.4-V1` | `platform.quality_check_results` provisioned idempotently (14 columns, `identity(zone)`+`day(checked_at)`, append-only); live catalog conformance including schema evolution and schema enforcement | 2026-08-06, schema-enforcement check run live 2026-08-08 |
| D1 | `P1-2.5-D1` | metadata-driven maintenance decisions read from the `files`, `manifests`, `delete_files`, and `snapshots` metadata tables, each threshold proven in both directions; orphan-file detection with every safeguard proven load-bearing by disabling it against the live cluster; no destructive path in either component | 2026-08-08 |
| D2 | promotion manifest above | ten developer conditions named with their production replacement task and stop condition | 2026-08-07 |

The live catalog conformance suite stands at **24 checks, all passing** against `apache/polaris:1.5.0` and live Ceph RGW; transcripts in `platform/polaris/compose-service/logs/`.

**What stands between that evidence and a tick.** Per the gate traceability rule the four producing tasks must reach `Accepted`, which is the platform owner's action under `P1-2.G-D` and is deliberately not taken on the owner's behalf. One clause also remains open — TLS for `polaris.stratus.local`, with `POLARIS_ALLOW_HTTP=true` in the harness template — which the owner either accepts as a recorded deferral against `P1-2.2-P1` and `P1-7.4`, as Increment 1 did for `P1-0.1`, or closes before the tick. No other functional item is outstanding.

### Production gate

Increment 2 is accepted when all of the following are true:

- [ ] **P1** - Polaris container running and managed by systemd on `polaris.stratus.local`
- [ ] **P2** - Polaris REST API responding at `https://polaris.stratus.local:8181` with TLS
- [ ] **P3** - Polaris uses the approved external metadata store (`relational-jdbc` over PostgreSQL); the test-only `in-memory` metastore is not used for completion evidence
- [ ] **P4** - Metadata-store backup, restore, monitoring, and HA/failover posture are documented and tested
- [ ] **P5** - `stratus` catalog created in Polaris
- [ ] **P6** - Four namespaces exist: `bronze`, `silver`, `gold`, `platform`
- [ ] **P7** - `svc-spark` and `svc-trino` principals created in Polaris with correct roles
- [ ] **P8** - `platform.quality_check_results` Iceberg table created with correct schema
- [ ] **P9** - `stratus-catalog-verifier` — all fifteen live checks (`IcebergRestCatalogConformanceTest` and `QualityCheckResultsConformanceTest`) pass against the production cluster from the published verifier image
- [ ] **P10** - Iceberg metadata files visible in Ceph RGW buckets through the approved S3 client
- [ ] **P11** - Restored Polaris resolves table identifiers to the same expected Iceberg metadata locations in Ceph RGW
- [ ] **P12** - Catalog audit logging and catalog/metadata-store alerts are configured
- [ ] **P13** - Polaris logs show no errors during the verification test run

The developer gate may unblock Increment 3 engineering. Only the production gate marks Increment 2 accepted in the Phase 1 tracker.

---

## 14. Troubleshooting

### Polaris container exits on startup

```bash
podman logs polaris
```

Common causes:
- Certificate path mismatch — confirm the `--tls-certificate` and `--tls-key` paths match the volume mount
- Metadata-store connection failure — confirm endpoint, credentials, TLS trust, database/schema permissions, and network route
- Port 8181 already in use — `ss -tlnp | grep 8181`

### `401 Unauthorized` from Polaris API

- Confirm the client ID and secret match what was set in `POLARIS_BOOTSTRAP_PRINCIPAL_CREDENTIAL`
- Confirm the `scope` parameter is included in the token request: `scope=PRINCIPAL_ROLE:ALL`
- Check that the token has not expired (default TTL is typically 1 hour)

### Iceberg cannot write to Ceph RGW

- Confirm `s3.path-style-access=true` is set unless virtual-hosted bucket access has been explicitly validated for the environment
- Confirm the `svc-polaris` credentials have write access to the target bucket in Ceph RGW
- Confirm the Ceph RGW endpoint in Polaris storage config matches the running Ceph cluster
- Test Ceph RGW access directly: `aws --endpoint-url https://object-store.stratus.local s3 ls s3://stratus-bronze/`

### `NoSuchTableException` in verification test

- The table was not created — check that test order 3 (create table) passed before test order 4 (write)
- Confirm the namespace exists in Polaris before attempting to create a table in it

### Verification test runs but parquet read returns zero rows

- The Iceberg snapshot may not have been committed — ensure `.commit()` was called after `newAppend()`
- Confirm the FileIO properties (Ceph RGW endpoint, credentials) are correctly set — they are built by `RestCatalogProperties` from `CatalogVerifierConfig`, so check the environment variables listed in §9 first

---

## 15. References

- Apache Polaris documentation: https://polaris.apache.org/
- Apache Polaris GitHub: https://github.com/apache/polaris
- Apache Iceberg Java API: https://iceberg.apache.org/docs/latest/java-api-quickstart/
- Iceberg REST Catalog spec: https://iceberg.apache.org/docs/latest/rest-catalog/
- Iceberg Parquet writer: https://iceberg.apache.org/docs/latest/api/
- Ceph Tentacle RGW S3 API compatibility: https://docs.ceph.com/en/tentacle/radosgw/s3/
- Stratus Phase 1 implementation plan: [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)
- Stratus architecture: [stratus_on_prem_data_fabric_architecture.md](../architecture/stratus_on_prem_data_fabric_architecture.md)
- Increment 1 — Ceph object storage foundation: [ceph_storage.md](ceph_storage.md)
