# Spark Compose cluster

The disposable developer Spark cluster: one standalone master and two workers,
attached to the Ceph harness network so they resolve both the object store and
the Polaris catalog by their published names.

This is the reduced topology `P1-3.1-D1` defines. It is not a production
deployment and cannot become one: there is no master recovery, no Spark
authentication, no transport encryption, and no restricted submission. Those
are named with their replacement tasks in the promotion manifest in
[spark_compute.md](../../../docs/implementation/spark_compute.md).

## Layout

| Path | Purpose |
|---|---|
| `compose.yaml` | Master and two workers, loopback-published, on the external Ceph network |
| `config/spark-defaults.conf.template` | Catalog and storage binding, rendered at startup from the providers' `connection.env` |
| `scripts/lifecycle/` | Start and stop |
| `scripts/tests/` | Authoritative and focused test entry points |
| `scripts/verify/` | Catalog principal bootstrap and non-test verification |
| `scripts/lib/` | Shared library and the image artifact resolver |
| `logs/`, `evidence/`, `certs/`, `.env` | Local disposable state; all ignored |

## Prerequisites

The Ceph, OpenBao, and Polaris harnesses must be running: this harness attaches
to a network it does not own and pulls its credentials from a secret store it
does not run (ADR-P1-003, ADR-P1-004). Startup fails with the exact command to
run when a provider is missing.

Two build steps the harness deliberately does not perform, because `P1-0.1`
keeps image and artifact assembly out of the harness scripts:

```bash
bash scripts/lib/spark-compose-resolve-artifacts.sh
docker build -f ../image/Dockerfile -t stratus/spark-runtime:dev ../image
./mvnw -pl :stratus-spark-jobs -am package -DskipTests
```

## Focused live-test fast path

The normal live runner remains the authoritative clean path. With no Maven
arguments it executes `verify -am`, builds the isolated AWS runtime before the
Spark tests consume its `runtime` classifier, and runs the complete live tier:

```bash
bash scripts/tests/spark-compose-run-live-tests.sh
```

For repeated work on one live class or method, prepare the exact upstream
snapshots once. Preparation is build-only and does not require the Stratus
containers to be running:

```bash
bash scripts/tests/spark-compose-prepare-focused-tests.sh
```

Then run a selected test without rebuilding the upstream reactor:

```bash
bash scripts/tests/spark-compose-run-focused-tests.sh \
  -Dtest=SparkClientConformanceTest

bash scripts/tests/spark-compose-run-focused-tests.sh \
  -Dtest=SparkPipelineVerificationTest#ingestionJobWritesBronzeTable
```

The focused runner accepts Maven `-D` properties only and requires an explicit
`-Dtest` selection. Test-suppression, tag-selection, no-test-success, lifecycle,
and local-repository overrides are refused. It compiles the current Spark test
sources through the module's normal `test` lifecycle, but resolves the AWS
runtime classifier and Spark jobs JAR from the prepared local snapshots.

Preparation records ignored state under `private/focused-tests/`. Before every
focused run, the harness checks the exact content of the root build, BOM,
build parent, AWS runtime inputs, and Spark job production sources—including
uncommitted and untracked files. It also checks the installed POMs and JARs and
both current target JARs. The focused invocation pins Maven to the same local
repository used during preparation and refuses a user-supplied repository
override. Missing or changed state is refused with the preparation command;
the runner never falls back silently to an older snapshot.

## Live-suite Spark context lifecycle

The Spark integration profile reuses one Surefire JVM. `SparkSuiteContext`
owns one two-core host-side Spark context for the complete JUnit launcher run,
while each live test class receives an isolated SQL session with its own
catalog configuration and principal. Closing a class client clears its session
cache; it does not stop the shared application. JUnit's root store closes the
context after the last selected class. The pipeline suite still launches one
separate packaged application because that test covers the real `spark-submit`
and job-JAR boundary.

This lifecycle is observable in the structured transcript: one
`client_connect_started`, one cluster application ID for all class sessions,
one `client_session_completed` per isolated session, session-scoped close
events, and one final context-scoped close event.

## Ports

Published on loopback only. The master web UI moves to 8090 on the host
because the Ceph dashboard proxy already publishes 8080; the in-container
ports remain the documented 8080, 8081, and 7077.

| Host | Container | Purpose |
|---|---|---|
| 7077 | 7077 | Master RPC, for submissions |
| 8090 | 8080 | Master web UI |
| 8091, 8092 | 8081 | Worker web UIs |

## Configuration is rendered, not written

`config/spark-defaults.conf` is generated at startup from
`spark-defaults.conf.template` by filling the providers' published endpoint,
catalog name, and network values. It is ignored by git, and the template
carries placeholders rather than endpoints: ADR-P1-003 forbids a consumer from
copying a provider's values into its own files, because the copy goes stale
silently.

The rendered file also holds the catalog principal secret, which is the second
reason it is never committed.

## Trust

Spark trusts two disposable lab CAs, held in one JVM truststore built at
startup: Ceph's, which signs the object-store certificate, and Polaris's,
which signs the catalog certificate. Neither harness signs for the other,
because a signing key never crosses a harness boundary.

## What the tests cover

`scripts/tests/spark-compose-run-live-tests.sh` runs the `spark-integration`
suites: cluster registration and capacity, runtime image contents and its
artifact lock, catalog namespace resolution, an Iceberg write and read landing
in the governed zone, an S3A raw object round trip, a forged principal secret
being refused, and the full batch pipeline through ingestion, quality,
promotion blocking, transform, materialisation, and maintenance.

Offline guardrails on this harness run in every build and need nothing
running: loopback-only ports, no credential in a tracked file, no provider
endpoint copied into the template, a pinned image, teardown that survives a
missing `.env`, and no provider started transitively.

## Test observability

The runner defaults `STRATUS_LOG_LEVEL` to `DEBUG` and writes one complete,
timestamped transcript under `logs/`. Stratus code logs through SLF4J 2.x with
Spark's Log4j2 provider. The level and suite run ID are propagated into the
host test JVM, Compose services, submitted drivers, and Spark executors so
records from every execution path use the same controls and correlation root.

Every record can carry three distinct correlation fields: `suiteRunId` for the
complete test invocation, `jobRunId` for one packaged platform job, and
`operationId` for a timed SQL, command, catalog, or job-phase operation. Nested
operations restore the caller's MDC values when they finish.

INFO includes test/class/suite boundaries, client connection and shutdown,
every SQL completion, catalog refreshes, platform-job phases, external command
outcomes, and p50/p95 timing summaries. DEBUG adds sanitized SQL and command
detail plus job diagnostic records and bounded, single-line failure stack
traces. SQL values, exception text, stack traces, assertion diagnostics, and
command output are bounded; credential-bearing options, quoted JSON/SQL secret
values, configuration keys, bearer tokens, and URI user information are
redacted before they reach a record.
