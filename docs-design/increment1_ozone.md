# Stratus Increment 1 - Apache Ozone Storage Foundation

> **Status: superseded.** The Stratus storage architecture decision ([stratus_on_prem_data_fabric_architecture.md section 2.8](stratus_on_prem_data_fabric_architecture.md#28-storage-architecture-decision)) selected **Ceph RGW** as the Phase 1 baseline. The active runbook is [increment1_ceph.md](increment1_ceph.md). This document preserves the evaluated Ozone path for a future architecture decision; its commands, versions, and configuration examples are not normative or maintained as part of the active Stratus implementation.

## 1. Purpose

This document is the technical implementation plan for Increment 1 of the Stratus platform as defined in [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md).

Increment 1 delivers the on-prem object-storage foundation consumed by Apache Polaris, Apache Iceberg, Spark, Airflow, Trino, and later Flink. This superseded variant describes the evaluated **Apache Ozone** implementation path.

When this increment is complete:

- Apache Ozone has been approved by the architecture decision for the target environment.
- The platform exposes an HTTPS S3-compatible endpoint through Ozone S3 Gateway.
- The five Stratus storage buckets exist.
- Platform service identities are isolated.
- TLS, Kerberos/Ranger-ready security, and operational checks are defined.
- A Java S3 verification suite proves the subset of S3 behavior required by Stratus.
- The storage layer is ready for Polaris and Iceberg in Increment 2.

This document intentionally separates two concerns:

- **Ozone as the production platform:** Ozone Manager, Storage Container Manager, Datanodes, S3 Gateway, Recon, security, erasure coding, and operations.
- **S3 as the client contract:** the compatibility surface required by Polaris/Iceberg/Spark/Trino/Airflow.

---

## 2. Storage Decision

The storage architecture decision, candidate comparison, scoring, and proof-of-fit gate are owned by [stratus_on_prem_data_fabric_architecture.md](stratus_on_prem_data_fabric_architecture.md#28-storage-architecture-decision). This document is an implementation variant for **Apache Ozone** and applies only if the architecture decision selects Ozone as the Increment 1 storage target.

### Implementation target

| Decision area | Baseline choice |
|---|---|
| Product | Apache Ozone |
| License | Apache 2.0 |
| Deployment model | on-prem distributed object store |
| Stratus client API | S3-compatible API through Ozone S3 Gateway |
| Native storage model | Ozone volumes and buckets |
| Durability | Ozone replication and/or erasure coding |
| Governance alignment | Kerberos, Ranger integration, ACLs, audit-capable security model |
| Operations | Ozone Manager, Storage Container Manager, Datanodes, Recon |
| Production DNS alias | `object-store.stratus.local` |

### Known Ozone S3 caveat

Ozone S3 Gateway is S3-compatible, but it is not a complete AWS S3 clone. Some AWS S3 features are unsupported or implemented differently. Do not design Stratus around AWS-only assumptions such as full AWS bucket-policy behavior, AWS lifecycle configuration, object lock, S3-native replication, or S3-native server-side encryption unless verified against the selected Ozone release.

For Stratus, this is acceptable if the following are true:

- Polaris/Iceberg/Spark/Trino can read and write through the required S3 subset.
- Bucket and object access controls are enforced through Ozone/Ranger/Kerberos rather than assumed AWS IAM behavior.
- Encryption, audit, retention, and recovery use Ozone-supported mechanisms.

---

## 3. Stratus Storage Contract

Every storage target for Increment 1 must satisfy this contract. For this document, the target is Ozone.

### Endpoint contract

| Requirement | Contract |
|---|---|
| API | S3-compatible object API exposed by Ozone S3 Gateway |
| Endpoint | `https://object-store.stratus.local` or environment-specific equivalent |
| Transport | HTTPS only |
| Path-style access | Required for first implementation unless all clients pass virtual-hosted validation |
| Region | `us-east-1` unless Ozone/client compatibility testing requires another fixed value |
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

Ozone internally organizes data as volumes and buckets. The Stratus convention is:

```text
ofs://ozone1/stratus/landing
ofs://ozone1/stratus/bronze
ofs://ozone1/stratus/silver
ofs://ozone1/stratus/gold
ofs://ozone1/stratus/platform
```

The S3 Gateway must expose these to S3 clients as:

```text
s3://stratus-landing
s3://stratus-bronze
s3://stratus-silver
s3://stratus-gold
s3://stratus-platform
```

If the selected Ozone release or S3 Gateway mapping requires a different native volume/bucket arrangement, record that in the runbook and keep the S3 bucket names stable.

### Service identity contract

| Principal | Minimum object-storage access |
|---|---|
| `svc-spark` | read/write/delete landing, bronze, silver, gold, platform |
| `svc-polaris` | read/write/delete bronze, silver, gold, platform; landing only if needed by catalog bootstrap |
| `svc-airflow` | read landing; no write access to bronze/silver/gold |
| `svc-trino` | read bronze for internal verification, read silver/gold/platform for query serving and quality visibility |

In production, these identities should be Kerberos principals and/or Ranger-managed service identities. For S3 clients, access keys must map to those identities through the Ozone S3 Gateway credential mechanism approved for the environment.

### Security contract

- TLS is required for S3 Gateway traffic.
- Kerberos-secured Ozone is the production target.
- Ranger integration is the policy path for production-like authorization.
- Root/admin credentials must not be used by platform applications.
- Service credentials must be stored outside source control.
- Credential rotation must be documented before production readiness.
- At-rest encryption must use Ozone-supported transparent data encryption or another approved Ozone/KMS model.
- Bucket policies alone are not the enterprise authorization layer.

### Recovery and operations contract

Production-like Ozone must define:

- Ozone Manager HA
- Storage Container Manager HA
- Datanode failure tolerance
- replication and erasure-coding policy
- metadata backup and recovery
- key material backup and recovery
- Recon monitoring
- capacity thresholds
- request/error metrics
- audit log collection
- failure drill and restore-test cadence

---

## 4. Ozone Production Architecture

### Core services

| Component | Responsibility |
|---|---|
| Ozone Manager (OM) | namespace metadata, volumes, buckets, keys, ACLs |
| Storage Container Manager (SCM) | block/container placement, pipeline management, datanode coordination |
| Datanodes | store data containers on local disks |
| S3 Gateway | exposes S3-compatible API to Polaris, Spark, Trino, Airflow, and verification tools |
| Recon | operational insight, cluster health, namespace and container visibility |
| Ranger | production authorization policy integration |
| Kerberos KDC | production authentication |
| KMS | transparent data encryption key management |

### Production topology

The production-like topology should be at least:

| Role | Count | Notes |
|---|---:|---|
| Ozone Manager | 3 | HA quorum |
| Storage Container Manager | 3 | HA quorum |
| Datanode | 3+ | scale by capacity and failure-domain requirements |
| S3 Gateway | 2+ | behind load balancer or DNS alias |
| Recon | 1+ | operational UI/API; HA according to runbook |
| Ranger | from Increment 6 | policy integration; lab may defer full enforcement |
| KMS | production-like only | required for transparent data encryption |

Example host layout:

| Host | Roles |
|---|---|
| `ozone-master1.stratus.local` | OM, SCM |
| `ozone-master2.stratus.local` | OM, SCM |
| `ozone-master3.stratus.local` | OM, SCM, Recon |
| `ozone-dn1.stratus.local` | Datanode |
| `ozone-dn2.stratus.local` | Datanode |
| `ozone-dn3.stratus.local` | Datanode |
| `ozone-s3g1.stratus.local` | S3 Gateway |
| `ozone-s3g2.stratus.local` | S3 Gateway |

```text
                object-store.stratus.local
                         |
              Load balancer / DNS alias
                         |
              +----------+----------+
              |                     |
        ozone-s3g1             ozone-s3g2
              |                     |
              +----------+----------+
                         |
              Ozone service quorum
        OM HA + SCM HA + Datanodes
                         |
              Ozone volumes / buckets
```

For a smaller lab, co-locate roles on fewer hosts only if the runbook labels the environment as non-production and does not use the result as production readiness evidence.

---

## 5. Assumptions and Prerequisites

- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- JDK supported by the selected Ozone release
- DNS resolution for all Ozone service hosts and `object-store.stratus.local`
- time synchronization across all nodes
- dedicated storage paths for Ozone Datanodes
- firewall rules opened for selected Ozone service ports
- TLS certificates for S3 Gateway and Ozone web endpoints
- Kerberos and Ranger planned for production-like environments
- Maven 3.9+ and JDK 25 on the approved build worker; the verification host requires only the approved container runtime and verifier runtime inputs

### Version discipline

Before implementation:

- select an approved Apache Ozone release
- read the matching Ozone documentation for that exact release
- pin all artifacts, images, packages, or tarballs
- record Java compatibility
- record S3 Gateway client compatibility for Spark/Flink/Trino
- record S3 Gateway limitations for that release

Do not copy commands from a different Ozone documentation version without checking compatibility.

---

## 6. Ports

Confirm exact ports against the selected Ozone release and runbook. Common Ozone service ports include:

| Component | Common port | Purpose |
|---|---:|---|
| S3 Gateway | 9878 | S3-compatible endpoint |
| Ozone Manager RPC | 9862 | client/service RPC |
| Ozone Manager HTTP | 9874 | OM web UI/API |
| Storage Container Manager client RPC | 9860 | SCM client RPC |
| Storage Container Manager datanode RPC | 9861 | SCM datanode RPC |
| Storage Container Manager HTTP | 9876 | SCM web UI/API |
| Datanode HTTP | 9882 | datanode web UI/API |
| Recon HTTP | 9888 | Recon UI/API |

Production exposure:

- expose S3 Gateway only through the approved HTTPS endpoint
- restrict OM/SCM/Datanode/Recon ports to administrative and cluster networks
- use TLS where supported and required
- document firewall rules in the runbook

---

## 7. TLS, Kerberos, Ranger, and KMS

### TLS

For lab use, self-signed certificates are acceptable. For production-like use, use the platform PKI path from the identity/security increment.

The S3 Gateway certificate must include:

- `object-store.stratus.local`
- each S3 Gateway host, such as `ozone-s3g1.stratus.local`
- any environment-specific aliases

All Java clients must trust the issuing CA. Production-like commands and tests must not require `--insecure`, `-k`, or disabled certificate validation.

### Kerberos

Production Ozone should be Kerberos-secured. Service principals should be planned for:

- Ozone Manager
- Storage Container Manager
- Datanodes
- S3 Gateways
- Recon
- platform service users that require native Ozone access

### Ranger

Ranger is the preferred production policy plane for Ozone. Increment 1 can bootstrap with Ozone ACLs if Ranger is not yet deployed, but the production design must document how Ranger policies will replace or govern access in Increment 6.

### KMS and encryption

Production-like environments must define transparent data encryption or equivalent at-rest protection. Do not assume AWS S3 server-side encryption headers are the right control path for Ozone unless verified against the selected release.

---

## 8. Ozone Installation Plan

Use the official Apache Ozone installation documentation for the selected release. This section defines the Stratus deployment shape and required configuration areas; exact commands belong in the release-pinned runbook.

### Filesystem layout

Recommended baseline:

| Path | Purpose |
|---|---|
| `/opt/ozone` | Ozone installation |
| `/etc/ozone` | Ozone configuration |
| `/var/log/ozone` | service logs |
| `/data/ozone/scm` | SCM metadata |
| `/data/ozone/om` | OM metadata |
| `/data/ozone/datanode` | datanode storage path |

Use separate disks or volumes for datanode storage in production-like environments. Do not claim production durability from multiple directories on one disk.

### Configuration areas

The runbook must configure:

- Ozone service IDs for OM HA and SCM HA
- OM node list and RPC addresses
- SCM node list and RPC addresses
- Datanode storage directories
- S3 Gateway binding address and port
- Recon endpoint
- TLS settings
- Kerberos settings for production-like environments
- Ranger plugin settings when Ranger is introduced
- KMS settings for encrypted buckets/volumes
- replication and erasure-coding defaults

### Service management

Use systemd units or the distribution-supported service manager for each Ozone daemon:

- `ozone-om`
- `ozone-scm`
- `ozone-datanode`
- `ozone-s3g`
- `ozone-recon`

Operators should be able to use:

```bash
sudo systemctl status ozone-om
sudo systemctl status ozone-scm
sudo systemctl status ozone-datanode
sudo systemctl status ozone-s3g
sudo systemctl status ozone-recon
```

---

## 9. Developer and Lab Topology

### Developer topology

Use the Ozone project-provided Docker Compose or development container topology for local validation when available for the selected release.

Developer validation should prove:

- S3 Gateway starts
- the five Stratus buckets can be created
- AWS SDK path-style access works
- the Java S3 verification suite runs

Developer topology is not a durability, HA, Kerberos, Ranger, or production security test.

### Lab topology

The lab should be a reduced but representative Ozone deployment:

- at least one OM
- at least one SCM
- at least three Datanodes if testing replication/failure behavior
- at least one S3 Gateway
- Recon enabled
- TLS on S3 Gateway

If the lab cannot run HA OM/SCM, label the limitation clearly. Production readiness still requires HA validation.

---

## 10. Stratus Volume, Bucket, and Identity Setup

### Native Ozone namespace

Create a Stratus volume and buckets using the Ozone CLI or Object Store shell for the selected release.

Conceptual native layout:

```text
volume: stratus
  bucket: landing
  bucket: bronze
  bucket: silver
  bucket: gold
  bucket: platform
```

The exact command syntax must be verified against the selected Ozone release. A representative flow is:

```bash
ozone sh volume create /stratus
ozone sh bucket create /stratus/landing
ozone sh bucket create /stratus/bronze
ozone sh bucket create /stratus/silver
ozone sh bucket create /stratus/gold
ozone sh bucket create /stratus/platform
```

### S3 bucket names

Expose or create S3-visible buckets matching the Stratus bucket contract:

```bash
aws --endpoint-url https://object-store.stratus.local s3 mb s3://stratus-landing
aws --endpoint-url https://object-store.stratus.local s3 mb s3://stratus-bronze
aws --endpoint-url https://object-store.stratus.local s3 mb s3://stratus-silver
aws --endpoint-url https://object-store.stratus.local s3 mb s3://stratus-gold
aws --endpoint-url https://object-store.stratus.local s3 mb s3://stratus-platform
```

Use the Ozone-supported S3 authentication flow to create access keys for the platform service identities. The runbook must record how each S3 access key maps back to an Ozone/Kerberos/Ranger identity.

### Service access policy

Initial lab policy may be implemented with Ozone ACLs. Production-like policy should be implemented through Ranger when Ranger is available.

Minimum access boundaries:

| Principal | Landing | Bronze | Silver | Gold | Platform |
|---|---|---|---|---|---|
| `svc-spark` | read/write/delete | read/write/delete | read/write/delete | read/write/delete | read/write/delete |
| `svc-polaris` | optional | read/write/delete | read/write/delete | read/write/delete | read/write/delete |
| `svc-airflow` | read | none | none | none | none |
| `svc-trino` | none | read | read | read | read |

Do not rely on AWS IAM policy examples unless Ozone S3 Gateway behavior for the selected release has been verified. Prefer Ozone ACLs and Ranger policy semantics.

---

## 11. Polaris and Iceberg Compatibility Requirements

Increment 2 depends on this storage layer. Before Increment 2 starts, prove the storage-only client contract with:

- AWS SDK for Java S3 client
- path-style access
- configured endpoint override
- object create/read/delete
- deterministic list and read-after-write behavior using synthetic objects and prefixes
- credentials used by `svc-polaris`

Expected Polaris/Ozone storage settings will look conceptually like:

```text
s3.endpoint = https://object-store.stratus.local
s3.path-style-access = true
warehouse / base locations = s3://stratus-bronze, s3://stratus-silver, s3://stratus-gold, s3://stratus-platform
```

Increment 2 must be updated to refer to Ozone/object storage.

Polaris binding, Iceberg metadata behavior, Spark write throughput, Trino scan throughput, and concurrent engine access are deferred to their owning increments and Phase 1 readiness. Any failure reopens the storage qualification with an impact assessment, remediation or ADR, and retest criteria.

---

## 12. Java Verification Module

The Java source and Maven dependencies in this section are build inputs only. The approved build system publishes the executable verifier as a pinned container image. Operators execute that image and do not build on the verification host or inside the verification container.

The verification suite proves the Stratus S3 storage contract against Ozone S3 Gateway.

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

export STRATUS_STORAGE_VERIFIER_IMAGE=registry.stratus.local/stratus/storage-verifier:<version>@sha256:<digest>
podman run --rm --env-file /etc/stratus/verifiers/storage.env \
  -v /data/stratus/evidence/increment1:/evidence:z \
  ${STRATUS_STORAGE_VERIFIER_IMAGE}
```

All tests must pass before Increment 2 begins.

---

## 13. Operational Checks

### Ozone service health

Use Ozone-native commands and Recon to verify:

- OM quorum healthy
- SCM quorum healthy
- Datanodes healthy
- S3 Gateway reachable
- volumes and buckets visible
- no under-replicated or unhealthy containers

Representative checks:

```bash
ozone admin om roles
ozone admin scm roles
ozone admin datanode list
```

Confirm through Recon that the cluster is healthy and capacity is reported correctly.

### S3 Gateway check

```bash
aws --endpoint-url https://object-store.stratus.local s3 ls
aws --endpoint-url https://object-store.stratus.local s3 ls s3://stratus-landing
```

These commands must work without disabling TLS validation in production-like environments.

### Write/read check

```bash
echo "stratus-ozone-verification" > /tmp/stratus-ozone-verification.txt
aws --endpoint-url https://object-store.stratus.local \
  s3 cp /tmp/stratus-ozone-verification.txt \
  s3://stratus-landing/verification/stratus-ozone-verification.txt

aws --endpoint-url https://object-store.stratus.local \
  s3 cp s3://stratus-landing/verification/stratus-ozone-verification.txt -
```

### Failure drill

For lab:

- stop one Datanode
- confirm expected under-replication or degraded status appears
- confirm read/write behavior matches the configured replication/EC policy
- restart the Datanode
- confirm the cluster returns to healthy state

For production-like:

- perform the Ozone-supported failure drill for Datanode, OM, SCM, and S3 Gateway according to the HA design
- attach evidence to the production readiness record

---

## 14. Completion Gates

### Lab gate

Increment 1 lab is complete when:

- [ ] approved Apache Ozone release is selected and pinned
- [ ] Ozone lab cluster is running
- [ ] S3 Gateway is reachable over HTTPS
- [ ] all five Stratus S3 buckets exist
- [ ] native Ozone volume/bucket mapping is documented
- [ ] platform service identities or lab equivalents exist
- [ ] access boundaries are enforced through Ozone ACLs or Ranger
- [ ] Java S3 verification suite passes
- [ ] Recon or Ozone-native health checks show expected state
- [ ] lab failure drill behavior is documented

When the lab gate passes, Increment 2 engineering work can begin.

### Production-ready gate

The Ozone storage foundation is production-ready only when:

- [ ] production Ozone topology is approved
- [ ] OM HA and SCM HA are configured and tested
- [ ] Datanode storage layout, replication, and erasure coding are approved
- [ ] S3 Gateway is deployed redundantly behind the approved endpoint
- [ ] Kerberos security is configured
- [ ] Ranger policy path is configured or explicitly scheduled for Increment 6 with interim controls
- [ ] KMS/encryption model is configured for sensitive zones
- [ ] TLS works without insecure client flags
- [ ] service credentials are stored in the approved secret-management location
- [ ] Java S3 verification passes against the production-like Ozone target
- [ ] Ozone-native health, failure, and recovery drills pass
- [ ] monitoring, alerting, audit logging, backup, restore, patching, and rotation runbooks exist
- [ ] Phase 1 operational readiness checklist accepts the storage layer

No production dataset should be onboarded based only on a single-node or non-secure Ozone developer topology.

---

## 15. Troubleshooting

### S3 Gateway unreachable

- Confirm DNS for `object-store.stratus.local`.
- Confirm S3 Gateway is running.
- Confirm load balancer or DNS alias points to healthy S3 Gateway nodes.
- Confirm firewall access to the S3 Gateway port.
- Confirm TLS certificate SAN includes the endpoint hostname.

### TLS validation fails

- Confirm the client trusts the issuing CA.
- Confirm the S3 Gateway serves the expected certificate chain.
- Confirm Java truststores used by Spark, Trino, Polaris, and tests contain the CA.
- Avoid `--insecure` or disabled validation outside disposable lab bring-up.

### S3 client access denied

- Confirm the access key maps to the expected Ozone identity.
- Confirm Ozone ACLs or Ranger policies allow the requested bucket/object action.
- Confirm the request uses the intended bucket name.
- Confirm path-style access is enabled in the client where required.

### Iceberg or Polaris write failure

- Confirm `S3FileIO` endpoint override points to Ozone S3 Gateway.
- Confirm path-style access is enabled.
- Confirm `svc-polaris` can write to the target warehouse bucket.
- Confirm list/read/delete operations work for metadata paths.
- Check S3 Gateway logs and Ozone Manager logs for authorization or namespace errors.

### Ozone cluster unhealthy

- Check OM and SCM leadership/quorum.
- Check Datanode status.
- Check Recon for unhealthy containers or capacity pressure.
- Confirm time synchronization across nodes.
- Confirm disks and metadata directories are writable.

---

## 16. References

- Apache Ozone documentation: https://ozone.apache.org/docs/current/
- Apache Ozone S3 protocol: https://ozone.apache.org/docs/current/interface/s3.html
- Apache Ozone security: https://ozone.apache.org/docs/current/security.html
- Apache Ozone Recon: https://ozone.apache.org/docs/current/feature/recon.html
- Ceph Object Gateway documentation: https://docs.ceph.com/en/latest/radosgw/
- AWS SDK for Java S3: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/home.html
- Stratus Phase 1 implementation plan: [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)
- Stratus architecture: [stratus_on_prem_data_fabric_architecture.md](stratus_on_prem_data_fabric_architecture.md)
