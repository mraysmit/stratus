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
import-error list, requires the landing-to-bronze DAG in Airflow's registry,
records phase timings, and shuts Airflow down through the lifecycle script. See
[`pipeline-development-progress-20260822.md`](../pipeline-development-progress-20260822.md)
for evidence and the remaining live pipeline work.
