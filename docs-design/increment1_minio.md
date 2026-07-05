# Stratus Increment 1 — MinIO Storage Foundation

## 1. Purpose

This document is the technical implementation plan for Increment 1 of the Stratus platform as defined in [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md).

Increment 1 delivers a multi-node distributed MinIO object storage cluster running on Podman containers across a lab cluster. When this increment is complete, all five platform buckets exist, TLS is enforced, service account credentials are isolated, and a Java verification suite confirms the storage layer is ready for Iceberg and Polaris in Increment 2.

---

## 2. Assumptions and Prerequisites

- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- Podman 4.x installed on each node
- DNS resolution works between nodes (either via `/etc/hosts` or a local DNS server)
- Nodes can reach each other on the ports defined in §4
- Sudo access on each node for initial setup
- The Java verification module requires JDK 21+ and Maven 3.9+

### Reference documentation audit

Reference baseline: 2026-07-05.

The current MinIO documentation now presents the Linux/container deployment path under MinIO AIStor, and the Apache Polaris MinIO guide explicitly warns that MinIO OSS is in maintenance mode and intended only for local or test use in that context. This increment can still use MinIO-compatible S3 semantics for a lab foundation, but implementation must make an explicit product/support decision before any production-like deployment:

- use a pinned, approved MinIO/AIStor image or internally mirrored artifact
- do not use a floating `latest` tag
- verify current distributed deployment, container, TLS, and access policy commands against the selected release documentation
- record whether the deployment is lab-only MinIO OSS or a supported production object-storage target

---

## 3. Cluster Topology

A distributed MinIO cluster requires a minimum of four nodes for erasure coding. This plan uses four nodes.

| Node | Hostname | Role |
|---|---|---|
| Node 1 | `minio1.stratus.local` | MinIO server |
| Node 2 | `minio2.stratus.local` | MinIO server |
| Node 3 | `minio3.stratus.local` | MinIO server |
| Node 4 | `minio4.stratus.local` | MinIO server |

Each node contributes one or more drives. MinIO recommends a minimum of four drives per node in production — for a lab cluster, one dedicated directory per node is acceptable for initial setup.

```text
minio1  ──┐
minio2  ──┤  MinIO distributed cluster (erasure-coded, 4 nodes)
minio3  ──┤
minio4  ──┘
           │
           ▼
    Load balancer / DNS alias
    minio.stratus.local:9000   ← S3 API
    minio.stratus.local:9001   ← Console UI
```

For a lab without a hardware load balancer, a simple round-robin DNS entry or a lightweight Nginx reverse proxy on any node is sufficient.

---

## 4. Ports

| Port | Purpose |
|---|---|
| 9000 | MinIO S3 API (TLS) |
| 9001 | MinIO Console UI (TLS) |

All inter-node MinIO communication also uses port 9000. Ensure these ports are open between all four nodes and from any client host.

---

## 5. TLS Certificates

MinIO requires TLS certificates. For Increment 1 use self-signed certificates. These will be replaced with FreeIPA Dogtag-issued certificates in Increment 7.

### Generate a self-signed CA and per-node certificates

On a dedicated admin machine or on `minio1`:

```bash
mkdir -p ~/stratus-certs && cd ~/stratus-certs

# Generate CA key and certificate
openssl genrsa -out ca.key 4096
openssl req -new -x509 -days 3650 -key ca.key -out ca.crt \
  -subj "/CN=Stratus Lab CA/O=Stratus/C=US"

# Generate a certificate for each node
for NODE in minio1 minio2 minio3 minio4; do
  openssl genrsa -out ${NODE}.key 2048
  openssl req -new -key ${NODE}.key -out ${NODE}.csr \
    -subj "/CN=${NODE}.stratus.local/O=Stratus/C=US"
  openssl x509 -req -days 3650 \
    -in ${NODE}.csr \
    -CA ca.crt -CAkey ca.key -CAcreateserial \
    -extfile <(printf "subjectAltName=DNS:%s.stratus.local,DNS:minio.stratus.local,IP:127.0.0.1" "$NODE") \
    -out ${NODE}.crt
done
```

Distribute to each node:

```bash
for NODE in minio1 minio2 minio3 minio4; do
  ssh ${NODE}.stratus.local "mkdir -p /etc/stratus/certs"
  scp ${NODE}.key ${NODE}.crt ca.crt \
    ${NODE}.stratus.local:/etc/stratus/certs/
done
```

---

## 6. Storage Preparation

On each node, create the data directory that MinIO will use. For a lab with one drive per node:

```bash
# Run on each node
sudo mkdir -p /data/minio
sudo chown $USER:$USER /data/minio
```

For a production-grade lab with multiple drives per node, mount each drive separately under `/data/minio1`, `/data/minio2` etc. and adjust the Podman volume mounts in §7 accordingly.

---

## 7. Podman Container Configuration

### Environment file

Create `/etc/stratus/minio.env` on each node. Replace `NODE_N_IP` with the actual IP addresses of each node.

```bash
# /etc/stratus/minio.env

# Root credentials — change before any non-lab use
MINIO_ROOT_USER=stratus-admin
MINIO_ROOT_PASSWORD=change-me-before-use

# Distributed cluster peers
MINIO_VOLUMES="https://minio{1...4}.stratus.local:9000/data/minio"

# TLS
MINIO_OPTS="--certs-dir /etc/stratus/certs --console-address :9001"

# Site name for observability
MINIO_SITE_NAME=stratus-lab
```

### Run MinIO on each node

Run the following on each node. The container name, hostname, and volume paths are the same on every node — MinIO's distributed mode uses the `MINIO_VOLUMES` peer list to differentiate nodes.

```bash
podman run -d \
  --name minio \
  --hostname $(hostname) \
  --network host \
  --env-file /etc/stratus/minio.env \
  -v /data/minio:/data/minio:z \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  quay.io/minio/minio:<approved-version> server \
    --certs-dir /etc/stratus/certs \
    --console-address ":9001" \
    ${MINIO_VOLUMES}
```

Replace `<approved-version>` with the specific MinIO or AIStor image tag approved for the environment. The tag must be recorded in the implementation runbook and kept consistent across all MinIO nodes.

`--network host` is used here so MinIO nodes can communicate with each other directly by hostname without Podman network address translation complexity. For environments where host networking is restricted, a Podman network with DNS can be configured instead.

### Verify the container started

```bash
podman ps | grep minio
podman logs minio | tail -20
```

Look for `API: https://...` and `Console: https://...` in the logs. If MinIO reports `Waiting for all MinIO nodes to be online`, the other nodes have not yet started — start all four nodes before MinIO begins serving requests.

### Auto-start with systemd

Generate a systemd unit from the running container on each node:

```bash
podman generate systemd --new --name minio \
  | sudo tee /etc/systemd/system/stratus-minio.service

sudo systemctl daemon-reload
sudo systemctl enable --now stratus-minio.service
```

---

## 8. Bucket and Service Account Setup

Once all four nodes are running and the cluster is healthy, connect to any node to perform initial setup. The MinIO client (`mc`) is the standard tool for this.

### Install the MinIO client

```bash
curl -O https://dl.min.io/client/mc/release/linux-amd64/mc
chmod +x mc
sudo mv mc /usr/local/bin/mc
```

### Configure the client

```bash
mc alias set stratus https://minio1.stratus.local:9000 \
  stratus-admin change-me-before-use \
  --insecure   # remove --insecure once CA cert is trusted on this machine
```

To trust the CA certificate instead of using `--insecure`:

```bash
sudo cp ~/stratus-certs/ca.crt /etc/pki/ca-trust/source/anchors/stratus-ca.crt
sudo update-ca-trust
```

### Create platform buckets

```bash
mc mb stratus/stratus-landing
mc mb stratus/stratus-bronze
mc mb stratus/stratus-silver
mc mb stratus/stratus-gold
mc mb stratus/stratus-platform
```

### Create service accounts

One service account per platform service. These are MinIO access key / secret key pairs scoped to specific buckets.

```bash
# Spark service account — read/write all data buckets
mc admin user add stratus svc-spark $(openssl rand -base64 32)

# Polaris service account — read/write all data buckets (catalog metadata)
mc admin user add stratus svc-polaris $(openssl rand -base64 32)

# Airflow service account — read landing zone only (job triggering)
mc admin user add stratus svc-airflow $(openssl rand -base64 32)

# Trino service account — read queryable Iceberg zones and platform quality metadata
mc admin user add stratus svc-trino $(openssl rand -base64 32)
```

### Create and apply access policies

Create policy files then apply them:

```bash
# svc-spark: read/write landing, bronze, silver, gold, platform
cat > /tmp/policy-spark.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject","s3:PutObject","s3:DeleteObject","s3:ListBucket"],
      "Resource": [
        "arn:aws:s3:::stratus-landing/*",
        "arn:aws:s3:::stratus-landing",
        "arn:aws:s3:::stratus-bronze/*",
        "arn:aws:s3:::stratus-bronze",
        "arn:aws:s3:::stratus-silver/*",
        "arn:aws:s3:::stratus-silver",
        "arn:aws:s3:::stratus-gold/*",
        "arn:aws:s3:::stratus-gold",
        "arn:aws:s3:::stratus-platform/*",
        "arn:aws:s3:::stratus-platform"
      ]
    }
  ]
}
EOF
mc admin policy create stratus policy-spark /tmp/policy-spark.json
mc admin policy attach stratus policy-spark --user svc-spark

# svc-polaris: read/write all data and platform buckets
mc admin policy attach stratus policy-spark --user svc-polaris

# svc-airflow: read landing zone only
cat > /tmp/policy-airflow.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject","s3:ListBucket"],
      "Resource": [
        "arn:aws:s3:::stratus-landing/*",
        "arn:aws:s3:::stratus-landing"
      ]
    }
  ]
}
EOF
mc admin policy create stratus policy-airflow /tmp/policy-airflow.json
mc admin policy attach stratus policy-airflow --user svc-airflow

# svc-trino: read bronze for internal verification, silver/gold for query serving,
# and platform for quality result visibility. Analyst access is still enforced
# through Trino/Ranger in later increments.
cat > /tmp/policy-trino.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject","s3:ListBucket"],
      "Resource": [
        "arn:aws:s3:::stratus-bronze/*",
        "arn:aws:s3:::stratus-bronze",
        "arn:aws:s3:::stratus-silver/*",
        "arn:aws:s3:::stratus-silver",
        "arn:aws:s3:::stratus-gold/*",
        "arn:aws:s3:::stratus-gold",
        "arn:aws:s3:::stratus-platform/*",
        "arn:aws:s3:::stratus-platform"
      ]
    }
  ]
}
EOF
mc admin policy create stratus policy-trino /tmp/policy-trino.json
mc admin policy attach stratus policy-trino --user svc-trino
```

Store all generated credentials in a secrets file on a secure admin host. These will be migrated to a secrets manager in a later increment.

---

## 9. Java Verification Module

The verification suite is a Java module in the Stratus repository that confirms the storage layer meets the requirements of Increment 1. It uses the AWS S3 SDK (compatible with MinIO's S3 API) and runs as a self-contained Maven test suite.

### Maven dependency

Add to `pom.xml`:

```xml
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

The verification suite reads MinIO connection details from environment variables so credentials are never committed to source control:

| Variable | Description |
|---|---|
| `STRATUS_MINIO_ENDPOINT` | e.g. `https://minio1.stratus.local:9000` |
| `STRATUS_MINIO_ACCESS_KEY` | root or service account access key |
| `STRATUS_MINIO_SECRET_KEY` | corresponding secret key |

### Verification test class

Place in `src/test/java/dev/mars/stratus/storage/MinioVerificationTest.java`:

```java
package dev.mars.stratus.storage;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MinioVerificationTest {

    static final List<String> REQUIRED_BUCKETS = List.of(
        "stratus-landing",
        "stratus-bronze",
        "stratus-silver",
        "stratus-gold",
        "stratus-platform"
    );

    static S3Client s3;

    @BeforeAll
    static void connect() {
        String endpoint  = System.getenv("STRATUS_MINIO_ENDPOINT");
        String accessKey = System.getenv("STRATUS_MINIO_ACCESS_KEY");
        String secretKey = System.getenv("STRATUS_MINIO_SECRET_KEY");

        assertThat(endpoint).as("STRATUS_MINIO_ENDPOINT must be set").isNotBlank();
        assertThat(accessKey).as("STRATUS_MINIO_ACCESS_KEY must be set").isNotBlank();
        assertThat(secretKey).as("STRATUS_MINIO_SECRET_KEY must be set").isNotBlank();

        s3 = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)))
            .region(Region.US_EAST_1)       // MinIO ignores region but SDK requires one
            .forcePathStyle(true)           // required for MinIO
            .build();
    }

    @Test
    @Order(1)
    void allRequiredBucketsExist() {
        Set<String> existing = s3.listBuckets().buckets().stream()
            .map(Bucket::name)
            .collect(Collectors.toSet());

        assertThat(existing)
            .as("All five platform buckets must exist")
            .containsAll(REQUIRED_BUCKETS);
    }

    @Test
    @Order(2)
    void canWriteToEachBucket() {
        for (String bucket : REQUIRED_BUCKETS) {
            String key = "verification/write-test.txt";
            assertThatNoException()
                .as("Write to bucket %s must succeed", bucket)
                .isThrownBy(() -> s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromString("stratus-verification", StandardCharsets.UTF_8)
                ));
        }
    }

    @Test
    @Order(3)
    void canReadBackFromEachBucket() {
        for (String bucket : REQUIRED_BUCKETS) {
            String key = "verification/write-test.txt";
            var response = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build());

            assertThat(response.asUtf8String())
                .as("Read-back from bucket %s must return written content", bucket)
                .isEqualTo("stratus-verification");
        }
    }

    @Test
    @Order(4)
    void serviceAccountIsolationLandingOnly() {
        // Verify svc-airflow credentials can only access stratus-landing
        String airflowKey    = System.getenv("STRATUS_MINIO_AIRFLOW_ACCESS_KEY");
        String airflowSecret = System.getenv("STRATUS_MINIO_AIRFLOW_SECRET_KEY");
        String endpoint      = System.getenv("STRATUS_MINIO_ENDPOINT");

        assumeThat(airflowKey).as("svc-airflow credentials not set — skipping isolation test").isNotBlank();

        S3Client airflowClient = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(airflowKey, airflowSecret)))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .build();

        // Should succeed: read landing
        assertThatNoException()
            .as("svc-airflow must be able to list stratus-landing")
            .isThrownBy(() -> airflowClient.listObjectsV2(
                ListObjectsV2Request.builder().bucket("stratus-landing").build()));

        // Should fail: write to bronze
        assertThatExceptionOfType(S3Exception.class)
            .as("svc-airflow must not be able to write to stratus-bronze")
            .isThrownBy(() -> airflowClient.putObject(
                PutObjectRequest.builder().bucket("stratus-bronze").key("test.txt").build(),
                RequestBody.fromString("should-be-denied", StandardCharsets.UTF_8)))
            .withMessageContaining("Access Denied");
    }

    @Test
    @Order(5)
    void tlsEnforced() {
        String endpoint = System.getenv("STRATUS_MINIO_ENDPOINT");
        assertThat(endpoint)
            .as("MinIO endpoint must use HTTPS — plaintext connections are not permitted")
            .startsWith("https://");
    }

    @AfterAll
    static void cleanup() {
        // Remove verification test objects written during the test run
        for (String bucket : REQUIRED_BUCKETS) {
            try {
                s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key("verification/write-test.txt")
                    .build());
            } catch (Exception ignored) { }
        }
        if (s3 != null) s3.close();
    }
}
```

### Running the verification suite

```bash
export STRATUS_MINIO_ENDPOINT=https://minio1.stratus.local:9000
export STRATUS_MINIO_ACCESS_KEY=stratus-admin
export STRATUS_MINIO_SECRET_KEY=change-me-before-use
export STRATUS_MINIO_AIRFLOW_ACCESS_KEY=<svc-airflow access key>
export STRATUS_MINIO_AIRFLOW_SECRET_KEY=<svc-airflow secret key>

mvn test -pl . -Dtest=MinioVerificationTest
```

All five tests must pass before Increment 1 is considered complete and Increment 2 begins.

---

## 10. Operational Checks

Once the verification suite passes, perform these additional operational checks before signing off Increment 1.

### Cluster health

```bash
mc admin info stratus
```

Expected output: all four nodes shown as `online`; drives shown as healthy; erasure coding status shown.

### Console access

Open `https://minio.stratus.local:9001` in a browser. Log in with the root credentials. Confirm:
- All five buckets are visible
- Bucket access policies are visible per bucket
- Server health shows green for all nodes

### Simulate node failure

Stop MinIO on one node:

```bash
# On minio4
sudo systemctl stop stratus-minio
```

Verify the cluster continues to serve read and write requests from the remaining three nodes:

```bash
mc cp /etc/hostname stratus/stratus-landing/failover-test.txt
mc cat stratus/stratus-landing/failover-test.txt
```

Both commands should succeed. Restart the stopped node and confirm it rejoins:

```bash
# On minio4
sudo systemctl start stratus-minio
mc admin info stratus   # minio4 should return to online
```

---

## 11. Completion Gate

Increment 1 is complete when all of the following are true:

- [ ] All four MinIO nodes are running as Podman containers managed by systemd
- [ ] Cluster shows all nodes online via `mc admin info stratus`
- [ ] All five buckets exist: `stratus-landing`, `stratus-bronze`, `stratus-silver`, `stratus-gold`, `stratus-platform`
- [ ] All four service accounts exist with correct access policies applied
- [ ] `MinioVerificationTest` — all five tests pass against the live cluster
- [ ] MinIO Console accessible via HTTPS on port 9001
- [ ] Single node failure test passes — cluster continues serving during one node outage
- [ ] TLS enforced — plaintext connection on port 9000 is rejected

When all gates are checked, Increment 2 (Iceberg and Polaris) can begin.

---

## 12. Troubleshooting

### Cluster does not form — nodes stuck waiting for peers

- Confirm all four hostnames resolve from each node: `ping minio2.stratus.local` from `minio1`
- Confirm port 9000 is open between nodes: `nc -zv minio2.stratus.local 9000`
- All four nodes must be started before MinIO begins serving — start them in quick succession

### TLS errors in logs

- Confirm the certificate SAN includes the node hostname and the shared alias (`minio.stratus.local`)
- Confirm the CA certificate is trusted on client machines: `update-ca-trust`
- Check certificate expiry: `openssl x509 -in /etc/stratus/certs/minio1.crt -noout -dates`

### `Access Denied` on bucket operations

- Confirm the service account policy is attached: `mc admin policy list stratus --user svc-spark`
- Confirm the bucket name in the policy matches exactly — MinIO bucket names are case-sensitive

### Podman container exits immediately

- Check logs: `podman logs minio`
- Common cause: data directory permissions — ensure `$USER` owns `/data/minio`
- Common cause: certificate path mismatch — confirm `-v /etc/stratus/certs:/etc/stratus/certs` mount path matches `--certs-dir` argument

---

## 13. References

- MinIO distributed deployment: https://min.io/docs/minio/linux/operations/install-deploy-manage/deploy-minio-multi-node-multi-drive.html
- MinIO Podman deployment: https://min.io/docs/minio/container/index.html
- MinIO access policy reference: https://min.io/docs/minio/linux/administration/identity-access-management/policy-based-access-control.html
- Apache Polaris MinIO guide: https://polaris.apache.org/guides/minio/
- AWS S3 SDK for Java: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/home.html
- Stratus Phase 1 implementation plan: [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)
- Stratus architecture: [on_prem_data_fabric_architecture.md](on_prem_data_fabric_architecture.md)
