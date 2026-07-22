# Stratus Ceph Compose Cluster: Testing and Validation Guide

- Author: Mark Raysmith
- Created: 2026-07-15
- Last updated: 2026-07-20
- Status: Active

This is the complete, self-contained guide to every test and validation process
that applies to the Ceph/RGW Compose cluster in `platform/ceph/compose-cluster`. It
assumes no prior knowledge of this module. If you have never run anything here,
start at [Who this is for](#who-this-is-for) and read straight through; if you
just want commands, jump to [Run everything, in order](#run-everything-in-order).

For what the environment *is* (its services, what it proves and does not prove,
and its configuration), read the module [README.md](README.md) first. This
document is about how you *test and validate* it.

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
users run them from Git Bash, e.g. `bash scripts/lifecycle/startup.sh`.

## The four validation layers at a glance

There are four distinct things people loosely call "the ceph tests". They have
different purposes and, crucially, different requirements. Know which one you
need.

| # | Layer | What it checks | Docker? | Live cluster? | Roughly how long |
|---|---|---|---|---|---|
| 1 | Static and JVM tests | Verifier Java logic, plus repository consistency guardrails | No | No | Seconds to ~1 min |
| 2 | Live harness validation | A real Ceph cluster boots and the prebuilt verifier passes every S3 contract and security-negative check against it | Yes | Yes (this module starts it) | Several minutes |
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
   `stratus-storage-verifier`. Its *unit and protocol tests* (Layer 1) run on
   your host JVM with no Docker and no Ceph. Its *live contract test*
   (`CephRgwIntegrationTest`, Layer 3) runs on your host JVM against the live
   endpoint.

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

**For Layer 3 additionally:** the JVM running Maven must trust the Compose CA, and
the live environment variables from
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

This is the everyday regression gate. It runs the whole reactor's `unit` and
`protocol` tagged tests, packages artifacts, produces the JaCoCo coverage
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

Two module groups matter for this doc.

**The verifier module (`stratus-storage-verifier`)** — its test files are in
[`verification/storage/src/test/java/dev/stratus/verification/storage/`](../../../verification/storage/src/test/java/dev/stratus/verification/storage/):

| Test | Tag | What it proves |
|---|---|---|
| `StorageVerifierTest` | unit | The contract-check logic: round-trip, zero-byte, overwrite, special-character keys, 1 MiB PUT, HEAD/list, forced pagination, eight-way concurrency, multipart, and cleanup, using an in-memory storage stub |
| `StorageVerifierMainTest` | unit | The entrypoint: mode selection (contract vs the two negatives), exit codes, writing pure-JSON evidence to `STRATUS_EVIDENCE_FILE`, the unwritable-evidence failure, and single-line ISO-8601 log records |
| `StorageVerifierConfigTest` | unit | Environment-variable parsing and validation |
| `VerificationReportTest` | unit | JSON serialization and string escaping of the report |
| `S3ObjectStorageClientTest` | protocol | The real AWS SDK client against a real in-process HTTP endpoint (no mocking framework — Mockito is prohibited here) |
| `CephRgwIntegrationTest` | ceph-integration | The live boundary — **not** run by this layer; see [Layer 3](#layer-3-live-maven-contract-test-docker) |

**The repository guardrails (`stratus-repo-guardrails`)** — static consistency
checks in
[`testing/repo-guardrails/`](../../../testing/repo-guardrails/), all tagged
`unit` so they run inside `clean verify`:

| Test | What it enforces |
|---|---|
| `DocumentationLinkTest` | Every relative Markdown link in a tracked doc resolves, and every `#anchor` matches a heading in its target. This is what keeps *this document's* links honest. |
| `NamingConventionTest` | Implementation docs use capability names (no `incrementN_*.md`), retired names never reappear, and every documented Maven module selector actually exists in the reactor |
| `HarnessContractTest` | The compose/`.env.template`/script/ignore contract: no dead template variables, the RGW port binds to loopback by default, one-shot vs long-running vs on-demand services declare correct restart/health/hardening policies, secrets are git-ignored, and no key material is tracked |
| `ScriptParityTest` | The single-bash-implementation convention (ADR-P1-002): no `.ps1` script may reappear under the harness script tree, every bash script keeps its shebang and `set -euo pipefail` fail-fast preamble, and `common.sh` keeps its Git Bash path handling |

### Expected result

A successful run ends with `BUILD SUCCESS`. The completion bar is stricter than
that — a run is only acceptable when **all** of the following hold (from the
[completion checklist](../../../docs/reference/maven_test_commands.md)):

- The log contains `BUILD SUCCESS` and **no** build, logging-binding, packaging,
  or test-fixture warnings.
- JaCoCo reports zero missed production lines and branches.
- The untagged-tests audit executes zero tests (every test carries an approved
  tag).

To run only the guardrails while iterating on scripts or docs:

PowerShell: `.\mvnw.cmd test -Punit-tests -pl :stratus-repo-guardrails`
bash: `./mvnw test -Punit-tests -pl :stratus-repo-guardrails`

A green guardrail run reports `Tests run: 15, Failures: 0, Errors: 0`.

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
negatives.

Run every command from the `platform/ceph/compose-cluster` directory.

### The sequence

```bash
cd platform/ceph/compose-cluster
./scripts/lifecycle/startup.sh
./scripts/verify/bootstrap-buckets.sh
./scripts/verify/check.sh
./scripts/verify/verify-java.sh
./scripts/verify/verify-security.sh
./scripts/lifecycle/shutdown.sh
```

To capture the whole run as a transcript (note `2>&1`, so stderr lines are
included), use the one-liner from the [README](README.md#workflow).

### Step by step

**`startup`** — [startup.sh](scripts/lifecycle/startup.sh)

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

**`bootstrap-buckets`** — creates the five Stratus buckets (`stratus-landing`,
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

**`verify-java`** — [verify-java.sh](scripts/verify/verify-java.sh) — runs the prebuilt verifier image once
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

**`verify-security`** — [verify-security.sh](scripts/verify/verify-security.sh) — runs three *negative* tests
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

Separate from the container-level scripts above, the verifier module has its own
live JVM test, `CephRgwIntegrationTest` — the product-compatibility boundary. It
runs from Maven on your host against the live endpoint, exercised through the
`-Pall-tests` profile (which runs every tag *and* re-enforces the coverage
gate). This is the test to run when you change signing, path-style routing,
bucket policy, object semantics, multipart, or timeout behavior.

### Requirements

The cluster from Layer 2 must be up, the JVM running Maven must trust the Compose CA,
and these variables must be set (see
[maven_test_commands.md](../../../docs/reference/maven_test_commands.md)):

```dotenv
CEPH_RGW_INTEGRATION=true
CEPH_RGW_ENDPOINT=https://object-store.stratus.local
CEPH_RGW_ACCESS_KEY=<the verifier access key from your .env>
CEPH_RGW_SECRET_KEY=<the matching secret>
CEPH_RGW_PROBE_BUCKET=stratus-landing
S3_PATH_STYLE_ACCESS=true
```

A selected live profile **fails** (never silently passes) if
`CEPH_RGW_INTEGRATION=true` is absent, so a skipped live test can never be
mistaken for a passing one.

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

PowerShell: `.\mvnw.cmd test -Pceph-integration-tests -pl :stratus-storage-verifier`
bash: `./mvnw test -Pceph-integration-tests -pl :stratus-storage-verifier`

### Expected result

`BUILD SUCCESS` with the `ceph-integration` test executed (not skipped). A
targeted profile deliberately skips the aggregate coverage gate because it runs
only part of the production code — so a targeted run is a *diagnostic*, never
completion evidence. Only the full `clean verify -Pall-tests` counts as the live
regression gate.

## Layer 4: harness self-test (Docker, destructive)

`selftest` validates the harness *scripts' own behavior* — the things the static
guardrails in Layer 1 cannot observe because they only read files. Its final
scenario exercises destructive reset, so it **refuses to run while any harness
container or preserved cluster volume exists**.

### How to run

Ensure the harness is fully stopped and its volumes are gone first (run
`shutdown`, and `reset --force` if you have preserved volumes you are willing to
lose). Then:

```bash
cd platform/ceph/compose-cluster
./scripts/verify/selftest.sh
```

### What it proves

| Scenario | What it does | Pass condition |
|---|---|---|
| Certificate renewal | Backdates the leaf certificate to near-expiry, reruns the generator | The leaf is renewed **and** the CA fingerprint is unchanged (renewal preserves the CA) |
| Vacuous-verifier rejection | Builds a fake verifier image that prints `{}` and exits 0, points `VERIFIER_IMAGE` at it, runs `verify-security` | `verify-security` **rejects** it with "does not show invalid credentials being rejected" |
| Teardown without `.env` | Removes `.env`, runs `shutdown` then `reset --force` | Both succeed with no `.env` present |

It cleans up after itself: it restores your `.env`, deletes the fake image, and
removes any evidence files it created.

### Expected result

Three `PASS` lines followed by:

```text
SELFTEST PASS: certificate-renewal, vacuous-verifier-rejected, teardown-without-env
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
4. scripts/lifecycle/startup                     Layer 2  — boots the cluster
5. scripts/verify/bootstrap-buckets
6. scripts/verify/check
7. scripts/verify/verify-java
8. scripts/verify/verify-security
9. (optional) mvnw clean verify -Pall-tests   Layer 3 — needs env + CA trust, cluster up
10. (optional) scripts/verify/failure-drill   Layer 3 — real daemon outages and recovery
11. scripts/lifecycle/shutdown
12. scripts/lifecycle/reset --force              only if you want a fresh cluster next time
13. scripts/verify/selftest                   Layer 4 — requires the harness stopped, volumes gone
```

Steps 2 and 4–8 are the normal validation. Add step 9 when the change touches the
live Ceph contract, and step 10 when it affects resilience or failover behavior
(the drill stops a real RGW, monitor, and OSD in turn and requires recovery to
`HEALTH_OK`). Run step 13 when you change harness scripts. Steps 12–13 are
destructive to the cluster; `reset` prompts for confirmation unless you pass
`--force`.

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
| `storage-verification-<ts>.json` | `verify-java` | Every S3 contract check against RGW passed |
| `storage-verification-<ts>-FAILED.json` | `verify-java` on failure | At least one contract check failed; open it to see which |
| `environment-<ts>.json` | `verify-java` | Snapshot of runtime, image digests, and cluster state for the same run |
| `storage-verifier-<ts>.0.log` | `verify-java` | Per-run verifier log; single-line ISO-8601 timestamped records |
| `storage-invalid-credentials-<ts>.json` | `verify-security` | RGW rejected invalid credentials |
| `storage-cross-identity-denial-<ts>.json` | `verify-security` | RGW denied cross-identity bucket access |
| `storage-untrusted-tls-<ts>.log` | `verify-security` | Captured output showing the JVM rejected the untrusted certificate (this is a log, not JSON) |

Evidence must never contain RGW secret keys, CA private keys, or the TLS server
private key. Note that transcripts legitimately contain the JVM line `Picked up
JAVA_TOOL_OPTIONS: ... trustStorePassword=changeit` — this is **not** a leaked
secret; `changeit` is the JVM's publicly documented default truststore password
and the truststore holds only public certificates. The [README Evidence
section](README.md#evidence) has the full rationale.

## Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| `VERIFIER_IMAGE must identify a prebuilt verifier image` or image-not-found on `verify-java` | No local image built | Do the [one-time image build](#one-time-setup-build-the-verifier-image) |
| Startup fails naming a network on `172.28.0.0/24` | Another cluster (often one left under an old project name) holds the subnet | Tear down whatever owns it (`docker compose -p <old-project> down`) and retry |
| `Neither Docker Compose nor Podman is available` | No container runtime on PATH | Install Docker/Podman, or set `COMPOSE_IMPLEMENTATION` |
| `verify-security` fails saying a denial was not shown | A security negative did not deny as required (real regression) — or a genuinely broken cluster | Read the named evidence file; do not treat this as flaky |
| `clean verify` fails in `DocumentationLinkTest` | A Markdown link or `#anchor` broke | The assertion prints the exact source → target; fix the link |
| `clean verify` fails in `ScriptParityTest` | A `.ps1` script reappeared under the harness script tree, or a bash script lost its shebang or `set -euo pipefail` preamble | Remove the `.ps1` or restore the preamble per the assertion message; the harness is bash-only (ADR-P1-002) |
| Live Maven profile "passes" but ran no Ceph test | `CEPH_RGW_INTEGRATION=true` not set | Set the full [Layer 3](#layer-3-live-maven-contract-test-docker) variable set; a selected live profile must never skip silently |
| `selftest` refuses to start | Harness containers or cluster volumes still exist | `scripts/lifecycle/shutdown` then `scripts/lifecycle/reset --force`, then rerun |
| Git Bash changes `/certs/...` into `C:/Program Files/Git/certs/...` | A raw `docker compose` command bypassed the shared MSYS path handling, or the scripts are stale | Run the checked-in lifecycle/verify scripts. Do not remove `MSYS_NO_PATHCONV` or the `cygpath` conversion in `scripts/lib/common.sh` |
| First verifier run reports `UnknownHostException: object-store.stratus.local` | The verifier script is stale, or Docker DNS did not register the proxy alias within the bounded readiness period | Use the current `verify-java` script, then inspect the `stratus-ceph-local_ceph` network and the `rgw-proxy` alias; do not add an ad hoc hosts entry inside the container |
| A shell script fails with `/usr/bin/env: 'bash\r'` or `^M` | CRLF line endings reached a Linux container | Keep `.gitattributes` with `*.sh text eol=lf`, restore the affected script with LF endings, and rerun `bash -n` |
| Docker Desktop works while `wsl -l -v` shows Ubuntu stopped | Expected Docker Desktop architecture | No remediation is needed. Git Bash and PowerShell use Docker Desktop directly; the user Ubuntu distribution is not a prerequisite |

For deeper cluster inspection (quorum, OSD tree, RGW users, manager status), use
the `ceph` commands listed under [Direct
inspection](README.md#direct-inspection) in the module README.
