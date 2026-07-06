# Stratus Increment 1 - Object Storage Foundation

## 1. Purpose

This document is the technical implementation plan for Increment 1 of the Stratus platform as defined in [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md).

Increment 1 delivers the object-storage foundation consumed by Polaris, Iceberg, Spark, Airflow, Trino, and later Flink. The durable platform requirement is not "install MinIO." The durable requirement is:

- a supported S3-compatible object-storage endpoint
- TLS enforced for all client and service traffic
- five required Stratus buckets
- isolated platform service credentials
- a repeatable verification suite proving bucket existence, S3 compatibility, credential isolation, and read/write behavior

MinIO OSS may be used for disposable developer and lab validation only when the intended production path is MinIO AIStor. If the production path is not AIStor, do not start with MinIO OSS; start Increment 1 against the actual selected storage target or its vendor-supported non-production equivalent.

---

## 2. Key Decision: Production Path First

### Current upstream warning

Reference baseline: 2026-07-05.

The Apache Polaris MinIO guide explicitly warns that its MinIO OSS example is for local testing only. It also states that MinIO OSS is in maintenance mode and that MinIO container images may no longer receive updates or security fixes.

Therefore, this plan does not treat MinIO OSS as the production storage product for Stratus.

### Production target

The baseline production target for Stratus Phase 1 is **MinIO AIStor**, the supported MinIO object-storage distribution, deployed as an on-prem multi-node, erasure-coded S3-compatible object-storage cluster.

This is an explicit design decision. Stratus keeps the S3 storage contract product-neutral enough to test and integrate cleanly, but the production implementation target for this plan is not an unnamed S3-compatible store. It is MinIO AIStor unless a later architecture decision record replaces it.

Production target summary:

| Decision area | Baseline choice |
|---|---|
| Product | MinIO AIStor |
| Deployment model | On-prem multi-node distributed object-storage cluster |
| API exposed to Stratus | S3-compatible API over HTTPS |
| Data protection | AIStor-supported erasure-coded topology, sized from vendor-supported node/drive guidance |
| Artifact discipline | approved AIStor image or artifact pinned by version and digest; no `latest` |
| Support model | vendor-supported or otherwise contractually supported AIStor deployment |
| Stratus endpoint name | `object-store.stratus.local` or environment-specific DNS alias |
| Lab compatibility target | MinIO OSS may emulate the API for disposable developer/lab validation only when AIStor remains the production target |

Non-baseline targets such as Ceph RGW, Dell ECS, NetApp StorageGRID, Cloudian, Apache Ozone S3 gateway, or an internally maintained MinIO-compatible artifact are not the default production path. They are replacement storage targets and require a superseding storage architecture decision record before use. That record must explain why MinIO AIStor is not being used and must prove the replacement target passes the same Stratus storage contract and downstream Polaris/Iceberg/Spark/Trino verification gates.

If a replacement storage target is selected, the lab should use that target's own development or non-production deployment path. A MinIO OSS lab is no longer representative in that case because it validates MinIO behavior, not the operational, security, policy, TLS, compatibility, and failure behavior of the replacement product.

### Decision record required

Before any shared, long-lived, regulated, or production-like environment is declared ready, create a storage decision record containing:

- selected object-storage product and edition
- support owner and support contract or internal ownership model
- approved version, appliance release, image tag, or image digest
- topology and capacity model
- TLS and certificate trust model
- identity and credential management model
- bucket, lifecycle, retention, encryption, backup, and recovery model
- monitoring, alerting, and log integration
- upgrade and patch process
- known deviations from the lab reference implementation
- verification evidence from the Java storage suite

Increment 1 lab completion can unblock Increment 2 engineering work. It does not by itself approve production dataset onboarding.

---

## 3. Stratus Storage Contract

Every storage target must satisfy this contract.

### Endpoint contract

| Requirement | Contract |
|---|---|
| API | S3-compatible object API |
| Transport | HTTPS only |
| Endpoint variable | `STRATUS_S3_ENDPOINT` |
| Path-style access | Required unless every downstream client has been verified with virtual-hosted style |
| Region | Use `us-east-1` for the baseline AIStor target unless a verified client configuration requires a different fixed value |
| CA trust | Clients must trust the issuing CA; routine commands must not require `--insecure` or `-k` |

### Bucket contract

| Bucket | Purpose |
|---|---|
| `stratus-landing` | raw source files and bounded external extracts before table ingestion |
| `stratus-bronze` | bronze Iceberg data and metadata |
| `stratus-silver` | silver Iceberg data and metadata |
| `stratus-gold` | gold Iceberg data and metadata |
| `stratus-platform` | platform-internal data such as quality results, Spark event logs, audit extracts, and maintenance metadata |

The bucket names are part of the Stratus platform contract. Alternative object-store products may expose these through different administrative tooling, but clients must see these exact S3 bucket names.

### Service credential contract

| Principal | Minimum object-storage access |
|---|---|
| `svc-spark` | read/write/delete landing, bronze, silver, gold, platform |
| `svc-polaris` | read/write/delete bronze, silver, gold, platform; landing access only if required by the selected Polaris storage configuration |
| `svc-airflow` | read landing; no write access to bronze/silver/gold |
| `svc-trino` | read bronze for internal verification, read silver/gold/platform for query serving and quality visibility |

These names describe platform service identities. The selected object store may implement them as IAM users, service accounts, access keys, LDAP-backed users, OIDC clients, or another product-specific mechanism. The access boundaries must remain equivalent.

### Security contract

- TLS is required on all S3 endpoints.
- Root/admin credentials must not be used by applications.
- Service credentials must be stored outside source control.
- Credential rotation must be documented before production readiness.
- Server-side encryption or equivalent at-rest protection must be configured for production-like environments according to the AIStor-supported model.
- Bucket policies are not the enterprise authorization layer for analytical users. User-facing data authorization is enforced later through Polaris, Trino, Ranger, and identity integration.

### Recovery and operations contract

Production-like storage must define:

- node, drive, or appliance failure tolerance
- backup or replication strategy for critical buckets and metadata
- restore procedure and restore-test cadence
- capacity alert thresholds
- service-level monitoring
- request/error metrics
- audit or access logging where supported

The lab reference implementation below exercises some failure behavior, but production recovery must be designed against the AIStor production topology or an approved replacement target.

---

## 4. Environments

| Environment | Purpose | Storage target |
|---|---|---|
| Developer | local command and client validation | optional Docker Compose MinIO OSS only for the AIStor path; otherwise use the selected target's dev image/appliance/sandbox |
| Lab | early integrated Stratus build and Increment 2 unblocker | AIStor non-production deployment, or MinIO OSS only as an AIStor API stand-in; replacement targets must use their own lab deployment |
| Production-like | shared, long-lived, regulated, performance, or pre-production validation | MinIO AIStor unless superseded by approved storage ADR |
| Production | real production dataset onboarding | MinIO AIStor with operational signoff unless superseded by approved storage ADR |

The rest of this document contains a production contract plus a lab reference implementation. Do not confuse the lab reference implementation with production approval.

---

## 5. Production Implementation Requirements

For production-like environments, implement MinIO AIStor using the approved AIStor runbook and vendor-supported topology. The Stratus-specific tasks are:

1. Publish a stable HTTPS S3 endpoint, for example `https://object-store.stratus.example:9000`.
2. Ensure client hosts, Polaris, Spark, Airflow, Trino, and later Flink trust the endpoint certificate chain.
3. Create the five required buckets.
4. Create or map the four platform service identities.
5. Apply equivalent least-privilege access policies.
6. Confirm path-style or virtual-hosted-style S3 behavior for all downstream clients.
7. Configure production-required encryption, retention, lifecycle, monitoring, alerting, audit logging, backup, and restore.
8. Run the Java verification suite against the production-like endpoint.
9. Record evidence in the storage decision record and operational readiness checklist.

If a superseding ADR selects a non-AIStor production target, skip the AIStor/MinIO container sections and replace them with that product's deployment runbook. Keep the bucket, identity, TLS, and verification sections.

---

## 6. Lab Reference Topology: MinIO / AIStor

This section is a reference implementation for the AIStor production path. Use a supported AIStor artifact if the lab is shared or long-lived. Use MinIO OSS only for disposable local or internal validation where the goal is to validate the AIStor/MinIO API shape before moving to supported AIStor.

If the production target is changed away from AIStor, skip this section and use the replacement product's own lab deployment. Do not use MinIO OSS as a generic stand-in for a non-MinIO production object store.

### Topology

The lab reference uses four nodes because distributed MinIO requires at least four nodes for erasure coding.

| Node | Hostname | Role |
|---|---|---|
| Node 1 | `minio1.stratus.local` | object-storage server |
| Node 2 | `minio2.stratus.local` | object-storage server |
| Node 3 | `minio3.stratus.local` | object-storage server |
| Node 4 | `minio4.stratus.local` | object-storage server |

```text
minio1  ----\
minio2  -----+--> distributed S3-compatible storage
minio3  -----+    endpoint alias: minio.stratus.local
minio4  ----/
```

Each node contributes one or more drives. For a disposable lab, one dedicated directory per node is acceptable. For a production-like AIStor/MinIO deployment, follow the supported drive and erasure-set layout for the approved product.

### Ports

| Port | Purpose |
|---|---|
| 9000 | S3 API over TLS |
| 9001 | MinIO Console over TLS |

All inter-node MinIO traffic also uses port `9000`.

---

## 7. Lab TLS Certificates

For lab use, generate a self-signed CA and per-node certificates. These are replaced later by the Phase 1 identity and PKI work, and they are not production certificate management.

On an admin machine:

```bash
mkdir -p ~/stratus-certs && cd ~/stratus-certs

openssl genrsa -out ca.key 4096
openssl req -new -x509 -days 3650 -key ca.key -out ca.crt \
  -subj "/CN=Stratus Lab CA/O=Stratus/C=US"

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

Install each node certificate using the filenames MinIO expects:

```bash
for NODE in minio1 minio2 minio3 minio4; do
  scp ${NODE}.key ${NODE}.crt ca.crt ${NODE}.stratus.local:/tmp/
  ssh ${NODE}.stratus.local "
    sudo mkdir -p /etc/stratus/certs/CAs
    sudo install -m 0600 /tmp/${NODE}.key /etc/stratus/certs/private.key
    sudo install -m 0644 /tmp/${NODE}.crt /etc/stratus/certs/public.crt
    sudo install -m 0644 /tmp/ca.crt /etc/stratus/certs/CAs/stratus-ca.crt
    rm -f /tmp/${NODE}.key /tmp/${NODE}.crt /tmp/ca.crt
  "
done
```

MinIO reads:

| File | Meaning |
|---|---|
| `/etc/stratus/certs/public.crt` | node certificate presented to clients and peers |
| `/etc/stratus/certs/private.key` | private key matching `public.crt` |
| `/etc/stratus/certs/CAs/stratus-ca.crt` | CA certificate trusted for peer connections |

---

## 8. Lab Storage Preparation

On each MinIO lab node:

```bash
sudo mkdir -p /data/minio
sudo chown $USER:$USER /data/minio
```

For a lab with multiple dedicated drives, mount each drive separately and update the MinIO peer path and volume mounts accordingly. Do not simulate production durability by placing multiple "drives" on the same underlying filesystem and calling that production-ready.

---

## 9. Lab Podman Configuration

This is the primary lab implementation: one container per node, managed by Podman and systemd. It is suitable for internal integration testing, not for production approval unless the AIStor support model and operations team explicitly adopt this exact model.

### Environment file

Create `/etc/stratus/minio.env` on each node:

```bash
# /etc/stratus/minio.env

MINIO_ROOT_USER=stratus-admin
MINIO_ROOT_PASSWORD=change-me-before-use
MINIO_SITE_NAME=stratus-lab
```

The root user and password are bootstrap credentials. They are used to create platform service identities and must not be used by applications.

### Run the container on each node

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
    "https://minio{1...4}.stratus.local:9000/data/minio"
```

Replace `<approved-version>` with the approved image tag or digest for the lab. Do not use `latest`.

Command notes:

- `--network host` makes the container bind directly to host ports `9000` and `9001`.
- `/data/minio` is the persistent object data path.
- `/etc/stratus/certs` is mounted read-only and contains the TLS files from §7.
- The peer list must be identical on all four nodes.
- `:z` applies a shared SELinux label on SELinux-enabled hosts.

### Verify startup

```bash
podman ps | grep minio
podman logs minio | tail -20
```

Look for HTTPS API and Console endpoints. If a node waits for peers, verify all four nodes are started, DNS resolves, port `9000` is open, certificates include the expected SANs, and every node uses the same peer list.

### Manage with systemd

Generate a service unit on each node:

```bash
podman generate systemd --new --name minio \
  | sudo tee /etc/systemd/system/stratus-minio.service

sudo systemctl daemon-reload
sudo systemctl enable --now stratus-minio.service
```

After enabling the unit, use systemd for routine operations:

```bash
sudo systemctl status stratus-minio
sudo systemctl restart stratus-minio
sudo journalctl -u stratus-minio -f
```

---

## 10. Optional Developer Docker Compose Topology

Docker Compose is included only as a single-host developer topology for the AIStor/MinIO path. It is useful for command validation and client testing when AIStor is the production target. It is not a deployment topology, not a resilience test, and not a substitute for the lab or production gates.

If a superseding ADR selects a non-AIStor production target, remove this Compose topology from the active implementation path and use that product's developer or non-production environment instead.

Use it to:

- validate container command syntax
- practice bucket and policy setup
- run the Java S3 verification suite against a disposable endpoint

### Prepare local directories and certificates

The Compose certificate files come from the lab certificates generated in §7. Copy and rename them into the filenames MinIO expects:

```bash
mkdir -p deploy/minio-compose/certs/minio{1..4}/CAs
mkdir -p deploy/minio-compose/data/minio{1..4}

for NODE in minio1 minio2 minio3 minio4; do
  cp ~/stratus-certs/${NODE}.crt deploy/minio-compose/certs/${NODE}/public.crt
  cp ~/stratus-certs/${NODE}.key deploy/minio-compose/certs/${NODE}/private.key
  cp ~/stratus-certs/ca.crt deploy/minio-compose/certs/${NODE}/CAs/stratus-ca.crt
done
```

For host-based clients, add a hosts-file entry mapping `minio1.stratus.local` to `127.0.0.1`. If the developer workflow must use `https://localhost:9000`, generate a developer-only certificate that includes `DNS:localhost`.

### Directory layout

```text
deploy/minio-compose/
  compose.yaml
  .env
  certs/
    minio1/public.crt
    minio1/private.key
    minio1/CAs/stratus-ca.crt
    minio2/public.crt
    minio2/private.key
    minio2/CAs/stratus-ca.crt
    minio3/public.crt
    minio3/private.key
    minio3/CAs/stratus-ca.crt
    minio4/public.crt
    minio4/private.key
    minio4/CAs/stratus-ca.crt
  data/
    minio1/
    minio2/
    minio3/
    minio4/
```

### `.env`

```bash
MINIO_IMAGE=quay.io/minio/minio:<approved-version>
MINIO_ROOT_USER=stratus-admin
MINIO_ROOT_PASSWORD=change-me-before-use
```

### `compose.yaml`

```yaml
services:
  minio1:
    image: ${MINIO_IMAGE}
    hostname: minio1.stratus.local
    command:
      - server
      - --certs-dir
      - /etc/stratus/certs
      - --console-address
      - ":9001"
      - https://minio{1...4}.stratus.local:9000/data/minio
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
      MINIO_SITE_NAME: stratus-compose-lab
    volumes:
      - ./data/minio1:/data/minio
      - ./certs/minio1:/etc/stratus/certs:ro
    ports:
      - "9000:9000"
      - "9001:9001"
    networks:
      stratus-minio:
        aliases:
          - minio1.stratus.local

  minio2:
    image: ${MINIO_IMAGE}
    hostname: minio2.stratus.local
    command:
      - server
      - --certs-dir
      - /etc/stratus/certs
      - --console-address
      - ":9001"
      - https://minio{1...4}.stratus.local:9000/data/minio
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
      MINIO_SITE_NAME: stratus-compose-lab
    volumes:
      - ./data/minio2:/data/minio
      - ./certs/minio2:/etc/stratus/certs:ro
    networks:
      stratus-minio:
        aliases:
          - minio2.stratus.local

  minio3:
    image: ${MINIO_IMAGE}
    hostname: minio3.stratus.local
    command:
      - server
      - --certs-dir
      - /etc/stratus/certs
      - --console-address
      - ":9001"
      - https://minio{1...4}.stratus.local:9000/data/minio
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
      MINIO_SITE_NAME: stratus-compose-lab
    volumes:
      - ./data/minio3:/data/minio
      - ./certs/minio3:/etc/stratus/certs:ro
    networks:
      stratus-minio:
        aliases:
          - minio3.stratus.local

  minio4:
    image: ${MINIO_IMAGE}
    hostname: minio4.stratus.local
    command:
      - server
      - --certs-dir
      - /etc/stratus/certs
      - --console-address
      - ":9001"
      - https://minio{1...4}.stratus.local:9000/data/minio
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
      MINIO_SITE_NAME: stratus-compose-lab
    volumes:
      - ./data/minio4:/data/minio
      - ./certs/minio4:/etc/stratus/certs:ro
    networks:
      stratus-minio:
        aliases:
          - minio4.stratus.local

networks:
  stratus-minio:
    name: stratus-minio
```

Start it:

```bash
docker compose up -d
docker compose logs -f minio1
```

Only `minio1` publishes host ports. Other nodes are reachable inside the Docker network through aliases.

---

## 11. Bucket and Identity Provisioning

Provision buckets and service identities on the AIStor production target or on the MinIO/AIStor lab reference target. The commands below use the MinIO client. If a superseding ADR selects another production storage product, use that product's equivalent administrative interface and preserve the same bucket names and access boundaries.

### Configure client access

For the lab reference:

```bash
mc alias set stratus https://minio1.stratus.local:9000 \
  stratus-admin change-me-before-use \
  --insecure
```

Remove `--insecure` after the CA is trusted:

```bash
sudo cp ~/stratus-certs/ca.crt /etc/pki/ca-trust/source/anchors/stratus-ca.crt
sudo update-ca-trust
```

Production-like environments must not require `--insecure`.

### Create buckets

```bash
mc mb stratus/stratus-landing
mc mb stratus/stratus-bronze
mc mb stratus/stratus-silver
mc mb stratus/stratus-gold
mc mb stratus/stratus-platform
```

### Create lab service users

```bash
mc admin user add stratus svc-spark $(openssl rand -base64 32)
mc admin user add stratus svc-polaris $(openssl rand -base64 32)
mc admin user add stratus svc-airflow $(openssl rand -base64 32)
mc admin user add stratus svc-trino $(openssl rand -base64 32)
```

For production-like AIStor, prefer the supported service-account or external identity model approved in the storage decision record and document the mapping to the four Stratus service principals.

### Apply lab access policies

Spark and Polaris read/write all platform buckets:

```bash
cat > /tmp/policy-spark.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:ListBucket"],
      "Resource": [
        "arn:aws:s3:::stratus-landing",
        "arn:aws:s3:::stratus-bronze",
        "arn:aws:s3:::stratus-silver",
        "arn:aws:s3:::stratus-gold",
        "arn:aws:s3:::stratus-platform"
      ]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject","s3:PutObject","s3:DeleteObject"],
      "Resource": [
        "arn:aws:s3:::stratus-landing/*",
        "arn:aws:s3:::stratus-bronze/*",
        "arn:aws:s3:::stratus-silver/*",
        "arn:aws:s3:::stratus-gold/*",
        "arn:aws:s3:::stratus-platform/*"
      ]
    }
  ]
}
EOF
mc admin policy create stratus policy-spark /tmp/policy-spark.json
mc admin policy attach stratus policy-spark --user svc-spark
mc admin policy attach stratus policy-spark --user svc-polaris
```

Airflow can read landing only:

```bash
cat > /tmp/policy-airflow.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:ListBucket"],
      "Resource": ["arn:aws:s3:::stratus-landing"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject"],
      "Resource": ["arn:aws:s3:::stratus-landing/*"]
    }
  ]
}
EOF
mc admin policy create stratus policy-airflow /tmp/policy-airflow.json
mc admin policy attach stratus policy-airflow --user svc-airflow
```

Trino can read queryable and verification zones:

```bash
cat > /tmp/policy-trino.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:ListBucket"],
      "Resource": [
        "arn:aws:s3:::stratus-bronze",
        "arn:aws:s3:::stratus-silver",
        "arn:aws:s3:::stratus-gold",
        "arn:aws:s3:::stratus-platform"
      ]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject"],
      "Resource": [
        "arn:aws:s3:::stratus-bronze/*",
        "arn:aws:s3:::stratus-silver/*",
        "arn:aws:s3:::stratus-gold/*",
        "arn:aws:s3:::stratus-platform/*"
      ]
    }
  ]
}
EOF
mc admin policy create stratus policy-trino /tmp/policy-trino.json
mc admin policy attach stratus policy-trino --user svc-trino
```

Store generated credentials in the approved secret location for the environment. Do not commit them.

---

## 12. Java Verification Module

The verification suite proves the Stratus storage contract against any S3-compatible target. It should avoid product-specific APIs.

### Maven dependencies

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

Preferred storage-neutral variables:

| Variable | Description |
|---|---|
| `STRATUS_S3_ENDPOINT` | HTTPS S3 endpoint |
| `STRATUS_S3_ACCESS_KEY` | admin or verification access key |
| `STRATUS_S3_SECRET_KEY` | matching secret key |
| `STRATUS_S3_AIRFLOW_ACCESS_KEY` | optional Airflow principal access key |
| `STRATUS_S3_AIRFLOW_SECRET_KEY` | optional Airflow principal secret key |

For backward compatibility with older increment docs, the test may also accept the previous `STRATUS_MINIO_*` names as aliases.

### Verification expectations

The suite must verify:

- all five buckets exist
- verification credentials can write/read/delete a test object in each required bucket
- `svc-airflow` can list/read landing
- `svc-airflow` cannot write bronze
- configured endpoint is HTTPS

The negative plaintext HTTP probe is kept as an operational check because behavior differs by product.

### Running the suite

```bash
export STRATUS_S3_ENDPOINT=https://minio1.stratus.local:9000
export STRATUS_S3_ACCESS_KEY=stratus-admin
export STRATUS_S3_SECRET_KEY=change-me-before-use
export STRATUS_S3_AIRFLOW_ACCESS_KEY=<svc-airflow access key>
export STRATUS_S3_AIRFLOW_SECRET_KEY=<svc-airflow secret key>

mvn test -pl . -Dtest=S3StorageVerificationTest
```

If the implementation keeps the older class name temporarily, run:

```bash
mvn test -pl . -Dtest=MinioVerificationTest
```

The class should be renamed to `S3StorageVerificationTest` when implemented so the code matches the product-neutral design.

---

## 13. Operational Checks

### Storage health

For MinIO/AIStor lab targets:

```bash
mc admin info stratus
```

Expected: all four nodes online and drives healthy.

If a superseding ADR selects another production storage product, use its supported health command or console and attach evidence to the decision record.

### Bucket visibility

```bash
mc ls stratus/
```

Expected buckets:

- `stratus-landing`
- `stratus-bronze`
- `stratus-silver`
- `stratus-gold`
- `stratus-platform`

### TLS validation

Production-like commands must work without `--insecure` or `-k`.

For MinIO/AIStor lab targets, confirm plaintext HTTP is rejected:

```bash
curl -v http://minio1.stratus.local:9000/minio/health/live
```

A successful plaintext response is a gate failure.

### Failure drill

For the four-node MinIO/AIStor lab target, stop one node:

```bash
sudo systemctl stop stratus-minio
```

Then verify a read/write path still succeeds from a surviving node:

```bash
mc cp /etc/hostname stratus/stratus-landing/failover-test.txt
mc cat stratus/stratus-landing/failover-test.txt
```

Restart the node and confirm it rejoins:

```bash
sudo systemctl start stratus-minio
mc admin info stratus
```

If a superseding ADR selects a different production target, perform that product's supported failure drill and record the result.

---

## 14. Completion Gates

### Lab gate

Increment 1 lab is complete when:

- [ ] storage target and support status are recorded as lab-only or supported
- [ ] S3 HTTPS endpoint is reachable
- [ ] all five Stratus buckets exist
- [ ] platform service identities exist or are mapped
- [ ] access policies enforce the service credential contract
- [ ] Java storage verification passes
- [ ] plaintext HTTP is rejected or otherwise impossible for the lab endpoint
- [ ] lab operational health check passes
- [ ] MinIO/AIStor lab, if used, passes the one-node failure drill

When the lab gate passes, Increment 2 engineering work can begin.

### Production-ready gate

The storage foundation is production-ready only when:

- [ ] storage decision record is approved
- [ ] MinIO AIStor support model is approved, or a superseding storage ADR approves a replacement target
- [ ] production topology, capacity, monitoring, backup, recovery, patching, and rotation runbooks exist
- [ ] TLS works without insecure client flags
- [ ] service credentials are stored in the approved secret-management location
- [ ] Java storage verification passes against the production-like target
- [ ] restore or failure drill passes according to the AIStor recovery model, or the approved replacement target's recovery model
- [ ] Phase 1 operational readiness checklist accepts the storage layer

No production dataset should be onboarded based only on the MinIO OSS lab gate.

---

## 15. Troubleshooting

### S3 endpoint unreachable

- Confirm DNS resolution from the client host.
- Confirm firewall access to the S3 HTTPS port.
- Confirm AIStor, or the approved replacement target, is listening on the expected endpoint.
- Confirm client truststore contains the issuing CA.

### TLS validation fails

- Confirm the endpoint hostname is present in the certificate SAN.
- Confirm the issuing CA is trusted by the client.
- Confirm intermediate certificates are served or installed as required.
- Avoid using `--insecure` except during disposable lab bring-up.

### Bucket access denied

- Confirm the service identity is the one expected by the test or component.
- Confirm bucket-level list permissions and object-level permissions are both present.
- Confirm AIStor policy syntax is correct, or that the approved replacement target has equivalent role mapping.
- Confirm there is no explicit deny from a broader policy.

### MinIO/AIStor lab cluster does not form

- Confirm all four hostnames resolve from every node.
- Confirm port `9000` is open between all nodes.
- Confirm each node has the correct `public.crt`, `private.key`, and CA file.
- Confirm every node uses the exact same peer list.
- Confirm all nodes use the same approved image tag or digest.

---

## 16. References

- Apache Polaris MinIO guide: https://polaris.apache.org/guides/minio/
- MinIO distributed deployment: https://min.io/docs/minio/linux/operations/install-deploy-manage/deploy-minio-multi-node-multi-drive.html
- MinIO container deployment: https://min.io/docs/minio/container/index.html
- MinIO access policy reference: https://min.io/docs/minio/linux/administration/identity-access-management/policy-based-access-control.html
- AWS SDK for Java S3: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/home.html
- Stratus Phase 1 implementation plan: [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)
- Stratus architecture: [on_prem_data_fabric_architecture.md](on_prem_data_fabric_architecture.md)
