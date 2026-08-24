# Airflow pipeline DAG development progress - 2026-08-22

## Scope

`P1-4.3-V1` is development-verified. This record covers its accepted
landing-to-bronze-to-silver-to-gold vertical path: landing-object detection,
live bronze ingestion and quality, promotion-gated transformation and
materialisation, live silver and gold quality, deliberately blocked promotion,
independent table/result verification, metadata-policy maintenance run/skip
behavior, deterministic cleanup, Airflow-native DAG parsing, and final public-API
positive/fail-closed verification. It does not accept the separate Increment 4
developer gate.

## Strict-TDD evidence

The new `AirflowPipelineDagTest` was run before implementation and failed four
tests because `stratus_common.py`, `stratus_alerts.py`, and
`stratus_landing_to_bronze.py` did not exist. After implementation, its four
source-contract tests passed. A second failing test then required the checked-in
Airflow parse harness; after adding the script, all five tests passed.

The bronze-to-silver slice followed the same sequence. New repository tests
first failed because the DAG and live harness did not exist. Unit tests for the
independent verifier then defined its exact probe-table allow-list, accepted and
blocked outcomes, SQL escaping and cleanup contract. After implementation, the
combined focused suite passed 12 tests. A final red test required the live-only
zero-retry override while retaining two retries as the ordinary DAG default;
all nine DAG guardrails then passed. No mocking framework was used.

The silver-to-gold slice began with three verifier unit tests and three new DAG
guardrails. The corrected red run failed at compilation because
`AirflowSilverToGoldVerifierJob` did not exist. After the DAG and verifier were
implemented, the verifier tests passed while the DAG guardrail still failed for
the absent live harness. Adding the checked-in harness made all 12 DAG
guardrails and all three verifier tests pass. The live test then exercised the
same accepted and blocked contracts against the real services; no mocking
framework was used.

The table-maintenance slice began with five metadata-policy tests, three
independent verifier tests, and three DAG/harness guardrails. The corrected red
runs failed for the absent policy job, verifier, DAG, and live harness. After
implementation, the focused Java suite and all 15 DAG guardrails passed. The
first live run then exposed a second red condition: policy selected compaction
at two small files, but Iceberg retained its independent default of five input
files and left all three fixture files unchanged. A regression test first fixed
the required procedure call contract; the implementation then passed the
policy's trigger and target size as explicit Iceberg rewrite options. The rerun
reduced three files to one while preserving all three rows. No mocking
framework was used.

The retry/alert slice began with repository contracts for a test-only probe,
isolated compose overlay, checked-in live harness, retry callback, terminal
callback, elapsed-time fields, safe exception metadata, and cleanup. The first
red run failed because the three test artifacts and retry/timing callback
behavior did not exist. The initial implementation made the focused suite green,
but its first live startup exposed an invalid file-under-read-only-directory
mount. A new failing mount contract required a whole test-DAG directory and a
separate platform callback path before the overlay was corrected. The next live
run exposed a deprecated Airflow operator import and unavailable callback
duration; failing regressions were added first for the supported Airflow SDK
import, start-time duration fallback, and numeric live duration. The final
implementation passed all 18 focused DAG guardrails and the real Airflow proof.
No mocking framework was used.

The Deadline Alert slice started with three new contracts for a test-only DAG,
isolated overlay, asynchronous triggerer callback, on-time outcome, breached
outcome, exactly-once delivery, numeric timing, secret checks, diagnostics, and
cleanup. The first red run failed for the missing artifacts and callback. After
implementation, the first scheduled live run exposed a shared deployment defect:
LocalExecutor workers inside the scheduler container attempted to reach the
execution API through `localhost:8080` and were killed after connection refusal.
A failing developer-topology regression first required the internal
`airflow-api-server:8080` address, then the shared Compose configuration was
corrected. A separate red/green regression retained scheduler, triggerer, API,
DAG-processor, and task diagnostics on future live failures. The final live run
passed both outcomes with all 21 DAG guardrails and five deployment guardrails
green. No mocking framework was used.

The final API/orchestration slice began with configuration and real HTTP fixture
tests for authentication, health, DAG registration, caller-owned run IDs,
terminal polling, task states, timing, redaction, and error handling. The first
live trigger exposed Airflow 3.3.1's required nullable `logical_date`; the next
exposed Java's clear-text HTTP/2 upgrade being rejected by Uvicorn before a POST
reached Airflow. Failing protocol assertions were added before the trigger model
and HTTP/1.1 client were corrected. The deliberately blocked run then exposed
that its retry override reached the scheduler but not the DAG processor that
serializes Airflow 3 DAG defaults. A failing deployment guardrail required both
services before the overlay was corrected. Finally, a successful functional run
left Ceph containers running because Airflow's exported compose project name
redirected the Ceph shutdown command. A failing Ceph contract required every
Ceph compose invocation to bind `stratus-ceph-local` explicitly. The final rerun
passed functional proof and teardown. No mocking framework was used.

Live execution continued in the same red-green sequence. Focused guardrails
failed before each correction for the missing S3A runtime, AWS SDK bundle,
analytics accelerator, verifier event-log directory, credential-identifier
redaction, and AWS bundle logging bridge. The corresponding behavioral logging
test first reproduced the AWS bundle's NOP fallback and then passed with both
the AWS bridge and Spark's single SLF4J 2 provider routed into Log4j2. No mocking
framework was used.

## Implemented contract

- `stratus_common.py` is the single `SparkSubmitOperator` factory. It uses the
  protected `spark_default` connection and accepted mounted jobs/runtime JARs;
  DAG source contains no Spark master, Polaris secret, or Ceph secret.
- `stratus_alerts.py` emits structured retry, terminal-failure, and missed-
  deadline records. Deadline records include DAG/run/correlation identity,
  deadline name and time, queued time, expected interval, observed elapsed time,
  and breach time. All callback paths deliberately omit arbitrary exception
  messages and credentials.
- `stratus_landing_to_bronze.py` uses a rescheduling `S3KeySensor`, two retries
  with a five-minute delay, one active run, the real packaged `IngestionJob` and
  `QualityCheckJob` classes, the Airflow run ID as batch/correlation ID, and a
  strict sensor-to-ingestion-to-quality dependency chain.
- `scripts/tests/airflow-pipeline-dag-parse-test.sh` is kept in the clearly named
  Airflow test-script directory and uses only the checked-in startup, health and
  shutdown lifecycle scripts.
- `scripts/tests/airflow-landing-to-bronze-live-test.sh` owns the complete live
  slice. It creates an isolated fixture, bootstraps protected connections,
  executes the DAG, invokes `AirflowPipelineVerifierJob`, requires verification
  and cleanup markers, rejects logged access/secret keys, records phase timing,
  and stops Airflow through the checked-in lifecycle script.
- `stratus_bronze_to_silver.py` runs the real `TransformJob` with the bronze
  quality run ID before any write, scopes the read to one source batch, uses
  `customer_id` and `updated_at` for deterministic deduplication, and only then
  runs blocking silver row-count and uniqueness checks. Its ordinary retry
  policy remains two attempts with a five-minute delay.
- `scripts/tests/airflow-bronze-to-silver-live-test.sh` proves both outcomes.
  It retains ordinary retries unless the harness explicitly sets the documented
  zero-retry override so the intentionally blocked case can finish immediately.
  It uses the lifecycle scripts, protected connections, isolated fixtures,
  structured phase timings, secret checks and exact cleanup markers.
- `AirflowBronzeToSilverVerifierJob` is independent of the DAG. For an accepted
  run it proves the source batch, promotion decision, three-row silver result,
  absence of bronze audit columns, two passing silver checks and an Iceberg
  snapshot. For a blocked run it proves the failing check and absence of the
  target table. Cleanup is restricted to exact allow-listed probe tables and
  correlated quality run IDs.
- `stratus_silver_to_gold.py` runs two blocking silver checks, passes their exact
  quality run ID to the real `MaterialisationJob` before any gold write, executes
  the platform-owned country aggregation, and then runs blocking gold row-count
  and country-uniqueness checks. Ordinary runs retain two retries and a
  five-minute delay; the live expected-failure path alone uses the documented
  zero-retry override.
- `AirflowSilverToGoldVerifierJob` independently proves the accepted silver
  promotion, exact `country`/`customer_count` gold schema, three groups, total
  customer count, two persisted gold checks, and a concrete Iceberg snapshot.
  For the blocked outcome it re-evaluates the failing silver gate and proves the
  gold table is absent. Cleanup accepts only exact bronze, silver, and gold probe
  table names plus explicitly correlated quality run IDs.
- `scripts/tests/airflow-silver-to-gold-live-test.sh` constructs both outcomes
  through the real landing-to-bronze and bronze-to-silver DAGs. It requires
  independent accepted and blocked markers, structured phase timings, secret
  checks, exact governed cleanup, landing cleanup, and lifecycle shutdown.
- `stratus_table_maintenance.py` is a daily, single-active-run trigger for the
  real packaged `TableMaintenanceJob`. Airflow supplies only a target table,
  policy version, and run ID; it cannot inject a list of Iceberg operations.
- `TableMaintenanceJob` validates the catalog, namespace, table, and versioned
  policy; measures `files`, `snapshots`, `manifests`, `delete_files`, and a
  dry-run orphan scan; emits before/after metrics and an explicit RUN or SKIP
  decision for every supported action; and delegates selected work to the
  established maintenance primitive. Rewrite calls carry the policy's
  `min-input-files` and `target-file-size-bytes` values, preventing Iceberg
  defaults from contradicting the selection threshold.
- `AirflowTableMaintenanceVerifierJob` is independent of the DAG and accepts
  only exact isolated maintenance-probe tables. It creates three rows in three
  files, verifies both unchanged and compacted states including snapshot counts,
  and performs an exact purge drop.
- `scripts/tests/airflow-table-maintenance-live-test.sh` proves a policy skip and
  a policy run against the same isolated fixture, independently verifies rows,
  files, and snapshots after each decision, records all phase timings, rejects
  protected secret values, performs exact cleanup, and stops Airflow through
  the checked-in lifecycle script.
- The scheduler mounts the exact checksum-locked Hadoop S3A, AWS SDK,
  analytics-accelerator and Log4j compatibility artifacts selected for the
  Spark runtime. The compatibility binder exists only for the AWS SDK bundle's
  legacy discovery contract; Spark application logging remains on its one
  SLF4J 2/Log4j2 provider.

## Live parse evidence

Run ID: `airflow-pipeline-parse-20260822T092510Z`.

- Airflow 3.3.1 with LocalExecutor and PostgreSQL 17.10 became healthy.
- `airflow dags list-import-errors --output json` returned `[]`.
- Airflow registered `stratus_landing_to_bronze` unpaused from the mounted,
  tracked DAG path.
- Startup/health completed in 59,563 ms.
- Airflow parsing and registry verification completed in 6,114 ms.
- Total suite time was 65,896 ms.
- The checked-in trap shut down all Airflow containers and preserved its named
  metadata/log volumes.

The ignored raw transcript is
`developer/evidence/airflow-pipeline-parse-20260822T092510Z.log`.

The expanded parser run `airflow-pipeline-parse-20260823T083316Z` subsequently
registered both `stratus_landing_to_bronze` and `stratus_bronze_to_silver` with
no import errors and completed in 77,623 ms.

The final expanded parser run `airflow-pipeline-parse-20260823T093309Z`
registered all three pipeline DAGs, returned no import errors, and completed in
90,312 ms: 81,857 ms for startup/health and 8,214 ms for parse/registry proof.

The maintenance-expanded parser run `airflow-pipeline-parse-20260823T112234Z`
registered all four DAGs with no import errors and completed in 76,704 ms:
67,497 ms for startup/health and 9,016 ms for parse/registry proof.

## Live landing-to-bronze evidence

Run ID: `airflow-pipeline-20260823T071231Z`.

- The read-only `svc-airflow` identity detected and read the isolated landing
  object; the Spark driver used the separately protected `svc-spark` identity.
- Airflow completed the immutable sensor -> ingestion -> quality chain.
- Ingestion wrote exactly three batch rows and emitted timed plan, write,
  property, lineage, and verification phases.
- Quality persisted exactly one passing `row_count_min` result associated with
  the same pipeline run ID.
- `AirflowPipelineVerifierJob` independently observed three batch rows, one
  passing quality result and a concrete Iceberg snapshot, then deleted the
  quality result and dropped the isolated probe table.
- The fixture helper deleted the landing object and proved that zero matching
  objects remained.
- The transcript contained neither RGW access keys nor secret keys, contained
  no AWS `StaticLoggerBinder`/NOP fallback warning, and Airflow's trap removed
  every Airflow container.

| Event or phase | Duration/result |
|---|---:|
| Airflow startup | 56,606 ms |
| Protected connections | 39,191 ms |
| Isolated input | 1,006 ms |
| DAG execution | 57,344 ms |
| Independent verification and cleanup | 30,796 ms |
| Landing cleanup | 1,040 ms |
| Complete suite | 187,300 ms |

The ignored raw transcript is
`developer/evidence/airflow-pipeline-20260823T071231Z.log`.

During development, an earlier verifier attempt created a valid test table but
could not start because its inherited event-log path was absent. The test trap
still removed Airflow and the landing fixture. After the event-log contract was
fixed, the exact orphaned quality row and probe table were explicitly removed;
no wildcard or non-test data cleanup was performed.

## Live bronze-to-silver evidence

Run ID: `airflow-bronze-to-silver-20260823T084502Z`.

- The accepted scenario ingested and quality-checked an isolated three-row
  bronze batch. `TransformJob` read its passing quality evidence before writing,
  selected the requested batch, produced three silver rows, and the downstream
  row-count and `customer_id` uniqueness checks both passed.
- The independent verifier observed three source rows, three target rows, two
  passing silver checks and silver snapshot `6836494764869137789`. It also
  proved that bronze audit columns were not copied into silver.
- The blocked scenario appended a real blocking `requires_four_rows` failure to
  the three-row bronze batch. The promotion gate examined both results, named
  the failing check, exited with the expected failure, and wrote no silver table.
- The independent blocked verifier re-evaluated the gate, proved the target did
  not exist, and emitted `AIRFLOW BRONZE TO SILVER BLOCK VERIFIED`.
- Both scenarios removed their exact bronze/silver probe tables, correlated
  quality rows and landing objects. The Airflow trap stopped its services, and
  the transcript contained neither protected RGW access keys nor secret keys.
- The test-only `STRATUS_BRONZE_TO_SILVER_RETRIES=0` setting prevents expected
  blocked-path evidence from waiting through normal retry delays. The compose
  and DAG defaults remain two retries when the override is absent.

| Event or phase | Duration/result |
|---|---:|
| Airflow startup | 56,423 ms |
| Protected connections | 33,545 ms |
| Accepted bronze seed and quality | 56,601 ms |
| Accepted transform and silver quality | 54,996 ms |
| Accepted verification and cleanup | 28,386 ms |
| Accepted landing cleanup | 850 ms |
| Blocked bronze seed and quality | 53,174 ms |
| Deliberate blocking-quality record | 18,671 ms |
| Blocked no-write verification and cleanup | 25,359 ms |
| Blocked landing cleanup | 851 ms |
| Complete suite | 356,572 ms |

The ignored raw transcript is
`developer/evidence/airflow-bronze-to-silver-20260823T084502Z.log`.

## Live silver-to-gold evidence

Run ID: `airflow-silver-to-gold-20260823T093453Z`.

- The accepted scenario built a real three-row silver table through the two
  upstream DAGs. Two fresh silver checks passed, the materialisation promotion
  gate examined both results, and the platform-owned aggregation wrote three
  country groups whose customer counts totalled three.
- The downstream gold row-count and country-uniqueness checks both passed. The
  independent verifier proved the exact two-column gold schema, three groups,
  total customer count, two correlated passing gold checks and Iceberg snapshot
  `4905475424229459833`.
- The blocked scenario built a second real silver table, then persisted a
  blocking `requires_four_silver_rows` result against its three rows. The DAG's
  own two silver checks passed, but `MaterialisationJob` examined all three
  results, named the failing check, and stopped before any gold write.
- The independent blocked verifier re-evaluated that gate, proved the gold table
  was absent, and emitted `AIRFLOW SILVER TO GOLD BLOCK VERIFIED`.
- Both outcomes removed their exact bronze, silver and gold probe tables,
  correlated quality rows and landing objects. The transcript contained neither
  protected RGW access keys nor secret keys, and the lifecycle trap stopped all
  Airflow services.

| Event or phase | Duration/result |
|---|---:|
| Airflow startup | 86,856 ms |
| Protected connections | 53,294 ms |
| Accepted silver seed and quality | 148,103 ms |
| Accepted gold DAG execution | 98,026 ms |
| Accepted verification and cleanup | 38,865 ms |
| Accepted landing cleanup | 1,014 ms |
| Blocked silver seed and quality | 167,672 ms |
| Deliberate blocking-quality record | 26,398 ms |
| Expected materialisation failure | 55,297 ms |
| Blocked no-write verification and cleanup | 36,917 ms |
| Blocked landing cleanup | 1,133 ms |
| Complete suite | 716,033 ms |

The ignored raw transcript is
`developer/evidence/airflow-silver-to-gold-20260823T093453Z.log`.

## Live table-maintenance evidence

Run ID: `airflow-table-maintenance-20260823T113447Z`.

- The verifier created an allow-listed bronze probe containing three rows in
  three current data files and three snapshots.
- `development-skip-v1` observed three small files against threshold four,
  logged explicit skips for rewrite and expiry, and left the table at three
  rows, three files, and three snapshots.
- `development-run-v1` observed the same three files against threshold two,
  selected `rewrite_data_files`, supplied the policy threshold and target size
  to Iceberg, and reduced the current file count from three to one.
- The independent verifier proved all three rows remained, one current data
  file existed, and the rewrite produced the expected fourth snapshot.
- Both policies logged before/after file count, small-file count, average file
  size, snapshot-chain length, manifest count, delete-file count, orphan-file
  count, selected action, threshold, observed value, and policy version.
- The verifier purge-dropped only the exact probe table. Secret checks passed,
  and the harness stopped all Airflow services while preserving its named
  metadata/log volumes.

| Event or phase | Duration/result |
|---|---:|
| Airflow startup | 77,812 ms |
| Protected connection | 31,227 ms |
| Seed three small files | 34,562 ms |
| Skip policy execution | 61,897 ms |
| Independent skip verification | 22,998 ms |
| Run policy execution | 45,917 ms |
| Independent run verification | 22,703 ms |
| Exact cleanup | 27,180 ms |
| Complete suite | 326,099 ms |

The ignored raw transcript is
`developer/evidence/airflow-table-maintenance-20260823T113447Z.log`.

## Live retry and failure-alert evidence

Run ID: `airflow-retry-alert-20260824T040947Z`.

- The isolated development-only DAG failed in transient mode on attempt one,
  emitted exactly one `event=airflow_task_retry` callback, and succeeded on
  attempt two. It emitted no terminal failure alert.
- Permanent mode failed on attempts one and two, emitted its retry callback only
  after the first attempt, and emitted exactly one `event=airflow_task_failed`
  callback after retry exhaustion on attempt two.
- The callback records included DAG, task, run, logical date, try number, log URL,
  numeric `duration_ms`, and `exception_class`. They deliberately excluded
  exception messages; the controlled permanent-failure detail was absent from
  the terminal alert record.
- The observed callback durations were 2,537 ms for the recovered transient
  attempt, 2,758 ms for the permanent retry, and 42 ms for the terminal attempt.
- The harness checked every generated Airflow configuration secret against the
  complete transcript. It ran without Ceph, OpenBao, Polaris, or Spark, then
  stopped the isolated Airflow stack through the checked-in lifecycle script.

| Event or phase | Duration/result |
|---|---:|
| Airflow startup and probe registration | 53,944 ms |
| Transient retry recovery | 9,517 ms |
| Permanent failure and terminal alert | 9,047 ms |
| Complete suite | 72,759 ms |

The ignored raw transcript is
`developer/evidence/airflow-retry-alert-20260824T040947Z.log`.

## Live Deadline Alert evidence

Run ID: `airflow-deadline-alert-20260824T051734Z`.

- The isolated DAG used Airflow's public `DeadlineAlert`,
  `DeadlineReference.DAGRUN_QUEUED_AT`, and asynchronous triggerer callback with
  a 12-second expected interval.
- The one-second probe completed successfully before its deadline and produced
  no missed-deadline callback.
- The 18-second probe remained active beyond its deadline, produced exactly one
  `event=airflow_deadline_missed` callback, and then completed successfully. A
  missed timing expectation is observable without incorrectly failing the DAG.
- The callback recorded DAG/run/correlation identity, stable deadline name,
  deadline and queued timestamps, `expected_interval_ms=12000`,
  `observed_elapsed_ms=13278`, and `breach_ms=1278`.
- The harness checked generated Airflow secrets against the complete transcript,
  retained component and task diagnostics on failure, and stopped Airflow through
  the checked-in lifecycle script. Ceph, OpenBao, Polaris, and Spark were not
  required.
- The proof also exercised the corrected LocalExecutor execution-API route at
  `http://airflow-api-server:8080`; both normally scheduled task runs completed.

| Event or phase | Duration/result |
|---|---:|
| Airflow startup and probe registration | 56,572 ms |
| On-time DAG completion | 13,642 ms |
| Missed-deadline DAG completion | 27,711 ms |
| Callback observability checks | 544 ms |
| Complete suite | 98,764 ms |

The ignored raw transcript is
`developer/evidence/airflow-deadline-alert-20260824T051734Z.log`.

## Live Airflow API/orchestration evidence

Run ID: `airflow-api-orchestration-20260824T073836Z`.

- Authentication completed in 62 ms, health in 20 ms, and registry validation
  in 56 ms. Five DAGs were visible and all four required Stratus DAGs were
  present and unpaused.
- `stratus_table_maintenance` completed successfully on attempt one. It was
  observed for 36,347 ms and reported 34,657 ms of Airflow run time; its policy
  task reported 33,453 ms.
- `stratus_bronze_to_silver` consumed a persisted blocking quality result and
  failed closed. It was observed for 20,194 ms and reported 18,056 ms of Airflow
  time. `run_silver_transform` failed on attempt one after 16,325 ms and
  `run_silver_quality` was `upstream_failed` with zero attempts.
- The independent maintenance verifier proved three rows and one current data
  file after the real DAG compacted three seeded files. The independent blocked
  verifier proved `requires_four_rows` failed and no silver target existed.
- The Java API verification completed in 57,137 ms; its Maven phase completed in
  61,719 ms. The complete provider startup, fixture, API, side-effect, cleanup,
  redaction, and shutdown suite completed successfully in 420,772 ms.
- Exact table, quality-result, and landing-object cleanup passed. Airflow, Spark,
  Polaris, OpenBao, and Ceph stopped through checked-in lifecycle scripts, and
  the final marker reported `remainingStratusContainers=0`.

The ignored raw transcript is
`developer/evidence/airflow-api-orchestration-20260824T073836Z.log`.

## Repository verification and shutdown

After the Deadline Alert evidence and status updates, `mvn -o verify` completed
the full 11-module reactor successfully in 51.896 seconds. The executed modules
ran 278 tests with zero failures, errors, or skips; this includes all 21 Airflow
DAG guardrails, five deployment guardrails, all maintenance policy/verifier
tests, the maintenance
procedure-option regression, the SLF4J/Log4j2 logging and redaction tests, and
the repository Java policy. `git diff --check` reported no whitespace errors.
The Deadline Alert harness stopped its Airflow stack. Spark, Polaris, OpenBao, and
Ceph were already stopped, and the final filtered Docker query returned no
running Stratus containers.

After the API verifier, Ceph project-isolation regression, and status-document
updates, the final `mvn -o verify` completed the expanded 12-module reactor in
1 minute 2 seconds. It ran 290 offline tests with zero failures, errors, or skips;
the separate live-only orchestration test is excluded from that count. Shell
syntax checks passed for both new live harnesses and the Ceph common helper.

## Next implementation-plan item

`P1-4.3-V1` implementation and development verification are complete. Proceed
to `P1-4.G-D`: assemble the D1-D2 gate/evidence matrix and record the local
metadata/log state, bootstrap credentials, local CA, and reduced service
availability in the development-state promotion manifest.
