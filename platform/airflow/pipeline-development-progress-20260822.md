# Airflow pipeline DAG development progress - 2026-08-22

## Scope

`P1-4.3-V1` is in progress. This record covers only its first vertical slice:
landing-object detection, bronze ingestion submission, bronze quality submission,
and Airflow-native DAG parsing. It does not accept the full task or the Increment
4 developer gate.

## Strict-TDD evidence

The new `AirflowPipelineDagTest` was run before implementation and failed four
tests because `stratus_common.py`, `stratus_alerts.py`, and
`stratus_landing_to_bronze.py` did not exist. After implementation, its four
source-contract tests passed. A second failing test then required the checked-in
Airflow parse harness; after adding the script, all five tests passed.

## Implemented contract

- `stratus_common.py` is the single `SparkSubmitOperator` factory. It uses the
  protected `spark_default` connection and accepted mounted jobs/runtime JARs;
  DAG source contains no Spark master, Polaris secret, or Ceph secret.
- `stratus_alerts.py` emits one structured failure record with DAG, task, run,
  logical-date, attempt, log URL, and exception-class context. It deliberately
  omits arbitrary exception messages and credentials.
- `stratus_landing_to_bronze.py` uses a rescheduling `S3KeySensor`, two retries
  with a five-minute delay, one active run, the real packaged `IngestionJob` and
  `QualityCheckJob` classes, the Airflow run ID as batch/correlation ID, and a
  strict sensor-to-ingestion-to-quality dependency chain.
- `scripts/tests/airflow-pipeline-dag-parse-test.sh` is kept in the clearly named
  Airflow test-script directory and uses only the checked-in startup, health and
  shutdown lifecycle scripts.

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

## Remaining P1-4.3 work

1. Bootstrap and test the protected `stratus_landing` S3-compatible Airflow
   connection, upload an isolated dated input, and execute this DAG live.
2. Implement and prove bronze-to-silver quality blocking and transformation.
3. Implement and prove silver-to-gold quality blocking and materialisation.
4. Implement policy-driven table maintenance and its run/skip evidence.
5. Prove transient retry and permanent-failure alert behavior.
6. Implement the Java Airflow REST/table-layer orchestration verifier and run
   the complete positive and negative scenarios.

Production deployment, external alert routing, immutable publication, HA and
readiness remain deferred to the separate later hardening stage.
