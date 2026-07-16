# Local Docker Ceph/RGW Environment

This is the disposable local Ceph developer environment. It deploys a genuine Ceph Tentacle 20.2.2 cluster locally in Docker, exposes RGW through trusted HTTPS, and runs prebuilt S3 client and Stratus verifier images against it.

It does not use an S3 mock and it does not require an external Ceph endpoint.

## Services

Ceph is a distributed storage system: instead of one process owning one disk,
a set of cooperating daemons spreads every piece of data across several disks
so that any single daemon can fail without losing data or availability. Ceph's
core stores *objects* (named blobs) in *pools* — logical namespaces that carry
the replication policy, here two copies of everything. The S3 protocol that
applications speak is a separate layer served on top by a gateway daemon.

The cluster here is assembled from genuine Ceph daemons, each in its own
container. If you have not worked with Ceph before, these are the moving
parts:

- **Monitors (`mon`)** hold the authoritative map of the cluster: which
  daemons exist, where they run, whether they are healthy, and where data is
  allowed to be placed. Every other daemon and client consults the monitors
  for this map before doing anything. Monitors operate as a quorum — a strict
  majority must agree before the map changes — so an odd number is deployed
  and the cluster survives losing a minority. With three monitors, two can
  still form a majority when one is lost; if two were lost, the cluster would
  stop accepting writes rather than risk the survivors disagreeing about the
  state (this is deliberate: consistency is chosen over availability).
- **Managers (`mgr`)** host Ceph's monitoring, metrics, and orchestration
  modules — cluster health summaries, usage statistics, and the machinery
  behind most `ceph` status commands. Exactly one manager is active at a
  time; the second is a hot standby that takes over automatically if the
  active one fails. Managers are not in the data path — objects flow even if
  both were briefly down — but health reporting would degrade. The active
  manager also serves the Ceph Dashboard web UI (see "Management console"
  below).
- **OSDs (Object Storage Daemons, `osd`)** store the actual data; each OSD
  owns one disk-shaped chunk of storage. It writes through BlueStore, Ceph's
  storage engine, which manages that raw space directly instead of going
  through an ordinary filesystem — here each OSD gets a disposable 1 GiB
  Docker volume. Objects are grouped into *placement groups*, and each
  placement group is assigned to a set of OSDs by the CRUSH map — Ceph's
  placement rules, which pick locations by walking a hierarchy of failure
  domains (host, rack, …) rather than keeping a lookup table. Here each OSD
  is registered as its own "host", so the two replicas of any object always
  land on two different OSDs. When an OSD dies, the survivors re-replicate
  its placement groups until every object is back to full redundancy.
- **RGW (RADOS Gateway, `rgw`)** is the HTTP server that speaks the S3
  protocol. It translates each S3 request — authentication, buckets, object
  PUT/GET, multipart uploads — into native Ceph operations against the pools,
  and it is the only component S3 clients ever talk to. RGW keeps no state of
  its own (buckets, users, and objects all live in the pools), so the two
  instances are interchangeable and either can serve every request while the
  other is offline.

Life of a request: an S3 client connects to
`https://object-store.stratus.local:8443`, where `rgw-proxy` terminates TLS
and forwards the request to whichever RGW is up; RGW checks the credentials,
maps the bucket and key to objects in a pool, and reads or writes them on the
OSDs, consulting the monitors' cluster map to find them — writes go to both
replicas before they are acknowledged; reads are served by the primary copy.

The remaining services exist to stand the cluster up, terminate TLS in front
of it, or exercise it as a client:

| Service | Purpose |
|---|---|
| `ceph-bootstrap` | One-shot setup job, re-run at every startup but doing its work only once (a sentinel file marks the cluster as bootstrapped). Creates the cluster's identity: its unique cluster ID (the "fsid"), the shared secret keys the daemons use to authenticate each other ("keyrings"), and the monitors' initial databases |
| `ceph-configure` | One-shot setup job, re-run at every startup and idempotent, that makes the cluster usable: waits for all three OSDs, creates the S3 user the verifier signs in as plus a second, unrelated S3 user whose bucket the verifier must be denied access to, sets every pool to two replicas, and enables the Ceph Dashboard with its sign-in account |
| `mon1`-`mon3` | The three monitors forming the quorum (see above) |
| `mgr1`-`mgr2` | The active and standby managers (see above) |
| `osd1`-`osd3` | The three data-storing OSDs (see above), each with its own Docker volume |
| `rgw1`-`rgw2` | The two S3 gateways (see above) |
| `rgw-proxy` | An nginx reverse proxy that provides the HTTPS endpoint `https://object-store.stratus.local:8443`: it holds the TLS certificate, decrypts incoming requests, and forwards them to whichever RGW is available. It also fronts the Ceph Dashboard on port `8444`, forwarding to whichever manager is active |
| `s3client` | A container with rclone, a command-line S3 client, used by the scripts to run bucket and object operations against the cluster |
| `verifier` | The prebuilt Stratus Java verifier: runs the automated S3 contract checks against the cluster and writes the evidence reports |
| `verifier-untrusted` | The same verifier image but deliberately missing the lab CA certificate — its only job is to prove that TLS connections are rejected when the server's certificate cannot be verified |

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

The first startup creates the ignored `.env` file from `.env.template`, replacing the credential placeholders with generated per-machine disposable secrets. The endpoint is:

```text
https://object-store.stratus.local:8443
```

The published port binds to `127.0.0.1` by default; set `CEPH_RGW_BIND_ADDRESS` only when remote access is deliberate. The Ceph Dashboard publishes separately on port `8444` — see "Management console" below.

Change `VERIFIER_IMAGE` to the immutable image reference produced by the build system. Do not add a Compose `build` section.

The local CA, server certificate, and private key are generated into ignored `certs/` and `private/` directories. Certificates renew automatically at startup when within seven days of expiry; leaf renewal preserves the existing CA. Client and verifier containers receive only the public CA. The server private key is mounted only into the TLS proxy and is deliberately unencrypted — it must never leave the developer machine.

On Windows, certificate generation uses host OpenSSL when available and otherwise uses the already-pinned Ceph image. On Linux, install OpenSSL with `scripts/lifecycle/install-prerequisites.sh` when necessary.

## Testing and validation

For a complete, self-contained guide to every test and validation process for
this module — the static and JVM tests, this live harness, the live Maven
contract test, and the harness self-test, each with how to run it, what it does,
and the expected results — see [ceph_local_validation_and_test_approach.md](ceph_local_validation_and_test_approach.md).

## Scripts

Scripts live in two directories with a strict split:

- **`scripts/`** — host-side scripts that you run, each shipped as a
  `.ps1`/`.sh` pair with identical behavior.
- **`ceph/`** — container-side scripts run by Compose *inside* the containers
  (`bootstrap.sh` creates the cluster identity, `configure.sh` creates users
  and pools policy and the dashboard, `daemon.sh` is the entrypoint every
  Ceph daemon runs), plus the proxy's `nginx.conf`. Never run these by hand.
  Despite the similar name, `ceph/bootstrap.sh` (cluster identity) is
  unrelated to `scripts/verify/bootstrap-buckets` (S3 buckets).

The host-side scripts are grouped by role:

- **`scripts/lifecycle/`** — turn the environment on and off.
- **`scripts/verify/`** — exercise and verify the running cluster.
- **`scripts/lib/`** — internals used by the other scripts; you rarely run
  these yourself.

In the order you meet them:

| Script pair | What it does | When to run it |
|---|---|---|
| `lifecycle/install-prerequisites` | Installs OpenSSL if the host lacks it | Once per machine, only if needed |
| `lifecycle/startup` | Brings everything up: generates `.env` secrets and certificates, then `docker compose up` and waits for health | First, every session |
| `verify/bootstrap-buckets` | Creates the five Stratus buckets and the denied-owner bucket through the S3 API | After startup, once per cluster |
| `verify/check` | Smoke check: lists every Stratus bucket through the TLS endpoint | Any time the cluster is up |
| `verify/verify-java` | Runs the prebuilt Java verifier against the cluster; writes evidence reports, logs, and an environment snapshot | Verification runs |
| `verify/verify-security` | Runs the three security negatives: invalid credentials, cross-identity denial, untrusted TLS | Verification runs |
| `lifecycle/shutdown` | Removes containers and the network; preserves cluster volumes for restart | Last, every session |
| `lifecycle/reset` | Destroys the cluster volumes for a fresh cluster next startup; prompts unless forced | When you want a clean slate |
| `verify/selftest` | Verifies the harness scripts' own runtime behavior (see "Harness self-test") | After changing the scripts |
| `lib/generate-lab-certificates` | Creates or renews the lab CA and server certificate | Called by startup; rarely run directly |
| `lib/common` | Shared helpers sourced by the other scripts | Never directly |

## Workflow

From `platform/ceph/local`:

PowerShell:

```powershell
.\scripts\lifecycle\startup.ps1
.\scripts\verify\bootstrap-buckets.ps1
.\scripts\verify\check.ps1
.\scripts\verify\verify-java.ps1
.\scripts\verify\verify-security.ps1
.\scripts\lifecycle\shutdown.ps1
```

Bash:

```bash
./scripts/lifecycle/startup.sh
./scripts/verify/bootstrap-buckets.sh
./scripts/verify/check.sh
./scripts/verify/verify-java.sh
./scripts/verify/verify-security.sh
./scripts/lifecycle/shutdown.sh
```

`shutdown` removes containers and the project network but preserves Ceph volumes so the environment can restart.

Capture run transcripts into the ignored harness-local `logs/` directory (the repository-root `logs/` is reserved for Maven build logs). Use `*>&1` so PowerShell status lines are included:

```powershell
$timestamp = Get-Date -Format yyyyMMdd-HHmmss
& { .\scripts\lifecycle\startup.ps1 && .\scripts\verify\bootstrap-buckets.ps1 && .\scripts\verify\check.ps1 && .\scripts\verify\verify-java.ps1 && .\scripts\verify\verify-security.ps1 && .\scripts\lifecycle\shutdown.ps1 } *>&1 |
    Tee-Object -FilePath "logs\ceph-local-verification-$timestamp.txt"
```

## Destructive reset

Reset removes only this Compose project's disposable Ceph configuration and data volumes. It preserves `.env`, generated certificates, pulled images, and evidence. It prompts for confirmation unless forced.

PowerShell:

```powershell
.\scripts\lifecycle\reset.ps1 -Force
```

Bash:

```bash
./scripts/lifecycle/reset.sh --force
```

The next startup creates a new Ceph cluster, the verifier identity, and the separate owner used for the access-boundary test.

## Harness self-test

`scripts/verify/selftest.{ps1,sh}` verifies the harness scripts' own runtime behavior: certificate renewal preserves the CA, `verify-security` rejects a verifier that exits 0 without denial evidence, and shutdown/reset work when `.env` is missing. It complements the static checks in `testing/repo-guardrails`. The self-test refuses to run while harness containers or preserved cluster volumes exist, because its final scenario exercises destructive reset.

## Management console

The active manager serves the Ceph Dashboard, Ceph's built-in web UI, through
the same TLS proxy as the S3 endpoint:

```text
https://object-store.stratus.local:8444
```

To open it from the host machine:

1. Start the harness. From this directory (`platform/ceph/local`), run:

   PowerShell:

   ```powershell
   .\scripts\lifecycle\startup.ps1
   ```

   Bash:

   ```bash
   ./scripts/lifecycle/startup.sh
   ```

   This boots the whole cluster — the same first step as the Workflow section
   above — and on first run generates the `.env` file with the dashboard
   credentials. Wait for it to finish; all services should report healthy.

2. Make the hostname resolve to loopback, once per machine. Add this line to
   the hosts file — `C:\Windows\System32\drivers\etc\hosts` on Windows
   (edit as Administrator), `/etc/hosts` on Linux and macOS:

   ```text
   127.0.0.1 object-store.stratus.local
   ```

3. Read the sign-in credentials from the generated `.env`:

   PowerShell:

   ```powershell
   Select-String -Path .env -Pattern '^CEPH_DASHBOARD_'
   ```

   Bash:

   ```bash
   grep '^CEPH_DASHBOARD_' .env
   ```

4. Browse to `https://object-store.stratus.local:8444` and sign in with
   `CEPH_DASHBOARD_USER` and `CEPH_DASHBOARD_PASSWORD`. The password is a
   per-machine disposable secret generated by startup, like the S3 keys.

The certificate is issued by the harness's own disposable lab CA, so the
browser will warn that it is untrusted. For this local-only environment it is
fine to click through the warning. To remove it instead, import
`certs/stratus-ca.crt` into the browser or OS trust store — but only ever
trust this CA on the machine that generated it, and remember `reset` keeps it
while a deleted `certs/` directory means a new CA on next startup.

The port publishes on `127.0.0.1` by default; set
`CEPH_DASHBOARD_BIND_ADDRESS` only when remote access is deliberate. Inside
the cluster the dashboard runs plain HTTP on the managers; the proxy
terminates TLS and fails over to whichever manager is active, so the URL
stays valid across a manager failover (allow ~30 seconds for Ceph's beacon
grace period before the standby takes over).

The dashboard is a convenience for local development. Its account has
administrator rights, so it can change the cluster — acceptable here because
the whole environment is disposable, but keep in mind that anything it
changes is invisible to the scripts. It is not part of the verification
contract: scripted checks and recorded evidence always come from the CLI,
the verifier, and the transcripts.

## Direct inspection

The authoritative management and inspection interface is the `ceph` command
line, run inside a monitor container:

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

Verifier reports (pure JSON, written directly by the verifier via `STRATUS_EVIDENCE_FILE`, each opening with a `description` field stating what the evidence proves), per-run rotating verifier logs (`storage-verifier-<timestamp>.N.log`, single-line ISO-8601 timestamped records), and an `environment-<timestamp>.json` snapshot are written directly under the ignored `evidence/` directory. The snapshot is captured by `verify-java` and records the compose runtime and platform, the resolved Ceph and verifier image identities, `ceph version`, `ceph status`, and the OSD tree. Evidence must record:

- Ceph image digest and `ceph version`
- Docker version and architecture
- `ceph status` and OSD `up`/`in` state
- RGW endpoint hostname without credentials
- verifier image digest
- bucket, object semantics, forced pagination, concurrency, multipart, and cleanup results
- invalid-credential, cross-identity denial, and untrusted-TLS negative results
- DEBUG operation/attempt logs when `STRATUS_LOG_LEVEL=DEBUG`

Never include RGW secret keys, CA private keys, or the TLS server private key in evidence.

Transcripts and the untrusted-TLS evidence log contain the JVM line `Picked up JAVA_TOOL_OPTIONS: -Djavax.net.ssl.trustStore=... -Djavax.net.ssl.trustStorePassword=changeit`. This is not a leaked secret: it is emitted by the standard Temurin base-image CA entrypoint, `changeit` is the JVM's publicly documented default keystore password, and the truststore is an ephemeral tmpfs file holding only public certificates. Truststore passwords protect integrity, not confidentiality.
