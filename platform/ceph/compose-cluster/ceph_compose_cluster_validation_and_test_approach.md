# Stratus Ceph Compose Cluster: Testing and Validation Guide

- Author: Mark Raysmith
- Created: 2026-07-15
- Last updated: 2026-07-28
- Status: Active

This is the complete, self-contained guide to every test and validation process
that applies to the Ceph/RGW Compose cluster in `platform/ceph/compose-cluster`. It
assumes no prior knowledge of this module. If you have never run anything here,
start at [Who this is for](#who-this-is-for) and read straight through; if you
just want commands, jump to [Run everything, in order](#run-everything-in-order).

For what the environment *is* (its services, what it proves and does not prove,
and its configuration), read the module [README.md](README.md) first. This
document is about how you *test and validate* it.

The environment also provides a browser-based Ceph admin console, officially
named **Ceph Dashboard**, at `https://object-store.stratus.local:8444`. The
`ceph-compose-verify-dashboard` step validates its REST API, authentication, health, and
daemon inventory. For workstation hostname, certificate, and browser sign-in
setup, use the [admin console procedure in the quick-start
guide](CEPH_COMPOSE_CLUSTER_QUICK_START_GUIDE.md#appendix-a-optional-access-to-the-ceph-admin-console-dashboard).

For the shortest first-run path, use the [Ceph Compose Cluster Quick Start
Guide](CEPH_COMPOSE_CLUSTER_QUICK_START_GUIDE.md).

## Contents

- [Who this is for](#who-this-is-for)
- [The four validation layers at a glance](#the-four-validation-layers-at-a-glance)
- [Mental model: two different verifiers](#mental-model-two-different-verifiers)
- [Prerequisites](#prerequisites)
- [One-time setup: build the verifier image](#one-time-setup-build-the-verifier-image)
- [Layer 1: static and JVM tests (no Docker)](#layer-1-static-and-jvm-tests-no-docker)
- [Layer 2: live harness validation (Docker)](#layer-2-live-harness-validation-docker)
- [Layer 3: live Maven contract test (Docker)](#layer-3-live-maven-contract-test-docker)
- [Layer 4: harness self-test (Docker, destructive)](#layer-4-harness-self-test-docker-destructive)
- [Run everything, in order](#run-everything-in-order)
- [Understanding the evidence](#understanding-the-evidence)
- [Troubleshooting](#troubleshooting)

## Who this is for

Anyone who needs to run or reason about the tests for the Ceph developer module: a
developer changing the verifier, a reviewer confirming a change is sound, or a
new contributor who has just cloned the repository. Everything here runs on a
single workstation. You do not need access to any shared Ceph cluster.

Harness commands are given in **bash** (Linux/macOS/WSL/Git for Windows); the
harness scripts ship as a single bash implementation (ADR-P1-002). Windows
users run them from Git Bash, e.g. `bash scripts/lifecycle/ceph-compose-startup.sh`.

## The four validation layers at a glance

There are four distinct things people loosely call "the ceph tests". They have
different purposes and, crucially, different requirements. Know which one you
need.

| # | Layer | What it checks | Docker? | Live cluster? | Roughly how long |
|---|---|---|---|---|---|
| 1 | Static and JVM tests | Verifier Java logic, plus repository consistency guardrails | No | No | Seconds to ~1 min |
| 2 | Live harness validation | A real Ceph cluster boots and passes every S3 contract, security-negative, management REST API, and dataset round-trip check | Yes | Yes (this module starts it) | Several minutes |
| 3 | Live Maven contract test | The verifier's own JVM test suite, run from Maven against the live endpoint | Yes | Yes | Minutes |
| 4 | Harness self-test | The harness *scripts themselves* (cert renewal, teardown, refusal to accept a fake verifier) | Yes | No (must be stopped) | ~1 min |

The mandatory gate for any code change is **Layer 1** (`clean verify`). Layers 2
and 3 are required additionally when a change touches Ceph endpoint behavior,
TLS, credentials, request signing, bucket policy, or object operations. Layer 4
is run when you change the harness scripts.

### Current validated baseline

The 2026-07-20 workstation validation used Docker Desktop from PowerShell and
from Git for Windows Bash. Both command surfaces completed the full live
lifecycle. The Bash run included all twelve S3 contract checks, all three
security negatives, RGW/MON/OSD failure and recovery scenarios, shutdown,
destructive reset, and the three harness self-test scenarios. `clean verify`
passed 18 storage-verifier tests and 15 repository guardrail tests. Podman was
not available in that environment and is therefore not claimed by this
validation record.

## Mental model: two different verifiers

The single most common point of confusion. The word "verifier" refers to two
things that must not be mixed up:

1. **The verifier as a Java program under test.** Its source lives in
   [`verification/storage/`](../../../verification/storage/) as the Maven module
   `stratus-storage-verifier`. Its unit tests (Layer 1) run on your host JVM
   with no Docker and no Ceph. The implementation-neutral live contract is
   owned separately by `platform/ceph/tests` as `CephRgwContractTest`
   (Layer 3) and runs on your host JVM against the supplied live endpoint.

2. **The verifier as a prebuilt container image.** The build system packages
   that same Java program into an immutable image referenced by the
   `VERIFIER_IMAGE` variable. **Compose never builds it and never runs Maven.**
   Layer 2 runs *this image* inside the Docker network against the real cluster.
   That is what proves the shipped artifact — not just the source — works
   against genuine Ceph.

So Layer 1 tests the *code*, Layer 2 tests the *shipped image against a real
cluster*, and Layer 3 tests the *code against a real cluster*. All three matter,
and they can disagree (for example, a working image built from stale source).

## Prerequisites

**For Layer 1 (static/JVM) only** — no Docker required:

- A JDK capable of building the reactor (the repository Maven wrapper `mvnw`
  downloads Maven itself; always use the wrapper, never a machine-wide Maven).
- `git` on the PATH. The guardrail tests enumerate tracked files with
  `git ls-files`, so they must run inside the git working tree.

**For Layers 2–4 (anything live)** — additionally:

- Docker Desktop / Docker Engine with Compose v2 (or Podman with `podman
  compose`; the scripts auto-detect, and `COMPOSE_IMPLEMENTATION=docker|podman`
  forces a choice).
- Bash on Linux, macOS, WSL, or Git for Windows. Git Bash is supported
  directly against Docker Desktop and does not require a running WSL
  distribution.
- Enough Docker memory and disk for the official Ceph image and three 1 GiB
  disposable BlueStore volumes.
- A prebuilt image tagged to match `VERIFIER_IMAGE`. See the next section.
- The harness subnet `172.28.0.0/24` must be free. Startup fails early and names
  the offending network if something else already holds it.

**For Layer 3 additionally:** the workstation must resolve the endpoint hostname,
the JVM running Maven must trust the Compose CA, and the live environment
variables from
[maven_test_commands.md](../../../docs/reference/maven_test_commands.md) must be
set. See [Layer 3](#layer-3-live-maven-contract-test-docker).

## One-time setup: build the verifier image

Because Compose refuses to build the verifier, a brand-new checkout has no image
for `VERIFIER_IMAGE` to resolve, and Layer 2 will fail with a missing-image
error until you produce one. For local work, build the convenience `:dev` image
that the template already points at.

The image [Dockerfile](../../../verification/storage/image/Dockerfile) copies the
shaded executable jar from `verification/storage/target/`, so build the jar
first, then the image. Run both from the **repository root** (the Dockerfile's
`COPY` path is repo-root-relative):

PowerShell:

```powershell
.\mvnw.cmd -pl :stratus-storage-verifier -am package
docker build -f verification\storage\image\Dockerfile -t stratus/storage-verifier:dev .
```

bash:

```bash
./mvnw -pl :stratus-storage-verifier -am package
docker build -f verification/storage/image/Dockerfile -t stratus/storage-verifier:dev .
```

This produces `stratus/storage-verifier:dev`, which is the default value of
`VERIFIER_IMAGE` in [.env.template](.env.template). The `:dev` tag is a
local-build convenience only. Any *recorded* verification run must instead set
`VERIFIER_IMAGE` to a digest-pinned reference published by the approved build
system; never present a `:dev` run as release evidence.

## Layer 1: static and JVM tests (no Docker)

This is the everyday regression gate. It runs the whole reactor's `unit`
tagged tests, packages artifacts, produces the JaCoCo coverage
report, and enforces 100% line and branch coverage. It deliberately excludes the
`ceph-integration` tag, so it needs neither Docker nor a live cluster.

### How to run

PowerShell:

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
$ts = Get-Date -Format yyyyMMdd-HHmmss
.\mvnw.cmd clean verify 2>&1 | Tee-Object -FilePath "logs\local-regression-$ts.txt"
```

bash:

```bash
mkdir -p logs
ts="$(date +%Y%m%d-%H%M%S)"
./mvnw clean verify 2>&1 | tee "logs/local-regression-${ts}.txt"
```

Always use `clean` so stale compiled classes cannot contaminate the result.
Capture the full stream with `Tee-Object`/`tee` and inspect the saved file; do
not filter the live Maven stream, which can hide context or delay failures.

### What it runs

Three module groups matter for this doc.

**The verifier module (`stratus-storage-verifier`)** — its test files are in
[`verification/storage/src/test/java/dev/stratus/verification/storage/`](../../../verification/storage/src/test/java/dev/stratus/verification/storage/):

| Test | Tag | What it proves |
|---|---|---|
| `StorageVerifierTest` | unit | Failure reporting against a real closed port plus INFO/DEBUG and rolling-file logging behavior |
| `StorageVerifierMainTest` | unit | The entrypoint: mode selection (contract vs the two negatives), exit codes, writing pure-JSON evidence to `STRATUS_EVIDENCE_FILE`, the unwritable-evidence failure, and single-line ISO-8601 log records |
| `StorageVerifierConfigTest` | unit | Environment-variable parsing and validation |
| `VerificationReportTest` | unit | JSON serialization and string escaping of the report |

**The Ceph tests (`stratus-ceph-tests`)** — Ceph-owned tests in
[`platform/ceph/tests/`](../tests/):

| Test | What it enforces |
|---|---|
| `CephRgwContractTest` | The reusable live Ceph RGW boundary through the AWS SDK, tagged `ceph-integration` and therefore not run by this layer; see [Layer 3](#layer-3-live-maven-contract-test-docker) |
| `CephS3RestContractTest` | The same S3 data boundary over raw AWS Signature Version 4 REST with no SDK in the call path, so a signing, payload-hash, or path-style defect cannot be absorbed by SDK compatibility handling. Tagged `ceph-integration` |
| `CephAdminOpsRestContractTest` | The RGW Admin Operations API (`/admin/...`) through a scoped reader holding only `buckets=read` and `usage=read`, including proof that those caps cannot reach identity keys. Tagged `ceph-integration` |
| `CephDashboardRestContractTest` | The Ceph Dashboard REST API: token authentication, rejection of unauthenticated callers, and bucket create/read/delete through `/api/rgw`. Tagged `ceph-integration` |
| `CephTestArchitectureTest` | That every live contract stays deployment-neutral (no Compose or script machinery), consumes the supplied environment, and fails rather than skips when a live profile is selected |
| `ComposeClusterContractTest` | The Compose implementation's `.env.template`/compose/script/ignore contract: no dead template variables, loopback port binding, correct service policies, secret ignore rules, and safe secret rotation |
| `ComposeClusterScriptTest` | The Compose implementation's single-bash convention: no `.ps1` twins, fail-fast preambles, and Git Bash path handling |

**The repository guardrails (`stratus-repo-guardrails`)** — technology-neutral
checks in [`testing/repo-guardrails/`](../../../testing/repo-guardrails/):

| Test | What it enforces |
|---|---|
| `DocumentationLinkTest` | Every relative Markdown link in a tracked doc resolves, and every `#anchor` matches a heading in its target. This is what keeps *this document's* links honest. |
| `NamingConventionTest` | Implementation docs use capability names (no `incrementN_*.md`), retired names never reappear, and every documented Maven module selector actually exists in the reactor |

### Expected result

A successful run ends with `BUILD SUCCESS`. The completion bar is stricter than
that — a run is only acceptable when **all** of the following hold (from the
[completion checklist](../../../docs/reference/maven_test_commands.md)):

- The log contains `BUILD SUCCESS` and **no** build, logging-binding, packaging,
  or test-fixture warnings.
- JaCoCo reports zero missed production lines and branches.
- The untagged-tests audit executes zero tests (every test carries an approved
  tag).

To run the Ceph implementation guardrails while iterating on harness assets:

PowerShell: `.\mvnw.cmd test -Punit-tests -pl :stratus-ceph-tests -am`
bash: `./mvnw test -Punit-tests -pl :stratus-ceph-tests -am`

A green Ceph guardrail run reports the Compose-specific tests with zero
failures and errors.

### When it fails

The assertion message names the exact problem — a broken doc link with source
and target, a compose service missing a restart policy, a script missing its
fail-fast preamble, a coverage gap. Fix the underlying inconsistency; these tests are the
repository's memory of decisions already made, not obstacles to route around.

## Layer 2: live harness validation (Docker)

This boots a genuine Ceph Tentacle 20.2.2 cluster in Docker (three monitors, two
managers, three BlueStore OSDs, two RGW daemons behind a TLS proxy), creates the
buckets, checks cluster health, and runs the **prebuilt verifier image** against
the live endpoint for both the positive S3 contract and the three security
negatives. It then verifies the Ceph Dashboard REST API on port `8444` and
proves a generated dataset round-trips through the object store byte-for-byte
identical.

Run every command from the `platform/ceph/compose-cluster` directory.

### The sequence

```bash
cd platform/ceph/compose-cluster
./scripts/lifecycle/ceph-compose-startup.sh
./scripts/verify/ceph-compose-bootstrap-buckets.sh
./scripts/verify/ceph-compose-verify-buckets.sh
./scripts/verify/ceph-compose-verify-storage.sh
./scripts/verify/ceph-compose-verify-security.sh
./scripts/verify/ceph-compose-verify-dashboard.sh
./scripts/verify/ceph-compose-verify-dataset.sh
./scripts/lifecycle/ceph-compose-shutdown.sh
```

To capture the whole run as a transcript (note `2>&1`, so stderr lines are
included), use the one-liner from the [README](README.md#workflow).

### Step by step

**`startup`** — [ceph-compose-startup.sh](scripts/lifecycle/ceph-compose-startup.sh)

- On first run it creates the git-ignored `.env` from
  [.env.template](.env.template), replacing the credential placeholders with
  freshly generated per-machine disposable secrets. On Windows the generated
  `.env` is ACL-restricted to the current user; on Linux/macOS it is `chmod
  600`.
- It runs the certificate generator, which is idempotent: it creates the
  disposable Compose CA and RGW server certificate on first run and renews them when
  within seven days of expiry. Leaf renewal preserves the existing CA.
- It fails early, naming the offender, if a foreign Docker network already holds
  the `172.28.0.0/24` harness subnet.
- It then brings the cluster up and waits for every service to become healthy
  (`compose up --wait`).

**Expected result:** the final `compose ps` lists every service, with the Ceph
daemons `healthy`. The one-shot `ceph-bootstrap` and `ceph-configure` jobs show
as completed/exited 0.

**`ceph-compose-bootstrap-buckets`** — creates the five Stratus buckets (`stratus-landing`,
`stratus-bronze`, `stratus-silver`, `stratus-gold`, `stratus-platform`) as the
verifier identity, plus the isolated `stratus-denied` bucket owned by a
*separate* RGW identity. That separate owner is what makes the cross-identity
access-denied negative meaningful.

**Expected result:** a `READY bucket=...` line for each of the five buckets and a
`READY isolated-policy-bucket=...` line for the denied bucket. Each line carries
an ISO-8601 UTC timestamp.

**`check`** — lists each of the five buckets through the S3 client to confirm
they are reachable and empty-listable.

**Expected result:** a `PASS bucket=...` line for each of the five buckets. The
steady-state cluster health target is `HEALTH_OK`, all three OSDs `up`/`in`, all
placement groups `active+clean` (inspect directly with the commands in the
[README](README.md#direct-inspection)).

**`ceph-compose-verify-storage`** — [ceph-compose-verify-storage.sh](scripts/verify/ceph-compose-verify-storage.sh) — runs the prebuilt verifier image once
in `CONTRACT` mode against the live endpoint. It first writes an
`environment-<timestamp>.json` snapshot (compose runtime and platform, resolved
Ceph and verifier image digests, `ceph version`, `ceph status`, OSD tree), then
runs the verifier, which writes its report directly to
`storage-verification-<timestamp>.json`.

The verifier performs these contract checks, in order, and reports one result
per check (`name` / `passed` / `detail`):

`required-buckets` → `missing-object` → `object-round-trip` → `zero-byte-object`
→ `object-overwrite` → `special-character-key` → `large-single-put` (1 MiB) →
`head-and-list` → `paginated-list` (forced small pages) → `concurrent-access`
(eight concurrent PUT/GET/HEAD on virtual threads) → `multipart-upload` (5 MiB +
1 KiB) → `probe-cleanup` (every probe object deleted and confirmed gone).

**Expected result:** the verifier exits `0`, the script logs `Evidence: ...` and
`Verifier log: ...`, and the report JSON has `"success":true` with every check
`"passed":true`. On any failure the verifier exits `2`, the script renames the
evidence to `storage-verification-<timestamp>-FAILED.json`, and stops with a
non-zero status.

**`ceph-compose-verify-security`** — [ceph-compose-verify-security.sh](scripts/verify/ceph-compose-verify-security.sh) — runs three *negative* tests
where **failure of the operation is the expected, asserted outcome**. Each run
is bracketed with an `EXPECTED`-failure banner so the transcript self-documents;
authentication errors, access-denied errors, and PKIX certificate errors in this
output are supposed to be there.

| # | Mode / service | What must happen | Evidence file |
|---|---|---|---|
| 1 | `AUTH_FAILURE` (deliberately invalid secret) | RGW rejects the bad credentials | `storage-invalid-credentials-<ts>.json` |
| 2 | `ACCESS_DENIED` | The verifier is denied listing a bucket owned by a separate identity | `storage-cross-identity-denial-<ts>.json` |
| 3 | `verifier-untrusted` service (no Compose CA) | The JVM rejects the RGW certificate (fails closed on TLS) | `storage-untrusted-tls-<ts>.log` |

The script asserts on evidence **content**, not just exit codes. For tests 1 and
2 it requires the report to contain `"name":"...","passed":true` (meaning the
denial genuinely occurred); a verifier that merely exits 0 without denial
evidence is rejected. For test 3 it requires exit code `2` **and** the output to
match `PKIX`, `SSLHandshake`, or `certification path`.

**Expected result:** three `PASS ...` lines, each naming its evidence file, and a
`NEGATIVE TESTS COMPLETE` banner. If a negative test's *denial* does not occur —
for example RGW accepts bad credentials, or Java trusts an untrusted cert — the
script fails loudly; that is a real security regression, not a flaky test.

**`ceph-compose-verify-dashboard`** — [ceph-compose-verify-dashboard.sh](scripts/verify/ceph-compose-verify-dashboard.sh) —
verifies the REST API for the Ceph admin console (Ceph Dashboard), published on
port `8444`. This management interface is distinct from the S3 API on `8443`.
The checks run with curl and jq inside `mon1`, so they cross the same nginx TLS
proxy a browser or API client uses, and the dashboard credentials plus the CA
travel over stdin — never on a command line or into the evidence. Six checks,
one result per check:

`dashboard-authentication` (`POST /api/auth` answers 201 with a session token)
→ `unauthenticated-request-rejected` (`GET /api/summary` without a token
answers 401 — real product behavior, not a simulation) → `cluster-health`
(`GET /api/health/minimal` reports `HEALTH_OK`) → `daemon-inventory` (three
monitors in the map, three OSDs up and in) → `reported-version` (the summary
identifies a Ceph version) → `session-logout` (`POST /api/auth/logout` revokes
the session the test created).

**Expected result:** the script prints the evidence JSON, which must contain
`"success": true` with every check `"passed": true`, and ends with a
`PASS dashboard-rest-api` line naming `dashboard-verification-<ts>.json`. The
host asserts on the evidence content, not just the exit code.

**`ceph-compose-verify-dataset`** — [ceph-compose-verify-dataset.sh](scripts/verify/ceph-compose-verify-dataset.sh) —
proves a dataset written to the object store reads back byte-for-byte
identical. Inside the `s3client` container it generates 24 seeded files
(1 KiB–64 KiB across nested directories), uploads them to
`stratus-landing/verification/dataset-<ts>/`, and then proves read-back two
independent ways: `rclone check --download` re-downloads every object and
byte-compares it against the source, and a full copy back into a second local
tree must hash-match the original. Object count and total bytes are asserted
between source and remote, the remote prefix is purged, and the purge is
confirmed empty, so the run cleans up its probe objects.

**Expected result:** the evidence JSON in `dataset-verification-<ts>.json`
contains `"success": true` for the five checks (`dataset-created`,
`dataset-uploaded`, `dataset-download-verified`, `dataset-readback-verified`,
`dataset-cleanup`) and the script ends with a `PASS dataset-round-trip` line.

**`shutdown`** — removes the containers and the project network but **preserves**
the Ceph data volumes, so the next `startup` restarts the same cluster. It works
even from a broken state with no `.env`, by tearing down via the compose project
name.

### First-run timing note

The very first `startup` also pulls the multi-hundred-megabyte Ceph image and
initializes three OSDs, so it is noticeably slower than later runs. The OSD
health checks allow up to a 30 s start period plus retries for exactly this
reason. Subsequent restarts against preserved volumes are much faster.

## Layer 3: live Maven contract test (Docker)

Separate from the container-level scripts above, the Ceph-owned test module has
four deployment-neutral live JVM contracts — the product-compatibility
boundary. They run from Maven on your host against any Ceph RGW endpoint
supplied through the environment. The Compose cluster is one provider of that
environment; cephadm and future implementations run the same tests without
copying or changing them.

| Contract | Surface |
|---|---|
| `CephRgwContractTest` | The S3 data API through the AWS SDK |
| `CephS3RestContractTest` | The same data API over raw signed REST, no SDK |
| `CephAdminOpsRestContractTest` | The RGW Admin Operations API (`/admin/...`) |
| `CephDashboardRestContractTest` | The Ceph Dashboard REST API (`/api/auth`, `/api/rgw/...`) |

### Requirements

The cluster from Layer 2 must be up, the workstation must resolve the endpoint
hostname, the JVM running Maven must trust the Compose CA, and these variables
must be set (see
[maven_test_commands.md](../../../docs/reference/maven_test_commands.md)):

```dotenv
CEPH_RGW_INTEGRATION=true
CEPH_RGW_ENDPOINT=https://object-store.stratus.local
CEPH_RGW_ACCESS_KEY=<the verifier access key from your .env>
CEPH_RGW_SECRET_KEY=<the matching secret>
CEPH_RGW_PROBE_BUCKET=stratus-landing
CEPH_DENIED_ACCESS_KEY=<the isolated identity access key from your .env>
CEPH_DENIED_SECRET_KEY=<the matching isolated identity secret>
CEPH_RGW_DENIED_BUCKET=stratus-denied
S3_PATH_STYLE_ACCESS=true
CEPH_ADMIN_OPS_ACCESS_KEY=<the Admin Operations reader access key from your .env>
CEPH_DASHBOARD_ENDPOINT=https://object-store.stratus.local:8444
CEPH_DASHBOARD_USER=<the dashboard user from your .env>
CEPH_DASHBOARD_PASSWORD=<the matching dashboard password>
```

The Admin Operations and Dashboard secrets are omitted above only to keep the
list readable; `CEPH_ADMIN_OPS_SECRET_KEY` is required alongside its access key.
Every value comes from the generated `.env`, and
`ceph-compose-run-live-tests.sh` exports the whole set for you rather than
requiring any of it by hand.

A selected live profile **fails** (never silently passes) if
`CEPH_RGW_INTEGRATION=true` is absent, so a skipped live test can never be
mistaken for a passing one.

#### Why Layer 3 needs a hosts-file entry and Layer 2 does not

This is the most common reason a Layer 3 run fails on a cluster that is
demonstrably healthy. Layer 2 scripts such as `ceph-compose-verify-storage.sh`, `ceph-compose-verify-dataset.sh`,
and `ceph-compose-verify-dashboard.sh` execute **inside containers**, where Compose DNS
already resolves `object-store.stratus.local` and the container truststore
already holds the CA. Layer 3 runs on the **workstation JVM**, which uses neither.

A passing Layer 2 run therefore tells you nothing about whether Layer 3 can
connect. The workstation needs the same one-time hosts-file entry the browser
needs for the admin console — see
[Quick Start Step 2](CEPH_COMPOSE_CLUSTER_QUICK_START_GUIDE.md#step-2-make-the-dashboard-hostname-resolve-on-the-workstation).
Confirm it resolves before running Maven:

```powershell
Resolve-DnsName object-store.stratus.local
```

```bash
getent hosts object-store.stratus.local
```

Either must report `127.0.0.1`. `Non-existent domain` or no output means the
entry is missing, and every live JVM test will fail on connection rather than on
product behavior.

### How to run

PowerShell:

```powershell
.\mvnw.cmd clean verify -Pall-tests 2>&1 | Tee-Object -FilePath "logs\all-tests-$ts.txt"
```

bash:

```bash
./mvnw clean verify -Pall-tests 2>&1 | tee "logs/all-tests-${ts}.txt"
```

To run only the live test while diagnosing a failure:

PowerShell: `.\mvnw.cmd test -Pceph-integration-tests -pl :stratus-ceph-tests -am`
bash: `./mvnw test -Pceph-integration-tests -pl :stratus-ceph-tests -am`

### Expected result

`BUILD SUCCESS` with the `ceph-integration` test executed (not skipped). A
targeted profile deliberately skips the aggregate coverage gate because it runs
only part of the production code — so a targeted run is a *diagnostic*, never
completion evidence. Only the full `clean verify -Pall-tests` counts as the live
regression gate.

## Layer 4: harness self-test (Docker, destructive)

`ceph-compose-verify-harness` validates the harness *scripts' own behavior* — the things the static
guardrails in Layer 1 cannot observe because they only read files. Its final
scenario exercises destructive reset, so it **refuses to run while any harness
container or preserved cluster volume exists**.

### How to run

Ensure the harness is fully stopped and its volumes are gone first (run
`shutdown`, and `reset --force` if you have preserved volumes you are willing to
lose). Then:

```bash
cd platform/ceph/compose-cluster
./scripts/verify/ceph-compose-verify-harness.sh
```

### What it proves

| Scenario | What it does | Pass condition |
|---|---|---|
| Certificate renewal | Backdates the leaf certificate to near-expiry, reruns the generator | The leaf is renewed **and** the CA fingerprint is unchanged (renewal preserves the CA) |
| Vacuous-verifier rejection | Builds a fake verifier image that prints `{}` and exits 0, points `VERIFIER_IMAGE` at it, runs `ceph-compose-verify-security` | `ceph-compose-verify-security` **rejects** it with "does not show invalid credentials being rejected" |
| Teardown without `.env` | Removes `.env`, runs `shutdown` then `reset --force` | Both succeed with no `.env` present |

It cleans up after itself: it restores your `.env`, deletes the fake image, and
removes any evidence files it created.

### Expected result

Three `PASS` lines followed by:

```text
HARNESS CHECK PASS: certificate-renewal, vacuous-verifier-rejected, teardown-without-env
```

Any other outcome means a harness script regressed — for example, the vacuous
verifier being accepted would mean the negative-test assertions are no longer
protecting you.

## Run everything, in order

A complete local validation from a clean state, in dependency order:

```text
1. Build the verifier image            (one-time / after verifier source changes)
2. mvnw clean verify                   Layer 1  — no Docker
3. cd platform/ceph/compose-cluster
4. scripts/lifecycle/ceph-compose-startup                     Layer 2  — boots the cluster
5. scripts/verify/ceph-compose-bootstrap-buckets
6. scripts/verify/ceph-compose-verify-buckets
7. scripts/verify/ceph-compose-verify-storage
8. scripts/verify/ceph-compose-verify-security
9. scripts/verify/ceph-compose-verify-dashboard
10. scripts/verify/ceph-compose-verify-dataset
11. (optional) mvnw clean verify -Pall-tests   Layer 3 — needs env + CA trust, cluster up
12. (optional) scripts/verify/ceph-compose-failure-drill   Layer 3 — real daemon outages and recovery
13. scripts/lifecycle/ceph-compose-shutdown
14. scripts/lifecycle/ceph-compose-reset --force              only if you want a fresh cluster next time
15. scripts/verify/ceph-compose-verify-harness                   Layer 4 — requires the harness stopped, volumes gone
```

Steps 2 and 4–10 are the normal validation. Add step 11 when the change touches
the live Ceph contract, and step 12 when it affects resilience or failover
behavior (the drill stops a real RGW, monitor, and OSD in turn and requires
recovery to `HEALTH_OK`). Run step 15 when you change harness scripts. Steps
14–15 are destructive to the cluster; `reset` prompts for confirmation unless
you pass `--force`.

### `shutdown` vs `reset`

- **`shutdown`** stops and removes containers and the network but **keeps** the
  data volumes. Use it between validation runs; the cluster restarts intact.
- **`reset`** additionally **deletes all cluster data and configuration
  volumes**. Use it to force a fresh cluster (new fsid, new identities). It
  preserves `.env`, certificates, pulled images, and the `evidence/` directory.

## Understanding the evidence

Layers 2 writes artifacts to the git-ignored `evidence/` directory. Every report
is pure JSON written directly by the verifier and opens with a `description`
field stating exactly what that evidence proves — including the deliberately
inverted meaning for the negatives, where `"success":true` means the denial
*happened*.

| File | Produced by | Meaning of `success:true` |
|---|---|---|
| `storage-verification-<ts>.json` | `ceph-compose-verify-storage` | Every S3 contract check against RGW passed |
| `storage-verification-<ts>-FAILED.json` | `ceph-compose-verify-storage` on failure | At least one contract check failed; open it to see which |
| `environment-<ts>.json` | `ceph-compose-verify-storage` | Snapshot of runtime, image digests, and cluster state for the same run |
| `storage-verifier-<ts>.0.log` | `ceph-compose-verify-storage` | Per-run verifier log; single-line ISO-8601 timestamped records |
| `storage-invalid-credentials-<ts>.json` | `ceph-compose-verify-security` | RGW rejected invalid credentials |
| `storage-cross-identity-denial-<ts>.json` | `ceph-compose-verify-security` | RGW denied cross-identity bucket access |
| `storage-untrusted-tls-<ts>.log` | `ceph-compose-verify-security` | Captured output showing the JVM rejected the untrusted certificate (this is a log, not JSON) |
| `dashboard-verification-<ts>.json` | `ceph-compose-verify-dashboard` | Every Ceph Dashboard REST API check passed, including the 401 for unauthenticated requests |
| `dataset-verification-<ts>.json` | `ceph-compose-verify-dataset` | The generated dataset uploaded, read back byte-for-byte identical, and was purged |

Evidence must never contain RGW secret keys, CA private keys, or the TLS server
private key. Containerized verifier output may contain the JVM's public default
truststore password because the standard Temurin entrypoint emits its
`JAVA_TOOL_OPTIONS`; the workstation live-test wrapper omits the password from
its command line. The [README Evidence section](README.md#evidence) has the full
rationale.

## Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| `VERIFIER_IMAGE must identify a prebuilt verifier image` or image-not-found on `ceph-compose-verify-storage` | No local image built | Do the [one-time image build](#one-time-setup-build-the-verifier-image) |
| Startup fails naming a network on `172.28.0.0/24` | Another cluster (often one left under an old project name) holds the subnet | Tear down whatever owns it (`docker compose -p <old-project> down`) and retry |
| `Neither Docker Compose nor Podman is available` | No container runtime on PATH | Install Docker/Podman, or set `COMPOSE_IMPLEMENTATION` |
| `ceph-compose-verify-security` fails saying a denial was not shown | A security negative did not deny as required (real regression) — or a genuinely broken cluster | Read the named evidence file; do not treat this as flaky |
| `clean verify` fails in `DocumentationLinkTest` | A Markdown link or `#anchor` broke | The assertion prints the exact source → target; fix the link |
| `clean verify` fails in `ComposeClusterScriptTest` | A `.ps1` script reappeared under the harness script tree, or a bash script lost its shebang or `set -euo pipefail` preamble | Remove the `.ps1` or restore the preamble per the assertion message; the harness is bash-only (ADR-P1-002) |
| Live Maven profile "passes" but ran no Ceph test | `CEPH_RGW_INTEGRATION=true` not set | Set the full [Layer 3](#layer-3-live-maven-contract-test-docker) variable set; a selected live profile must never skip silently |
| Every live JVM test fails to connect while Layer 2 scripts pass | The workstation does not resolve `object-store.stratus.local`; Layer 2 runs inside containers and never needs it | Add the [hosts-file entry](#why-layer-3-needs-a-hosts-file-entry-and-layer-2-does-not) and confirm with `Resolve-DnsName object-store.stratus.local` |
| `ceph-compose-verify-harness` refuses to start | Harness containers or cluster volumes still exist | `scripts/lifecycle/ceph-compose-shutdown` then `scripts/lifecycle/ceph-compose-reset --force`, then rerun |
| Git Bash changes `/certs/...` into `C:/Program Files/Git/certs/...` | A raw `docker compose` command bypassed the shared MSYS path handling, or the scripts are stale | Run the checked-in lifecycle/verify scripts. Do not remove `MSYS_NO_PATHCONV` or the `cygpath` conversion in `scripts/lib/ceph-compose-common.sh` |
| First verifier run reports `UnknownHostException: object-store.stratus.local` | The verifier script is stale, or Docker DNS did not register the proxy alias within the bounded readiness period | Use the current `ceph-compose-verify-storage` script, then inspect the `stratus-ceph-local_ceph` network and the `rgw-proxy` alias; do not add an ad hoc hosts entry inside the container |
| A shell script fails with `/usr/bin/env: 'bash\r'` or `^M` | CRLF line endings reached a Linux container | Keep `.gitattributes` with `*.sh text eol=lf`, restore the affected script with LF endings, and rerun `bash -n` |
| Docker Desktop works while `wsl -l -v` shows Ubuntu stopped | Expected Docker Desktop architecture | No remediation is needed. Git Bash and PowerShell use Docker Desktop directly; the user Ubuntu distribution is not a prerequisite |

For deeper cluster inspection (quorum, OSD tree, RGW users, manager status), use
the `ceph` commands listed under [Direct
inspection](README.md#direct-inspection) in the module README.
