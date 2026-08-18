# Airflow developer deployment

This directory implements `P1-4.1-D1`: Airflow 3.3.1 with LocalExecutor and
PostgreSQL 17.10. The API is published on loopback only. PostgreSQL metadata and
Airflow logs use named volumes, so ordinary shutdown is non-destructive.

Implementation and offline guardrails are complete, but live acceptance is
paused. Startup requires an already-built image and intentionally does not build
one. Resume the two-cycle exercise only after `P1-4.1-S2` has published the
registry-layer Airflow/Spark image and `AIRFLOW_IMAGE` identifies that accepted
digest. The legacy gigabyte-scale local build is not a prerequisite workaround;
see
[`airflow_spark_runtime_reassessment_20260818.md`](../../../docs/implementation/airflow_spark_runtime_reassessment_20260818.md).

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
`evidence/` directories. `P1-4.2-D1` adds Spark submission, Ceph/Polaris trust,
protected Airflow connections, and immutable DAG delivery.
