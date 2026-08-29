# Stratus performance review and remediation register - 2026-08-28

**Status:** Active.

**Last reviewed source revision:** base revision `f4794a9` plus the explicitly recorded working-tree
candidate validated on 2026-08-29. Immutable post-commit acceptance remains pending.

**Scope:** Developer build, Spark, Airflow, Iceberg REST catalog and live acceptance paths. The
figures in this document describe the reference development workstation and small deterministic
fixtures. They are diagnostic evidence, not production capacity claims.

## 1. Purpose and authority

This is the current register for performance problems, measurements, completed remediations and
remaining work. It consolidates the active runtime reassessment and the useful evidence retained in
the archived Spark profiling handover. Dated acceptance records remain authoritative for the exact
result of a named run; this register owns the current interpretation and priority.

Use the following status terms:

- **Resolved:** the cause was changed and the relevant focused proof passed.
- **Improved:** measurements show a material reduction, but avoidable cost remains.
- **Open:** current code or evidence still shows the concern.
- **Measure:** the concern is credible, but its isolated cost has not been established.
- **Historical:** useful baseline evidence that no longer describes the current implementation.

Performance changes must preserve the governance boundary: Airflow makes promotion visible as its
own task, the writer rechecks the same evidence immediately before writing, blocked runs fail
closed, and tests still prove exact side effects and cleanup.

## 2. Current position

The original diagnosis remains valid: most of the long developer-test runtime is orchestration and
application lifecycle cost rather than computation over the fixture rows. Spark driver startup,
executor allocation, catalog initialization, object-store access, Airflow startup and repeated
cleanup dominate the measurements.

The most important completed changes are:

- the Airflow image no longer carries a second complete Spark runtime through full PySpark;
- dependency resolution now rejects stale or additional wheelhouse artifacts and safely promotes a
  verified cache;
- the JVM Spark live tests share a suite-scoped Spark context and use prepared artifacts safely;
- promotion is an explicit Airflow task backed by Iceberg's Java reader rather than a Spark
  application;
- the blocked bronze-to-silver path no longer repeats ingestion, quality or Spark verification;
- the API proof no longer creates pipeline fixtures or submits a Spark application; and
- routine logs and Spark submission verbosity have been reduced in the current source.

The canonical suite now starts Ceph, OpenBao, Polaris, Spark and Airflow once, then passes that
suite-scoped environment to every compatible focused harness. The separate two-cycle lifecycle
qualification is no longer nested inside the canonical live run because service restart is the
behavior that test exists to prove. The complete 2026-08-29 working-tree run proved that shared
lifecycle and reduced elapsed time from 2,975.509 seconds to 1,961.505 seconds (a directional
34.1% reduction). A committed live rerun is still required for immutable acceptance.
The focused silver-to-gold harness no longer reconstructs landing, bronze and silver state for
both outcomes. It prepares the accepted and naturally invalid silver boundaries in one Spark
application, schedules both scenarios through Airflow's public API, verifies them with direct
Iceberg readers, and cleans them in one Spark application. The remaining pipeline-suite cost is
now the lack of shared accepted-path state across the three separately runnable focused harnesses.

## 3. Measurement baseline

Measurements from different runs are not compared as query benchmarks unless their setup and
contention conditions match.

| Date | Evidence | Result | Interpretation |
|---|---|---:|---|
| 2026-08-14 | Original Spark live suite | 730 seconds | 252 visible Spark jobs, five host Spark contexts and one packaged submission; only 136.14 seconds was visible scheduler job time, so orchestration dominated |
| 2026-08-22 | Airflow-to-Spark integration | 110.629 seconds | One real packaged job and complete Ceph/Polaris/Iceberg proof; Spark submission phase was 25.668 seconds |
| 2026-08-24 | Canonical Airflow V1 acceptance | 2,975.509 seconds | About 49.6 minutes for the complete release/gate proof; unsuitable as an inner development loop |
| 2026-08-24 | API V1 full-stack proof | 420.772 seconds | Repeated data fixtures, Spark jobs and side-effect proofs obscured the API contract cost |
| 2026-08-28 | Bronze-to-silver V2 focused proof | 410.717 seconds | Passed; 125.430 seconds was Airflow startup under concurrent Maven load, 101.985 seconds the accepted DAG, 50.758 seconds verification/cleanup and 22.243 seconds the blocked DAG |
| 2026-08-28 | API V2 proof | 212.751 seconds | Passed; API behavior took 33.972 seconds, while provider and Airflow setup consumed most of the total |
| 2026-08-29 | Shared-lifecycle canonical working-tree proof | 1,961.505 seconds | Passed both 312-test reactors, zero-Critical image scan, retry/deadline, Spark submission, three data flows, maintenance and public-API cases; one Airflow start and zero remaining Stratus containers. Base revision `f4794a9`; candidate changes were not yet committed. |
| 2026-08-29 | Silver-to-gold boundary-fixture working-tree proof | 233.770 seconds | Run `airflow-silver-to-gold-20260829T143008Z` passed one accepted and one naturally invalid scenario through the real scheduler/API with exact task states, direct Iceberg assertions and exact cleanup. The Spark master proved exactly six new applications, from baseline count 6 to final count 12. |
| 2026-08-29 | Landing real-scheduler working-tree proof | 44.393 seconds Airflow duration | Run `airflow-pipeline-20260829T144452Z` passed exact sensor, ingestion and quality states. Do not use its 309.206-second harness wall time as a baseline: an intentionally interrupted pre-fix run remained in preserved metadata and held `max_active_runs=1` until explicitly failed. |

The API V2 change reduced its full-suite elapsed time by 208.021 seconds, or 49.4%, relative to the
V1 record. This comparison shows the benefit of narrowing the scenario, but it is not a controlled
microbenchmark because the two runs occurred under different repository states and host conditions.

The V2 bronze-to-silver evidence also exposes the difference between data work and framework cost.
The three-row ingestion spent 15.089 seconds planning the batch and 15.734 seconds writing it. The
writer-side promotion evidence read took 16.592 seconds, while several subsequent transform phases
took milliseconds. The tiny dataset therefore does not explain the complete runtime.

## 4. Issue and remediation register

| ID | Status | Problem and evidence | Remediation and result | Remaining decision or acceptance criterion |
|---|---|---|---|---|
| PERF-001 | Improved | The original Spark live suite took 730 seconds and exposed 252 Spark jobs for tiny fixtures. JVM startup, Maven work, Iceberg/Ceph operations and cleanup outweighed scheduler work. | Added phase timings; introduced a safe prepared-artifact runner; shared one host Spark context; isolated quality-result state; reduced Spark actions by 58 jobs (25.1%); split routine and release feedback. Disabling AQE was tested and reverted because it did not justify a global behavior change. | Retain as a historical baseline. Profile again only when the Spark suite changes materially or breaches its current tier budget. |
| PERF-002 | Resolved | The Java 21 Airflow rebuild path handled a 455.5 MB PySpark source archive and a 546.3 MB Spark archive, exceeding 1 GB before other wheels. Resolution took 663.689 seconds and Docker context transfer was stopped after about 305 seconds. | The image now takes `/opt/spark` and Java 21 from a digest-pinned OCI stage and installs the 1.6 MB `pyspark-client` instead of full PySpark. The corrected wheelhouse was 9,931,122 bytes; a later context transferred in 1.2 seconds, the checked-in build completed in 7.632 seconds and smoke passed in 17.951 seconds. | Reopen if an in-process Python Spark API is introduced; require a focused compatibility proof before adding full PySpark. |
| PERF-003 | Resolved | A stale, internally consistent wheelhouse retained the superseded 455.5 MB PySpark archive and produced a 458.59 MB build context. | Resolution now uses temporary staging, locked hashes, exact selected-file comparison, rollback and interrupted-promotion recovery. The bad cache failed in 6.7 seconds; refreshed resolution took 33.2 seconds and all 13 artifact tests passed. | Keep the exact-set and recovery tests in the offline gate. |
| PERF-004 | Improved | The first promotion-task design started a Spark driver and executors to read a handful of quality rows. | Commit `7dba05e` replaced the Airflow-side gate with a direct Iceberg REST catalog and generic Java reader. Accepted and blocked decisions were observed in seconds and blocked downstream Spark tasks did not start. | The direct gate still opens a JVM and catalog client for each decision. Measure connection/catalog setup separately before considering further design changes. |
| PERF-005 | Improved | The V1 public-API suite mixed API state validation with ingestion, quality, maintenance, Spark verification and cleanup, taking 420.772 seconds. | V2 uses a one-task positive probe and a real missing-evidence denial. It creates no data fixture and submits no Spark application. The run fell to 212.751 seconds; the Java API phase was 33.972 seconds. | Remove Spark cluster startup and principal bootstrap from this suite if a clean run proves the direct catalog check and mounted runtime inputs do not require a running Spark cluster. Preserve Ceph, Polaris and trust validation. |
| PERF-006 | Improved | The V1 canonical acceptance script caused about 11 Airflow starts: two lifecycle cycles plus registry, retry, deadline, Spark submission, three pipeline suites, maintenance and API proofs. | The runner exports an explicit suite-ownership contract, combines the Spark, retry and Deadline Alert inputs in one Compose deployment, starts each provider and Airflow once, and makes every nested harness verify and reuse that deployment. The complete 2026-08-29 working-tree run passed in 1,961.505 seconds versus the 2,975.509-second V1 record, used one Airflow start, retained isolated run IDs, shut down the shared services successfully and reported zero remaining Stratus containers. | Repeat after commit and record the immutable revision before marking this resolved. Retain the separately runnable two-cycle lifecycle qualification and failure-path cleanup tests. |
| PERF-007 | Improved | The old silver-to-gold harness rebuilt landing, bronze and silver state for both outcomes and used about 15 Spark applications: eight accepted and seven blocked, including separate Spark verifiers. Its V1 run took 716.033 seconds. | The focused harness now uses one combined boundary-fixture application, three accepted DAG applications, one blocked quality application, direct Iceberg verification and one exact-cleanup application. The naturally duplicated silver data causes the DAG's own uniqueness check to fail; no external failing evidence is injected. Live run `airflow-silver-to-gold-20260829T143008Z` passed in 233.770 seconds and the Spark master measured exactly six new applications, a directional 67.4% elapsed-time reduction from V1. | Reuse one accepted landing-to-gold state across the canonical landing, bronze and silver phases rather than rebuilding independent accepted datasets, then rerun the complete canonical suite. |
| PERF-008 | Open | The API V2 suite starts Ceph, OpenBao, Polaris and Spark, then Airflow, even though it submits no Spark application. In the 212.751-second run, Spark startup/principal took 9.713 seconds; all provider bootstrap before Airflow took 94.803 seconds. | Scenario work was narrowed, but provider lifecycle was not. | Establish the minimum dependency set experimentally. A candidate passes only if it retains real catalog no-write proof, trust/credential behavior, deterministic cleanup and the exact Airflow task-state contract. |
| PERF-009 | Open | The DAG operators do not currently set `execution_timeout`. A hung Spark submission or catalog call can therefore consume the harness's outer polling allowance and hide the failing phase. Normal promotion tasks also inherit two retries with five-minute delays unless a test overlay sets retries to zero. | Focused expected-failure suites set their retry variables to zero. API polling is bounded. | Add evidence-based task timeouts for Spark submissions and direct gates, test timeout behavior, and keep normal transient retries distinct from deterministic policy denial. A policy denial must not spend ten minutes retrying. |
| PERF-010 | Measure | `IngestionJob` enables CSV `inferSchema` when no schema is supplied. Spark documents that inference requires an extra pass over the data. Current tiny fixtures do not pass an explicit schema. | The implementation already prefers an explicit `--schema`; no current harness supplies one. | Add the governed source schema to deterministic fixtures and measure `plan_batch` before and after. Do not attribute the full 15.089-second phase to inference until isolated timing proves it. Define how production source schemas are owned and versioned before removing the fallback. |
| PERF-011 | Resolved | The direct and writer-side promotion readers filtered quality evidence by `run_id` only, so evidence for another dataset could contaminate a decision. | Both Iceberg readers now predicate on run ID, dataset namespace and dataset name. The evaluator defensively applies the same exact target filter. Real Iceberg expression-evaluation tests prove unrelated failures are ignored and unrelated passing evidence cannot authorize promotion. The 2026-08-29 accepted and blocked live scenarios passed with the scoped reader. | Retain the collision tests. Assess partitioning or clustering only with representative result-table volume; do not weaken fail-closed behavior for missing or mismatched evidence. |
| PERF-012 | Measure | The writer-side defence-in-depth evidence read took 16.592 seconds in the formal bronze-to-silver run. The independent Airflow gate and the writer both read the same small quality result set, by design. | The duplicate decision is required by the accepted governance boundary; only the Airflow-side read was moved out of Spark. | Break the 16.592 seconds into catalog open, planning, file read and evaluation. Optimize the result-table lookup and metadata layout without removing the writer recheck. |
| PERF-013 | Open | The warm offline reactor took 65.475 seconds against an under-60-second objective. The canonical suite repeats the reactor before and after all live work. | Prepared-artifact focused execution provides a faster safe development path. | Profile module and plugin time, retain the two full reactors only in the release gate, and keep ordinary change-specific feedback below the existing warm budget. |
| PERF-014 | Open | V2 live evidence was produced before its immutable source revision was recorded. It proves behavior of the tested working tree, while the dated V1 gate remains the last formally accepted revision. | Source revision `7dba05e` now contains the V2 implementation. | Run the superseding V2 acceptance against the committed revision and record its phase timings. Do not label pre-commit evidence as immutable acceptance. |
| PERF-015 | Open | The successful 2026-08-29 canonical run emitted misleading error-level noise: one successful `dag.test()` quality task reported a terminal-state supervisor `Task Instance not found` response after the task result was persisted, and Iceberg's asynchronous REST metrics reporter repeatedly logged 404s while newly created tables were becoming visible. The DAGs and independent data checks still passed. | The shared lifecycle makes these intermittent messages visible in one correlated evidence file rather than hiding them across restarts. They are not classified as accepted failure signals. | Reproduce each message with the narrowest focused harness, determine whether it is an Airflow SDK test-runner race or Stratus lifecycle error, and either remove the cause or downgrade only a proven-benign condition. Add a regression assertion so genuine terminal-state and catalog failures remain error-level and fail the appropriate test. |

## 5. Remediation order

1. Reuse one accepted landing-to-gold state across the canonical pipeline phases and enforce their
   Spark application budgets from observed scheduler events.
2. Add task-level timeouts and make deterministic policy denial non-retriable while preserving
   retries for transient infrastructure failures.
3. Remove Spark cluster startup from the API suite if the reduced-dependency proof retains every
   current assertion.
4. Retain the completed silver-to-gold boundary-fixture design and its direct Iceberg verifier as
   the focused regression; do not reintroduce upstream DAG construction into that harness.
5. Run and measure the implemented suite-scoped service lifecycle, including failure cleanup and
   standalone focused-harness regression. The working-tree proof passed on 2026-08-29; only the
   immutable post-commit rerun remains.
6. Supply an explicit governed schema to the CSV ingestion fixtures and measure the isolated
   change.
7. Profile the writer-side promotion read and the warm Maven reactor using their existing phase
   markers.
8. Run and record a committed V2 acceptance baseline, then set a realistic end-to-end gate budget
   from the new phase data.
9. Remove or precisely classify the false error-level signals recorded as PERF-015 so a green
   acceptance log does not train operators to ignore `ERROR` records.

This order protects correctness first, removes whole service and application boundaries next, and
leaves engine-level tuning until the larger fixed costs have been removed.

## 6. Performance budgets and gate rules

The accepted tier objectives remain:

| Tier | Objective | Current reading |
|---|---:|---|
| Warm offline repository guardrails | under 60 seconds | Post-live reactor was 63.792 seconds on 2026-08-29; still above objective |
| Image smoke after cached layers | under 2 minutes | Met; 17.951 seconds in the corrected 2026-08-25 proof |
| Two-cycle developer lifecycle | under 3 minutes | Met in V1; canonical phase was 136.432 seconds |
| One Spark submission with ready data plane | under 5 minutes | Met; canonical V1 phase was 119.896 seconds |
| API state contract after healthy Airflow | under 2 minutes | Met; 15.555-second Java API verification and 38.373-second canonical phase on 2026-08-29 |
| Canonical release/gate suite | No approved budget yet | Working-tree candidate passed in 1,961.505 seconds; immutable V1 baseline was 2,975.509 seconds |

A performance result is acceptable only when it:

- records total, environment startup, Airflow scheduling, Spark application startup, catalog,
  compute, verification and cleanup time separately where those phases exist;
- records the source revision, image identity, warm/cold state and material host contention;
- reports Spark application count for Spark-backed focused suites;
- preserves the functional assertions, fail-closed behavior, secret checks and exact cleanup;
- compares like-for-like runs or clearly labels the comparison as directional; and
- leaves slow diagnostics available without making verbose logging the routine default.

The canonical suite is release evidence. It must not become the only way to obtain confidence while
developing. Focused offline, parse, gate, API and single-pipeline tests remain the normal feedback
paths.

## 7. Evidence map

- Active runtime and validation decision:
  [`airflow_spark_runtime_reassessment_20260818.md`](airflow_spark_runtime_reassessment_20260818.md)
- Current Airflow design and acceptance boundary:
  [`airflow_orchestration.md`](airflow_orchestration.md)
- Historical Spark profiling baseline:
  [`spark_live_suite_profiling_handover_20260814.md`](../operations/archive/spark_live_suite_profiling_handover_20260814.md)
- Image, lifecycle and submission acceptance:
  [`development-acceptance-20260822.md`](../../platform/airflow/development-acceptance-20260822.md)
- Point-in-time V1 developer gate:
  [`developer-gate-20260824.md`](../../platform/airflow/developer-gate-20260824.md)
- Current developer commands and named live runs:
  [`platform/airflow/developer/README.md`](../../platform/airflow/developer/README.md)
- Current canonical suite:
  [`airflow-development-acceptance-suite.sh`](../../platform/airflow/developer/scripts/tests/airflow-development-acceptance-suite.sh)
- Current silver-to-gold harness:
  [`airflow-silver-to-gold-live-test.sh`](../../platform/airflow/developer/scripts/tests/airflow-silver-to-gold-live-test.sh)
- V2 focused evidence:
  `platform/airflow/developer/evidence/airflow-bronze-to-silver-20260828T052459Z.log`
- V2 API evidence:
  `platform/airflow/developer/evidence/airflow-api-orchestration-20260828T053205Z.log`

The evidence logs are machine-local diagnostic artifacts and may remain ignored. Stable results
needed for future decisions must be copied into a tracked acceptance record.

## 8. External technical basis

The remediation strategy uses the following current upstream guidance:

- [Spark 4.1 CSV options](https://spark.apache.org/docs/4.1.0/sql-data-sources-csv.html)
  confirms that `inferSchema` requires an extra pass over CSV data.
- [Spark standalone monitoring](https://spark.apache.org/docs/4.1.0/spark-standalone.html)
  describes the master/worker statistics, worker logs and shared event-log location used to
  distinguish application lifecycle from job execution.
- [Airflow 3.3 task timeouts](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/tasks.html#timeouts)
  defines `execution_timeout` as the maximum permitted runtime for each task execution.
- [Airflow 3.3 DAG testing](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/dags.html#testing-a-dag)
  documents simulated local DAG execution and real executor-backed testing, supporting separate
  feedback tiers rather than one all-purpose live suite.
- [Airflow 3.3 scheduler guidance](https://airflow.apache.org/docs/apache-airflow/stable/concepts/scheduler.html#fine-tuning-your-scheduler-performance)
  recommends measuring deployment resources, DAG structure and scheduler settings before changing
  tuning controls.
- [Iceberg performance](https://iceberg.apache.org/docs/latest/performance/)
  explains metadata and data-file pruning and supports a single-node Java reader for selective
  catalog checks.
- [Iceberg partitioning](https://iceberg.apache.org/docs/latest/partitioning/)
  explains hidden partitioning and partition evolution. Any quality-result layout change must be
  driven by representative lookup measurements rather than tiny fixtures alone.

## 9. Update rule

Every performance-related change must update the relevant issue row with the source revision,
before/after measurement, test command and outcome. Close an issue only when the focused proof has
passed and the change has not weakened the acceptance contract. When a new committed V2 baseline
exists, retain the V1 numbers as historical comparisons and replace the current readings rather
than rewriting the dated evidence records.
