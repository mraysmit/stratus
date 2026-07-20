# ADR-P1-001: Ceph RGW Storage Baseline

- Status: Accepted
- Date: 2026-07-14
- Task: `P1-1.1-S1`
- Decision owners: Platform architect and architecture owner
- Acceptance basis: owner direction that Increment 1 development uses a realistic Ceph environment in local Docker

## Context

Stratus requires an open-source, on-premises object-storage foundation with an S3-compatible endpoint. The developer track requires a disposable genuine Ceph/RGW target that starts locally with Docker. The production track separately requires a supported multi-host topology with explicit failure domains, durable storage, secure endpoints, and operated recovery.

Build and verification remain separate. The build system produces the Stratus verifier artifact and immutable verifier image. The Docker verification environment pulls and executes that prebuilt image; it never compiles source or builds the verifier image.

## Decision

Ceph Tentacle 20.2.2 is the Increment 1 baseline. The developer environment deploys genuine Ceph MON, MGR, BlueStore OSD, and RGW daemons from the official Ceph image in local Docker Compose. An HTTPS proxy presents `object-store.stratus.local`, and the prebuilt rclone and Stratus verifier containers exercise that RGW endpoint.

The runtime profiles are:

| Profile | Deployment | Valid evidence |
|---|---|---|
| Disposable developer environment | Docker Desktop or Docker Engine Compose; separate containers run three MONs, two MGRs, three BlueStore OSDs, and two RGWs; a separate proxy terminates local TLS | Ceph/RGW version and health, container-level quorum and failover, replicated-pool degradation/recovery, real S3 compatibility, TLS trust, verifier behavior, repeatable start/stop/reset |
| Representative lab | Linux hosts with Podman or Docker Engine and cephadm, explicit OSD devices and failure domains, redundant services where required | The production characteristics actually represented by its topology and completed drills |
| Production | Approved Linux hosts with cephadm, durable multi-host MON/MGR/OSD/RGW topology, managed TLS, monitoring, backup, and recovery | Production gates only |

The Docker developer environment is intentionally single-host and disposable. It does prove monitor quorum, manager active/standby state, replicated placement across distinct container-level CRUSH hosts, one-OSD degraded client operation/recovery, and one-RGW failover. It does not claim physical-device, Docker-host, rack, or site failure tolerance; production CRUSH failure-domain correctness; production capacity or durability; or production operational readiness.

Production Ceph remains cephadm-managed. The local Compose topology is not promoted into production.

## Immutable Ceph image

The official image digests were verified on 2026-07-14 from the `quay.io/ceph/ceph:v20.2.2` OCI index:

| Target | Immutable image reference |
|---|---|
| Multi-architecture index | `quay.io/ceph/ceph:v20.2.2@sha256:6b4b5ae33acd3d736eb26d2a19238bce71a22f9cfb99cca887ba6312d0957644` |
| Linux AMD64 | `quay.io/ceph/ceph:v20.2.2@sha256:55a5c2014b4db34589ad8886606409727f633319131bb663d37e0d489e350703` |
| Linux ARM64 v8 | `quay.io/ceph/ceph:v20.2.2@sha256:99f4c2f3c65c21d9186e07d0fe6842f5b6ce635eda9d4d0c9b37cd0575436e92` |

Automation must not deploy the mutable tag alone.

## Verified developer proof

On 2026-07-14 the local Docker implementation demonstrated:

- Ceph Tentacle 20.2.2 with three MONs in quorum
- active/standby MGR pair
- three BlueStore OSDs `up` and `in` on distinct container-level CRUSH hosts
- replicated RGW pools with all placement groups `active+clean`
- two RGWs behind the HTTPS proxy
- trusted HTTPS access through `object-store.stratus.local`
- creation and listing of all five Stratus buckets with rclone
- a real S3 PUT, GET with exact content, and DELETE through RGW
- the prebuilt Java verifier passed bucket discovery, object round trip, HEAD and prefix listing, multipart upload, and cleanup
- an untrusted client was rejected and the plaintext RGW port was not published
- monitor quorum converged to two members during one-MON loss and recovered to three
- rclone and the Java verifier passed while one RGW was offline
- rclone and the Java verifier passed while one OSD was offline; the cluster then recovered to `HEALTH_OK` with 321 placement groups `active+clean`
- destructive reset, clean recreation, and re-verification passed

Steady-state health is `HEALTH_OK`. Expected warnings during deliberate failure drills are observed and retained; they are not suppressed or represented as production resilience.

## Alternatives considered

### Client-only Compose against an external endpoint

Rejected as the default developer path. It does not meet the requirement for a disposable local Ceph target and makes development depend on external infrastructure.

### Unrelated S3-compatible substitute

Rejected for Increment 1 verification because it does not prove Ceph RGW behavior.

### cephadm inside a separate developer VM

Not required for the developer profile. It remains useful for a representative lab and is the production lifecycle model, but it must not replace the required local Docker environment.

## Consequences

- `platform/ceph/developer/compose.yaml` deploys Ceph, the HTTPS proxy, rclone, and the prebuilt verifier image.
- Compose contains no verifier `build` section and startup invokes no Maven command.
- Ceph runtime initialization is deployment configuration; it does not compile Ceph or Stratus artifacts.
- Developer volumes are disposable and may be removed only by the explicit reset operation.
- Production acceptance still requires the separate cephadm production tasks and evidence.

## Reconsideration triggers

Re-open this decision when:

- a newer approved Tentacle patch is selected
- a security advisory requires an image change
- Docker Desktop cannot run the pinned image on a supported developer architecture
- RGW fails a required S3, policy, encryption, or verifier contract
- the production support or topology decision changes

## Authoritative references

- Ceph Tentacle release notes: https://docs.ceph.com/en/latest/releases/tentacle/
- Ceph release timeline: https://docs.ceph.com/en/latest/releases/
- Ceph Object Gateway: https://docs.ceph.com/en/tentacle/radosgw/
- Ceph S3 API: https://docs.ceph.com/en/tentacle/radosgw/s3/
- Cephadm production lifecycle: https://docs.ceph.com/en/tentacle/cephadm/
