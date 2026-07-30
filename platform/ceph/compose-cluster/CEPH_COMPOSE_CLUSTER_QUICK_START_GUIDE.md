# Ceph Compose Cluster Quick Start Guide

- Author: Mark Raysmith
- Created: 2026-07-20
- Last updated: 2026-07-28

Use this guide to start and verify the Stratus Ceph developer environment for
the first time. It gives you a real, local S3-compatible object store at:

```text
https://object-store.stratus.local:8443
```

It also includes the Ceph admin console, officially named **Ceph Dashboard**,
at:

```text
https://object-store.stratus.local:8444
```

The S3 endpoint and admin console are separate interfaces with separate
credentials. See [Appendix
A](#appendix-a-optional-access-to-the-ceph-admin-console-dashboard) for the
browser setup and sign-in procedure.

The scripts create the Ceph cluster, local credentials, and certificates. You
do not need an AWS account, a cloud service, an existing Ceph installation, or
a running Ubuntu WSL distribution.

For architecture and operational detail, use the [module README](README.md).
For every test layer and evidence requirement, use the [testing and validation
guide](ceph_compose_cluster_validation_and_test_approach.md).

## Choose Your Goal

**Purpose:** Choose the shortest path that matches what you need to accomplish.
The numbered sections build on one another, while the appendices are optional
workflows for people who want to interact with the running cluster manually.

You do not need to complete every section to use the cluster:

| Goal | Follow |
|---|---|
| Start a local S3 endpoint | Sections 1-4 |
| Create and confirm the Stratus buckets | Sections 1-5 |
| Run the complete local verification | Sections 1-7 |
| Use the Ceph admin console (Dashboard) or a desktop S3 client | Complete Sections 1-5, then use the optional appendices |

For a first run, follow Sections 1-7. Leave the cluster running if you want to
explore it, and shut it down with Section 8 when finished.

## 1. Before You Start

**Purpose:** Confirm that the workstation can build and run the environment
before any images, credentials, certificates, containers, or volumes are
created. Complete this section on the first run and revisit it when changing
workstations or container runtimes.

Install or confirm:

- Docker Desktop or Docker Engine with Compose v2
- Bash on Linux or macOS, or Git Bash on Windows
- JDK 25, which is the Java release configured by the repository build
- `curl` when using the optional command-line Dashboard connectivity check
- enough free Docker disk for the Ceph and client images
- enough free Docker storage for three disposable 1 GiB OSD volumes

A practical starting allocation is 8 GiB of memory available to Docker and
10 GiB of free Docker disk. Actual use varies by platform and cached images;
the three OSD volumes alone consume 3 GiB.

Confirm the required tools before starting:

```bash
docker version
docker compose version
bash --version
java -version
./mvnw --version
```

Run the final two commands from the repository root. `java -version` and the
Java runtime reported by Maven must be version 25.

Supported shell paths are:

| Workstation | Shell used by this guide |
|---|---|
| Windows 11 | Git Bash; validated path |
| Linux | native Bash |
| macOS | native Bash |
| WSL | not currently a validated path |

Docker Desktop users may use Git Bash directly; Ubuntu WSL does not need to be
running. Podman is supported by the scripts but is a separate runtime
qualification; this quick start follows the validated Docker path.

On Windows 11, run the harness from **Git Bash** — that is the terminal the
harness is validated on, and the shared script library depends on Git Bash
(MSYS) path handling. Hosting Git Bash in a Windows Terminal profile gives the
best day-to-day experience. PowerShell can act as an outer shell
(`bash scripts/lifecycle/ceph-compose-startup.sh`) but cannot run the `.sh` scripts
natively; WSL bash is not the validated environment for these scripts.

The following local resources must be available:

| Resource | Used for |
|---|---|
| TCP `8443` | S3-compatible RGW endpoint |
| TCP `8444` | optional Ceph admin console (Ceph Dashboard) |
| `172.28.0.0/24` | private Compose network |

Startup detects a conflicting Compose network and stops with an explanatory
error instead of modifying another environment.

## 2. Build the Verifier Image

**Purpose:** Package the repository's Java storage verifier into the local
container image used by the live checks in Section 6. This builds test tooling,
not Ceph itself, and does not start the cluster.

Do this once after cloning, and repeat it after verifier source changes.
Compose deliberately does not compile source code or build this image.

From the repository root:

PowerShell:

```powershell
.\mvnw.cmd -pl :stratus-storage-verifier -am package
docker build -f verification\storage\image\Dockerfile -t stratus/storage-verifier:dev .
```

Bash:

```bash
./mvnw -pl :stratus-storage-verifier -am package
docker build -f verification/storage/image/Dockerfile -t stratus/storage-verifier:dev .
```

Confirm the image exists:

```text
docker image inspect stratus/storage-verifier:dev
```

The `:dev` image is for workstation use. Recorded release or acceptance evidence
must use the immutable, digest-pinned verifier image published by the build
system.

## 3. Open the Cluster Directory

**Purpose:** Put the shell in the directory that contains the Compose file,
generated `.env`, certificates, scripts, and evidence directory. The remaining
commands use paths relative to this location.

PowerShell:

```powershell
Set-Location platform\ceph\compose-cluster
```

Bash:

```bash
cd platform/ceph/compose-cluster
```

Run all remaining commands from this directory.

## 4. Start Ceph

**Purpose:** Create or reuse the local configuration and certificates, then
start the complete Ceph environment and wait until its required services are
healthy. This is the step that makes the S3 endpoint and admin console
available.

The harness scripts are bash-only; Windows users run them from Git Bash.

```bash
./scripts/lifecycle/ceph-compose-startup.sh
```

On the first run, startup:

1. Creates ignored `.env` configuration with random local credentials.
2. Generates the disposable CA and HTTPS endpoint certificate.
3. Pulls the pinned Ceph, nginx, and rclone images when absent.
4. Starts three monitors, two managers, three OSDs, two RGWs, the HTTPS proxy,
   and the S3 client.
5. Waits for the required services to become healthy.

The first run commonly takes several minutes because Docker must download
images and initialize the Ceph volumes. Slow image downloads can extend that
time. Success ends with the Compose service table. `mon1`-`mon3`,
`mgr1`-`mgr2`, `osd1`-`osd3`, `rgw1`-`rgw2`, and `rgw-proxy` must be healthy.
The one-shot `ceph-bootstrap` and `ceph-configure` jobs may show as exited after
completing successfully.

Do not edit the generated access keys merely to replace them with cloud-style
credentials. They are genuine Ceph RGW credentials created for this local
cluster.

## 5. Create and Check the Buckets

**Purpose:** Create the standard Stratus storage buckets and perform a quick
readiness check through the S3-compatible endpoint. This confirms that the
cluster is usable at the bucket level before the deeper verification in
Section 6.

```bash
./scripts/verify/ceph-compose-bootstrap-buckets.sh
./scripts/verify/ceph-compose-verify-buckets.sh
```

The bootstrap is idempotent and is safe to repeat. It creates:

- `stratus-landing`
- `stratus-bronze`
- `stratus-silver`
- `stratus-gold`
- `stratus-platform`
- `stratus-denied`, owned by a separate identity for the access-denial test

Success prints `READY` during bootstrap and `PASS` for each required Stratus
bucket during the check.

## 6. Run the Contract and Security Verification

**Purpose:** Prove that the running environment behaves as Stratus expects,
rather than merely confirming that its containers are up. These checks exercise
S3 operations, rejected access, certificate trust, the admin console REST API,
and a multi-file upload/download workflow.

```bash
./scripts/verify/ceph-compose-verify-storage.sh
./scripts/verify/ceph-compose-verify-security.sh
./scripts/verify/ceph-compose-verify-dashboard.sh
./scripts/verify/ceph-compose-verify-dataset.sh
```

`ceph-compose-verify-storage` runs twelve live S3 checks, including bucket discovery, object
round trips, overwrite, zero-byte and 1 MiB objects, special-character keys,
listing and pagination, concurrent access, multipart upload, and cleanup. Its
JSON result must contain:

```json
"success": true
```

`ceph-compose-verify-security` deliberately tries invalid credentials, cross-identity bucket
access, and a TLS connection without the local CA. Error output during these
three scenarios is expected; the script succeeds only when all three attempts
are rejected for the intended reason.

`ceph-compose-verify-dashboard` exercises the Ceph admin console (Ceph Dashboard) REST API
on port `8444` — the management interface, distinct from the S3 API on `8443` —
using the generated credentials from `.env`. It signs in through
`POST /api/auth`, confirms an
unauthenticated request is rejected with HTTP `401`, asserts `HEALTH_OK` and
the three-monitor, three-OSD inventory through `GET /api/health/minimal`,
reads the cluster version, and logs the session out again. Its JSON result
must contain `"success": true`.

`ceph-compose-verify-dataset` generates a 24-file dataset inside the `s3client` container,
uploads it to `stratus-landing/` under the prefix `verification/dataset-<ts>/`, 
re-downloads every object with a byte-for-byte comparison, copies the whole
dataset back into a second local tree that must hash-match the original, then
purges the remote prefix and asserts nothing remains. Its JSON result must also
contain `"success": true`.

## 7. Find the Results and Logs

**Purpose:** Locate the machine-readable evidence and diagnostic logs produced
by Section 6. Use these files to confirm success or investigate a failure, while
keeping generated credentials and private certificate material out of source
control and shared reports.

The scripts write ignored, persistent artifacts under `evidence/`:

| Pattern | Contents |
|---|---|
| `storage-verification-<timestamp>.json` | twelve-check S3 result |
| `environment-<timestamp>.json` | runtime, image, Ceph status, and OSD snapshot |
| `storage-verifier-<timestamp>.0.log` | timestamped rolling verifier log |
| `storage-invalid-credentials-<timestamp>.json` | invalid credentials were rejected |
| `storage-cross-identity-denial-<timestamp>.json` | cross-identity access was denied |
| `storage-untrusted-tls-<timestamp>.log` | untrusted certificate was rejected |
| `dashboard-verification-<timestamp>.json` | six-check Ceph Dashboard REST API result |
| `dataset-verification-<timestamp>.json` | dataset upload, read-back, and cleanup result |

Do not commit `.env`, private keys, or generated evidence. Do not put RGW secret
keys or private certificate material into tickets or test reports.

### Success checklist

Your complete local verification succeeded when:

- startup completed without an error and all required services are healthy;
- bucket bootstrap printed `READY`;
- the bucket check printed `PASS` for every required Stratus bucket;
- the Java, Dashboard, and dataset JSON results contain `"success": true`; and
- the security script confirmed all three expected rejection scenarios.

## 8. Stop or Reset the Cluster

**Purpose:** Choose how much local state to retain when you finish. Normal
shutdown is the everyday choice because it preserves cluster data; reset is a
deliberately destructive recovery option for creating a clean cluster.

Normal shutdown removes containers and the Compose network but preserves Ceph
data volumes for the next session.

```bash
./scripts/lifecycle/ceph-compose-shutdown.sh
```

Use reset only when you intentionally want to delete the disposable cluster
data and create a fresh cluster on the next startup.

```bash
./scripts/lifecycle/ceph-compose-reset.sh --force
```

Reset preserves `.env`, certificates, pulled images, and existing evidence.

### Rotate credentials without deleting data

If the local RGW credentials, Dashboard password, CA key, or endpoint key may
have been exposed, rotate them in place while the cluster is running:

```bash
./scripts/lifecycle/ceph-compose-rotate-secrets.sh --preflight
./scripts/lifecycle/ceph-compose-rotate-secrets.sh
```

The preflight changes nothing. The confirmed rotation preserves all buckets,
objects, and Ceph volumes: it overlaps new and old RGW keys during validation,
switches the local clients and TLS proxy, verifies the new paths, and only then
revokes the old keys. Failures before revocation are rolled back.

Rotation replaces the disposable CA as well as the server certificate. Remove
the old `Stratus Disposable Compose CA` entry from host trust stores and import
the new `certs/stratus-ca.crt`. Read the new ignored `.env` values into any
host-side S3 or Dashboard clients; previously saved credentials will no longer
work.

## 9. Complete Copy-Ready Sequence

**Purpose:** Provide one uninterrupted command sequence for readers who already
understand Sections 1-8 and want to run the standard workflow without copying
each command separately. This is an alternative way to execute those sections,
not an additional verification stage.

Bash, starting at the repository root:

```bash
set -euo pipefail
./mvnw -pl :stratus-storage-verifier -am package
docker build -f verification/storage/image/Dockerfile -t stratus/storage-verifier:dev .
cd platform/ceph/compose-cluster
./scripts/lifecycle/ceph-compose-startup.sh
./scripts/verify/ceph-compose-bootstrap-buckets.sh
./scripts/verify/ceph-compose-verify-buckets.sh
./scripts/verify/ceph-compose-verify-storage.sh
./scripts/verify/ceph-compose-verify-security.sh
./scripts/verify/ceph-compose-verify-dashboard.sh
./scripts/verify/ceph-compose-verify-dataset.sh
```

The cluster remains running after this sequence so you can use the S3 endpoint,
Dashboard, or another client. When finished, run:

```bash
./scripts/lifecycle/ceph-compose-shutdown.sh
```

## 10. Optional Next Steps

**Purpose:** Exercise failure recovery or test the harness itself after the
normal workflow succeeds. These are maintainer-oriented checks and are not
required simply to run or use the local S3 endpoint.

Run the real RGW, monitor, and OSD outage/recovery drill while the cluster is
running:

```bash
./scripts/verify/ceph-compose-failure-drill.sh
```

After changing harness scripts, stop and reset the cluster, then run the
destructive harness self-test:

```bash
./scripts/verify/ceph-compose-verify-harness.sh
```

## Appendix A: Optional Access to the Ceph Admin Console (Dashboard)

**Purpose:** Configure the workstation browser to reach the running cluster's
administration interface and sign in with its generated local credentials. Use
this appendix for visual inspection and diagnosis; it is not required for
application access to S3.

The Ceph admin console, officially named **Ceph Dashboard**, is the cluster's
browser-based administration interface. It is served by the active Ceph manager
and published through the local HTTPS proxy on port `8444`. It is separate from
the S3 endpoint on port `8443`.

### Step 1: Start the cluster

From `platform/ceph/compose-cluster`:

```bash
./scripts/lifecycle/ceph-compose-startup.sh
```

Wait for startup to finish and confirm `rgw-proxy`, `mgr1`, and `mgr2` are
healthy.

### Step 2: Make the Dashboard hostname resolve on the workstation

Containers already resolve `object-store.stratus.local` through Compose DNS.
Your host browser uses the workstation's DNS configuration instead, so add this
local mapping once:

```text
127.0.0.1 object-store.stratus.local
```

This entry is **not only for the browser**. Everything that runs on the
workstation rather than inside a container needs it, including the live Maven
contract tests (`-Pceph-integration-tests` and `-Pall-tests`). Those tests reach
RGW on port 8443 from the host JVM, so without this mapping they fail on
connection rather than on Ceph behavior — even though the harness verification
scripts, which run inside containers, pass. See
[Layer 3](ceph_compose_cluster_validation_and_test_approach.md#why-layer-3-needs-a-hosts-file-entry-and-layer-2-does-not).

On Windows, open PowerShell **as Administrator** and run:

```powershell
$hostsFile = "$env:SystemRoot\System32\drivers\etc\hosts"
$entry = '127.0.0.1 object-store.stratus.local'
if (-not (Select-String -LiteralPath $hostsFile -Pattern '^\s*127\.0\.0\.1\s+object-store\.stratus\.local(\s|$)' -Quiet)) {
    Add-Content -LiteralPath $hostsFile -Value "`r`n$entry"
}
ipconfig /flushdns
```

Git Bash users on Windows must make this Windows hosts-file change; editing
Git Bash's `/etc/hosts` is not the supported path.

On Linux or macOS, run:

```bash
grep -Eq '^[[:space:]]*127\.0\.0\.1[[:space:]]+object-store\.stratus\.local([[:space:]]|$)' /etc/hosts \
  || printf '%s\n' '127.0.0.1 object-store.stratus.local' | sudo tee -a /etc/hosts
```

### Step 3: Confirm the host can reach the UI

PowerShell:

```powershell
Test-NetConnection object-store.stratus.local -Port 8444
```

The result must show `TcpTestSucceeded : True`.

Bash:

```bash
curl --cacert certs/stratus-ca.crt -I https://object-store.stratus.local:8444
```

An HTTP response proves hostname resolution, TCP connectivity, and TLS trust.
A redirect or authentication response is acceptable at this step.

### Step 4: Read the generated login credentials

Startup creates a per-workstation administrator password in the ignored `.env`
file.

PowerShell:

```powershell
Select-String -Path .env -Pattern '^CEPH_DASHBOARD_(USER|PASSWORD)='
```

Bash:

```bash
grep -E '^CEPH_DASHBOARD_(USER|PASSWORD)=' .env
```

Use the value after `CEPH_DASHBOARD_USER=` as the username and the value after
`CEPH_DASHBOARD_PASSWORD=` as the password. Do not commit or paste the password
into shared evidence.

### Step 5: Optionally trust the local CA or accept the local warning

The Dashboard certificate is issued by the generated disposable CA at
`certs/stratus-ca.crt`. A browser that does not trust this CA displays a
certificate warning even though the certificate hostname is correct.

For the quickest local-only access, use the browser's advanced option to
continue to the site. Importing the CA is optional and makes a persistent change
to the workstation's trust store. For normal repeated use, you may import
`certs/stratus-ca.crt` into the browser's trusted certificate authorities.

On Windows, import it into the current user's trusted roots with:

```powershell
certutil -user -addstore Root .\certs\stratus-ca.crt
```

Restart the browser after importing it. Trust only the CA generated on this
workstation. If the `certs/` directory is deleted and startup creates a new CA,
remove the old trust entry named `Stratus Disposable Compose CA` and import the
new certificate. On Windows, manage the current user's trusted roots with
`certmgr.msc`; on macOS, use Keychain Access; on Linux, use the trust-store
manager for your distribution or browser.

### Step 6: Open and sign in

Browse to:

```text
https://object-store.stratus.local:8444
```

Sign in with the two values read from `.env`. The account has Ceph Dashboard
administrator rights. Changes made in the UI affect this local cluster but are
not represented in the repository configuration, so use the UI primarily for
inspection and diagnosis.

The Dashboard is available only while the cluster is running. `shutdown`
makes it unavailable but preserves its data and credentials. `reset` deletes
the Ceph cluster data; the next startup recreates the Dashboard account using
the credentials retained in `.env`.

## Appendix B: Optional Access Ceph RGW with Postman or Another S3 Client

**Purpose:** Configure a desktop API tool or other S3-compatible client to send
signed requests directly to the local object store. Use this appendix for manual
API exploration; the automated verifier does not require Postman.

Ceph Dashboard on port `8444` administers the cluster. Applications and API
tools access object storage through the Ceph RGW S3-compatible API on port
`8443`. These are different interfaces:

| Interface | URL | Purpose |
|---|---|---|
| Ceph admin console (Ceph Dashboard) | `https://object-store.stratus.local:8444` | browser administration UI |
| Ceph RGW | `https://object-store.stratus.local:8443` | S3-compatible bucket and object API |

Ceph RGW authenticates S3 requests with access and secret keys and supports S3
Signature Version 4. Postman calls its SigV4 helper **AWS Signature**. That UI
label describes the signing algorithm inherited from the S3 protocol; it does
not connect Postman to AWS and it does not require an AWS account. Every request
below is sent directly to the local Ceph endpoint. See the official [Ceph RGW
authentication documentation](https://docs.ceph.com/en/latest/radosgw/s3/authentication/)
and [Postman AWS Signature authorization
documentation](https://learning.postman.com/latest-v-12/docs/use/send-requests/authorization/aws-signature).

### Step 1: Prepare the running endpoint

Start the cluster and create the buckets before opening Postman:

```bash
./scripts/lifecycle/ceph-compose-startup.sh
./scripts/verify/ceph-compose-bootstrap-buckets.sh
```

Postman runs on the workstation, so it needs the same
`127.0.0.1 object-store.stratus.local` hosts-file entry described in [Dashboard
Step 2](#step-2-make-the-dashboard-hostname-resolve-on-the-workstation).

### Step 2: Add the local CA to Postman

Keep TLS certificate verification enabled. In the Postman desktop app:

1. Open **Settings > App settings > Certificates**.
2. Turn on **CA certificates**.
3. Select `platform/ceph/compose-cluster/certs/stratus-ca.crt` as the PEM CA
   certificate.

Postman stores this CA locally and does not sync it to the Postman cloud. The
Postman web app requires the Postman Desktop Agent for local certificate and
localhost access. See Postman's official [CA certificate
instructions](https://learning.postman.com/latest-v-12/docs/use/send-requests/authorization/certificates).

Do not disable SSL verification as the routine solution. Doing so would hide
hostname, expiry, and trust-chain failures that Stratus deliberately tests.

### Step 3: Read the Ceph RGW credentials

These are the local Ceph credentials, not the Dashboard username and password.

PowerShell:

```powershell
Select-String -Path .env -Pattern '^CEPH_RGW_(ACCESS_KEY|SECRET_KEY)='
```

Bash:

```bash
grep -E '^CEPH_RGW_(ACCESS_KEY|SECRET_KEY)=' .env
```

Keep the secret out of collections, exported environments, screenshots, and
shared workspaces. In Postman Local Vault, create:

| Vault secret | Value from `.env` |
|---|---|
| `stratus-ceph-access-key` | `CEPH_RGW_ACCESS_KEY` |
| `stratus-ceph-secret-key` | `CEPH_RGW_SECRET_KEY` |

Postman vault references use `{{vault:secret-name}}` and keep the actual value
out of the request definition. See the official [Postman Vault secret
instructions](https://learning.postman.com/docs/use/postman-vault/use-vault-secrets).

### Step 4: Create a Postman collection and variables

Create a collection named `Stratus Ceph RGW`. Add these collection variables:

| Variable | Value | Secret? |
|---|---|---:|
| `ceph_endpoint` | `https://object-store.stratus.local:8443` | No |
| `ceph_bucket` | `stratus-landing` | No |
| `ceph_object_key` | `postman/hello.txt` | No |

Do not put either credential into ordinary collection variables.

On the collection's **Authorization** tab, configure:

| Postman field | Value |
|---|---|
| Auth Type | `AWS Signature` |
| Add authorization data to | `Request Headers` |
| AccessKey | `{{vault:stratus-ceph-access-key}}` |
| SecretKey | `{{vault:stratus-ceph-secret-key}}` |
| AWS Region | `default` |
| Service Name | `s3` |
| Session Token | leave blank |

`default` is the SigV4 credential-scope value used by the working Stratus Ceph
client. It is not a Stratus deployment region, an AWS region, a data-placement
setting, or an infrastructure location. Ceph data placement is controlled by
Ceph pools and CRUSH, not by this request-signing string.

Set each request below to **Inherit auth from parent** so Postman signs it using
the collection configuration. The harness uses path-style S3 URLs: the bucket
name is the first URL path segment rather than part of the hostname.

### Step 5: Send bucket and object requests

Create these requests in the collection.

#### List all buckets

```text
GET {{ceph_endpoint}}/
```

Expected result: HTTP `200` and XML containing the five Stratus buckets.

#### List objects in `stratus-landing`

```text
GET {{ceph_endpoint}}/{{ceph_bucket}}?list-type=2
```

Expected result: HTTP `200` and an XML `ListBucketResult`. An empty result is
valid before objects are uploaded.

#### Upload a text object

```text
PUT {{ceph_endpoint}}/{{ceph_bucket}}/{{ceph_object_key}}
```

In **Body**, select **raw**, choose **Text**, and enter:

```text
Hello from Postman through Ceph RGW.
```

Expected result: HTTP `200`. Postman calculates and signs the request payload;
do not manually create the `Authorization`, `X-Amz-Date`, or payload-hash
headers.

#### Download the object

```text
GET {{ceph_endpoint}}/{{ceph_bucket}}/{{ceph_object_key}}
```

Expected result: HTTP `200` and the uploaded text in the response body.

#### Inspect object metadata

```text
HEAD {{ceph_endpoint}}/{{ceph_bucket}}/{{ceph_object_key}}
```

Expected result: HTTP `200`, no response body, and object metadata in the
headers.

#### Delete the object

```text
DELETE {{ceph_endpoint}}/{{ceph_bucket}}/{{ceph_object_key}}
```

Expected result: HTTP `204`. A subsequent `GET` should return HTTP `404`.

### Configuration for another S3-compatible client

Use this client contract in Cyberduck, S3 Browser, an SDK, or another tool that
supports custom S3 endpoints:

| Setting | Stratus value |
|---|---|
| Protocol/API | S3-compatible over HTTPS |
| Endpoint | `https://object-store.stratus.local:8443` |
| Access key | generated `CEPH_RGW_ACCESS_KEY` from `.env` |
| Secret key | generated `CEPH_RGW_SECRET_KEY` from `.env` |
| Addressing style | path style / force path style |
| TLS CA | `certs/stratus-ca.crt` |
| Signature | S3 Signature Version 4 |
| Signing service | `s3` |
| Signing scope/region field | `default` |
| Session token | none |

If a client requires a cloud account, forces a public-cloud endpoint, cannot
load the local CA, or cannot use path-style addressing, it is not suitable for
this local Ceph profile.

## Troubleshooting: First Problems to Check

**Purpose:** Map the most common first-run symptoms to the next concrete action.
Start here when a command does not produce the success result described in its
section, then continue to the full validation guide if the problem remains.

| Symptom | Action |
|---|---|
| Verifier image is missing | Repeat [Build the Verifier Image](#2-build-the-verifier-image) from the repository root |
| Docker is unavailable | Start Docker Desktop and confirm `docker compose version` works in the selected shell |
| Startup reports `172.28.0.0/24` in use | Remove or reconfigure the other Docker network named by the startup error |
| Git Bash rewrites `/certs` as `C:/Program Files/Git/certs` | Run the supplied scripts rather than reconstructing their raw Compose commands |
| Script reports `bash\r` or `^M` | Restore LF endings; keep the repository `.gitattributes` rule for `*.sh` |
| Security verification prints authentication, access-denied, or PKIX errors | These are expected inside the three negative scenarios; judge the final script result and evidence file |
| Self-test refuses to run | Shut down and destructively reset the disposable cluster first |
| `ceph-compose-verify-dashboard` reports an authentication failure | Read the current `CEPH_DASHBOARD_USER`/`CEPH_DASHBOARD_PASSWORD` from `.env` and require `mgr1` and `mgr2` to be healthy; the credentials are applied by `ceph-configure` during startup |
| Dashboard name does not resolve | Add the workstation hosts-file entry from [Step 2](#step-2-make-the-dashboard-hostname-resolve-on-the-workstation); do not modify a container hosts file |
| Dashboard port is unreachable | Start the cluster and require `rgw-proxy` plus both manager services to be healthy, then rerun the port check |
| Browser reports an untrusted certificate | Import `certs/stratus-ca.crt` or explicitly accept the disposable local warning; never disable TLS in the verifier |
| Dashboard login fails | Read the current generated values from `.env`; do not assume a default password |
| Postman returns `InvalidAccessKeyId` or `SignatureDoesNotMatch` | Use the current RGW values from `.env`, collection-level `AWS Signature`, service `s3`, scope `default`, request-header authorization, and path-style URLs |
| Postman reports a self-signed certificate error | Add `certs/stratus-ca.crt` under Postman CA certificates and keep SSL verification enabled |
| Postman receives `403 AccessDenied` for `stratus-denied` | Expected: that bucket belongs to the separate denial-test identity; use a normal Stratus bucket |
| `ceph-compose-verify-dataset` reports `success:false` in evidence | Open `dataset-verification-<timestamp>.json` to see which check failed; run `docker compose logs s3client` to diagnose rclone upload, download, or hash-comparison failures |

For deeper diagnosis, use the full [troubleshooting
table](ceph_compose_cluster_validation_and_test_approach.md#troubleshooting).
