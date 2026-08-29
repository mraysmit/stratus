# Airflow developer deployment

This directory implements `P1-4.1-D1`: Airflow 3.3.1 with LocalExecutor and
PostgreSQL 17.10. The API is published on loopback only. PostgreSQL metadata and
Airflow logs use named volumes, so ordinary shutdown is non-destructive.

Implementation, offline guardrails, and the two-cycle live acceptance passed on
2026-08-22 using the accepted `P1-4.1-S2` local development image. Startup
requires that already-built image and intentionally does not build or scan it.
Registry publication and immutable promotion belong to the later production-
hardening stage. Exact acceptance evidence is recorded in
[`development-acceptance-20260822.md`](../development-acceptance-20260822.md).

The first startup creates `.env` from `.env.template` and generates disposable
database, Fernet, JWT, and API secrets. The file is git-ignored and owner-only.
The built-in SimpleAuthManager allows loopback developer access without a
password; this shortcut is prohibited in production and is replaced by the
Increment 7 identity work.

Run from any directory using Bash 4+:

```bash
bash platform/airflow/developer/scripts/lifecycle/airflow-compose-startup.sh
bash platform/airflow/developer/scripts/tests/airflow-compose-verify-health.sh
bash platform/airflow/developer/scripts/lifecycle/airflow-compose-shutdown.sh
```

The acceptance exercise runs two complete start, health, and stop cycles:

```bash
bash platform/airflow/developer/scripts/tests/airflow-compose-lifecycle-test.sh
```

Reset is intentionally destructive and prompts unless `--force` is supplied:

```bash
bash platform/airflow/developer/scripts/lifecycle/airflow-compose-reset.sh
```

Generated migration and lifecycle transcripts remain under ignored `logs/` and
`evidence/` directories.

## Airflow-to-Spark development acceptance

`P1-4.2-D1` is implemented by `compose.spark.yaml`, the immutable
`dags/stratus_spark_submission_probe.py` DAG, and the checked-in test harness in
`scripts/tests/`. The test uses the existing developer Ceph, OpenBao, Polaris and
Spark services; each provider must be started with its own checked-in lifecycle
scripts first. The Airflow test itself starts and stops Airflow, preserving its
named volumes.

```bash
bash platform/airflow/developer/scripts/tests/airflow-spark-submission-test.sh
```

The suite creates a protected `spark_default` Airflow connection, verifies host
and mounted DAG hashes, verifies the Spark defaults, truststore, jobs JAR and
locked Iceberg runtime hashes, then submits the packaged Java probe. The probe
performs distributed work, discovers Polaris namespaces and creates, writes,
reads and drops an isolated Iceberg table on Ceph. It logs suite, phase and job
timings with correlation IDs, requires the completion marker, and rejects any
transcript containing the actual generated Airflow or Spark storage secrets.

The accepted run completed in 110.629 seconds. Its ignored raw transcript was
`evidence/airflow-spark-20260822T090250Z.log`; durable results and limitations are
preserved in the tracked acceptance record linked above. `P1-4.3-V1`, the full
pipeline DAG and orchestration-verifier task, is implemented and verified in the
development environment. The Increment 4 developer gate was accepted on
2026-08-24 against the canonical one-command development suite.

The first P1-4.3 slice can be parsed and registered through Airflow without
starting the data-plane providers:

```bash
bash platform/airflow/developer/scripts/tests/airflow-pipeline-dag-parse-test.sh
```

This checked-in test starts Airflow, runs its health contract, requires an empty
import-error list, requires all four pipeline DAGs in Airflow's registry, records
phase timings, and shuts Airflow down through the lifecycle script. See
[archived pipeline progress record](../archive/pipeline-development-progress-20260822.md)
for the accepted live pipeline evidence and maintenance guidance.

The accepted live landing-to-bronze slice uses the same provider prerequisites
and remains entirely in the clearly named `scripts/tests/` directory:

```bash
bash platform/airflow/developer/scripts/tests/airflow-landing-to-bronze-live-test.sh
```

It stages an isolated three-row landing object, executes the sensor -> ingestion
-> quality DAG, independently verifies the bronze batch and persisted quality
result, removes both governed test records and the landing object, checks that
RGW access and secret keys never entered the transcript, and shuts Airflow down.
Run `airflow-pipeline-20260823T071231Z` passed in 187,300 ms. The locked S3A
dependencies and AWS bundle Log4j compatibility bridge are mounted from the
Spark artifact set; Spark continues to use exactly one SLF4J 2 provider.

The bronze-to-silver slice is exercised by the second checked-in live
harness in the same test directory:

```bash
bash platform/airflow/developer/scripts/tests/airflow-bronze-to-silver-live-test.sh
```

The harness proves one accepted path from an isolated landing object through bronze quality, the
lightweight direct-catalog promotion task, a defence-in-depth gated deterministic transform, two
passing silver checks and an independently verified three-row Iceberg snapshot. Its blocked path
uses a deliberately unknown quality run, requires the gate to fail closed in seconds, leaves
transform and downstream quality unstarted, and proves the target is absent with a direct catalog
lookup. It does not repeat ingestion, quality or a standalone Spark verifier for the blocked case.
The historical V1 run `airflow-bronze-to-silver-20260823T084502Z` passed in 356,572 ms; V2 evidence
is recorded separately and does not rewrite that result.

Normal executions use two retries with a five-minute delay. The live harness
sets `STRATUS_BRONZE_TO_SILVER_RETRIES=0` only for its deliberately blocked
scenario so the expected failure does not wait through retry intervals.

The checked-in focused silver-to-gold harness starts at its silver input boundary:

```bash
bash platform/airflow/developer/scripts/tests/airflow-silver-to-gold-live-test.sh
```

One Spark application prepares an accepted three-customer table and a naturally invalid
three-row/two-customer table. A manifest-driven Java verifier triggers both real DAG runs through
Airflow's public API and requires their exact task-state maps. The accepted run executes silver
quality, the direct-catalog gate, materialisation and gold quality. The blocked run executes its own
silver uniqueness check, fails the gate, leaves materialisation and gold quality
`upstream_failed`, and never creates the target. No failing quality result is pre-seeded.

The independent verifier reads Iceberg directly, checking exact rows, countries, aggregates,
snapshots, scoped quality evidence and blocked-target absence without starting Spark. One final
Spark application removes only the four isolated tables and four exact quality-run IDs. The hard
budget is six Spark applications: prepare, three accepted tasks, one blocked quality task and
cleanup. Run `airflow-silver-to-gold-20260829T143008Z` passed in 233,770 ms and the Spark master
proved exactly six new applications, from baseline count 6 to final count 12. Its two scheduler
scenarios took 125,625 ms, including accepted Airflow duration 93,712 ms and blocked duration
29,626 ms. The historical V1 run took 716,033 ms with about 15 applications. Normal executions use
two retries; the harness sets `STRATUS_SILVER_TO_GOLD_RETRIES=0` only for expected-failure evidence.

The checked-in maintenance harness proves both sides of the metadata-policy
decision against an exact isolated table:

```bash
bash platform/airflow/developer/scripts/tests/airflow-table-maintenance-live-test.sh
```

It seeds three rows as three current Iceberg files, requires the skip policy to
leave all three files unchanged, then requires the run policy to compact them to
one file without changing the rows. An independent packaged verifier checks
rows, files, snapshots and exact purge cleanup after the real Airflow DAG runs.
The harness also checks structured phase timings and protected secret values.
Run `airflow-table-maintenance-20260823T113447Z` passed in 326,099 ms.

The retry and terminal-alert contract has its own isolated test DAG, compose
overlay, and harness under the clearly named `scripts/tests/` tree:

```bash
bash platform/airflow/developer/scripts/tests/airflow-retry-alert-live-test.sh
```

The harness starts only the Airflow developer stack. It verifies a transient
first-attempt failure emits one structured retry callback and succeeds on the
second attempt, then verifies a permanent failure retries once and emits exactly
one structured terminal callback. Callback records include run identity, try
number, log URL, numeric elapsed milliseconds, and exception class without
including exception messages. It also validates generated-secret redaction and
stops Airflow on every exit path. Run
`airflow-retry-alert-20260824T040947Z` passed in 72,759 ms; its callback timings
were 2,537 ms, 2,758 ms, and 42 ms.

The DAG-level timing expectation is proven separately with Airflow's native
Deadline Alert scheduler/triggerer path:

```bash
bash platform/airflow/developer/scripts/tests/airflow-deadline-alert-live-test.sh
```

The isolated test DAG completes a one-second run inside a 12-second deadline,
then keeps a second run active for 18 seconds. The first run must emit no alert;
the second must emit exactly one asynchronous callback with safe DAG, run,
correlation, deadline and numeric breach-timing fields before completing
successfully. The harness retains component/task diagnostics on failure, checks
generated Airflow secrets, and shuts down Airflow on every exit path. Run
`airflow-deadline-alert-20260824T051734Z` passed in 98,764 ms with 13,278 ms
observed elapsed time and a 1,278 ms breach.

The final control-plane and fail-closed behavior is exercised through Airflow's
public REST API by the checked-in full-stack harness:

```bash
bash platform/airflow/developer/scripts/tests/airflow-api-orchestration-live-test.sh
```

The harness starts Ceph, OpenBao, Polaris, Spark, and Airflow with their checked-in
lifecycle scripts. Its Java verifier authenticates to Airflow 3.3.1, validates
scheduler and metadata health, requires the platform DAGs and the API contract probe to be
registered and unpaused, supplies caller-owned run IDs, and records bounded poll, DAG, and task
timings. A no-op DAG proves the positive API trigger/state contract. The real bronze-to-silver DAG
uses an unknown quality run and must fail at `evaluate_bronze_promotion`, leaving transform and
downstream quality `upstream_failed`. A direct Iceberg catalog lookup proves the target was never
created. This V2 API proof creates no data fixture and starts no Spark application; pipeline data
behavior remains covered by its focused live suites. Generated secrets are scanned, and all five
provider stacks are stopped in reverse order on every exit path.

The V1 accepted run `airflow-api-orchestration-20260824T073836Z` completed in 420,772
ms. The positive DAG used 34,657 ms of Airflow time; the deliberately blocked DAG
used 18,056 ms, failed the transform on attempt one, and left the downstream
quality task unexecuted. The Java verification completed in 57,137 ms, both
independent side-effect checks passed, and cleanup reported
`remainingStratusContainers=0`.

Those task-state details are historical V1 evidence. `P1-4.3-V2` now exposes
promotion as its own Airflow task and retains the writer check as defence in depth;
the revised run `airflow-api-orchestration-20260828T053205Z` passed in 212,751 ms. Its Java API
phase took 33,972 ms, including a 2,332 ms positive probe and a 24,393 ms real fail-closed scenario;
the direct no-write check took 3,916 ms and cleanup reported zero remaining containers. The focused
bronze-to-silver V2 run `airflow-bronze-to-silver-20260828T052459Z` also passed. A superseding V2
acceptance record still requires the immutable source revision produced by the eventual commit.

Expected-failure retry overrides must reach both `airflow-dag-processor` and
`airflow-scheduler`: Airflow 3 serializes DAG defaults in the DAG processor, while
the scheduler executes the serialized result. The API client deliberately uses
HTTP/1.1 because the pinned Uvicorn listener rejects Java's clear-text HTTP/2
upgrade before the Airflow API receives a POST.

Normally scheduled LocalExecutor tasks use
`AIRFLOW__API__BASE_URL=http://airflow-api-server:8080` so worker subprocesses in
the scheduler container reach the execution API over Compose DNS. The loopback
address remains valid only for health checks running inside the API container.

## Audience-facing Airflow demonstrations

The accepted behavior is packaged as three shorter, one-command demonstrations
under [`demos/README.md`](demos/README.md): a customer landing-to-gold journey, a
fail-closed quality gate, and REST API-driven Iceberg maintenance. These entry
points reuse the live acceptance harnesses, print concise expected outcomes and
default to checked cleanup. Pass `--keep-running` only when the Airflow UI should
remain available for inspection, then use the documented demo shutdown command.

The complete regression is reserved for release/gate evidence, not routine feedback:

```bash
bash platform/airflow/developer/scripts/tests/airflow-development-acceptance-suite.sh
```

This runner starts Ceph, OpenBao, Polaris, Spark and Airflow once and reuses them across registry,
retry, Deadline Alert, Spark, pipeline, maintenance and API phases. Each nested harness continues to
own its fixtures and exact cleanup but does not restart shared services. The focused commands above
remain independently runnable and own their own Airflow lifecycle outside the canonical suite. The
two-cycle lifecycle qualification is intentionally run separately because its purpose is to test
restart and retained-state behavior.

Canonical run `airflow-development-acceptance-20260824T103411Z` passed every
Airflow development phase, repeated 294 offline tests before and after live work,
and closed the Increment 4 developer gate with zero remaining Stratus containers.
