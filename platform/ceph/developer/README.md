# Ceph RGW Developer Harness

This directory contains the Increment 1 developer harness for exercising the Stratus object-storage contract against an existing Ceph Object Gateway (RGW) endpoint.

The harness:

- builds the Java 25 storage verifier with the repository Maven wrapper
- builds a non-root verifier container containing the prebuilt JAR and no source or build tools
- starts an `rclone` S3-protocol client and the Java verifier as long-running tool containers
- verifies the five required Stratus buckets
- performs object, prefix-listing, multipart, TLS, and cleanup checks
- writes timestamped JSON verification evidence to the host
- supports Docker Desktop, Docker Engine, and Podman Compose workflows

It does **not** deploy Ceph. Ceph MON, MGR, OSD, and RGW services run under cephadm on Linux hosts. Docker Desktop is a developer client runtime only and cannot prove Ceph durability, quorum, placement, failover, or production readiness.

## Directory Layout

```text
platform/ceph/developer/
├── .env.template
├── .gitignore
├── compose.yaml
├── README.md
├── certs/
│   └── stratus-ca.crt              # supplied by the operator; ignored by Git
├── evidence/
│   └── .gitkeep
├── private/                        # generated lab private keys; ignored by Git
└── scripts/
    ├── common.sh / common.ps1
    ├── startup.sh / startup.ps1
    ├── bootstrap-buckets.sh / bootstrap-buckets.ps1
    ├── check.sh / check.ps1
    ├── verify-java.sh / verify-java.ps1
    ├── shutdown.sh / shutdown.ps1
    ├── install-prerequisites.sh / install-prerequisites.ps1
    └── generate-lab-certificates.sh / generate-lab-certificates.ps1
```

The verifier implementation is under `verification/storage-contract/src/main/java/dev/stratus/verification/storage/`. Its image definition is `verification/storage-contract/image/Dockerfile`.

## Runtime Architecture

```text
Developer workstation
│
├── s3client container
│   └── rclone -> HTTPS S3 protocol -> Ceph RGW
│
└── verifier container
    └── Java verifier -> HTTPS S3 protocol -> Ceph RGW
                                      │
                                      └── Ceph pools and OSDs
```

Both containers receive the RGW endpoint and a scoped RGW identity. They mount only the public CA certificate. RGW endpoint private keys and Ceph administrator credentials must never enter this harness.

The containers use:

- read-only root filesystems
- temporary writable `/tmp` filesystems
- `no-new-privileges`
- no repository or source-code mount
- a dedicated evidence mount for generated reports
- UID/GID `10001` for the Java verifier

## Prerequisites

### Required on every workstation

- Java 25 available on `PATH`
- network and DNS access to the selected Ceph RGW endpoint
- a scoped Ceph RGW verification identity
- the public CA certificate that validates the endpoint certificate
- enough free space for the Temurin, rclone, and verifier images

The repository wrapper downloads and uses Maven 3.9.16. A machine-wide Maven installation is not required.

### Docker Desktop or Docker Engine

- Docker Desktop or Docker Engine is running
- `docker compose version` succeeds
- Compose v2 or newer is available

### Podman

- Podman is running and can pull images
- a working `podman compose` provider is installed
- `podman compose version` or the environment-approved equivalent succeeds

Set `COMPOSE_IMPLEMENTATION=podman` explicitly when both Docker and Podman are installed and Podman is required. `auto` prefers Docker when `docker` is available.

### Certificate generation

The optional disposable-lab certificate scripts require the OpenSSL command-line tools. Install them with the repository helper before generating certificates.

Windows PowerShell:

```powershell
.\scripts\install-prerequisites.ps1
openssl version
```

The PowerShell helper is idempotent. It returns immediately when OpenSSL is already available; otherwise it uses `winget` to install `ShiningLight.OpenSSL.Light` without pinning an obsolete package version. Run PowerShell as Administrator if the package installer requests elevation. If `winget` is unavailable, install or update Microsoft App Installer, open a new PowerShell window, and rerun the helper.

Manual Windows equivalent:

```powershell
winget install --id ShiningLight.OpenSSL.Light --exact --accept-package-agreements --accept-source-agreements
openssl version
```

Linux:

```bash
chmod +x scripts/*.sh
./scripts/install-prerequisites.sh
openssl version
```

The Linux helper is idempotent and supports `apt-get`, `dnf`, `yum`, `zypper`, `apk`, and `pacman`. It uses `sudo` when the current user is not root and installs the distribution's current `openssl` and `ca-certificates` packages. It fails without changing files when no supported package manager or privilege-elevation path is available.

Manual Linux equivalents are:

```bash
# Debian or Ubuntu
sudo apt-get update && sudo apt-get install -y openssl ca-certificates

# Fedora or RHEL family
sudo dnf install -y openssl ca-certificates
```

## Required Ceph Identities

Use separate bootstrap and verification identities when bucket creation is required.

### Verification identity

The normal verification identity needs:

- permission to list the five required buckets
- permission to list objects under `verification/` in `CEPH_RGW_PROBE_BUCKET`
- `PUT`, `GET`, `HEAD`, multipart-upload, and `DELETE` permissions under that prefix
- no Ceph cluster-administrator permissions
- no permission to create or delete buckets
- no access to unrelated buckets or production data prefixes

The verifier creates only timestamped objects under:

```text
verification/<epoch-milliseconds>/
```

It attempts to delete every probe object and fails the run if cleanup is denied.

### Bootstrap identity

The optional bucket bootstrap scripts require permission to create these exact buckets:

- `stratus-landing`
- `stratus-bronze`
- `stratus-silver`
- `stratus-gold`
- `stratus-platform`

Use the bootstrap identity only while running `bootstrap-buckets`. Replace it with the narrower verification identity before routine checks. Never use Ceph dashboard, cephadm, or RGW administrator credentials in `.env`.

## Configuration

This harness is a client of an existing Ceph RGW service. It does not discover an RGW endpoint, create an RGW user, or retrieve secrets from the Ceph cluster. Before configuring it, identify which of these tracks applies:

| Track | Endpoint and identity | Certificate |
|---|---|---|
| Shared lab or production-backed development | Supplied by the Ceph service owner through the approved secret-delivery channel | Public CA chain supplied by the PKI or Ceph service owner |
| Disposable isolated lab | Supplied by the operator of that lab RGW instance | Generated locally, then installed on that lab RGW instance by its operator |

Do not put Ceph administrator credentials into this harness. The access and secret keys are an RGW application identity, comparable to a username and password for the object-storage API. They are not cloud credentials and do not require an AWS account.

### 1. Obtain the RGW connection details

For a shared environment, request a **Stratus RGW verification connection bundle** from the Ceph service owner. It must contain:

- the HTTPS origin of the RGW service, for example `https://object-store.stratus.local`
- the scoped verification user's RGW access key
- the matching RGW secret key, delivered through the organization's secret manager or another approved one-time channel
- the PEM-encoded public CA certificate or CA chain that validates the endpoint
- the bucket to use for probes, normally `stratus-landing`

The endpoint must be an origin only: scheme, hostname, and optional port. Do not append a bucket name or API path. Your workstation must be able to resolve the hostname and reach its port. Check this before handling credentials:

```powershell
Resolve-DnsName object-store.stratus.local
Test-NetConnection object-store.stratus.local -Port 443
```

Linux:

```bash
getent hosts object-store.stratus.local
timeout 5 bash -c '</dev/tcp/object-store.stratus.local/443'
```

If these checks fail, stop and ask the network or Ceph service owner for the correct DNS, VPN, firewall, or proxy configuration. Changing the endpoint to HTTP is not a valid workaround.

### 2. Create the local environment file

From the repository root, create the untracked working copy:

```bash
cd platform/ceph/developer
cp .env.template .env
chmod 600 .env
```

PowerShell:

```powershell
Set-Location platform\ceph\developer
Copy-Item .env.template .env
```

On a shared Windows workstation, restrict the file to your account:

```powershell
icacls .env /inheritance:r /grant:r "${env:USERNAME}:(R,W)"
```

Edit `.env` and replace only the three `REPLACE_WITH_...`/example connection values:

```dotenv
CEPH_RGW_ENDPOINT=https://object-store.stratus.local
CEPH_RGW_ACCESS_KEY=<access key supplied for the verification user>
CEPH_RGW_SECRET_KEY=<matching secret delivered securely>
CEPH_RGW_PROBE_BUCKET=stratus-landing
```

Leave `CEPH_RGW_ALLOW_HTTP=false`, `S3_PATH_STYLE_ACCESS=true`, and `USE_SYSTEM_CA_CERTS=1` for normal use. Select `COMPOSE_IMPLEMENTATION=docker` or `podman` when you need to force a particular runtime; `auto` uses Docker when both are installed.

The repository ignores `.env`, but that is only an accidental-commit safeguard. Never paste its contents into tickets, logs, chat, screenshots, or test evidence. If a key is exposed, ask the Ceph service owner to rotate the RGW identity.

### 3. Install the public CA certificate

Create the local certificate directory if necessary and copy the **public CA certificate or CA chain**, not the RGW server certificate and never a private key:

```powershell
New-Item -ItemType Directory -Force certs | Out-Null
Copy-Item C:\path\from\pki-owner\stratus-ca.crt certs\stratus-ca.crt
openssl x509 -in certs\stratus-ca.crt -noout -subject -issuer -dates
```

Linux:

```bash
mkdir -p certs
cp /path/from/pki-owner/stratus-ca.crt certs/stratus-ca.crt
chmod 644 certs/stratus-ca.crt
openssl x509 -in certs/stratus-ca.crt -noout -subject -issuer -dates
```

It is safe for the containers to read this public CA file. The corresponding CA private key and the RGW endpoint private key must remain with the PKI/Ceph operator. The repository ignores generated files under `certs/`, but the public certificate should still be obtained from a trusted source rather than copied from an unverified network response.

### 4. Validate the endpoint certificate

Replace the host and port below when your supplied endpoint differs:

```bash
openssl s_client \
  -connect object-store.stratus.local:443 \
  -servername object-store.stratus.local \
  -CAfile certs/stratus-ca.crt \
  -verify_return_error </dev/null
```

PowerShell:

```powershell
'' | openssl s_client `
  -connect object-store.stratus.local:443 `
  -servername object-store.stratus.local `
  -CAfile certs\stratus-ca.crt `
  -verify_return_error
```

A successful result ends with `Verify return code: 0 (ok)`. A hostname, expiry, issuer, or trust error must be resolved with the PKI/Ceph owner before startup.

### 5. Start and verify

Run `scripts/startup.ps1` on Windows or `./scripts/startup.sh` on Linux. Startup checks that `.env` and `certs/stratus-ca.crt` exist, validates the endpoint policy, builds the verifier, and starts the selected Compose implementation. Then run `scripts/check.ps1`/`check.sh` for read-only bucket visibility and `scripts/verify-java.ps1`/`verify-java.sh` for the write/read/multipart/delete contract.

Generated reports go under `evidence/` and may contain endpoint names, bucket names, object keys, timings, and failure details. They are ignored by Git by default. Review and sanitize them before intentionally promoting approved results into the repository-level evidence process.

### Persistent logs

Verifier logs survive container replacement because `/evidence` is bind-mounted to the developer harness. On the host, the active and rotated files are:

```text
platform/ceph/developer/evidence/logs/storage-contract-verifier.0.log
platform/ceph/developer/evidence/logs/storage-contract-verifier.1.log
platform/ceph/developer/evidence/logs/storage-contract-verifier.2.log
...
```

Generation `0` is the active file. When it reaches `STRATUS_LOG_MAX_BYTES`, Java rotates the generations and retains at most `STRATUS_LOG_FILE_COUNT` files. Startup appends to the existing generation rather than truncating it. The entire `evidence/` working directory remains ignored by Git; sanitize logs before promoting selected evidence because endpoint and object metadata may be operationally sensitive.

### Parameter reference

| Parameter | Required | Default | Purpose |
|---|---:|---|---|
| `CEPH_RGW_ENDPOINT` | yes | none | Absolute Ceph RGW origin URL, normally `https://object-store.stratus.local`; credentials, paths, query strings, and fragments are rejected by the Java verifier |
| `CEPH_RGW_ACCESS_KEY` | yes | none | Access key belonging to the scoped RGW bootstrap or verification user |
| `CEPH_RGW_SECRET_KEY` | yes | none | Secret key paired with `CEPH_RGW_ACCESS_KEY`; never commit or print it |
| `CEPH_RGW_PROBE_BUCKET` | no | `stratus-landing` | Required Stratus bucket used for temporary object and multipart probes |
| `CEPH_RGW_ALLOW_HTTP` | no | `false` | Allows a plaintext endpoint only for a disposable isolated lifecycle test; HTTP results are not valid Increment 1 evidence |
| `S3_PATH_STYLE_ACCESS` | no | `true` | Uses path-style bucket URLs, which is the Stratus baseline for the internal RGW endpoint |
| `USE_SYSTEM_CA_CERTS` | no | `1` | Imports mounted PEM certificates into the Temurin container trust configuration; leave enabled for HTTPS verification |
| `STRATUS_LOG_LEVEL` | no | `INFO` | Verifier operational logging level: `INFO` for lifecycle outcomes or `DEBUG` for bucket, object, and multipart diagnostic detail; credentials and payloads are never logged |
| `STRATUS_LOG_FILE` | no | `/evidence/logs/storage-contract-verifier.%g.log` | Persistent rotating log pattern; `%g` is replaced with the rotation generation number |
| `STRATUS_LOG_MAX_BYTES` | no | `10485760` | Maximum size of one log generation in bytes (10 MiB by default) |
| `STRATUS_LOG_FILE_COUNT` | no | `5` | Number of log generations retained, including the active generation |
| `COMPOSE_IMPLEMENTATION` | no | `auto` | Selects `auto`, `docker`, or `podman`; `auto` prefers Docker when available |
| `S3CLIENT_IMAGE` | no | `rclone/rclone:1.74.4` | S3-protocol diagnostic image; pin by digest in shared environments |
| `VERIFIER_IMAGE` | no | `stratus/storage-contract-verifier:dev` | Locally built Java verifier image name; shared environments use an approved registry digest |
| `TEMURIN_IMAGE` | no | `eclipse-temurin:25.0.3_9-jre-noble` | Java 25 runtime base image used to build the verifier; pin by digest in shared environments |

There is deliberately no cloud provider, cloud endpoint, cloud account, or infrastructure-region parameter. The endpoint is the on-premises Ceph RGW service.

### Example `.env`

```dotenv
CEPH_RGW_ENDPOINT=https://object-store.stratus.local
CEPH_RGW_ACCESS_KEY=REPLACE_WITH_SCOPED_RGW_ACCESS_KEY
CEPH_RGW_SECRET_KEY=REPLACE_WITH_SCOPED_RGW_SECRET_KEY
CEPH_RGW_PROBE_BUCKET=stratus-landing
CEPH_RGW_ALLOW_HTTP=false
S3_PATH_STYLE_ACCESS=true
USE_SYSTEM_CA_CERTS=1
STRATUS_LOG_LEVEL=INFO
STRATUS_LOG_FILE=/evidence/logs/storage-contract-verifier.%g.log
STRATUS_LOG_MAX_BYTES=10485760
STRATUS_LOG_FILE_COUNT=5
COMPOSE_IMPLEMENTATION=auto
S3CLIENT_IMAGE=rclone/rclone:1.74.4
VERIFIER_IMAGE=stratus/storage-contract-verifier:dev
TEMURIN_IMAGE=eclipse-temurin:25.0.3_9-jre-noble
```

## TLS Certificate Setup

### Shared lab or production-issued certificate

Obtain the public CA chain from the Ceph or platform PKI owner and place it at:

```text
platform/ceph/developer/certs/stratus-ca.crt
```

The file must contain the PEM-encoded CA certificate or chain that validates the certificate presented by `CEPH_RGW_ENDPOINT`.

Do not copy any of the following into the harness:

- the RGW endpoint private key
- the CA private key
- Ceph admin keyrings
- cephadm SSH keys
- platform secret-store exports

Validate the public CA and endpoint before startup:

```bash
openssl x509 -in certs/stratus-ca.crt -noout -subject -issuer -dates
openssl s_client \
  -connect object-store.stratus.local:443 \
  -servername object-store.stratus.local \
  -CAfile certs/stratus-ca.crt \
  -verify_return_error </dev/null
```

### Disposable lab certificate

Bash:

```bash
./scripts/generate-lab-certificates.sh
```

PowerShell:

```powershell
.\scripts\generate-lab-certificates.ps1
```

The scripts are idempotent and generate:

| File | Use |
|---|---|
| `certs/stratus-ca.crt` | public CA mounted into developer clients |
| `certs/object-store.stratus.local.crt` | lab RGW endpoint certificate |
| `private/stratus-lab-ca.key` | disposable CA private key; never commit or distribute |
| `private/object-store.stratus.local.key` | RGW endpoint private key; apply only on the Ceph admin path |

Certificate generation does not configure RGW. A Ceph operator must register the endpoint certificate and key from a Ceph admin shell, then apply the RGW service specification. For an RGW service named `rgw.stratus`, the reference pattern is:

```bash
ceph orch certmgr cert set \
  --cert-name rgw_ssl_cert \
  --service-name rgw.stratus \
  -i certs/object-store.stratus.local.crt

ceph orch certmgr key set \
  --key-name rgw_ssl_key \
  --service-name rgw.stratus \
  -i private/object-store.stratus.local.key

ceph orch redeploy rgw.stratus
ceph orch ps --service-name rgw.stratus
```

Run those commands only on the Ceph administration path. The developer harness continues to receive only `certs/stratus-ca.crt`.

`USE_SYSTEM_CA_CERTS=1` is mandatory for routine HTTPS verification. An empty value is supported only for the repository's explicitly enabled disposable HTTP lifecycle test and is not acceptable verification evidence.

## Standard Workflow

### 1. Start

Bash:

```bash
./scripts/startup.sh
```

PowerShell:

```powershell
.\scripts\startup.ps1
```

Startup performs the following operations:

1. loads and validates `.env`
2. confirms `certs/stratus-ca.crt` exists
3. rejects plaintext RGW unless `CEPH_RGW_ALLOW_HTTP=true`
4. runs the Maven 3.9.16 wrapper and all unit tests
5. packages the shaded verifier JAR
6. validates the Compose model
7. builds the non-root verifier image
8. starts or reconciles both tool containers
9. prints container status

Running startup again is supported. Compose reconciles the existing project instead of requiring manual container deletion.

### 2. Bootstrap buckets when required

Skip this step when the Ceph operator has already provisioned the buckets.

Bash:

```bash
./scripts/bootstrap-buckets.sh
```

PowerShell:

```powershell
.\scripts\bootstrap-buckets.ps1
```

The operation is idempotent. It creates only the five named buckets. After it succeeds, replace bootstrap credentials in `.env` with the normal verification identity and restart the harness so the narrower credentials are loaded.

### 3. Run the lightweight client check

Bash:

```bash
./scripts/check.sh
```

PowerShell:

```powershell
.\scripts\check.ps1
```

Expected output:

```text
PASS bucket=stratus-landing
PASS bucket=stratus-bronze
PASS bucket=stratus-silver
PASS bucket=stratus-gold
PASS bucket=stratus-platform
```

This check proves endpoint reachability, CA trust, credential validity, and visibility of each required bucket through `rclone`. It does not write objects.

### 4. Run the Java contract verifier

Bash:

```bash
./scripts/verify-java.sh
```

PowerShell:

```powershell
.\scripts\verify-java.ps1
```

The verifier performs these checks in order:

1. all five required buckets are visible
2. a timestamped object is uploaded and read back byte-for-byte
3. HEAD reports the expected size and prefix listing returns the object
4. a real multipart upload larger than 5 MiB completes with the expected size
5. all probe objects are deleted

A successful report resembles:

```json
{"timestamp":"2026-07-12T10:15:30Z","success":true,"checks":[{"name":"required-buckets","passed":true,"detail":"Found all 5 required buckets"},{"name":"object-round-trip","passed":true,"detail":"PUT and GET content matched"},{"name":"head-and-list","passed":true,"detail":"HEAD size and prefix listing matched"},{"name":"multipart-upload","passed":true,"detail":"Multipart upload completed with expected size"},{"name":"probe-cleanup","passed":true,"detail":"Removed all verification probe objects"}]}
```

The script writes the same JSON to:

```text
evidence/storage-verification-<UTC timestamp>.json
```

### 5. Shut down

Bash:

```bash
./scripts/shutdown.sh
```

PowerShell:

```powershell
.\scripts\shutdown.ps1
```

Shutdown removes the harness containers and project network. It preserves:

- `.env`
- public certificates
- protected lab keys
- locally built and pulled images
- `evidence/`

Running shutdown repeatedly is supported.

## Direct Compose Operations

The scripts are the supported interface because they select Docker or Podman consistently and apply the correct project directory and environment file. For diagnostics, the equivalent Docker commands are:

```bash
docker compose --project-directory . --env-file .env -f compose.yaml ps
docker compose --project-directory . --env-file .env -f compose.yaml logs verifier
docker compose --project-directory . --env-file .env -f compose.yaml logs s3client
docker compose --project-directory . --env-file .env -f compose.yaml exec -T verifier id
```

From the repository root, replace `.` with `platform/ceph/developer`.

Do not repair a running verifier by copying JARs into the container. Rebuild through `startup` so the image remains reproducible.

## Verifier Exit Codes

| Exit code | Meaning |
|---:|---|
| `0` | all storage contract checks passed |
| `2` | endpoint, bucket, object, multipart, or cleanup verification failed |
| `64` | required configuration was absent or invalid |

The scripts preserve the verifier exit code through the pipeline that writes the evidence file. A failed verifier must not be marked successful merely because an evidence file was created.

## Automated Tests

Run unit tests and package the executable JAR from the repository root:

```bash
./mvnw clean verify
```

PowerShell:

```powershell
.\mvnw.cmd clean verify
```

Unit tests do not require Ceph. They cover:

- strict endpoint and HTTPS validation
- required parameter validation
- secret redaction
- required-bucket detection
- object round-trip behavior
- multipart behavior
- cleanup after success and failed reads
- cleanup-denial failure reporting

`CephRgwIntegrationTest` is tagged `ceph-integration` and excluded from the ordinary unit build. The container workflow is the preferred live verification path because it exercises the same image, CA import, and configuration injection used by developers.

## Evidence Handling

`evidence/` is ignored by Git. For shared lab or production acceptance:

1. retain the JSON report
2. record the verifier image digest
3. record the endpoint hostname without credentials
4. record the Ceph release and runtime profile
5. record the test identity name, never its secret
6. attach relevant Ceph health output and failure-drill records
7. move or upload evidence to the approved durable evidence system using the Increment 1 task ID

Developer evidence can unblock later engineering but does not accept the production storage gate.

## Reset and Cleanup

Normal reset:

```bash
./scripts/shutdown.sh
./scripts/startup.sh
```

The scripts do not delete buckets or application data. Probe objects are limited to the `verification/` prefix and are deleted by the verifier.

Before manually removing any residual probe object, confirm the exact bucket and timestamped key. Never recursively delete the entire `verification/` prefix while another verifier may be running.

To rotate credentials:

1. stop the harness
2. update `.env`
3. start the harness
4. run the lightweight and Java checks
5. revoke the old RGW key after successful validation

Compose does not reload changed environment variables into existing containers until they are recreated by startup.

## Troubleshooting

### `.env` is missing

Symptom:

```text
Create .../.env from .env.template
```

Create `.env`, fill every required value, and rerun startup.

### CA certificate is missing

Symptom:

```text
Missing certs/stratus-ca.crt
```

Obtain the public CA chain from the endpoint owner or generate a disposable lab certificate. Do not disable TLS validation to bypass this check.

### TLS validation fails

Check:

- `CEPH_RGW_ENDPOINT` uses the hostname present in the endpoint certificate SAN
- `certs/stratus-ca.crt` contains the correct PEM CA chain
- the RGW certificate has not expired
- workstation and Ceph host clocks are correct
- an intercepting proxy is not replacing the certificate
- `USE_SYSTEM_CA_CERTS=1` is present

Inspect from inside the verifier:

```bash
docker compose exec -T verifier java -XshowSettings:properties -version
```

Do not set insecure client flags as a fix.

### Endpoint cannot be resolved or reached

Validate workstation DNS and routing:

```bash
nslookup object-store.stratus.local
curl --cacert certs/stratus-ca.crt -I https://object-store.stratus.local
```

Also confirm the RGW/load-balancer port is reachable from the developer network.

### Access is denied

Determine which operation failed:

- bucket listing failure: identity cannot see required buckets
- PUT failure: probe-prefix write permission is missing
- GET or HEAD failure: read permission is missing
- multipart failure: multipart initiation, part upload, or completion is denied
- cleanup failure: delete permission is missing

Do not widen access globally. Update only the scoped verification-prefix policy and rerun negative access tests.

### Required bucket is missing

The verifier reports every missing bucket and stops before writing probes. Ask the Ceph operator to provision the bucket, or temporarily use the dedicated bootstrap identity with `bootstrap-buckets`.

### Multipart upload fails

Confirm:

- the identity has all multipart permissions
- the RGW endpoint and any reverse proxy accept the request size
- RGW quotas are not exhausted
- Ceph cluster health and available capacity are acceptable
- no proxy timeout is shorter than the multipart operation

### Probe cleanup fails

The verifier reports failure and includes the timestamped key. Correct delete permission or endpoint health, remove only the reported residual object, and rerun verification. Cleanup failure is not a warning; it keeps the gate open.

### Containers start but checks fail

Startup validates and starts tooling; it does not claim the RGW endpoint is healthy. Run:

```bash
docker compose ps
docker compose logs verifier
docker compose logs s3client
```

Then run `check` to distinguish container startup from endpoint and credential behavior.

### Docker and Podman are both installed

Set the runtime explicitly:

```dotenv
COMPOSE_IMPLEMENTATION=docker
```

or:

```dotenv
COMPOSE_IMPLEMENTATION=podman
```

This avoids operating on different Compose projects during startup and shutdown.

### Image pull or build fails

Confirm registry access and the exact image tags in `.env`. Shared environments should replace mutable tags with approved digests. The verifier build context intentionally contains only the Dockerfile and packaged JAR through the repository `.dockerignore`.

## What This Harness Proves

- the developer can build and run the immutable verifier artifact
- the workstation trusts the RGW certificate
- scoped credentials can reach the on-prem Ceph RGW endpoint
- all required buckets are visible
- core object operations, prefix listing, multipart upload, and cleanup work
- startup and shutdown are repeatable
- JSON evidence can be captured without mounting source or build tools into the verifier

## What This Harness Does Not Prove

- MON quorum or MGR failover
- OSD and CRUSH failure-domain correctness
- pool replication or erasure-coding durability
- redundant RGW and load-balancer failover
- production capacity, latency, throughput, or concurrency targets
- production PKI issuance and renewal
- production secret-management and rotation controls
- backup and restore
- OSD, host, or network failure recovery
- production operational readiness

Those controls are exercised by the representative lab and production tasks in [the Ceph implementation plan](../../../docs/implementation/increment1_ceph.md).
