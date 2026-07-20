# Ceph Compose Cluster Quick Start Guide

- Author: Mark Raysmith
- Created: 2026-07-20
- Last updated: 2026-07-20
- Status: Active

Use this guide to start and verify the Stratus Ceph developer environment for
the first time. It gives you a real, local S3-compatible object store at:

```text
https://object-store.stratus.local:8443
```

The scripts create the Ceph cluster, local credentials, and certificates. You
do not need an AWS account, a cloud service, an existing Ceph installation, or
a running Ubuntu WSL distribution.

For architecture and operational detail, use the [module README](README.md).
For every test layer and evidence requirement, use the [testing and validation
guide](ceph_compose_cluster_validation_and_test_approach.md).

## 1. Before You Start

Install or confirm:

- Docker Desktop or Docker Engine with Compose v2
- PowerShell 7+ on Windows, or Bash on Linux, macOS, WSL, or Git for Windows
- a JDK suitable for the repository build
- `curl` when using the optional command-line Dashboard connectivity check
- enough free Docker disk for the Ceph and client images
- enough free Docker storage for three disposable 1 GiB OSD volumes

Docker Desktop users may use PowerShell or Git Bash directly. Ubuntu WSL does
not need to be running. Podman is supported by the scripts but is a separate
runtime qualification; this quick start follows the validated Docker path.

The following local resources must be available:

| Resource | Used for |
|---|---|
| TCP `8443` | S3-compatible RGW endpoint |
| TCP `8444` | optional Ceph Dashboard |
| `172.28.0.0/24` | private Compose network |

Startup detects a conflicting Compose network and stops with an explanatory
error instead of modifying another environment.

## 2. Build the Verifier Image

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

PowerShell:

```powershell
.\scripts\lifecycle\startup.ps1
```

Bash:

```bash
./scripts/lifecycle/startup.sh
```

On the first run, startup:

1. Creates ignored `.env` configuration with random local credentials.
2. Generates the disposable CA and HTTPS endpoint certificate.
3. Pulls the pinned Ceph, nginx, and rclone images when absent.
4. Starts three monitors, two managers, three OSDs, two RGWs, the HTTPS proxy,
   and the S3 client.
5. Waits for the required services to become healthy.

The first run takes longer because Docker must download images and initialize
the Ceph volumes. Success ends with the Compose service table. `mon1`-`mon3`,
`mgr1`-`mgr2`, `osd1`-`osd3`, `rgw1`-`rgw2`, and `rgw-proxy` must be healthy.
The one-shot `ceph-bootstrap` and `ceph-configure` jobs may show as exited after
completing successfully.

Do not edit the generated access keys merely to replace them with cloud-style
credentials. They are genuine Ceph RGW credentials created for this local
cluster.

## 5. Create and Check the Buckets

PowerShell:

```powershell
.\scripts\verify\bootstrap-buckets.ps1
.\scripts\verify\check.ps1
```

Bash:

```bash
./scripts/verify/bootstrap-buckets.sh
./scripts/verify/check.sh
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

PowerShell:

```powershell
.\scripts\verify\verify-java.ps1
.\scripts\verify\verify-security.ps1
```

Bash:

```bash
./scripts/verify/verify-java.sh
./scripts/verify/verify-security.sh
```

`verify-java` runs twelve live S3 checks, including bucket discovery, object
round trips, overwrite, zero-byte and 1 MiB objects, special-character keys,
listing and pagination, concurrent access, multipart upload, and cleanup. Its
JSON result must contain:

```json
"success": true
```

`verify-security` deliberately tries invalid credentials, cross-identity bucket
access, and a TLS connection without the local CA. Error output during these
three scenarios is expected; the script succeeds only when all three attempts
are rejected for the intended reason.

## 7. Find the Results and Logs

The scripts write ignored, persistent artifacts under `evidence/`:

| Pattern | Contents |
|---|---|
| `storage-verification-<timestamp>.json` | twelve-check S3 result |
| `environment-<timestamp>.json` | runtime, image, Ceph status, and OSD snapshot |
| `storage-verifier-<timestamp>.0.log` | timestamped rolling verifier log |
| `storage-invalid-credentials-<timestamp>.json` | invalid credentials were rejected |
| `storage-cross-identity-denial-<timestamp>.json` | cross-identity access was denied |
| `storage-untrusted-tls-<timestamp>.log` | untrusted certificate was rejected |

Do not commit `.env`, private keys, or generated evidence. Do not put RGW secret
keys or private certificate material into tickets or test reports.

## 8. Stop or Reset the Cluster

Normal shutdown removes containers and the Compose network but preserves Ceph
data volumes for the next session.

PowerShell:

```powershell
.\scripts\lifecycle\shutdown.ps1
```

Bash:

```bash
./scripts/lifecycle/shutdown.sh
```

Use reset only when you intentionally want to delete the disposable cluster
data and create a fresh cluster on the next startup.

PowerShell:

```powershell
.\scripts\lifecycle\reset.ps1 -Force
```

Bash:

```bash
./scripts/lifecycle/reset.sh --force
```

Reset preserves `.env`, certificates, pulled images, and existing evidence.

## 9. Complete Copy-Ready Sequence

PowerShell, starting at the repository root:

```powershell
$ErrorActionPreference = 'Stop'
.\mvnw.cmd -pl :stratus-storage-verifier -am package
docker build -f verification\storage\image\Dockerfile -t stratus/storage-verifier:dev .
Set-Location platform\ceph\compose-cluster
.\scripts\lifecycle\startup.ps1
.\scripts\verify\bootstrap-buckets.ps1
.\scripts\verify\check.ps1
.\scripts\verify\verify-java.ps1
.\scripts\verify\verify-security.ps1
.\scripts\lifecycle\shutdown.ps1
```

Bash, starting at the repository root:

```bash
set -euo pipefail
./mvnw -pl :stratus-storage-verifier -am package
docker build -f verification/storage/image/Dockerfile -t stratus/storage-verifier:dev .
cd platform/ceph/compose-cluster
./scripts/lifecycle/startup.sh
./scripts/verify/bootstrap-buckets.sh
./scripts/verify/check.sh
./scripts/verify/verify-java.sh
./scripts/verify/verify-security.sh
./scripts/lifecycle/shutdown.sh
```

## 10. Optional Next Steps

Run the real RGW, monitor, and OSD outage/recovery drill while the cluster is
running:

```powershell
.\scripts\verify\failure-drill.ps1
```

```bash
./scripts/verify/failure-drill.sh
```

After changing harness scripts, stop and reset the cluster, then run the
destructive harness self-test:

```powershell
.\scripts\verify\selftest.ps1
```

```bash
./scripts/verify/selftest.sh
```

## 11. Access the Ceph Administration UI

Ceph Dashboard is the cluster's browser-based administration interface. It is
served by the active Ceph manager and published through the local HTTPS proxy
on port `8444`. It is separate from the S3 endpoint on port `8443`.

### Step 1: Start the cluster

From `platform/ceph/compose-cluster`:

PowerShell:

```powershell
.\scripts\lifecycle\startup.ps1
```

Bash:

```bash
./scripts/lifecycle/startup.sh
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

### Step 5: Trust the local CA or accept the local warning

The Dashboard certificate is issued by the generated disposable CA at
`certs/stratus-ca.crt`. A browser that does not trust this CA displays a
certificate warning even though the certificate hostname is correct.

For the quickest local-only access, use the browser's advanced option to
continue to the site. For normal repeated use, import
`certs/stratus-ca.crt` into the browser's trusted certificate authorities.

On Windows, import it into the current user's trusted roots with:

```powershell
certutil -user -addstore Root .\certs\stratus-ca.crt
```

Restart the browser after importing it. Trust only the CA generated on this
workstation. If the `certs/` directory is deleted and startup creates a new CA,
remove the old trust entry and import the new certificate.

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

## 12. Access Ceph RGW with Postman or Another S3 Client

Ceph Dashboard on port `8444` administers the cluster. Applications and API
tools access object storage through the Ceph RGW S3-compatible API on port
`8443`. These are different interfaces:

| Interface | URL | Purpose |
|---|---|---|
| Ceph Dashboard | `https://object-store.stratus.local:8444` | browser administration UI |
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

PowerShell:

```powershell
.\scripts\lifecycle\startup.ps1
.\scripts\verify\bootstrap-buckets.ps1
```

Bash:

```bash
./scripts/lifecycle/startup.sh
./scripts/verify/bootstrap-buckets.sh
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

## 13. First Problems to Check

| Symptom | Action |
|---|---|
| Verifier image is missing | Repeat [Build the Verifier Image](#2-build-the-verifier-image) from the repository root |
| Docker is unavailable | Start Docker Desktop and confirm `docker compose version` works in the selected shell |
| Startup reports `172.28.0.0/24` in use | Remove or reconfigure the other Docker network named by the startup error |
| Git Bash rewrites `/certs` as `C:/Program Files/Git/certs` | Run the supplied scripts rather than reconstructing their raw Compose commands |
| Script reports `bash\r` or `^M` | Restore LF endings; keep the repository `.gitattributes` rule for `*.sh` |
| Security verification prints authentication, access-denied, or PKIX errors | These are expected inside the three negative scenarios; judge the final script result and evidence file |
| Self-test refuses to run | Shut down and destructively reset the disposable cluster first |
| Dashboard name does not resolve | Add the workstation hosts-file entry from [Step 2](#step-2-make-the-dashboard-hostname-resolve-on-the-workstation); do not modify a container hosts file |
| Dashboard port is unreachable | Start the cluster and require `rgw-proxy` plus both manager services to be healthy, then rerun the port check |
| Browser reports an untrusted certificate | Import `certs/stratus-ca.crt` or explicitly accept the disposable local warning; never disable TLS in the verifier |
| Dashboard login fails | Read the current generated values from `.env`; do not assume a default password |
| Postman returns `InvalidAccessKeyId` or `SignatureDoesNotMatch` | Use the current RGW values from `.env`, collection-level `AWS Signature`, service `s3`, scope `default`, request-header authorization, and path-style URLs |
| Postman reports a self-signed certificate error | Add `certs/stratus-ca.crt` under Postman CA certificates and keep SSL verification enabled |
| Postman receives `403 AccessDenied` for `stratus-denied` | Expected: that bucket belongs to the separate denial-test identity; use a normal Stratus bucket |

For deeper diagnosis, use the full [troubleshooting
table](ceph_compose_cluster_validation_and_test_approach.md#troubleshooting).
