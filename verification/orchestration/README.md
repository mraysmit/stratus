# Orchestration Verification

Verifies that Apache Airflow is deployed with its PostgreSQL metadata database, that DAGs are registered and schedulable, and that the batch pipeline runs end to end without manual intervention. Verification covers the ingestion, bronze-to-silver, silver-to-gold, and maintenance DAGs; enforcement of the quality promotion gate as a DAG task that halts downstream work on failure; retry behaviour on transient failures; and alert emission on permanent failure and SLA breach. A deliberately failed quality check must halt the DAG and leave downstream tasks unexecuted.

Prerequisite: `compute` verification passed against a live cluster.

Implementation status (2026-08-18): the shared Airflow 3.3.1 image baseline has
hash/digest locks plus build, provider-import smoke, and daemon-isolated scan
scripts under `platform/airflow/image/`. Trivy 0.74.0 reported zero Critical and
84 High occurrences representing 35 unique package/CVE pairs, all in the
upstream Spark/Hadoop JAR set. The High findings remain tracked for upstream
upgrade and reachability analysis. The shared baseline is accepted for developer
use through 2026-09-16 by `WAIVER-P1-4.1-S1-20260817`; production promotion is
prohibited. Profiling has superseded that image's host-side Spark/PySpark assembly
path with `P1-4.1-S2`. The LocalExecutor/PostgreSQL harness and offline guardrails
are implemented, but live lifecycle acceptance is paused pending a published
registry-layer image digest. This directory remains a placeholder: the executable
Java verifier and DAG scenarios belong to `P1-4.3-V1` and have not been
implemented or claimed as verified. See
[`airflow_spark_runtime_reassessment_20260818.md`](../../docs/implementation/airflow_spark_runtime_reassessment_20260818.md).
