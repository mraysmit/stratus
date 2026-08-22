# Orchestration Verification

Verifies that Apache Airflow is deployed with its PostgreSQL metadata database, that DAGs are registered and schedulable, and that the batch pipeline runs end to end without manual intervention. Verification covers the ingestion, bronze-to-silver, silver-to-gold, and maintenance DAGs; enforcement of the quality promotion gate as a DAG task that halts downstream work on failure; retry behaviour on transient failures; and alert emission on permanent failure and SLA breach. A deliberately failed quality check must halt the DAG and leave downstream tasks unexecuted.

Prerequisite: `compute` verification passed against a live cluster.

Implementation status (2026-08-22): the current Airflow 3.3.1 S2 image has
hash/digest locks plus build, provider-import smoke, and daemon-isolated scan
scripts under `platform/airflow/image/`. Its scan reported zero Critical and 61
High occurrences across 38 unique package/CVE pairs. `P1-4.1-D1` passed two full
LocalExecutor/PostgreSQL lifecycle cycles, and `P1-4.2-D1` passed a live packaged
Java submission that exercised distributed Spark and Polaris/Ceph-backed Iceberg.
This directory remains a placeholder because those platform proofs do not
implement the complete orchestration behavior. `P1-4.3-V1` is in progress: the
landing-to-bronze source contract now passes offline guardrails and Airflow's own
parse/registry check, while its live run, the remaining pipeline DAGs, and the
executable Java verifier remain. Registry publication and
readiness controls belong to the later production-hardening stage. See
[`platform/airflow/development-acceptance-20260822.md`](../../platform/airflow/development-acceptance-20260822.md).
