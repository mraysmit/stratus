# Orchestration Verification

Verifies that Apache Airflow is deployed with its PostgreSQL metadata database, that DAGs are registered and schedulable, and that the batch pipeline runs end to end without manual intervention. Verification covers the ingestion, bronze-to-silver, silver-to-gold, and maintenance DAGs; enforcement of the quality promotion gate as a DAG task that halts downstream work on failure; retry behaviour on transient failures; and alert emission on permanent failure and SLA breach. A deliberately failed quality check must halt the DAG and leave downstream tasks unexecuted.

Prerequisite: `compute` verification passed against a live cluster.

Implementation status: V1 is accepted point-in-time evidence from 2026-08-24;
V2 explicit promotion-task verification is in progress from 2026-08-25.
`P1-4.3-V1` now has an executable
Airflow 3 REST verifier, real HTTP protocol fixtures, bounded configuration,
SLF4J lifecycle/timing telemetry, and a checked-in full-stack live harness. The
earlier Airflow image, deployment, Spark submission, ingestion, transformation,
quality, maintenance, retry and alert proofs remain part of the acceptance chain;
the API verifier closes the outstanding control-plane and fail-closed scenarios.
See
[`platform/airflow/development-acceptance-20260822.md`](../../platform/airflow/development-acceptance-20260822.md).

## Why this verifier exists

A successful `airflow dags test` proves task code in a local invocation, but it
does not prove the deployed API server can authenticate a caller, see the same
serialized DAGs as the scheduler, accept caller-owned correlation IDs, schedule
through LocalExecutor, expose terminal task states, or stop downstream work after
a quality failure. This module exercises that deployed control plane and keeps
the assertions independent from the shell harness that creates and inspects the
Iceberg fixtures.

The Java layer deliberately owns only stable Airflow API behavior:

- endpoint and credential validation, with HTTP allowed only by explicit
  development configuration;
- credentialed `POST /auth/token` and the explicitly enabled disposable
  SimpleAuthManager `GET /auth/token` path;
- HTTP/1.1 requests, because Java's clear-text h2c upgrade is rejected by the
  pinned Airflow/Uvicorn listener before FastAPI receives a POST;
- Airflow 3.3.1's required nullable `logical_date` trigger property;
- health, DAG registration/unpaused state, API trigger, bounded polling, terminal
  DAG state, exact task state/attempt count, and server/observer durations; and
- response-body and credential redaction on every error path.

The shell layer owns disposable provider startup, exact S3/Iceberg fixture
creation, successful maintenance and blocked promotion side-effect checks,
secret-value scanning, cleanup and reverse-order shutdown. It is intentionally
located with the other Airflow tests at
`platform/airflow/developer/scripts/tests/airflow-api-orchestration-live-test.sh`.

Run the offline protocol/configuration tests from the repository root:

```bash
./mvnw -o test -pl :stratus-orchestration-verifier -am
```

Run the real development-stack proof with Git Bash on Windows or Bash on Linux:

```bash
bash platform/airflow/developer/scripts/tests/airflow-api-orchestration-live-test.sh
```

Accepted development evidence: run
`airflow-api-orchestration-20260824T073836Z` completed in 420,772 ms. API health
and five-DAG registry checks passed; the maintenance DAG succeeded on attempt one
in 34,657 ms of Airflow time; and the deliberately blocked bronze-to-silver DAG
failed in 18,056 ms, with `run_silver_transform=failed` on attempt one and
`run_silver_quality=upstream_failed` without an attempt. Independent verifiers
proved three rows remained after compaction from three files to one and proved
the silver target was absent. Exact cleanup completed with
`remainingStratusContainers=0`.

The preceding task states are preserved V1 evidence. The current V2 contract expects
`evaluate_bronze_promotion=failed` and both `run_silver_transform` and
`run_silver_quality=upstream_failed`; a new live run must pass before V2 supersedes V1.

## Maintenance for an Airflow version change

Treat the pinned Airflow OpenAPI model and observed wire behavior as a compatibility
contract. Start with a failing protocol test before changing authentication,
trigger fields, routes, response parsing, terminal states, or HTTP version. Then
run the module tests, repository guardrails, the complete live proof, and the full
offline reactor. A later UAT or production release must promote the same accepted
verifier and DAG content; environment-specific endpoints and credentials are
supplied at runtime, while expected states, timeouts, redaction and correlation
rules stay unchanged.
