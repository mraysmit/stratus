# Stratus handover — Spark live-suite profiling session, 2026-08-14

## 1. One-paragraph summary

The Spark/Ceph/Iceberg/Polaris remediation completed earlier on 2026-08-14 was
committed and pushed before this session continued. This session then profiled
the remaining 12-minute live Spark suite because its runtime was still
disproportionate to the tiny fixture data. The profiling found no worker-loss,
core-starvation, 200-partition regression, or steady-state endpoint failure.
The dominant cost is orchestration rather than computation: the green suite
runs 252 visible Spark jobs, starts five host-side Spark contexts plus one
packaged `spark-submit` application, and repeatedly pays Iceberg planning,
Polaris metadata, Ceph object, commit, fixture, cleanup, JVM, and Maven reactor
costs. In the last full run, visible scheduler job time totalled only 136.14
seconds of the 730-second build. Focused runs reproduced the problem and
identified an avoidable two-minute rebuild penalty during iterative tests, a
101-second four-row external submission, shared quality-result state that
grows between runs, and substantial host-to-host/run-to-run variability. No
product or test code was changed. This dated handover is the session's only new
tracked file.

---

## 2. Where the work is

| | |
|---|---|
| Branch | `master` |
| `HEAD` | `fa0513fe4b4a06695b1e21f2b62d8fdf21b7e94f` — Spark live semantics and integration-test overhead remediation |
| Remote before this document | `master` and `origin/master` in sync (`0` ahead, `0` behind) |
| Working tree before this document | clean |
| New tracked work | this handover only |
| Full offline baseline | 201 tests passed |
| Full live baseline | 46/46 tests passed in 12:10 (730 seconds) |
| Focused profiling tests | 5/5 client tests and 1/1 isolated ingestion test passed |
| Harness state | all Stratus containers stopped |
| Persistent state | Ceph, Polaris, and Spark volumes preserved |
| Disposable state | OpenBao dev-mode secrets discarded by design |

The remediation commit is already pushed. Do not recreate or recommit those
changes from this handover. The next work starts with the performance changes
listed in §9.

---

## 3. Starting point

The last completed full live run was:

`platform/spark/compose-cluster/logs/spark-conformance-tests-20260814T065454Z.log`

It completed successfully with 46 tests in 12:10. The same revision also
passed the complete offline reactor with 201 tests. The remediation already in
`HEAD` had:

- reduced the developer cluster's default parallelism and shuffle partitions
  from Spark's 200-partition default to 8;
- made SQL timestamps consistently UTC;
- qualified Iceberg maintenance procedure table names through the `stratus`
  catalog;
- corrected the live-test opt-in across Git Bash and Windows processes;
- fixed the incremental replay mode;
- removed the duplicate SLF4J provider;
- replaced the per-JAR classpath scan with one Python scan;
- improved executor discovery and warmed-query measurement; and
- made the full offline and live gates green.

Those changes removed the clearest defects but did not make a 12-minute suite
reasonable for its data volume. This session addressed that remaining question
through measurement rather than speculative code changes.

---

## 4. Profiling method

### 4.1 Existing evidence

The completed live transcript and Surefire XML reports were used to extract:

- per-class and per-method duration;
- test fixture/fork time (suite duration less summed test-method duration);
- Spark context starts, application IDs, connection, uptime, and shutdown;
- every visible `Starting job` / `Job ... finished` duration;
- stage counts;
- external command and `spark-submit` durations; and
- Iceberg scan-planning and commit metrics.

This produced a complete accounting without rerunning the full 12-minute
suite.

### 4.2 Focused live experiments

The full developer platform was started in its documented dependency order:
Ceph, OpenBao, Ceph buckets and identities, Polaris and its catalog, then
Spark and its principal. Git for Windows Bash was used explicitly so Docker
Desktop and the Windows Docker context were used rather than an unrelated WSL
Docker context.

The focused experiments were:

1. `SparkClientConformanceTest` through the normal `verify -am` wrapper.
2. The same class through direct Surefire after installing the already-built
   reactor artifacts into the local Maven repository.
3. Only
   `SparkPipelineVerificationTest#ingestionJobWritesBronzeTable`, preserving
   its real four-row packaged `spark-submit` path.
4. Ten unauthenticated TLS probes each against Polaris and Ceph to distinguish
   steady endpoint latency from authenticated catalog/storage work.
5. Spark master JSON inspection during concurrent applications.
6. Docker resource snapshots and Ceph health checks.
7. Executor `stderr` inspection from both workers for the external ingestion
   application.

After profiling, Spark, Polaris, OpenBao, and Ceph were stopped in reverse
dependency order. No profiling process or Stratus container remains running.

### 4.3 Invalid sample that must not be used

The first focused wrapper invocation exceeded the original five-minute command
allowance while Maven and Surefire were still alive. The output pipeline was
severed, leaving those two Java processes blocked behind an undrained stream.
Only the exact Maven and Surefire processes started by that invocation were
terminated. That Spark application is visible in the master history but is not
used in any timing conclusion below.

All numbers below come from completed, green runs with intact transcripts.

---

## 5. What the 12:10 run actually did

### 5.1 Per-class wall time

| Class | Tests | Time | Share of total |
|---|---:|---:|---:|
| `SparkPipelineVerificationTest` | 12 | 235.20 s | 32.2% |
| `SparkIncrementalLoadVerificationTest` | 17 | 208.20 s | 28.5% |
| `SparkCatalogBindingConformanceTest` | 4 | 76.38 s | 10.5% |
| `SparkClientConformanceTest` | 5 | 72.27 s | 9.9% |
| `SparkPrincipalSeparationTest` | 3 | 26.93 s | 3.7% |
| `SparkClusterConformanceTest` | 5 | 13.20 s | 1.8% |
| Maven/reactor/fork time outside the class reports | — | about 97.82 s | 13.4% |

Pipeline and incremental verification account for 443.4 seconds, or 60.7% of
the complete build. They also account for 231 of the 252 visible Spark jobs.

### 5.2 Job execution versus everything around it

| Measurement | Result |
|---|---:|
| Visible Spark jobs started and finished | 252 |
| Sum of scheduler-reported job duration | 136.14 s |
| Full Maven duration | 730 s |
| Visible job duration as a share of the full run | 18.6% |
| Visible job duration as a share of reported class time | about 21.5% |

The visible job sum does not include the packaged ingestion driver's internal
jobs because the harness deliberately relays selected job records rather than
the subprocess's complete Spark transcript. Its enclosing 78.57-second command
is included in the pipeline class time.

The important conclusion is unchanged: most elapsed time is outside executor
computation. Tiny data does not make catalog authentication, Iceberg planning,
metadata reads, commits, object-store round trips, driver/executor startup, or
process boundaries disappear.

### 5.3 Longest individual tests in the baseline

| Test | Time |
|---|---:|
| pipeline packaged ingestion | 96.72 s |
| client governed write/read | 36.74 s |
| catalog raw S3A write/read | 32.79 s |
| catalog governed Iceberg write/read | 31.14 s |
| defective incremental batch correction | 29.02 s |
| pipeline quality job | 22.96 s |
| pipeline maintenance job | 21.87 s |
| first incremental batch | 19.15 s |
| incremental snapshot expiry | 15.60 s |

The suite is therefore not spending 12 minutes repeatedly evaluating
`SELECT 1`. It is running many small end-to-end transactions. The problem is
that the number and lifecycle of those transactions are excessive for a
developer feedback loop.

---

## 6. Focused results from this session

### 6.1 Client class without repeated reactor compilation

The valid direct-Surefire run is:

`platform/spark/compose-cluster/logs/spark-conformance-tests-20260814T084844Z.log`

| Measurement | Last full run | Focused run |
|---|---:|---:|
| JUnit class time | 72.27 s | 134.66 s |
| Spark context uptime | 67.88 s | 121.80 s |
| Governed write/read method | 36.74 s | 71.53 s |
| Warm tiny-query p50 | 632 ms | 1,258 ms |
| Warm tiny-query maximum | 858 ms | 1,825 ms |
| Visible Spark jobs | 13 | 13 |
| Sum of visible job duration | 16.94 s | 31.79 s |
| Testcase duration | 46.84 s | 85.97 s |
| Fixture/fork duration | 25.44 s | 48.68 s |

The current workstation run was about 1.9 times slower than the earlier run,
including range-based warm queries that do not read Ceph. This is evidence of
material host/Docker variability in addition to catalog and storage cost.
Nevertheless, executor work was still only 31.79 seconds of a 134.66-second
class.

### 6.2 Normal wrapper versus prepared-artifact execution

The normal focused wrapper runs `verify -am`. It rebuilds and shades the AWS
runtime, rewrites a classified dependency, and causes Maven to recompile all 18
Spark test sources because the dependency changed.

| Path | Maven/class result |
|---|---:|
| Normal focused `verify -am` | 265 s Maven total; 128.53 s class |
| Direct Surefire with prepared local artifacts | 152 s Maven total; 134.66 s class |
| Measured repeated-build difference | about 113–119 s |

Installing the already-built upstream snapshots once took 62 seconds. The
direct single-module attempt before that installation failed correctly because
`stratus-spark-jobs:1.0-SNAPSHOT` was reactor-only and absent from the local
repository. A future fast path must prepare matching artifacts explicitly; it
must not silently test stale installed snapshots.

This repeated-build penalty primarily hurts iterative focused tests. The full
suite pays its reactor preparation once, where the earlier unreported overhead
was about 98 seconds across all classes.

### 6.3 Isolated packaged ingestion

The completed focused ingestion run is:

`platform/spark/compose-cluster/logs/spark-conformance-tests-20260814T085229Z.log`

| Measurement | Result |
|---|---:|
| Tests | 1/1 passed |
| JUnit suite time | 220.10 s |
| Test-method time | 159.15 s |
| Fixture and cleanup | 60.94 s |
| `docker exec` plus `spark-submit` command | 101.11 s |
| Spark master application lifetime | 76.03 s |
| Test client's visible job time | 3.91 s |
| First governed scan planning duration after ingestion | 6.48 s |

The test client's one-core application and the ingestion job's two-core
application ran concurrently, using three of the four available cores. The
external application did not sit waiting for resources. Its two executors
started, connected, read the 200-byte CSV, committed one output file, and shut
down successfully.

Approximately 25 seconds surrounded the registered application in Docker and
`spark-submit` process startup/shutdown. The first executor task did not finish
until roughly 39 seconds after application registration. Input processing,
Iceberg planning and writing, commit, and shutdown occupied the rest of the
76-second application lifetime. This is genuine cold application conformance,
not a useful proxy for steady four-row query performance.

### 6.4 Provider and resource observations

- Ten warm unauthenticated Polaris probes completed in roughly 66–199 ms.
- Ten warm Ceph endpoint probes completed in roughly 51–203 ms.
- Ceph reported `HEALTH_OK`, 3/3 OSDs up, and all 225 PGs active and clean.
- Spark reported both workers alive with four total cores.
- Both concurrent applications received their requested cores.
- Docker had 12 CPUs and about 16 GiB available to its VM.
- Shortly after startup, Ceph MON/OSD activity was CPU-heavy even while health
  was green. This helps explain why cold and recently-started runs vary.

The provider endpoint probes rule out a permanently slow TLS listener. They do
not rule out the authenticated multi-request work of token exchange, catalog
load, Iceberg metadata access, or Ceph object operations.

---

## 7. Root causes and what was ruled out

### 7.1 Confirmed causes

1. **Too many Spark actions.** Each `StratusSparkClient.sql` call ends in
   `collectAsList`. Many logically related assertions therefore cause separate
   planning and execution cycles. Incremental verification alone starts 182
   visible jobs.
2. **Repeated cold driver lifecycles.** Five integration classes create and
   stop independent host-side contexts. The pipeline also starts one real
   packaged driver through `spark-submit`.
3. **Metadata and transaction dominance.** Scheduler time is a minority of
   class time. Polaris authentication/catalog access, Iceberg scan planning,
   commits, Ceph objects, and cleanup dominate small-data tests.
4. **Focused-test build amplification.** `verify -am` shades an upstream
   runtime and recompiles every Spark test source on each focused invocation.
5. **Shared quality-results growth.** Both incremental and pipeline tests use
   the permanent `stratus.platform.quality_check_results` table. The earlier
   full transcript showed 19 accumulated data files/manifests. Unique run IDs
   prevent correctness collisions but do not prevent metadata growth or
   run-to-run performance drift.
6. **Host variability.** The same client class and same number of jobs took
   roughly 1.9 times as long in the focused session as in the earlier full run.

### 7.2 Not the cause

- The old 200-shuffle-partition explosion did not return.
- Both Spark workers were registered and healthy.
- Executors were placed on both workers.
- The external ingestion job was not starved by the test client.
- Standalone FIFO application scheduling did not serialize the applications.
- Warm Polaris and Ceph listeners were not taking seconds per request.
- The tests passed on the configured Java 26 workstation; no Java 17/21
  enforcement was added or is recommended here.
- No evidence points to a Ceph data-volume bottleneck: the governed objects are
  only kilobytes. The cost is request, metadata, and lifecycle overhead.

### 7.3 Upstream behavior used to validate the interpretation

Apache Spark documents that only one `SparkContext` should be active per JVM,
and that the active context must be stopped before another is created. That
does not require one permanent JVM per sequential test class, and it supports
sharing one context with isolated SQL sessions where the context-level
configuration can be common:

- <https://spark.apache.org/docs/latest/api/java/org/apache/spark/SparkContext.html>

Spark's standalone documentation confirms the observed client-mode behavior,
application core caps, FIFO scheduling between applications, and automatic JAR
distribution:

- <https://spark.apache.org/docs/latest/spark-standalone.html>

---

## 8. Evidence inventory

All profiling transcripts are under the Spark harness's git-ignored `logs/`
directory.

| File | Purpose |
|---|---|
| `spark-conformance-tests-20260814T065454Z.log` | last full green 46-test baseline |
| `spark-conformance-tests-20260814T084031Z.log` | valid normal-wrapper client profile |
| `spark-conformance-tests-20260814T084844Z.log` | valid prepared-artifact client profile |
| `spark-conformance-tests-20260814T085229Z.log` | valid isolated packaged-ingestion profile |
| `profile-client-console.log` | console mirror for normal wrapper profile |
| `profile-client-isolated-surefire-console.log` | console mirror for direct Surefire profile |
| `profile-pipeline-ingestion-console.log` | console mirror for isolated ingestion |
| `profile-install-reactor.log` | one-time local artifact installation timing |

Two deliberately retained failure transcripts document why direct Surefire
requires prepared artifacts:

- `profile-client-surefire-console.log`
- `profile-client-direct-surefire-console.log`

They failed in dependency resolution before running tests and are not product
failures.

---

## 9. Recommended continuation order

### 9.1 Add phase-level timing before restructuring

Add structured durations for:

- Maven preparation versus JUnit execution;
- Spark context construction and master connection;
- first executor registration and first completed job;
- catalog initialization;
- Iceberg scan planning and commit;
- external submission process versus registered application lifetime; and
- fixture setup and cleanup.

The existing class, command, and warm-query timing is useful but leaves too
much inferred from log boundaries. Keep thresholds advisory initially; use
relative regression thresholds once several stable samples exist.

### 9.2 Add a safe prepared-artifact fast path

Keep the current `verify -am` path as the authoritative clean/full path. Add an
explicit developer fast path that:

1. prepares or installs the exact current runtime and job snapshots once;
2. proves they match the working source revision or current target outputs;
3. invokes the selected Spark tests directly through Surefire; and
4. refuses stale or missing artifacts rather than falling back silently.

Measured benefit for a focused class on this workstation: about 114 seconds
per repeat after preparation.

### 9.3 Share the host Spark context

Create a suite-scoped test fixture or JUnit extension that owns one two-core
host-side `SparkContext`. Give each class an isolated `SparkSession` and
class-specific catalog identity/configuration. The principal-separation test
already proves the `newSession` pattern in one context.

Keep one separate packaged `spark-submit` test because it validates the real
job JAR and deployment path. Do not pretend that an in-process call proves
packaged submission.

Target: reduce five host-side context lifecycles to one, while retaining the
one deliberate external application.

### 9.4 Reduce action count without weakening contracts

- Combine related scalar assertions into one SQL aggregation returning several
  columns.
- Reuse results already produced by the job under test.
- Read Iceberg metadata directly where the contract is metadata, not executor
  computation.
- Avoid repeated `SHOW`, `count`, and row-read actions that assert the same
  state in adjacent methods.
- Preserve end-to-end write, replay, schema-evolution, authorization,
  maintenance, and failure contracts.

The first target should be fewer than 120 visible jobs for the complete deep
suite, down from 252. Measure again before setting a stricter target.

### 9.5 Isolate quality-result state

`QualityCheckJob` currently fixes its result table at
`stratus.platform.quality_check_results`. Choose one of:

- make the results table injectable and give the live suite a unique table that
  it drops with purge;
- clean each run's rows and execute bounded snapshot expiry/compaction; or
- reset the developer table as an explicit suite fixture while retaining one
  conformance check against the canonical production name.

The selected design must keep the production table contract covered while
preventing test history from changing later run times.

### 9.6 Split feedback tiers

Recommended tiers:

| Tier | Contents | When |
|---|---|---|
| Offline | unit, configuration, harness, compatibility | every change |
| Fast live | cluster, identity, namespace, one governed write/read, warm latency | normal live feedback |
| Deep semantic | incremental replay, schema evolution, quality, promotion, maintenance | scheduled and release gate |
| Cold packaged submission | one real `spark-submit` using the production job JAR | scheduled and release gate |

Splitting tiers improves feedback time; it is not a substitute for reducing
the deep suite's action count and state growth.

---

## 10. Resuming the environment

OpenBao dev-mode state was discarded, and Polaris's developer metastore is
bootstrap-oriented. Resume in the documented order rather than starting only
Spark:

```bash
bash platform/ceph/compose-cluster/scripts/lifecycle/ceph-compose-startup.sh
bash platform/openbao/compose-service/scripts/lifecycle/openbao-compose-startup.sh
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-bootstrap-buckets.sh
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-provision-service-identities.sh
bash platform/polaris/compose-service/scripts/lifecycle/polaris-compose-startup.sh
bash platform/polaris/compose-service/scripts/verify/polaris-compose-bootstrap-catalog.sh
bash platform/spark/compose-cluster/scripts/lifecycle/spark-compose-startup.sh
bash platform/spark/compose-cluster/scripts/verify/spark-compose-bootstrap-principal.sh
```

On Windows, invoke these with Git for Windows Bash. Do not use an unqualified
`bash` if it resolves to WSL and cannot see the Docker Desktop networks.

The jobs JAR, Spark runtime image, and persistent volumes were preserved. The
local Maven repository also now contains the current snapshot artifacts used
for the direct-Surefire profiling experiment; a future fast path must still
verify freshness rather than trusting their presence.

---

## 11. Handover acceptance state

- The pushed Spark remediation remains green: 201 offline tests and 46 live
  tests passed before profiling.
- Today's two valid focused profiles also passed.
- The 12-minute runtime is explained well enough to begin implementation.
- No performance fix was implemented in this profiling session.
- No Stratus container or Maven/Surefire process remains running.
- Provider and Spark persistent volumes are preserved.
- This handover is the only new tracked file created in the session.

