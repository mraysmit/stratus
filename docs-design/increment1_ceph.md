# Stratus Increment 1 - Ceph Object Storage Foundation

## 1. Purpose

This document is the technical implementation plan for Increment 1 of the Stratus platform as defined in [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md).

Increment 1 delivers the on-prem object-storage foundation consumed by Apache Polaris, Apache Iceberg, Spark, Airflow, Trino, and later Flink. This version of the increment uses **Ceph Object Gateway (RGW)** as the production S3-compatible storage target.

When this increment is complete:

- Ceph RGW is the selected production object-storage target.
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

The storage architecture decision, candidate comparison, scoring, and proof-of-fit gate are owned by [on_prem_data_fabric_architecture.md](on_prem_data_fabric_architecture.md#28-storage-architecture-decision). This increment implements the current candidate baseline selected there: **Ceph Object Gateway (RGW)** on a production Ceph cluster.

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
- Maven 3.9+ and JDK 21+ on the verification host

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
| Developer workstation | Docker Desktop with Docker Compose | Run AWS CLI and Java verification containers against an existing RGW endpoint | No |
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

For Iceberg workloads, validate small-file and metadata-heavy behavior. Iceberg metadata can create many small objects and list operations, so the RGW pool and bucket-index behavior must be part of testing.

### RGW service

The RGW layer must define:

- realm, zonegroup, and zone strategy, even if single-site
- RGW frontend configuration
- TLS endpoint
- load balancer or DNS alias
- access log and audit settings
- service restart and failover process

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

Bootstrap on the first Ceph host:

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

Enable the Ceph CLI:

```bash
sudo cephadm shell -- ceph status
sudo cephadm install ceph-common
ceph status
```

Add lab hosts:

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

Add OSDs using the approved lab devices. For a disposable lab where all unused devices may be consumed:

```bash
ceph orch apply osd --all-available-devices
```

For a safer lab, explicitly list devices in the runbook and add only those devices. Never run `--all-available-devices` on a host that has disks not dedicated to Ceph.

Deploy RGW:

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
- SDK path-style access works
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
- `STRATUS_S3_ENDPOINT`, `STRATUS_S3_ACCESS_KEY`, and `STRATUS_S3_SECRET_KEY` are stored in a local `.env` file outside source control
- the Java verification source tree is available on the workstation

For Windows workstations, run Docker Compose from PowerShell or WSL, but keep path handling consistent. If the repository is mounted from Windows into Linux containers, confirm line endings and volume paths before running the Java verification suite.

### Developer Podman setup

On a Linux developer host, the same client harness can be run with Podman Compose or a compatible `podman compose` workflow if that is the local standard. The harness still targets an existing RGW endpoint; it does not replace the cephadm lab.

The developer Podman path is useful when engineers want their local client runtime to match the production Podman profile more closely. It still does not prove production storage durability, HA, or recovery.

### Compose directory layout

```text
deploy/ceph-rgw-client-compose/
  compose.yaml
  .env
  certs/
    stratus-ca.crt
```

### `.env`

```bash
STRATUS_S3_ENDPOINT=https://object-store.stratus.local
STRATUS_S3_ACCESS_KEY=<verification access key>
STRATUS_S3_SECRET_KEY=<verification secret key>
AWS_DEFAULT_REGION=us-east-1
```

### `compose.yaml`

```yaml
services:
  awscli:
    image: public.ecr.aws/aws-cli/aws-cli:2
    entrypoint: ["sleep", "infinity"]
    environment:
      AWS_ACCESS_KEY_ID: ${STRATUS_S3_ACCESS_KEY}
      AWS_SECRET_ACCESS_KEY: ${STRATUS_S3_SECRET_KEY}
      AWS_DEFAULT_REGION: ${AWS_DEFAULT_REGION}
      AWS_CA_BUNDLE: /certs/stratus-ca.crt
      STRATUS_S3_ENDPOINT: ${STRATUS_S3_ENDPOINT}
    volumes:
      - ./certs/stratus-ca.crt:/certs/stratus-ca.crt:ro

  verifier:
    image: eclipse-temurin:21
    working_dir: /workspace
    entrypoint: ["sleep", "infinity"]
    environment:
      STRATUS_S3_ENDPOINT: ${STRATUS_S3_ENDPOINT}
      STRATUS_S3_ACCESS_KEY: ${STRATUS_S3_ACCESS_KEY}
      STRATUS_S3_SECRET_KEY: ${STRATUS_S3_SECRET_KEY}
    volumes:
      - ../..:/workspace:ro
      - ./certs/stratus-ca.crt:/certs/stratus-ca.crt:ro
```

Start the harness:

```bash
docker compose up -d
```

For a Podman-based developer harness, use the locally approved equivalent:

```bash
podman compose up -d
```

Run S3 smoke checks:

```bash
docker compose exec awscli \
  aws s3 --endpoint-url "$STRATUS_S3_ENDPOINT" ls

docker compose exec awscli \
  aws s3 --endpoint-url "$STRATUS_S3_ENDPOINT" ls s3://stratus-landing
```

Run the Java verification suite after Maven is available inside the verifier container or mounted from the host:

```bash
docker compose exec verifier mvn test -Dtest=S3StorageVerificationTest
```

If the image does not trust the CA by default, update the container trust store or use a project-specific Java truststore. Do not add `--no-verify-ssl` to routine tests; that would bypass a core Increment 1 requirement.

For Docker Desktop, expected developer evidence is:

- `docker compose ps` shows the client harness containers running
- AWS CLI can list the five Stratus buckets through the RGW endpoint
- Java verification passes with TLS validation enabled
- no secrets are committed to the repository

For Podman developer workstations, capture the same evidence with the equivalent `podman compose` commands.

### What Compose validates

The Compose harness validates:

- the five Stratus buckets can be created
- AWS SDK path-style access works
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
- AWS SDK path-style access works
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

Use AWS CLI or another S3-compatible client pointed at RGW:

```bash
aws --endpoint-url https://object-store.stratus.local s3 mb s3://stratus-landing
aws --endpoint-url https://object-store.stratus.local s3 mb s3://stratus-bronze
aws --endpoint-url https://object-store.stratus.local s3 mb s3://stratus-silver
aws --endpoint-url https://object-store.stratus.local s3 mb s3://stratus-gold
aws --endpoint-url https://object-store.stratus.local s3 mb s3://stratus-platform
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

Increment 2 depends on this storage layer. Before Increment 2 starts, prove that Ceph RGW works with:

- AWS SDK for Java S3 client
- Iceberg `S3FileIO`
- path-style access
- configured endpoint override
- object create/read/delete
- multipart upload behavior for larger files
- listing behavior required by Iceberg metadata operations
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

The verification suite proves the Stratus S3 storage contract against Ceph RGW.

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

| Variable | Description |
|---|---|
| `STRATUS_S3_ENDPOINT` | `https://object-store.stratus.local` |
| `STRATUS_S3_ACCESS_KEY` | verification/admin access key |
| `STRATUS_S3_SECRET_KEY` | verification/admin secret key |
| `STRATUS_S3_AIRFLOW_ACCESS_KEY` | optional `svc-airflow` access key |
| `STRATUS_S3_AIRFLOW_SECRET_KEY` | optional `svc-airflow` secret key |

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
export STRATUS_S3_ENDPOINT=https://object-store.stratus.local
export STRATUS_S3_ACCESS_KEY=<verification access key>
export STRATUS_S3_SECRET_KEY=<verification secret key>
export STRATUS_S3_AIRFLOW_ACCESS_KEY=<svc-airflow access key>
export STRATUS_S3_AIRFLOW_SECRET_KEY=<svc-airflow secret key>

mvn test -pl . -Dtest=S3StorageVerificationTest
```

All tests must pass before Increment 2 begins.

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
- [ ] lab runtime profile is recorded as Podman, Docker Engine, or Docker Desktop client harness only
- [ ] Ceph lab cluster is running
- [ ] RGW is reachable over HTTPS
- [ ] all five Stratus S3 buckets exist
- [ ] pool and CRUSH layout are documented
- [ ] platform service identities or lab equivalents exist
- [ ] access boundaries are enforced through RGW policy/user controls
- [ ] Java S3 verification suite passes
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
- Apache Iceberg AWS/S3 storage documentation: https://iceberg.apache.org/docs/latest/aws/
- Trino S3 file system support: https://trino.io/docs/current/object-storage/file-system-s3.html
- AWS SDK for Java S3: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/home.html
- Stratus Phase 1 implementation plan: [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)
- Stratus architecture: [on_prem_data_fabric_architecture.md](on_prem_data_fabric_architecture.md)
