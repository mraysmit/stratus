# Stratus Increment 1 - Ceph Object Storage Foundation

## 1. Purpose

This document is the technical implementation plan for Increment 1 of the Stratus platform as defined in [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md).

Increment 1 delivers the on-prem object-storage foundation consumed by Apache Polaris, Apache Iceberg, Spark, Airflow, Trino, and later Flink. This version of the increment uses **Ceph Object Gateway (RGW)** as the production S3-compatible storage target.

When this increment is complete:

- Ceph RGW has been approved by the architecture decision for the target environment.
- The platform exposes an HTTPS S3-compatible endpoint through RGW.
- The five Stratus storage buckets exist.
- Platform service identities are isolated.
- TLS, least-privilege S3 credentials, and operational checks are defined.
- A Java S3 verification suite proves the S3 behavior required by Stratus.
- The storage layer is ready for Polaris and Iceberg in Increment 2.

This document intentionally separates:

- **Ceph as the production platform:** monitors, managers, OSDs, CRUSH, pools, RGW, dashboard, replication, recovery, and operations.
- **S3 as the client contract:** the compatibility surface required by Polaris/Iceberg/Spark/Trino/Airflow.

---

## 2. What Ceph Provides

Ceph is an open-source, software-defined distributed storage platform. It can provide object storage, block storage, and file storage from the same underlying storage cluster, but Stratus uses it specifically for **object storage** through **Ceph Object Gateway**, also called **RADOS Gateway** or **RGW**.

At the storage layer, Ceph stores data in **RADOS**, a distributed object store made of Object Storage Daemons (OSDs). Ceph uses the **CRUSH** placement algorithm to distribute data across disks, hosts, racks, or other failure domains without relying on a central lookup table. This gives the platform explicit control over durability, placement, rebalancing, and recovery behavior.

For Stratus clients, RGW exposes Ceph as an **S3-compatible HTTPS endpoint**. Polaris, Iceberg, Spark, Airflow, Trino, and the Java verification suite interact with RGW using normal S3 client behavior: buckets, objects, access keys, endpoint overrides, path-style access, and object read/write/list/delete operations.

Ceph offers the capabilities Stratus needs from a production object-storage foundation:

- **On-prem control:** runs on Linux servers and local disks under platform ownership.
- **Open-source production storage:** keeps the object-storage layer self-hosted and open source while using a platform designed for production distributed storage.
- **S3 compatibility:** exposes RGW for standard S3 clients and table engines.
- **Durability controls:** supports replicated pools and erasure-coded pools.
- **Failure-domain awareness:** CRUSH can place data across hosts, racks, or zones.
- **Operational maturity:** provides Ceph CLI, dashboard, health checks, orchestration, and Prometheus/Grafana integration.
- **Security controls:** supports TLS, RGW users/access keys, bucket policies or equivalent controls, and encryption options.
- **Scalability:** can add OSD hosts and disks without changing the Stratus S3 client contract.
- **Recovery model:** has explicit health, backfill, rebalance, and degraded-state behavior that can be tested before production onboarding.

Ceph is suitable for Stratus because the platform needs more than a local S3-compatible API. It needs a production storage substrate for Iceberg table data and metadata, with operational visibility, failure handling, capacity growth, and a real recovery model. Ceph RGW provides the S3 surface needed by the lakehouse engines while Ceph itself provides the distributed storage machinery behind that API.

The main tradeoff is operational complexity. Ceph is a real storage platform, not a single binary. It requires deliberate design for MON quorum, MGR availability, OSD layout, CRUSH rules, pool strategy, RGW high availability, TLS, identity, monitoring, and recovery. This document treats that complexity as part of the design rather than hiding it behind a generic "S3-compatible" label.

---

## 3. Storage Decision

The storage architecture decision, candidate comparison, scoring, and proof-of-fit gate are owned by [stratus_on_prem_data_fabric_architecture.md](stratus_on_prem_data_fabric_architecture.md#28-storage-architecture-decision). This increment implements the current candidate baseline selected there: **Ceph Object Gateway (RGW)** on a production Ceph cluster.

### Implementation target

| Decision area | Baseline choice |
|---|---|
| Product | Ceph with RADOS Gateway |
| License | LGPL/GPL mix under the Ceph project |
| Deployment model | on-prem distributed storage cluster |
| Stratus client API | S3-compatible API through Ceph RGW |
| Native storage model | RADOS objects in replicated or erasure-coded pools |
| Durability | CRUSH-controlled replication or erasure coding |
| Governance alignment | S3 users, bucket policy/IAM-like controls, optional Keystone/LDAP/OIDC integration depending on deployment |
| Operations | Ceph MON, MGR, OSD, RGW, Dashboard, Prometheus/Grafana integration |
| Production DNS alias | `object-store.stratus.local` |

### Known Ceph RGW caveat

Ceph RGW is S3-compatible, but it is not AWS S3. Some AWS S3 behavior may differ, especially around newer AWS-specific APIs, IAM semantics, lifecycle details, object-lock behavior, replication semantics, encryption header behavior, and edge cases in multipart/listing behavior.

For Stratus, this is acceptable if:

- Polaris/Iceberg/Spark/Trino can read and write through the required S3 subset.
- Bucket/object authorization is enforced through RGW-supported users, policies, roles, or an approved identity integration.
- Encryption, audit, retention, and recovery use Ceph-supported mechanisms.
- The Java verification suite and Increment 2 Iceberg/Polaris tests pass against RGW.

---

## 4. Stratus Storage Contract

Every storage target for Increment 1 must satisfy this contract. For this document, the target is Ceph RGW.

### Endpoint contract

| Requirement | Contract |
|---|---|
| API | S3-compatible object API exposed by Ceph RGW |
| Endpoint | `https://object-store.stratus.local` or environment-specific equivalent |
| Transport | HTTPS only |
| Path-style access | Required for first implementation unless all clients pass virtual-hosted validation |
| Region | `us-east-1` unless Ceph/client compatibility testing requires another fixed value |
| CA trust | clients must trust the endpoint CA; routine commands must not require `--insecure` or `-k` |

### Bucket contract

Stratus clients must see these S3 bucket names:

| S3 bucket | Purpose |
|---|---|
| `stratus-landing` | raw source files and bounded external extracts before table ingestion |
| `stratus-bronze` | bronze Iceberg data and metadata |
| `stratus-silver` | silver Iceberg data and metadata |
| `stratus-gold` | gold Iceberg data and metadata |
| `stratus-platform` | platform-internal data such as quality results, Spark event logs, audit extracts, and maintenance metadata |

Ceph stores these buckets in RGW metadata and RADOS data pools. The S3 names above are the stable Stratus contract; pool names, placement targets, and CRUSH rules are Ceph implementation details.

### Service identity contract

| Principal | Minimum object-storage access |
|---|---|
| `svc-spark` | read/write/delete landing, bronze, silver, gold, platform |
| `svc-polaris` | read/write/delete bronze, silver, gold, platform; landing only if needed by catalog bootstrap |
| `svc-airflow` | read landing; no write access to bronze/silver/gold |
| `svc-trino` | read bronze for internal verification, read silver/gold/platform for query serving and quality visibility |

In Ceph RGW, these are represented as RGW users, subusers, access keys, or an approved identity integration. Access boundaries must be encoded in bucket policies, user caps, roles, or another RGW-supported control that passes verification.

### Security contract

- TLS is required for RGW traffic.
- Root/admin Ceph credentials must not be used by platform applications.
- S3 access keys must be stored outside source control.
- Credential rotation must be documented before production readiness.
- At-rest protection must use Ceph-supported encryption or approved storage-layer controls.
- Bucket policies are not the enterprise authorization layer for analytical users. User-facing data authorization is enforced later through Polaris, Trino, Ranger, and identity integration.

### Recovery and operations contract

Production-like Ceph must define:

- monitor quorum and failure tolerance
- manager daemon placement and failover
- OSD failure tolerance
- CRUSH failure domains
- replicated and/or erasure-coded pool design
- RGW high availability and load balancing
- metadata and bucket-index recovery strategy
- backup or replication strategy for critical buckets
- capacity thresholds and backfill/rebalance expectations
- dashboard and Prometheus/Grafana monitoring
- audit log collection where enabled
- failure drill and restore-test cadence

---

## 5. Ceph Production Architecture

### Core services

| Component | Responsibility |
|---|---|
| Monitor (MON) | cluster map quorum and membership |
| Manager (MGR) | cluster management, dashboard, modules, metrics |
| OSD | object storage daemon storing data on disks |
| CRUSH | placement map and failure-domain-aware data placement |
| RADOS Gateway (RGW) | S3-compatible object API |
| Dashboard | operational UI |
| Prometheus/Grafana | metrics collection and dashboards |
| Optional Keystone/LDAP/OIDC integration | identity integration, if selected |

### Production topology

The production-like topology should be at least:

| Role | Count | Notes |
|---|---:|---|
| MON | 3 or 5 | odd quorum |
| MGR | 2+ | active/standby |
| OSD hosts | 3+ | scale by capacity and failure-domain requirements |
| RGW | 2+ | behind load balancer or DNS alias |
| Dashboard | via MGR | admin access only |

Example host layout:

| Host | Roles |
|---|---|
| `ceph-mon1.stratus.local` | MON, MGR |
| `ceph-mon2.stratus.local` | MON, MGR standby |
| `ceph-mon3.stratus.local` | MON |
| `ceph-osd1.stratus.local` | OSDs |
| `ceph-osd2.stratus.local` | OSDs |
| `ceph-osd3.stratus.local` | OSDs |
| `ceph-rgw1.stratus.local` | RGW |
| `ceph-rgw2.stratus.local` | RGW |

```text
                object-store.stratus.local
                         |
              Load balancer / DNS alias
                         |
              +----------+----------+
              |                     |
          ceph-rgw1             ceph-rgw2
              |                     |
              +----------+----------+
                         |
                    Ceph cluster
             MON quorum + MGR + OSDs
                         |
              RADOS pools and bucket data
```

For a smaller lab, roles may be co-located, but the lab must be clearly marked non-production. Production readiness requires quorum, OSD failure-domain, RGW HA, and recovery validation.

---

## 6. Assumptions and Prerequisites

- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- dedicated disks or block devices for OSDs
- DNS resolution for all Ceph service hosts and `object-store.stratus.local`
- time synchronization across all nodes
- firewall rules opened for selected Ceph service ports
- TLS certificates for RGW and dashboard endpoints
- approved Ceph release and deployment method
- Maven 3.9+ and JDK 25 on the approved build worker; the verification host requires only the approved container runtime, registry access, target network access, trust material, protected configuration, and evidence storage
- approved build pipeline capable of producing the storage verifier artifact and immutable verifier image
- approved artifact repository and container registry with checksum, digest, scan, and provenance retention
- approved read-only trust-material path, protected verifier environment-file path, and writable evidence destination

### Version discipline

Before implementation:

- select an approved Ceph release
- read the matching Ceph documentation for that exact release
- pin all repositories, packages, containers, or deployment artifacts
- record RGW S3 compatibility notes for that release
- record dashboard, metrics, and alerting integration
- record upgrade and patch process

Do not copy commands from a different Ceph release without checking compatibility.

---

## 7. Ports

Confirm exact ports against the selected Ceph release and deployment runbook. Common Ceph service ports include:

| Component | Common port(s) | Purpose |
|---|---:|---|
| MON | 3300, 6789 | monitor protocol |
| MGR dashboard | 8443 | dashboard HTTPS, if enabled |
| MGR Prometheus module | 9283 | metrics |
| OSD | 6800-7300 | OSD communication range |
| RGW | 80/443 or configured port | S3-compatible API |

Production exposure:

- expose RGW only through the approved HTTPS endpoint
- restrict MON/MGR/OSD ports to cluster and administrative networks
- restrict dashboard access to administrators
- document firewall rules in the runbook

---

## 8. TLS, Identity, Policy, and Encryption

### TLS

For lab use, self-signed certificates are acceptable. For production-like use, use the platform PKI path from the identity/security increment.

The RGW certificate must include:

- `object-store.stratus.local`
- each RGW host, such as `ceph-rgw1.stratus.local`
- any environment-specific aliases

All Java clients must trust the issuing CA. Production-like commands and tests must not require `--insecure`, `-k`, or disabled certificate validation.

### RGW users and access keys

RGW S3 clients authenticate with access key and secret key pairs. The Stratus runbook must define:

- one RGW user or equivalent identity per Stratus service principal
- generated access keys for each service principal
- rotation procedure
- storage location in the approved secret manager
- mapping between application service name and RGW identity

### Policy model

Ceph RGW supports S3-like bucket policies and user-level controls, but the exact policy feature set must be verified against the selected release. Use bucket policies for service isolation only after testing them with the verification suite.

User-facing table authorization is not implemented at the bucket layer. It is enforced later through Polaris, Trino, Ranger, and identity integration.

### Encryption

Production-like environments must define:

- whether RGW server-side encryption is used
- whether encryption is delegated to encrypted OSD/block devices
- key-management integration
- which Stratus buckets require encryption
- recovery procedure for key material

Do not assume AWS KMS semantics unless they are verified against the selected Ceph RGW release and deployment mode.

---

## 9. Ceph Installation Plan

Use the official Ceph deployment documentation for the selected release. This section defines the Stratus deployment shape and required configuration areas; exact commands belong in the release-pinned runbook.

### Local non-cloud deployment model

Stratus deploys Ceph on local, non-cloud Linux infrastructure. The baseline is not a Compose stack and not a developer workstation. It is a set of physical or virtualized Linux hosts with persistent disks, routable hostnames, time synchronization, SSH access for cephadm orchestration, and a supported container runtime.

The official cephadm deployment flow is:

1. Prepare Linux hosts with the required host dependencies.
2. Install `cephadm` using the selected release's documented package or release-specific install method.
3. Bootstrap the first host with `cephadm bootstrap --mon-ip <mon-ip>`.
4. Enable the Ceph CLI through `cephadm shell`, `ceph-common`, or the environment-standard CLI method.
5. Add the remaining hosts to the cluster with `ceph orch host add`.
6. Place additional MON and MGR daemons with `ceph orch apply`.
7. Add OSDs from real unused disks or block devices.
8. Deploy RGW with `ceph orch apply rgw`.
9. Configure RGW TLS, endpoint routing, service identities, bucket policy, metrics, logging, backup, and failure drills.

Required host capabilities:

| Area | Requirement |
|---|---|
| Operating system | supported Linux distribution for the selected Ceph release |
| Process manager | systemd |
| Runtime | Podman preferred; Docker Engine allowed only when approved and release-compatible |
| Python | Python 3 available for cephadm |
| Storage tooling | LVM2 available for OSD provisioning |
| Time | Chrony, NTP, or approved time synchronization |
| SSH | SSH running and reachable for cephadm host enrollment |
| Storage devices | dedicated unused disks or block devices for OSDs |
| Networking | stable management/public network; optional separate cluster network for replication/recovery traffic |
| DNS | stable hostnames for Ceph hosts and `object-store.stratus.local` |

The first host bootstrap creates the first MON and MGR, writes `/etc/ceph/ceph.conf`, writes the admin keyring, and prepares cephadm SSH access for adding the rest of the cluster. After bootstrap, Stratus must add additional hosts and place services deliberately; the first host alone is not a production topology.

Production-like local topology must include:

- three or five MONs, depending on node count and quorum design
- at least two MGR daemons with standby behavior
- multiple OSD hosts with documented CRUSH failure domains
- dedicated OSD devices, not loopback files or shared OS disks
- at least two RGW daemons behind the approved endpoint
- dashboard, metrics, logs, and alert routing
- documented failure drills for OSD, MON, MGR, RGW, host, and endpoint/load-balancer paths

Docker Desktop is not part of this deployment model. It can run developer-side client containers after RGW exists, but it is not a Ceph host runtime and does not prove Ceph quorum, storage placement, recovery, or production operations.

### Deployment method

Choose and document one supported deployment method:

- `cephadm` with Podman
- `cephadm` with Docker Engine
- distribution packages
- internally standardized automation

The runbook must describe bootstrap, host enrollment, daemon placement, and upgrade process.

For Stratus, the preferred deployment method is **cephadm with Podman** on Linux hosts. Cephadm is the Ceph-native lifecycle tool for containerized Ceph daemons. It bootstraps the first monitor and manager, enrolls additional hosts, and deploys services such as OSD and RGW through Ceph's orchestration layer.

Docker Engine is acceptable as the container runtime only when the environment standardizes on Docker Engine and the selected Ceph release documents compatibility with that Docker version. Do not hand-write standalone `podman run` or `docker run` commands for MON/MGR/OSD/RGW as the primary deployment mechanism; use `cephadm` so daemon placement, configuration, upgrades, health checks, and service lifecycle stay under Ceph's orchestrator.

Docker Desktop is a developer workstation tool in this design. It may run the Docker Compose client harness in §11 and developer-side verification containers, but it is not production evidence for MON quorum, OSD placement, CRUSH behavior, RGW high availability, or recovery. Production Ceph nodes must run a supported Linux host runtime such as Podman or Docker Engine.

### Runtime profile matrix

| Profile | Runtime | Purpose | Production evidence? |
|---|---|---|---|
| Developer workstation | Docker Desktop with Docker Compose | Run S3 client and Java verification containers against an existing RGW endpoint | No |
| Developer Linux host | Podman or Docker Engine with single-host cephadm | Bring up a disposable Ceph/RGW endpoint for S3 client development | No |
| Lab cluster | Podman with cephadm | Preferred representative lab for RGW, S3 behavior, Ceph health, and failure drills | Partial, only if topology matches the gate being proven |
| Lab cluster | Docker Engine with cephadm | Acceptable when Docker Engine is the approved Linux runtime and the selected Ceph release supports it | Partial, only if topology matches the gate being proven |
| Production | Podman with cephadm | Preferred Stratus production deployment model | Yes |
| Production | Docker Engine with cephadm | Allowed only with release compatibility, operations approval, and daemon restart/runbook evidence | Yes |
| Production | Docker Desktop | Not allowed | No |

### Production Podman profile

The production Podman profile is the default Stratus path. It uses `cephadm` to manage Ceph daemon containers on Linux hosts while keeping daemon placement, configuration, health, upgrades, and restarts under the Ceph orchestrator.

Production Podman requirements:

- selected Ceph release and image digest are pinned
- Podman version is approved against the selected Ceph release
- every Ceph host runs supported Linux, systemd, time synchronization, LVM2, SSH, and persistent host storage
- OSD devices are dedicated disks or block devices, not loopback files
- cephadm SSH keys and admin credentials are stored and rotated according to the platform secret-management standard
- MON, MGR, OSD, and RGW placement is declared through `ceph orch`, not ad hoc container commands
- RGW is deployed redundantly behind `object-store.stratus.local`
- Prometheus/Grafana, Ceph Dashboard, audit logs, and alert routing are enabled before the production-ready gate

Production Podman runbooks must include:

- host bootstrap and enrollment
- daemon placement specs for MON, MGR, OSD, and RGW
- pool and CRUSH creation
- RGW realm, zonegroup, zone, and frontend configuration
- TLS certificate install and rotation
- image upgrade, rollback, and failed-upgrade handling
- OSD replacement and host drain procedure
- Podman service failure and host reboot behavior

### Production Docker Engine profile

The production Docker Engine profile is allowed only when the platform operations team standardizes on Docker Engine for Linux servers and the selected Ceph release documents compatibility with that Docker version.

Production Docker Engine requirements are the same as the Podman profile, with additional evidence:

- Docker Engine version and package source are pinned and approved
- Docker daemon configuration, restart policy, logging driver, and storage driver are documented
- Docker daemon restart behavior is tested because daemon loss can affect all Ceph daemon containers on the host
- cephadm remains the lifecycle owner for Ceph containers
- operators use `ceph orch` commands for daemon management, not raw `docker stop`, `docker rm`, or `docker run`
- host-level monitoring includes Docker daemon health in addition to Ceph health

Docker Engine production approval must be recorded in the storage decision evidence bundle. If the selected Ceph release or enterprise runtime policy does not support Docker Engine for production Ceph, use the Podman profile.

### Storage layout

Recommended baseline:

| Area | Requirement |
|---|---|
| OSD devices | dedicated disks or block devices |
| DB/WAL devices | optional fast devices when performance requires |
| Failure domains | host-level at minimum; rack/zone if available |
| CRUSH rules | documented for replicated and erasure-coded pools |
| RGW pools | created by RGW or pre-created according to runbook |

Do not use loopback files or multiple directories on one disk as production evidence.

### Pool strategy

The runbook must define:

- replicated pool settings for RGW metadata
- replicated or erasure-coded pool settings for object data
- CRUSH failure domain
- expected recovery/backfill behavior
- capacity overhead
- minimum-size and degraded-write behavior

Increment 1 uses synthetic small-object and prefix-listing workloads to establish the RGW and bucket-index baseline. Real Iceberg metadata and small-file behavior is qualified in Increments 2 and 3 when those capabilities exist.

### Performance, metadata, and cost evidence

Before Increment 1 can unblock Polaris and Iceberg implementation, the Ceph evidence bundle must include these storage-only results:

| Evidence area | Ceph-specific evidence to capture |
|---|---|
| Concurrent S3 client access | Run mixed put/get/head/list/delete workloads using multiple isolated verifier identities. Record p50/p95/p99 request latency, 4xx/5xx rate, retry rate, stale-read incidents, throttling, and denied or over-broad access. |
| Multipart throughput | Run scaled multipart writes and reads using the storage verifier. Record sustained throughput, create/complete/abort behavior, retry rate, cleanup of abandoned uploads, and RGW/OSD saturation. |
| Small-object and prefix-listing behavior | Generate representative synthetic object and prefix counts. Record object count, prefix list latency, bucket-index health, retry rate, error rate, and resource saturation. |
| Request latency and error budget | Run mixed S3 put/get/head/list/delete/multipart operations. Record p50/p95/p99 latency by operation, timeout rate, retry rate, and 4xx/5xx rate. |
| Cost and capacity model | Record raw capacity, usable capacity, replication or erasure-code overhead, metadata/bucket-index overhead assumptions, 12/24/36-month growth estimate, expansion trigger, and expected hardware/operator ownership. |
| Operator effort | Record operator steps and elapsed effort for install, health review, failure drill, restore drill, upgrade rehearsal, credential rotation, alert setup, and runbook correction. |

The first run does not need to prove final production scale, but it must establish a storage-only baseline. Any failed threshold must either stop the increment or produce a dated ADR with mitigation, owner, and retest criteria.

### Deferred cross-increment storage qualification

These tests remain mandatory but cannot block Increment 1 because their required components are delivered later:

| Owning gate | Deferred storage evidence |
|---|---|
| Increment 2 | Polaris storage binding plus Iceberg S3FileIO, metadata, manifest, snapshot, and listing behavior |
| Increment 3 | Spark ingestion/write throughput, multipart behavior, small-file generation, compaction, and orphan cleanup |
| Increment 4 | Airflow credential use and submission of the accepted Spark artifact without bypassing storage controls |
| Increment 5 | Trino scan throughput, retry behavior, and query correctness against Spark-produced tables |
| Phase 1 readiness | Concurrent Spark write, Trino read, Polaris resolution, operator listing, recovery, and integrated performance/error thresholds |

A deferred failure reopens the storage qualification with an owner, impact assessment, remediation or ADR, and retest criteria. It does not retroactively make the Increment 1 sequencing circular.

### RGW service

The RGW layer must define:

- realm, zonegroup, and zone strategy, even if single-site
- RGW frontend configuration
- TLS endpoint
- load balancer or DNS alias
- access log and audit settings
- service restart and failover process

### RGW TLS certificate application

RGW TLS is configured on the Ceph RGW service, not inside the developer Compose harness. The developer harness only trusts the CA that issued the RGW endpoint certificate.

For production-like environments, use the platform PKI path from the identity/security increment. For a disposable lab, a lab CA is acceptable if the CA and private keys are clearly marked non-production.

Cephadm-managed RGW supports three certificate patterns:

| Pattern | Use when | Notes |
|---|---|---|
| cephadm-signed | disposable lab where automatic Ceph-managed certificates are acceptable | convenient, but clients still need to trust the issuing CA |
| inline | small lab specs where embedding certificate and key in the RGW service spec is acceptable | avoid for long-lived environments because private key material lives in the spec file |
| reference | production-like or shared lab environments | preferred Stratus pattern; certificate and key are registered with cephadm certmgr and referenced by the RGW service spec |

The preferred Stratus lab and production-like pattern is **reference**.

#### Generate a disposable lab CA and RGW certificate

Use this only for lab environments. Production certificates must come from the approved platform CA.

```bash
mkdir -p certs private
chmod 700 private

openssl genrsa -out private/stratus-lab-ca.key 4096

openssl req -x509 -new -nodes \
  -key private/stratus-lab-ca.key \
  -sha256 \
  -days 365 \
  -out certs/stratus-ca.crt \
  -subj "/CN=Stratus Lab CA"

openssl genrsa -out private/object-store.stratus.local.key 4096

cat > certs/object-store.stratus.local.cnf <<'EOF'
[req]
default_bits = 4096
prompt = no
default_md = sha256
distinguished_name = dn
req_extensions = req_ext

[dn]
CN = object-store.stratus.local

[req_ext]
subjectAltName = @alt_names

[alt_names]
DNS.1 = object-store.stratus.local
DNS.2 = ceph-rgw1.stratus.local
DNS.3 = ceph-rgw2.stratus.local
EOF

openssl req -new \
  -key private/object-store.stratus.local.key \
  -out certs/object-store.stratus.local.csr \
  -config certs/object-store.stratus.local.cnf

openssl x509 -req \
  -in certs/object-store.stratus.local.csr \
  -CA certs/stratus-ca.crt \
  -CAkey private/stratus-lab-ca.key \
  -CAcreateserial \
  -out certs/object-store.stratus.local.crt \
  -days 365 \
  -sha256 \
  -extensions req_ext \
  -extfile certs/object-store.stratus.local.cnf
```

The generated files have different purposes:

| File | Purpose | Commit? |
|---|---|---|
| `certs/stratus-ca.crt` | CA certificate mounted into developer clients and Java verifier | yes for disposable lab only if the repository policy allows lab public CA files |
| `private/stratus-lab-ca.key` | lab CA private key | never |
| `private/object-store.stratus.local.key` | RGW endpoint private key | never |
| `certs/object-store.stratus.local.crt` | RGW endpoint certificate | no for production; lab only if policy allows |

#### Register the RGW certificate with cephadm certmgr

Run these commands from a Ceph admin shell on a host that has Ceph admin credentials. The service name must match the RGW service id, for example `rgw.stratus`.

```bash
ceph orch certmgr cert set \
  --cert-name rgw_ssl_cert \
  --service-name rgw.stratus \
  -i certs/object-store.stratus.local.crt

ceph orch certmgr key set \
  --key-name rgw_ssl_key \
  --service-name rgw.stratus \
  -i private/object-store.stratus.local.key
```

Apply or update the RGW service spec:

```yaml
service_type: rgw
service_id: stratus
placement:
  hosts:
    - ceph-rgw1.stratus.local
    - ceph-rgw2.stratus.local
spec:
  ssl: true
  certificate_source: reference
  rgw_frontend_type: beast
  rgw_frontend_port: 443
```

```bash
ceph orch apply -i rgw-stratus.yaml
ceph orch redeploy rgw.stratus
ceph orch ps --service-name rgw.stratus
```

After deployment, verify the endpoint certificate from a client network:

```bash
openssl s_client \
  -connect object-store.stratus.local:443 \
  -servername object-store.stratus.local \
  -CAfile certs/stratus-ca.crt \
  -verify_return_error
```

The developer harness uses only `certs/stratus-ca.crt`. It must never mount RGW private keys.

---

## 10. Podman and Docker Engine Lab Deployment

This section provides a containerized lab deployment pattern for Ceph. It is not a replacement for the production topology in §5. It is intended to prove RGW, S3 client behavior, bucket setup, and Java verification before building the full production-like cluster.

### Podman-based cephadm lab

Use this path when the lab host or lab nodes use Podman. This is the preferred Stratus lab path because it is closest to the Linux/container runtime used by many Ceph deployments.

Prerequisites on each lab host:

- Python 3
- systemd
- Podman version compatible with the selected Ceph release
- LVM2
- SSH service running
- time synchronization
- unused disks or block devices for OSDs

Install `cephadm` using the selected release's documented package method or release-specific installer. Confirm `cephadm` is on the host path before bootstrap:

```bash
which cephadm
```

Bootstrap on the first Ceph host. Use the management/public IP address that the other Ceph hosts and clients can reach:

```bash
sudo cephadm bootstrap \
  --mon-ip <ceph-mon1-ip> \
  --initial-dashboard-user stratus-admin \
  --initial-dashboard-password <change-me> \
  --dashboard-password-noupdate
```

What this does:

- creates the first Ceph Monitor and Manager
- creates `/etc/ceph/ceph.conf`
- creates `/etc/ceph/ceph.client.admin.keyring`
- configures cephadm's SSH key for adding hosts
- starts managing Ceph daemons as containers through the selected runtime
- enables the dashboard unless disabled in the selected release/runbook

Do not bootstrap from a laptop or Docker Desktop VM. Bootstrap runs on the first Ceph host that will become part of the local cluster.

Enable the Ceph CLI:

```bash
sudo cephadm shell -- ceph status
sudo cephadm install ceph-common
ceph status
```

Add lab hosts. Hostnames must resolve consistently from the bootstrap host and from the rest of the operations environment:

```bash
ceph orch host add ceph-mon2.stratus.local <ceph-mon2-ip>
ceph orch host add ceph-mon3.stratus.local <ceph-mon3-ip>
ceph orch host add ceph-osd1.stratus.local <ceph-osd1-ip>
ceph orch host add ceph-osd2.stratus.local <ceph-osd2-ip>
ceph orch host add ceph-osd3.stratus.local <ceph-osd3-ip>
ceph orch host add ceph-rgw1.stratus.local <ceph-rgw1-ip>
ceph orch host add ceph-rgw2.stratus.local <ceph-rgw2-ip>
```

Apply monitor and manager placement:

```bash
ceph orch apply mon --placement="3 ceph-mon1.stratus.local ceph-mon2.stratus.local ceph-mon3.stratus.local"
ceph orch apply mgr --placement="2 ceph-mon1.stratus.local ceph-mon2.stratus.local"
```

Inspect devices before creating OSDs:

```bash
ceph orch device ls
```

Add OSDs using the approved lab devices. For a disposable lab where all unused devices may be consumed:

```bash
ceph orch apply osd --all-available-devices
```

For a safer lab, explicitly list devices in the runbook and add only those devices. Never run `--all-available-devices` on a host that has disks not dedicated to Ceph.

Deploy RGW. For a single-site Stratus lab, use a named RGW service and place at least two daemons when testing endpoint availability:

```bash
ceph orch apply rgw stratus \
  --realm=stratus \
  --zonegroup=stratus \
  --zone=stratus-primary \
  --placement="2 ceph-rgw1.stratus.local ceph-rgw2.stratus.local"
```

Confirm services:

```bash
ceph orch ps
ceph status
ceph health detail
```

Expose RGW through `object-store.stratus.local` using the lab load balancer, reverse proxy, or DNS pattern approved for the lab. Production-like environments must use a redundant endpoint and trusted TLS certificate.

### Docker Engine-based cephadm lab

Use this path only when Docker Engine is the approved container runtime. The Ceph deployment model is still cephadm; only the container runtime changes.

Prerequisites are the same as the Podman path, except Docker Engine replaces Podman. Confirm the selected Ceph release supports the installed Docker version before bootstrap.

The bootstrap and orchestration commands are intentionally the same:

```bash
sudo cephadm bootstrap --mon-ip <ceph-mon1-ip>
ceph orch host add <host> <ip>
ceph orch apply osd --all-available-devices
ceph orch apply rgw stratus --placement="2 ceph-rgw1.stratus.local ceph-rgw2.stratus.local"
```

Operational notes:

- cephadm owns the Ceph daemon containers
- do not manually stop, remove, or replace Ceph daemon containers with raw Docker commands
- use `ceph orch ps`, `ceph orch restart`, and Ceph health commands for lifecycle operations
- Docker daemon restart behavior must be documented because losing Docker can affect all Ceph daemon containers on that host

### Single-host cephadm developer lab

Ceph supports single-host cephadm bootstrap for development and evaluation. This is useful for S3 client and Java verification work, but it is not a durability or production-readiness test.

```bash
sudo cephadm bootstrap \
  --mon-ip <host-ip> \
  --single-host-defaults
```

Use this only to validate:

- RGW starts
- Stratus buckets can be created
- S3 credentials work
- S3 client path-style access works
- Java verification suite can run

Do not use a single-host lab to prove failure tolerance, CRUSH design, OSD recovery, MON quorum, MGR failover, or RGW high availability.

---

## 11. Docker Desktop and Docker Compose Developer Harness

Docker Compose is not the Ceph cluster deployment mechanism for Stratus. A real Ceph lab should use cephadm with Podman or Docker Engine as described in §10.

Compose is useful as a **developer client harness** that runs tools against an existing RGW endpoint. It keeps S3 smoke-test commands repeatable without pretending to orchestrate MON/MGR/OSD/RGW.

### Developer Docker Desktop setup

Use this setup on a developer workstation running Docker Desktop on Windows, macOS, or Linux Desktop. The purpose is to exercise the Stratus S3 client contract against a Ceph RGW endpoint that already exists.

The endpoint may be:

- a shared Ceph/RGW lab endpoint
- a single-host cephadm lab running in a separate Linux VM
- a production-like non-production Ceph endpoint approved for development tests

Do not run the production Ceph cluster itself inside Docker Desktop. Docker Desktop does not provide the production evidence Stratus needs for systemd-managed Linux hosts, dedicated OSD devices, MON quorum, MGR failover, CRUSH placement, RGW high availability, Ceph Dashboard operations, or recovery drills.

Developer prerequisites:

- Docker Desktop with Docker Compose v2 enabled
- network access to `object-store.stratus.local` or the selected RGW endpoint
- developer workstation trusts the Stratus lab CA
- `CEPH_RGW_ENDPOINT`, `CEPH_RGW_ACCESS_KEY`, and `CEPH_RGW_SECRET_KEY` are stored in a local `.env` file outside source control
- the pinned storage verifier image is available from the approved registry

For Windows workstations, run Docker Compose from PowerShell or WSL, but keep path handling consistent for the certificate and evidence directories. The repository is not mounted into the verifier container; the build system publishes the verifier image before developers run this harness.

### Developer Podman setup

On a Linux developer host, the same client harness can be run with Podman Compose or a compatible `podman compose` workflow if that is the local standard. The harness still targets an existing RGW endpoint; it does not replace the cephadm lab.

The developer Podman path is useful when engineers want their local client runtime to match the production Podman profile more closely. It still does not prove production storage durability, HA, or recovery.

### Compose directory layout

```text
deploy/ceph-rgw-client-compose/
  compose.yaml
  .env.template
  .env
  certs/
    stratus-ca.crt
  scripts/
    startup.sh
    shutdown.sh
    check.sh
    verify-java.sh
```

The directory should be created once and then reused by developers. `.env` and private key material are local files and must not be committed.

### `.env.template`

Commit this template so new developers know exactly what to fill in:

```bash
# HTTPS endpoint exposed by the Ceph RGW service or approved lab endpoint.
CEPH_RGW_ENDPOINT=https://object-store.stratus.local

# Scoped Ceph RGW verification user. Do not use root/admin credentials.
CEPH_RGW_ACCESS_KEY=
CEPH_RGW_SECRET_KEY=

# Use path-style addressing for the first implementation.
S3_PATH_STYLE_ACCESS=true

# Compose implementation. Allowed values: docker, podman, auto.
COMPOSE_RUNTIME=auto

# Pinned internal registry images. The verifier image is produced by the build system
# and contains the already-built storage verifier artifact.
S3CLIENT_IMAGE=REPLACE_WITH_PINNED_RCLONE_IMAGE
VERIFIER_IMAGE=REPLACE_WITH_PINNED_STRATUS_STORAGE_VERIFIER_IMAGE
```

### `.env`

The developer harness targets the Ceph RGW endpoint directly. These variables describe the Ceph RGW S3-compatible endpoint, the RGW verification user, TLS trust, and S3 client behavior. They do not describe cloud infrastructure.

```bash
CEPH_RGW_ENDPOINT=https://object-store.stratus.local
CEPH_RGW_ACCESS_KEY=REPLACE_WITH_VERIFICATION_RGW_ACCESS_KEY
CEPH_RGW_SECRET_KEY=REPLACE_WITH_VERIFICATION_RGW_SECRET_KEY
S3_PATH_STYLE_ACCESS=true
COMPOSE_RUNTIME=auto
S3CLIENT_IMAGE=REPLACE_WITH_PINNED_RCLONE_IMAGE
VERIFIER_IMAGE=REPLACE_WITH_PINNED_STRATUS_STORAGE_VERIFIER_IMAGE
```

### `compose.yaml`

```yaml
services:
  s3client:
    image: ${S3CLIENT_IMAGE}
    entrypoint: ["sleep", "infinity"]
    environment:
      RCLONE_CONFIG_CEPHRGW_TYPE: s3
      RCLONE_CONFIG_CEPHRGW_PROVIDER: Ceph
      RCLONE_CONFIG_CEPHRGW_ENDPOINT: ${CEPH_RGW_ENDPOINT}
      RCLONE_CONFIG_CEPHRGW_ACCESS_KEY_ID: ${CEPH_RGW_ACCESS_KEY}
      RCLONE_CONFIG_CEPHRGW_SECRET_ACCESS_KEY: ${CEPH_RGW_SECRET_KEY}
      RCLONE_CONFIG_CEPHRGW_FORCE_PATH_STYLE: ${S3_PATH_STYLE_ACCESS}
      RCLONE_CA_CERT: /certs/stratus-ca.crt
    volumes:
      - ./certs/stratus-ca.crt:/certs/stratus-ca.crt:ro

  verifier:
    image: ${VERIFIER_IMAGE}
    read_only: true
    tmpfs:
      - /tmp
    entrypoint: ["/bin/bash", "-lc"]
    command: >
      cp "$$JAVA_HOME/lib/security/cacerts" /tmp/stratus-cacerts &&
      keytool -importcert
      -alias stratus-lab-ca
      -file /certs/stratus-ca.crt
      -keystore /tmp/stratus-cacerts
      -storepass changeit
      -noprompt &&
      exec java -jar /opt/stratus/verifiers/stratus-storage-verifier.jar
    environment:
      CEPH_RGW_ENDPOINT: ${CEPH_RGW_ENDPOINT}
      CEPH_RGW_ACCESS_KEY: ${CEPH_RGW_ACCESS_KEY}
      CEPH_RGW_SECRET_KEY: ${CEPH_RGW_SECRET_KEY}
      S3_PATH_STYLE_ACCESS: ${S3_PATH_STYLE_ACCESS}
      STRATUS_EVIDENCE_DIR: /evidence
      JAVA_TOOL_OPTIONS: >-
        -Djavax.net.ssl.trustStore=/tmp/stratus-cacerts
        -Djavax.net.ssl.trustStorePassword=changeit
    volumes:
      - ./certs/stratus-ca.crt:/certs/stratus-ca.crt:ro
      - ./evidence:/evidence
```

### Compose parameter contract

The Compose harness is intentionally small, but every parameter must be explicit because it controls either endpoint resolution, credentials, TLS trust, or Java verification behavior.

| Parameter | Required | Used by | Example | Purpose |
|---|---:|---|---|---|
| `CEPH_RGW_ENDPOINT` | Yes | `s3client`, `verifier` | `https://object-store.stratus.local` | HTTPS Ceph RGW endpoint under test. This must resolve to the on-prem RGW service or approved lab endpoint. |
| `CEPH_RGW_ACCESS_KEY` | Yes | `s3client`, `verifier` | `REPLACE_WITH_VERIFICATION_RGW_ACCESS_KEY` | Access key for a scoped Ceph RGW verification user. Use a test/service identity, not a Ceph admin credential. |
| `CEPH_RGW_SECRET_KEY` | Yes | `s3client`, `verifier` | `REPLACE_WITH_VERIFICATION_RGW_SECRET_KEY` | Secret key paired with `CEPH_RGW_ACCESS_KEY`. This is a secret and must stay out of source control. |
| `S3_PATH_STYLE_ACCESS` | Yes | `s3client`, `verifier` | `true` | Forces path-style bucket addressing such as `https://object-store.stratus.local/stratus-landing`. Keep enabled until all clients pass virtual-hosted-style validation. |
| `COMPOSE_RUNTIME` | No | scripts | `auto` | Selects Docker Compose or Podman Compose for scripts. `auto` prefers Docker when available and falls back to Podman. |
| `S3CLIENT_IMAGE` | Yes | `s3client` | `REPLACE_WITH_PINNED_RCLONE_IMAGE` | Pinned S3-compatible client image. Replace with an internally mirrored image where required. |
| `VERIFIER_IMAGE` | Yes | `verifier` | `registry.stratus.local/stratus/storage-verifier:<version>@sha256:<digest>` | Pinned runtime image produced by the build system. It contains the prebuilt storage verifier JAR and a compatible JRE, but no source tree or build toolchain. |
| `RCLONE_CONFIG_CEPHRGW_TYPE` | Yes | `s3client` | `s3` | Declares the `cephrgw` rclone remote as S3-compatible storage. |
| `RCLONE_CONFIG_CEPHRGW_PROVIDER` | Yes | `s3client` | `Ceph` | Selects Ceph-specific S3 client behavior where rclone supports it. |
| `RCLONE_CONFIG_CEPHRGW_ENDPOINT` | Derived | `s3client` | `${CEPH_RGW_ENDPOINT}` | Maps the Ceph RGW endpoint into the rclone remote. |
| `RCLONE_CONFIG_CEPHRGW_ACCESS_KEY_ID` | Derived | `s3client` | `${CEPH_RGW_ACCESS_KEY}` | Maps the Ceph RGW verification access key into the rclone remote. |
| `RCLONE_CONFIG_CEPHRGW_SECRET_ACCESS_KEY` | Derived | `s3client` | `${CEPH_RGW_SECRET_KEY}` | Maps the Ceph RGW verification secret key into the rclone remote. |
| `RCLONE_CONFIG_CEPHRGW_FORCE_PATH_STYLE` | Derived | `s3client` | `${S3_PATH_STYLE_ACCESS}` | Keeps the rclone remote aligned with the Stratus path-style default. |
| `RCLONE_CA_CERT` | Yes | `s3client` | `/certs/stratus-ca.crt` | CA bundle used by the S3 client to validate the Ceph RGW TLS certificate. Do not bypass TLS verification. |
| `JAVA_TOOL_OPTIONS` | Yes | `verifier` | `-Djavax.net.ssl.trustStore=/tmp/stratus-cacerts ...` | Injects the temporary Java truststore so SDK tests validate RGW TLS. |
| `STRATUS_EVIDENCE_DIR` | Yes | `verifier` | `/evidence` | Directory where the prebuilt verifier writes its machine-readable result bundle. |
| `/certs/stratus-ca.crt` | Yes | `s3client`, `verifier` | mounted from `./certs/stratus-ca.crt` | CA certificate that issued or anchors the RGW endpoint certificate. |
| `/evidence` | Yes | `verifier` | mounted from `./evidence` | Dedicated writable output location for machine-readable verification results. The verifier artifact and container filesystem remain read-only where practical. |

Parameter rules:

- `.env` must not be committed.
- The baseline developer harness must not use AWS cloud credentials, AWS endpoints, or AWS-branded container images.
- `CEPH_RGW_ENDPOINT` must use `https://`; plaintext HTTP is not valid for routine verification.
- `CEPH_RGW_ACCESS_KEY` and `CEPH_RGW_SECRET_KEY` must belong to a verification or service-scoped RGW identity.
- The CA mounted at `./certs/stratus-ca.crt` must validate the actual certificate presented by `CEPH_RGW_ENDPOINT`.
- `RCLONE_CA_CERT` is for the S3 client container only; Java uses the temporary truststore created by the verifier container command.
- `S3_PATH_STYLE_ACCESS=true` is the default developer setting because internal RGW endpoints often use endpoint overrides instead of virtual-hosted bucket names.
- If `CEPH_RGW_ENDPOINT` points to a shared lab, the credentials must be scoped so the developer harness cannot mutate unrelated buckets or production data.
- `S3CLIENT_IMAGE` and `VERIFIER_IMAGE` must be pinned by version or digest. Do not use floating `latest` tags.
- `VERIFIER_IMAGE` must be built, tested, scanned, and published by the approved build system. Do not mount the repository or run Maven inside the verifier container.

### Certificate setup for developer clients

Developers need the CA certificate that validates `CEPH_RGW_ENDPOINT`.

Preferred paths:

| Source | Developer action |
|---|---|
| platform PKI | copy the approved public CA chain to `certs/stratus-ca.crt` |
| shared Ceph lab | copy the lab CA public certificate from the lab owner to `certs/stratus-ca.crt` |
| disposable local lab | generate the lab CA and RGW certificate using the RGW TLS section above, then copy only `certs/stratus-ca.crt` into this harness |

Do not copy RGW private keys into the developer harness. The harness only needs the public CA certificate.

Validate the CA file before startup:

```bash
openssl x509 -in certs/stratus-ca.crt -noout -subject -issuer -dates
```

### `scripts/startup.sh`

The startup script is idempotent. It creates missing local scaffolding, checks required parameters, validates the CA file, starts the Compose services, and runs a smoke check.

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [ ! -f ".env" ]; then
  cp .env.template .env
  echo "Created .env from .env.template. Fill in CEPH_RGW_ACCESS_KEY and CEPH_RGW_SECRET_KEY, then rerun."
  exit 1
fi

set -a
. ./.env
set +a

required_vars="CEPH_RGW_ENDPOINT CEPH_RGW_ACCESS_KEY CEPH_RGW_SECRET_KEY S3_PATH_STYLE_ACCESS S3CLIENT_IMAGE VERIFIER_IMAGE"
for var in $required_vars; do
  value="${!var:-}"
  if [ -z "$value" ] || echo "$value" | grep -q "^REPLACE_WITH_"; then
    echo "Missing or placeholder value for $var in .env"
    exit 1
  fi
done

mkdir -p certs
if [ ! -f "certs/stratus-ca.crt" ]; then
  echo "Missing certs/stratus-ca.crt. Copy the public CA certificate for CEPH_RGW_ENDPOINT before startup."
  exit 1
fi

openssl x509 -in certs/stratus-ca.crt -noout >/dev/null

runtime="${COMPOSE_RUNTIME:-auto}"
case "$runtime" in
  docker)
    compose_cmd="docker compose"
    ;;
  podman)
    compose_cmd="podman compose"
    ;;
  auto)
    if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
      compose_cmd="docker compose"
    elif command -v podman >/dev/null 2>&1 && podman compose version >/dev/null 2>&1; then
      compose_cmd="podman compose"
    else
      echo "Neither docker compose nor podman compose is available."
      exit 1
    fi
    ;;
  *)
    echo "Unsupported COMPOSE_RUNTIME=$runtime. Use docker, podman, or auto."
    exit 1
    ;;
esac

$compose_cmd pull
$compose_cmd up -d --remove-orphans
$compose_cmd ps
$compose_cmd exec -T s3client rclone lsd cephrgw:
```

### `scripts/check.sh`

The check script is safe to run repeatedly. It verifies the harness is up and that RGW buckets are visible.

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

set -a
. ./.env
set +a

runtime="${COMPOSE_RUNTIME:-auto}"
if [ "$runtime" = "podman" ]; then
  compose_cmd="podman compose"
elif [ "$runtime" = "docker" ]; then
  compose_cmd="docker compose"
elif command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  compose_cmd="docker compose"
else
  compose_cmd="podman compose"
fi

$compose_cmd ps
$compose_cmd exec -T s3client rclone lsd cephrgw:
$compose_cmd exec -T s3client rclone lsf cephrgw:stratus-landing
```

### `scripts/verify-java.sh`

The Java verification script is also safe to run repeatedly. It starts the pinned verifier image, which executes the prebuilt storage verifier artifact with the environment and truststore configured by Compose. Artifact construction and publication happen in the build system before this script is run.

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

set -a
. ./.env
set +a

runtime="${COMPOSE_RUNTIME:-auto}"
if [ "$runtime" = "podman" ]; then
  compose_cmd="podman compose"
elif [ "$runtime" = "docker" ]; then
  compose_cmd="docker compose"
elif command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  compose_cmd="docker compose"
else
  compose_cmd="podman compose"
fi

$compose_cmd run --rm verifier
```

### `scripts/shutdown.sh`

The shutdown script is idempotent. It stops the developer harness without deleting `.env`, certificates, or local source files.

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [ -f ".env" ]; then
  set -a
  . ./.env
  set +a
fi

runtime="${COMPOSE_RUNTIME:-auto}"
if [ "$runtime" = "podman" ]; then
  compose_cmd="podman compose"
elif [ "$runtime" = "docker" ]; then
  compose_cmd="docker compose"
elif command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  compose_cmd="docker compose"
elif command -v podman >/dev/null 2>&1 && podman compose version >/dev/null 2>&1; then
  compose_cmd="podman compose"
else
  echo "No Compose runtime found; nothing to stop."
  exit 0
fi

$compose_cmd down --remove-orphans
```

After writing the scripts, mark them executable on Linux or WSL:

```bash
chmod +x scripts/startup.sh scripts/check.sh scripts/verify-java.sh scripts/shutdown.sh
```

Start the harness:

```bash
scripts/startup.sh
```

Check the harness:

```bash
scripts/check.sh
```

Run Java verification:

```bash
scripts/verify-java.sh
```

Stop the harness:

```bash
scripts/shutdown.sh
```

The verifier container copies the base JVM truststore to `/tmp/stratus-cacerts`, imports `stratus-ca.crt`, and points Java at that temporary truststore through `JAVA_TOOL_OPTIONS` before executing the prebuilt verifier JAR. Do not add `--no-verify-ssl` or disable Java TLS validation in routine tests; that would bypass a core Increment 1 requirement.

For Docker Desktop, expected developer evidence is:

- `docker compose ps` shows the client harness containers running
- the S3 client can list the five Stratus buckets through the RGW endpoint
- Java verification passes with TLS validation enabled
- no secrets are committed to the repository

For Podman developer workstations, capture the same evidence with the equivalent `podman compose` commands.

### What Compose validates

The Compose harness validates:

- the five Stratus buckets can be created
- S3 client path-style access works
- the Java S3 verification suite runs
- client TLS trust works from a clean container image

### What Compose does not validate

The Compose harness does not validate:

- MON quorum
- MGR failover
- OSD failure or recovery
- CRUSH placement
- RGW high availability
- pool design
- dashboard or monitoring
- production encryption and secret-management posture

---

## 12. Developer and Lab Topology

### Developer topology

Use either:

- Docker Desktop with the Compose client harness against an existing RGW endpoint
- single-host cephadm with Podman or Docker Engine on a Linux host
- a cephadm-managed lab cluster
- the Docker Compose client harness against an existing RGW endpoint

Do not substitute an unrelated S3-compatible service for the developer topology; developer validation should exercise Ceph RGW or a Ceph-managed lab endpoint.

Developer validation should prove:

- RGW starts in the cephadm lab, or an existing RGW endpoint is reachable
- the five Stratus buckets can be created
- S3 client path-style access works
- the Java S3 verification suite runs

Developer topology is not a durability, HA, encryption, or production security test.

### Lab topology

The lab should be a reduced but representative Ceph deployment:

- three MONs if possible
- at least one MGR
- at least three OSD hosts if testing failure behavior
- at least one RGW
- dashboard enabled
- RGW TLS enabled

If the lab cannot run quorum and OSD failure-domain behavior, label the limitation clearly. Production readiness still requires HA validation.

---

## 13. Bucket and Identity Setup

### Create buckets

Use an approved S3-compatible client pointed at RGW. The examples below use the same `s3client` remote defined in the Docker Compose developer harness:

```bash
rclone mkdir cephrgw:stratus-landing
rclone mkdir cephrgw:stratus-bronze
rclone mkdir cephrgw:stratus-silver
rclone mkdir cephrgw:stratus-gold
rclone mkdir cephrgw:stratus-platform
```

These commands must work without TLS bypass in production-like environments.

### Create RGW users

Create one RGW identity per Stratus service principal. Representative command shape:

```bash
radosgw-admin user create --uid svc-spark --display-name "Stratus Spark"
radosgw-admin user create --uid svc-polaris --display-name "Stratus Polaris"
radosgw-admin user create --uid svc-airflow --display-name "Stratus Airflow"
radosgw-admin user create --uid svc-trino --display-name "Stratus Trino"
```

Record generated access and secret keys in the approved secret-management location. If keys are generated separately or rotated, document the exact command sequence in the runbook.

### Service access policy

Minimum access boundaries:

| Principal | Landing | Bronze | Silver | Gold | Platform |
|---|---|---|---|---|---|
| `svc-spark` | read/write/delete | read/write/delete | read/write/delete | read/write/delete | read/write/delete |
| `svc-polaris` | optional | read/write/delete | read/write/delete | read/write/delete | read/write/delete |
| `svc-airflow` | read | none | none | none | none |
| `svc-trino` | none | read | read | read | read |

Policy can be implemented with RGW bucket policies, user policies/caps, or an approved identity integration. The exact mechanism must be verified against the selected Ceph release.

Representative bucket-policy shape must separate bucket-level and object-level actions:

```json
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
```

Do not assume this exact JSON is sufficient until RGW policy behavior is tested with the selected release.

---

## 14. Polaris and Iceberg Compatibility Requirements

Increment 2 depends on this storage layer. Before Increment 2 starts, prove the storage-only client contract with:

- the Java S3-compatible client used by the verification suite
- path-style access
- configured endpoint override
- object create/read/delete
- multipart upload behavior for larger files
- deterministic list and read-after-write behavior using synthetic objects and prefixes
- credentials used by `svc-polaris`

Expected Polaris/Ceph storage settings will look conceptually like:

```text
s3.endpoint = https://object-store.stratus.local
s3.path-style-access = true
warehouse / base locations = s3://stratus-bronze, s3://stratus-silver, s3://stratus-gold, s3://stratus-platform
```

Increment 2 must be updated to refer to Ceph/object storage and the `STRATUS_S3_*` configuration contract.

---

## 15. Java Verification Module

The Java source and Maven dependencies in this section are build inputs only. The approved build system compiles and tests the module, packages the executable verifier, creates and scans the verifier image, publishes it by immutable digest, and records provenance. Operators execute that image; they do not build the module on the verification host or inside the verification container.

The verification suite proves the Stratus S3 storage contract against Ceph RGW.

### Maven dependencies

The verification module must pin an approved Java S3-compatible client library in its POM. This document does not mandate an AWS-branded SDK for the Ceph developer harness.

The chosen Java client must support:

- explicit Ceph RGW endpoint override
- path-style bucket addressing
- TLS validation through a Java truststore
- access key and secret key authentication against RGW
- multipart upload
- object create, read, list, delete, and metadata operations

The approved dependency and version must be recorded in the implementation runbook and kept aligned with the client behavior exercised by Polaris, Iceberg, Spark, Trino, and Flink.

### Configuration

| Variable | Description |
|---|---|
| `CEPH_RGW_ENDPOINT` | `https://object-store.stratus.local` |
| `CEPH_RGW_ACCESS_KEY` | verification RGW access key |
| `CEPH_RGW_SECRET_KEY` | verification RGW secret key |
| `S3_PATH_STYLE_ACCESS` | `true` for the first implementation |
| `CEPH_RGW_AIRFLOW_ACCESS_KEY` | optional `svc-airflow` RGW access key |
| `CEPH_RGW_AIRFLOW_SECRET_KEY` | optional `svc-airflow` RGW secret key |

If the chosen Java S3 client requires a signing-region or equivalent request-signing scope for Ceph RGW, that value belongs in the verification client configuration and implementation runbook for that client. It is not a Stratus platform region and must not be modeled as a storage architecture parameter.

### Verification expectations

The suite must verify:

- all five S3 buckets exist
- verification principal can write/read/delete a test object in each bucket
- object listing behaves as expected for Iceberg-style paths
- multipart upload works for a file larger than the single PUT threshold used by the SDK
- `svc-airflow` can list/read `stratus-landing`
- `svc-airflow` cannot write to `stratus-bronze`
- endpoint uses HTTPS
- path-style access works

The test class should be product-neutral and named:

```text
src/test/java/dev/mars/stratus/storage/S3StorageVerificationTest.java
```

### Running the suite

```bash
export CEPH_RGW_ENDPOINT=https://object-store.stratus.local
export CEPH_RGW_ACCESS_KEY=REPLACE_WITH_VERIFICATION_RGW_ACCESS_KEY
export CEPH_RGW_SECRET_KEY=REPLACE_WITH_VERIFICATION_RGW_SECRET_KEY
export S3_PATH_STYLE_ACCESS=true
export CEPH_RGW_AIRFLOW_ACCESS_KEY=REPLACE_WITH_SVC_AIRFLOW_RGW_ACCESS_KEY
export CEPH_RGW_AIRFLOW_SECRET_KEY=REPLACE_WITH_SVC_AIRFLOW_RGW_SECRET_KEY

podman run --rm \
  --env-file /etc/stratus/verifiers/storage.env \
  -v /etc/stratus/pki/stratus-ca.crt:/certs/stratus-ca.crt:ro,z \
  -v /data/stratus/evidence/increment1:/evidence:z \
  registry.stratus.local/stratus/storage-verifier:<version>@sha256:<digest>
```

All tests must pass before Increment 2 begins.

### Evidence bundle

Store the Increment 1 evidence bundle with the implementation record. It must include:

- selected Ceph release, deployment method, node/drive profile, pool profile, and RGW topology
- declared smoke-test thresholds for latency, error rate, retry rate, throughput, and concurrent access
- raw command or job references for the concurrency, throughput, metadata-heavy, and small-file stress runs
- observed p50/p95/p99 latency and 4xx/5xx/timeout/retry rates by operation where available
- Iceberg object-count, file-size, manifest, snapshot, delete-file, and orphan-cleanup measurements
- capacity model showing raw-to-usable ratio, growth assumptions, metadata overhead, and expansion trigger
- operator-effort record for install, failure drill, restore drill, credential rotation, observability setup, and any runbook fixes
- explicit pass/fail decision and any ADRs raised for failed thresholds

---

## 16. Operational Checks

### Ceph health

Use Ceph-native commands and the dashboard to verify:

- MON quorum healthy
- MGR active with standby available
- OSDs up and in
- no degraded or misplaced objects
- RGW reachable
- pools healthy
- capacity reported correctly

Representative checks:

```bash
ceph status
ceph health detail
ceph osd tree
ceph df
ceph orch ps
```

### RGW check

```bash
aws --endpoint-url https://object-store.stratus.local s3 ls
aws --endpoint-url https://object-store.stratus.local s3 ls s3://stratus-landing
```

These commands must work without disabling TLS validation in production-like environments.

### Write/read check

```bash
echo "stratus-ceph-verification" > /tmp/stratus-ceph-verification.txt
aws --endpoint-url https://object-store.stratus.local \
  s3 cp /tmp/stratus-ceph-verification.txt \
  s3://stratus-landing/verification/stratus-ceph-verification.txt

aws --endpoint-url https://object-store.stratus.local \
  s3 cp s3://stratus-landing/verification/stratus-ceph-verification.txt -
```

### Failure drill

For lab:

- stop or mark out one OSD
- confirm expected degraded state appears
- confirm read/write behavior matches pool `size` / `min_size` or EC policy
- restore the OSD
- confirm backfill/recovery completes
- confirm cluster returns to `HEALTH_OK` or documented acceptable state

For production-like:

- perform supported failure drills for OSD, MON, MGR, RGW, and load balancer paths
- attach evidence to the production readiness record

---

## 17. Completion Gates

### Lab gate

Increment 1 lab is complete when:

- [ ] approved Ceph release is selected and pinned
- [ ] the build pipeline publishes the storage verifier image by immutable digest with scan and provenance evidence
- [ ] lab runtime profile is recorded as Podman, Docker Engine, or Docker Desktop client harness only
- [ ] Ceph lab cluster is running
- [ ] RGW is reachable over HTTPS
- [ ] all five Stratus S3 buckets exist
- [ ] pool and CRUSH layout are documented
- [ ] platform service identities or lab equivalents exist
- [ ] access boundaries are enforced through RGW policy/user controls
- [ ] Java S3 verification suite passes
- [ ] concurrent synthetic S3, multipart, and small-object/prefix-listing baselines pass without later engines
- [ ] verifier execution uses the published image, protected configuration injection, read-only trust material, and dedicated evidence mount without source or build tools
- [ ] Ceph health checks show expected state
- [ ] lab failure drill behavior is documented

When the lab gate passes, Increment 2 engineering work can begin.

### Production-ready gate

The Ceph storage foundation is production-ready only when:

- [ ] production Ceph topology is approved
- [ ] production runtime profile is approved as Podman or Docker Engine; Docker Desktop is not used for production Ceph nodes
- [ ] MON quorum and MGR failover are configured and tested
- [ ] OSD storage layout, CRUSH rules, pool replication, and/or erasure coding are approved
- [ ] RGW is deployed redundantly behind the approved endpoint
- [ ] TLS works without insecure client flags
- [ ] service credentials are stored in the approved secret-management location
- [ ] bucket policies or equivalent access controls pass verification
- [ ] encryption model is approved and tested where required
- [ ] Java S3 verification passes against the production-like Ceph target
- [ ] Ceph health, failure, and recovery drills pass
- [ ] monitoring, alerting, audit logging, backup, restore, patching, and rotation runbooks exist
- [ ] Phase 1 operational readiness checklist accepts the storage layer

No production dataset should be onboarded based only on a single-node or non-secure Ceph developer topology.

---

## 18. Troubleshooting

### RGW endpoint unreachable

- Confirm DNS for `object-store.stratus.local`.
- Confirm RGW daemons are running.
- Confirm load balancer or DNS alias points to healthy RGW nodes.
- Confirm firewall access to the RGW HTTPS port.
- Confirm TLS certificate SAN includes the endpoint hostname.

### TLS validation fails

- Confirm the client trusts the issuing CA.
- Confirm RGW serves the expected certificate chain.
- Confirm Java truststores used by Spark, Trino, Polaris, and tests contain the CA.
- Avoid `--insecure` or disabled validation outside disposable lab bring-up.

### S3 client access denied

- Confirm the access key maps to the expected RGW user.
- Confirm bucket policy or user controls allow the requested bucket/object action.
- Confirm the request uses the intended bucket name.
- Confirm path-style access is enabled in the client where required.

### Iceberg or Polaris write failure

- Confirm `S3FileIO` endpoint override points to Ceph RGW.
- Confirm path-style access is enabled.
- Confirm `svc-polaris` can write to the target warehouse bucket.
- Confirm list/read/delete/multipart operations work for metadata and data paths.
- Check RGW logs and Ceph health for authorization, bucket-index, or pool issues.

### Ceph cluster unhealthy

- Check `ceph health detail`.
- Check MON quorum.
- Check OSD up/in status.
- Check pool fullness, degraded objects, misplaced objects, and backfill status.
- Check RGW daemon status and logs.
- Confirm time synchronization across nodes.
- Confirm disks and metadata devices are healthy.

---

## 19. References

- Ceph Object Gateway documentation: https://docs.ceph.com/en/latest/radosgw/
- Ceph Object Gateway S3 API: https://docs.ceph.com/en/latest/radosgw/s3/
- Ceph RGW bucket policies: https://docs.ceph.com/en/latest/radosgw/bucketpolicy/
- Ceph RGW encryption: https://docs.ceph.com/en/latest/radosgw/encryption/
- Cephadm documentation: https://docs.ceph.com/en/latest/cephadm/
- Cephadm new cluster deployment: https://docs.ceph.com/en/latest/cephadm/install/
- Ceph architecture: https://docs.ceph.com/en/latest/architecture/
- Ceph operations: https://docs.ceph.com/en/latest/rados/operations/
- Ceph dashboard: https://docs.ceph.com/en/latest/mgr/dashboard/
- Apache Ozone documentation: https://ozone.apache.org/docs/current/
- Apache Ozone S3 protocol: https://ozone.apache.org/docs/current/interface/s3.html
- Apache Ozone security: https://ozone.apache.org/docs/current/security.html
- Apache Iceberg S3 FileIO documentation: https://iceberg.apache.org/docs/latest/aws/
- Trino S3 file system support: https://trino.io/docs/current/object-storage/file-system-s3.html
- Stratus Phase 1 implementation plan: [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)
- Stratus architecture: [stratus_on_prem_data_fabric_architecture.md](stratus_on_prem_data_fabric_architecture.md)
