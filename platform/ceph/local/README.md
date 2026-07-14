# Local Docker Ceph/RGW Environment

This is the Increment 1 disposable developer environment. It deploys a genuine Ceph Tentacle 20.2.2 cluster locally in Docker, exposes RGW through trusted HTTPS, and runs prebuilt S3 client and Stratus verifier images against it.

It does not use an S3 mock and it does not require an external Ceph endpoint.

## Services

| Service | Purpose |
|---|---|
| `mon1`-`mon3` | Three genuine Ceph monitors forming a quorum |
| `mgr1`-`mgr2` | Genuine active/standby Ceph managers |
| `osd1`-`osd3` | Three genuine BlueStore OSDs, each with its own disposable Docker volume and CRUSH host |
| `rgw1`-`rgw2` | Two genuine RGW daemons backed by the Ceph cluster |
| `rgw-proxy` | TLS termination for `https://object-store.stratus.local:8443` |
| `s3client` | Prebuilt rclone S3 client |
| `verifier` | Prebuilt immutable Stratus Java verifier image |
| `verifier-untrusted` | The same prebuilt image without the lab CA, used only to prove TLS fails closed |

Compose never builds the verifier or runs Maven. The build system produces the verifier image before it is deployed here.

## What it proves

- the selected Ceph release starts in local Docker
- MON, MGR, BlueStore OSD, and RGW are genuine Ceph daemons
- all three monitors form quorum and one monitor can be lost without losing quorum
- one manager is active and the second is standby
- all three OSDs become `up` and `in`, with replicated pools and distinct container-level CRUSH hosts
- client operations continue while one OSD is offline, then all placement groups recover to `active+clean`
- either RGW can serve requests through the TLS proxy while the other is offline
- RGW accepts trusted HTTPS S3 requests
- the five Stratus buckets can be created and listed
- missing-object behavior, zero-byte and 1 MiB objects, overwrite, Unicode/special-character keys, PUT, GET, HEAD, forced pagination, multipart upload, and confirmed DELETE work through RGW
- one shared Java client completes eight concurrent PUT/GET/HEAD sequences
- Java rejects invalid credentials and cannot list a bucket owned by a separate RGW identity
- Java rejects the RGW certificate when the lab CA is absent
- SDK throttling retries, individual HTTP attempts, operation timing, status, and request IDs are covered by protocol tests and debug logs; connection, socket, attempt, and total-call timeouts are bounded
- the prebuilt Stratus verifier runs against a realistic local Ceph target
- startup, shutdown, and destructive reset are repeatable

## What it does not prove

The environment is a multi-container cluster on one Docker host. It proves daemon-level quorum, replication, degraded operation, recovery, and RGW failover in that boundary. It does not prove:

- physical-host or rack failure tolerance
- dedicated-device behavior or production disk replacement
- multi-host or multi-rack CRUSH placement
- external load-balancer or Docker-host failover
- production durability, capacity, performance, PKI, backup, restore, or operations

Those are handled by the separate representative-lab and production cephadm tasks.

## Prerequisites

- Docker Desktop or Docker Engine with Compose v2
- enough Docker memory and disk for the official Ceph image and three 1 GiB disposable BlueStore volumes
- a prebuilt verifier image identified by `VERIFIER_IMAGE`

Java and Maven are not required on the verification environment.

## Configuration

The first startup copies `.env.template` to the ignored `.env` file. The template uses explicitly disposable local credentials and the endpoint:

```text
https://object-store.stratus.local:8443
```

Change `VERIFIER_IMAGE` to the immutable image reference produced by the build system. Do not add a Compose `build` section.

The local CA, server certificate, and private key are generated into ignored `certs/` and `private/` directories. Client and verifier containers receive only the public CA. The server private key is mounted only into the TLS proxy.

On Windows, certificate generation uses host OpenSSL when available and otherwise uses the already-pinned Ceph image. On Linux, install OpenSSL with `scripts/install-prerequisites.sh` when necessary.

## Workflow

From `platform/ceph/local`:

PowerShell:

```powershell
.\scripts\startup.ps1
.\scripts\bootstrap-buckets.ps1
.\scripts\check.ps1
.\scripts\verify-java.ps1
.\scripts\verify-security.ps1
.\scripts\shutdown.ps1
```

Bash:

```bash
./scripts/startup.sh
./scripts/bootstrap-buckets.sh
./scripts/check.sh
./scripts/verify-java.sh
./scripts/verify-security.sh
./scripts/shutdown.sh
```

`shutdown` removes containers and the project network but preserves Ceph volumes so the environment can restart.

## Destructive reset

Reset removes only this Compose project's disposable Ceph configuration and data volumes. It preserves `.env`, generated certificates, pulled images, and evidence.

PowerShell:

```powershell
.\scripts\reset.ps1
```

Bash:

```bash
./scripts/reset.sh
```

The next startup creates a new Ceph cluster, the verifier identity, and the separate owner used for the access-boundary test.

## Direct inspection

```powershell
docker compose --env-file .env -f compose.yaml ps
docker compose --env-file .env -f compose.yaml exec -T mon1 ceph status
docker compose --env-file .env -f compose.yaml exec -T mon1 ceph quorum_status
docker compose --env-file .env -f compose.yaml exec -T mon1 ceph mgr dump
docker compose --env-file .env -f compose.yaml exec -T mon1 ceph osd tree
docker compose --env-file .env -f compose.yaml exec -T mon1 radosgw-admin user list
docker compose --env-file .env -f compose.yaml logs rgw-proxy
```

The steady-state developer health target is `HEALTH_OK`, with all three OSDs `up`/`in` and all placement groups `active+clean`. A deliberate one-OSD drill produces a real temporary degraded/recovery state; do not suppress it or describe this single-Docker-host result as production resilience.

## Evidence

Verifier JSON and logs are written beneath the ignored `evidence/` directory. Evidence must record:

- Ceph image digest and `ceph version`
- Docker version and architecture
- `ceph status` and OSD `up`/`in` state
- RGW endpoint hostname without credentials
- verifier image digest
- bucket, object semantics, forced pagination, concurrency, multipart, and cleanup results
- invalid-credential, cross-identity denial, and untrusted-TLS negative results
- DEBUG operation/attempt logs when `STRATUS_LOG_LEVEL=DEBUG`

Never include RGW secret keys, CA private keys, or the TLS server private key in evidence.
