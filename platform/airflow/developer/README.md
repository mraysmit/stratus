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
pipeline DAG and orchestration-verifier task, is now in progress.

The first P1-4.3 slice can be parsed and registered through Airflow without
starting the data-plane providers:

```bash
bash platform/airflow/developer/scripts/tests/airflow-pipeline-dag-parse-test.sh
```

This checked-in test starts Airflow, runs its health contract, requires an empty
import-error list, requires all four pipeline DAGs in Airflow's registry, records
phase timings, and shuts Airflow down through the lifecycle script. See
[`pipeline-development-progress-20260822.md`](../pipeline-development-progress-20260822.md)
for evidence and the remaining live pipeline work.

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

The accepted bronze-to-silver slice is exercised by the second checked-in live
harness in the same test directory:

```bash
bash platform/airflow/developer/scripts/tests/airflow-bronze-to-silver-live-test.sh
```

The harness first proves the accepted path from an isolated landing object
through bronze quality, gated deterministic transform, two passing silver
checks and an independently verified three-row Iceberg snapshot. It then adds
a genuine blocking quality failure, requires the Airflow transform task to
fail, and independently proves that no silver target was created. Both paths
clean their exact tables, quality rows and landing objects. Run
`airflow-bronze-to-silver-20260823T084502Z` passed in 356,572 ms.

Normal executions use two retries with a five-minute delay. The live harness
sets `STRATUS_BRONZE_TO_SILVER_RETRIES=0` only for its deliberately blocked
scenario so the expected failure does not wait through retry intervals.

The checked-in silver-to-gold harness continues through the real upstream DAGs:

```bash
bash platform/airflow/developer/scripts/tests/airflow-silver-to-gold-live-test.sh
```

Its accepted path proves the silver quality gate, governed country aggregation,
gold quality results and independent aggregate/snapshot verification. Its
blocked path persists a real failing silver check, requires materialisation to
fail before writing gold, and independently proves the target is absent. Both
paths remove only their exact bronze/silver/gold probe tables, quality rows and
landing objects. Run `airflow-silver-to-gold-20260823T093453Z` passed in 716,033
ms. Normal silver-to-gold executions use two retries; the harness sets
`STRATUS_SILVER_TO_GOLD_RETRIES=0` only for expected-failure evidence.

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
