# Stratus Harness Operations Runbook

This is the single operational reference for running the Stratus local
harnesses and every layer of the test suite. It covers first-time setup,
the daily start/verify/stop cycle, each test layer with its exact command
and expected result, the destructive operations, where evidence is written,
and the failures a new operator will actually hit.

Scope: the developer harnesses in `platform/`. Production deployment is
governed by the increment implementation documents and
[stratus_phase1_operational_readiness.md](stratus_phase1_operational_readiness.md).

Related references: [Maven Test and Build Commands](../reference/maven_test_commands.md)
for profile mechanics, [Code Style and Engineering Rules](../reference/code_style_rules.md)
§7 for the testing rules these commands enforce.

---

## 1. Prerequisites

| Requirement | Why | How to satisfy |
|---|---|---|
| Docker Engine or Docker Desktop, running | every harness is Compose-based | `docker info` must succeed |
| Git Bash (Windows) or any POSIX shell | harness scripts are bash-only (ADR-P1-002); there are no PowerShell twins | run scripts as `bash <path>` |
| `JAVA_HOME` set | the live test wrappers build a CA truststore with `keytool` | must point at the JDK used by the build |
| Hosts-file entry for `object-store.stratus.local` | live JVM tests run on the workstation, not in a container, so they must resolve the RGW endpoint | `bash platform/ceph/compose-cluster/scripts/lifecycle/ceph-compose-configure-hostname.sh` (privileged) |
| Repository Maven wrapper | reproducible builds; a machine-wide Maven install is not the supported path | use `./mvnw`, never `mvn` |

One-time prerequisite installation:

```bash
bash platform/ceph/compose-cluster/scripts/lifecycle/ceph-compose-install-prerequisites.sh
```

The live test wrappers supply endpoints, scoped credentials, and the CA
truststore themselves. Do not set `CEPH_RGW_*` or `STRATUS_POLARIS_*` by
hand.

---

## 2. Start the platform

Order matters. Polaris pulls its `svc-polaris` storage identity from
OpenBao, and the Ceph provisioning step is what publishes it.

```bash
bash platform/ceph/compose-cluster/scripts/lifecycle/ceph-compose-startup.sh
bash platform/openbao/compose-service/scripts/lifecycle/openbao-compose-startup.sh
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-bootstrap-buckets.sh
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-provision-service-identities.sh
bash platform/polaris/compose-service/scripts/lifecycle/polaris-compose-startup.sh
bash platform/polaris/compose-service/scripts/verify/polaris-compose-bootstrap-catalog.sh
```

Add the Spark batch engine only if you need it; it is a consumer of both
harnesses above and never starts them itself:

```bash
bash platform/spark/compose-cluster/scripts/lifecycle/spark-compose-startup.sh
bash platform/spark/compose-cluster/scripts/verify/spark-compose-bootstrap-principal.sh
bash platform/spark/compose-cluster/scripts/verify/spark-compose-verify-cluster.sh
```

The first Spark start needs the runtime image, which the harness never builds
for you (P1-0.1). Resolve its artifacts and build it once:

```bash
bash platform/spark/compose-cluster/scripts/lib/spark-compose-resolve-artifacts.sh
docker build -f platform/spark/image/Dockerfile -t stratus/spark-runtime:dev platform/spark/image
```

Startup also mounts the platform job jar and refuses to start without it. The
harness never builds it, for the same reason it never builds the image:

```bash
./mvnw -pl :stratus-spark-jobs -am package -DskipTests
```

Rebuild the jar after any change under `jobs/spark/` and restart the cluster;
the mount is read at container creation.

Expected end state: `READY bucket=` lines for all five buckets,
`PUBLISH openbao path=stratus/service-identities/svc-polaris`,
`READY catalog=stratus`, four `READY namespace=` lines, and
`READY table=platform.quality_check_results`. With Spark added: `PUBLISH
openbao path=stratus/service-identities/svc-spark`, `READY
principal=svc-spark`, and `PASS spark-cluster workers=2/2`.

Every step is idempotent — re-running converges and reports
`(already exists)`.

**After any Polaris restart, re-run the catalog bootstrap.** Polaris 1.5.0
runs an in-memory metastore in this harness: the catalog, namespaces, and
table registrations are discarded on shutdown while the underlying Ceph
objects persist.

**After any OpenBao restart, re-run the Ceph service-identity
provisioning.** OpenBao runs in dev mode and discards secrets on shutdown.

---

## 3. Test layers

### 3.1 Offline — no harness required

The mandatory regression for every change.

```bash
./mvnw clean verify
```

Runs every `unit`-tagged and untagged test across all modules, packages the
artifacts, and writes JaCoCo reports. Live-tagged suites are excluded by
default. Expected: `BUILD SUCCESS`, and — per the completion gate in the
code style rules — a saved log with no build, packaging, or
logging-binding warnings.

Tag audit, which must execute zero tests:

```bash
./mvnw test -Puntagged-tests
```

### 3.2 Live storage — Ceph RGW

```bash
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-run-live-tests.sh
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-verify-storage.sh
```

The first runs the `ceph-integration` JVM suites in the workstation JVM
against the live cluster. The second runs the packaged storage verifier
image and writes machine-readable evidence.

`verify-storage` fails deliberately when the `stratus/storage-verifier:dev`
image is older than the verifier sources — it never builds anything. Rebuild
when it does:

```bash
./mvnw -pl :stratus-storage-verifier -am package
docker build -f verification/storage/image/Dockerfile -t stratus/storage-verifier:dev .
```

### 3.3 Live catalog — Iceberg tables and the Polaris catalog

```bash
bash platform/polaris/compose-service/scripts/verify/polaris-compose-run-catalog-tests.sh
```

Runs the `catalog-integration` suite: zone namespaces, table create/write/
read/drop in every data zone, schema evolution, snapshot expiry, partition
layout, attribute round trip through the catalog, the
`platform.quality_check_results` schema and write path, and a forged-
credential negative. Requires Ceph, OpenBao, and Polaris up and the catalog
bootstrapped.

`STRATUS_LOG_LEVEL` defaults to `DEBUG` here so transcripts prove both
operational log levels.

### 3.4 Live secrets — OpenBao

```bash
bash platform/openbao/compose-service/scripts/verify/openbao-compose-run-secrets-tests.sh
```

### 3.5 Live batch compute — Spark

```bash
bash platform/spark/compose-cluster/scripts/verify/spark-compose-run-live-tests.sh
```

Submits real statements and real jobs to the standalone cluster. It proves
worker registration, the runtime image contents, catalog namespace resolution,
an Iceberg write and read landing in the governed zone, an S3A raw object
round trip, a forged principal secret being refused, and the whole batch
pipeline: a landing file ingested to bronze, quality rules recorded, a
blocking rule stopping promotion, deduplication into silver, materialisation
into gold, and maintenance on a live table. Requires Ceph, OpenBao, Polaris,
and Spark up, the `svc-spark` identity provisioned, and the job jar built.

Submitting one job by hand follows the same shape the suite uses:

```bash
docker compose --project-name stratus-spark-local exec -T spark-master   /opt/spark/bin/spark-submit   --master spark://spark-master.stratus.local:7077   --class dev.stratus.jobs.spark.IngestionJob   /opt/stratus/jobs/stratus-spark-jobs.jar   --sourceFile s3a://stratus-landing/customers/customers.csv   --targetTable stratus.bronze.customers --sourceSystem crm
```

Pass quality rules as `--checksBase64` rather than `--checks` when submitting
from Windows: the container runtime strips the double quotes out of a JSON
argument, and the job then fails on a document that was correct when you wrote
it.

### 3.6 Full Ceph harness sequence in one command

```bash
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-validate-cluster.sh
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-validate-cluster.sh --full
```

Runs bucket bootstrap, identity provisioning, bucket/storage/security/
dashboard/dataset verification, and the live JVM tests as one command with a
per-step transcript. `--full` wraps the sequence in startup and shutdown.

### 3.7 Running the live Maven profiles directly

Each wrapper passes extra arguments through to Maven, so a targeted or
combined run keeps the environment the wrapper supplies:

```bash
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-run-live-tests.sh clean verify -Pall-tests
bash platform/polaris/compose-service/scripts/verify/polaris-compose-run-catalog-tests.sh test -Pcatalog-integration-tests
```

Invoking `./mvnw ... -Pall-tests` bare will fail: the live profiles require
endpoints, scoped credentials, and a CA truststore that only the wrappers
supply, and no single wrapper supplies both the Ceph and the Polaris
environment. Run each layer through its own wrapper for a complete sweep.

---

## 4. Destructive and privileged operations

Run these deliberately; they are not part of a routine cycle.

| Command | Effect |
|---|---|
| `verify/ceph-compose-failure-drill.sh` | kills daemons to prove failover, then restores |
| `verify/ceph-compose-verify-harness.sh` | **destroys cluster volumes and rebuilds**; requires a fully stopped harness |
| `lifecycle/ceph-compose-reset.sh --force` | destroys and recreates local cluster state |
| `lifecycle/ceph-compose-rotate-secrets.sh` | rotates harness credentials and re-verifies |
| `lifecycle/ceph-compose-rotate-secrets.sh --repair-keys` | reconciles RGW with `.env` after an interrupted rotation; rotates nothing |
| `lifecycle/polaris-compose-reset.sh` | resets the Polaris service state |

### Recovering from an interrupted rotation

**Key propagation is not a defect.** RGW answers `403` on writes for an owner
whose keys were just rotated until the change propagates — roughly 90 seconds
after a normal rotation. An operator rotating twice in quick succession will
see a failure that is expected. Wait for propagation before the second
rotation rather than treating the first as failed.

**Churned keys settle far more slowly than the 300s gate allows.** The figure
above is the *normal* case. Measured on 2026-08-08, an identity whose keys were
rotated and then reconciled by `--repair-keys` took roughly **19 minutes** to
settle, exhausting two consecutive 300s `verify-dashboard` deadlines. The
deadline is deliberately not sized for this case, so a `verify-dashboard`
failure shortly after repair is the gate reporting an unsettled cluster.
Confirm rather than assume, with the discriminating test: through one dashboard
session, create a bucket on behalf of several owners. If only the churned
identity answers `500` (RGW `403`) while the others answer `201`, it is
propagation. It clears with no intervention — restarting daemons or the harness
only consumes the settle time.

**A rotation invalidates the Polaris truststore.** Rotation regenerates the
harness CA, and Polaris builds its truststore once at startup. A Polaris
started before the rotation cannot validate the new RGW certificate afterwards,
and the whole catalog suite fails with a *server-side*
`PKIX path validation failed: Path does not chain with any of the trust
anchors`. The workstation JVM is not at fault — its truststore is rebuilt on
every test run. Restart Polaris and re-run the catalog bootstrap after any
Ceph secret rotation:

```bash
bash platform/polaris/compose-service/scripts/lifecycle/polaris-compose-shutdown.sh
bash platform/polaris/compose-service/scripts/lifecycle/polaris-compose-startup.sh
bash platform/polaris/compose-service/scripts/verify/polaris-compose-bootstrap-catalog.sh
```

**A killed rotation no longer needs manual repair.** The lock records its
owning process. If a rotation is killed outright, the next run confirms that
process is gone, reclaims the lock, removes the stage directories the dead run
left behind, and proceeds. A lock held by a *live* rotation still fails closed.

**Key drift has a repair command.** A rolled-back rotation can leave `.env`
disagreeing with RGW. Preflight detects this and refuses to run, naming the
fix:

```bash
bash platform/ceph/compose-cluster/scripts/lifecycle/ceph-compose-rotate-secrets.sh --repair-keys
```

Repair attaches each `.env` key pair to its identity and removes every other
key on those two identities — a key left behind by a failed rotation is an
un-revoked credential, so removing it is the point. It rotates nothing and
exits before any rotation state is generated. Re-run `--preflight` afterwards
to confirm the cluster is ready.

The original observations are recorded in
[harness_verification_handover-5-Aug-2026.md](harness_verification_handover-5-Aug-2026.md).

---

## 5. Shut down

Consumers first, providers last:

```bash
bash platform/spark/compose-cluster/scripts/lifecycle/spark-compose-shutdown.sh
bash platform/polaris/compose-service/scripts/lifecycle/polaris-compose-shutdown.sh
bash platform/openbao/compose-service/scripts/lifecycle/openbao-compose-shutdown.sh
bash platform/ceph/compose-cluster/scripts/lifecycle/ceph-compose-shutdown.sh
```

Shutdown works with a missing or unusable `.env` by design; this is enforced
by `HarnessShutdownBehaviorTest`. Ceph data volumes are preserved. Polaris
catalog state and OpenBao secrets are discarded, as described in §2.

Confirm nothing is left running:

```bash
docker ps --format '{{.Names}}' | grep stratus
```

---

## 6. Where output goes

| Output | Location |
|---|---|
| Maven build logs | repository-root `logs/` (gitignored) |
| Ceph harness transcripts | `platform/ceph/compose-cluster/logs/` |
| Live Ceph JVM test transcripts | `platform/ceph/tests/logs/` |
| Catalog conformance transcripts | `platform/polaris/compose-service/logs/` |
| Spark conformance and pipeline transcripts | `platform/spark/compose-cluster/logs/` |
| Quality check results (a governed table, not a file) | `stratus.platform.quality_check_results`, queryable by `run_id` |
| Storage verification evidence (JSON) | `platform/ceph/compose-cluster/evidence/` |
| Spark cluster verification evidence (JSON) | `platform/spark/compose-cluster/evidence/` |
| Per-test reports | each module's `target/surefire-reports/` |
| Coverage reports | each module's `target/site/jacoco/` |

Transcripts are timestamped and carry explicit `RUN startedAtUtc=` and
`RUN completedAtUtc= ... exitCode=` boundaries. Surefire reports are erased
by any `clean` build; the transcripts are the durable record.

Reading a catalog transcript: `INFO` records carry lifecycle outcomes with
stable identifiers (action, table, snapshot id, row count); `DEBUG` records
add diagnostic detail (storage locations, client property keys, full column
lists and table properties). Filter one channel with
`grep " DEBUG " <transcript>`.

---

## 7. Troubleshooting

| Symptom | Cause and resolution |
|---|---|
| `curl (52) empty reply` from a bootstrap script | the service is still starting. The Polaris bootstrap now waits for the API itself; if this appears, the service is not merely slow — check its container logs |
| `PKIX path ... does not chain with any of the trust anchors` from Polaris | the harness CA was rotated after Polaris started; restart Polaris and re-run the catalog bootstrap (§4) |
| `curl` closes the connection to `https://127.0.0.1:8181` with no HTTP status | Windows `curl` is Schannel-backed and refuses a privately issued certificate whose revocation status it cannot check. This is expected: the harness scripts run curl inside the network instead. Do not add `--ssl-no-revoke`. JVM clients are unaffected — they validate against the truststore the test runner builds |
| `POLARIS_ALLOW_HTTP=true in .env` on startup | an `.env` generated before TLS termination landed; delete it and re-run `polaris-compose-startup.sh` to regenerate from the template |
| Live JVM tests fail on TLS or unknown host | missing hosts-file entry — run `ceph-compose-configure-hostname.sh`; the truststore itself is supplied by the wrapper |
| `verify-storage` reports a stale image | rebuild the verifier image (§3.2) |
| `NoSuchTable: platform.quality_check_results` | Polaris was restarted; re-run the catalog bootstrap (§2) |
| Polaris cannot fetch `svc-polaris` | OpenBao was restarted; re-run `ceph-compose-provision-service-identities.sh` |
| Rotation preflight refuses to run | `.env` and RGW disagree after a failed rotation; run `ceph-compose-rotate-secrets.sh --repair-keys` (§4), then re-run `--preflight` |
| `Another secret rotation appears to be active` | a rotation really is running; a lock from a killed run is reclaimed automatically (§4) |
| Spark startup says the platform job jar is missing | build it with `./mvnw -pl :stratus-spark-jobs -am package -DskipTests`; the harness never builds it |
| A quality job fails with `--checks is not valid JSON` | the JSON lost its quotes in transit; submit the same document with `--checksBase64` |
| The promotion gate blocks with `checksExamined=0` | no results were recorded for that run id, so the quality job did not complete. This is deliberate: an unrecorded run is never treated as a pass |
| Spark startup says the runtime image does not exist | resolve the artifacts and build it once (§2); the harness never builds an image |
| Spark fails with `Failed to create any local dir` | the event-log or scratch volume predates the image that owns those directories; `spark-compose-shutdown.sh --volumes` then start again |
| Spark resolves the catalog but every write fails on PKIX | the executors are missing the truststore — check both `spark.driver.extraJavaOptions` and `spark.executor.extraJavaOptions` in the rendered `config/spark-defaults.conf` |
| `rclone` reports a Windows path for `--ca-cert` | Git Bash path conversion — prefix the command with `MSYS_NO_PATHCONV=1` |
| `Did not find winutils.exe` in a catalog transcript | benign Hadoop probe on Windows workstations; see [the catalog verifier README](../../verification/catalog/README.md) for the assessment |

---

## 8. Component references

- [platform/ceph/compose-cluster/README.md](../../platform/ceph/compose-cluster/README.md) — Ceph harness topology and configuration
- [platform/ceph/tests/README.md](../../platform/ceph/tests/README.md) — live Ceph conformance suites
- [platform/polaris/compose-service/README.md](../../platform/polaris/compose-service/README.md) — Polaris service harness
- [platform/openbao/README.md](../../platform/openbao/README.md) — secret distribution harness
- [verification/catalog/README.md](../../verification/catalog/README.md) — catalog conformance suite and its classpath notes
- [verification/secrets/README.md](../../verification/secrets/README.md) — secret-store conformance suite
