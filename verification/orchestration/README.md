# Orchestration Verification

Verifies that Apache Airflow is deployed with its PostgreSQL metadata database, that DAGs are registered and schedulable, and that the batch pipeline runs end to end without manual intervention. Verification covers the ingestion, bronze-to-silver, silver-to-gold, and maintenance DAGs; enforcement of the quality promotion gate as a DAG task that halts downstream work on failure; retry behaviour on transient failures; and alert emission on permanent failure and SLA breach. A deliberately failed quality check must halt the DAG and leave downstream tasks unexecuted.

Prerequisite: `compute` verification passed against a live cluster.

Implementation status (2026-08-17): the shared Airflow 3.3.1 image baseline has
hash/digest locks plus build, provider-import smoke, and daemon-isolated scan
scripts under `platform/airflow/image/`. Trivy 0.74.0 reported zero Critical and
84 High occurrences representing 35 unique package/CVE pairs, all in the
upstream Spark/Hadoop JAR set. The High findings remain tracked for upstream
upgrade and reachability analysis. The shared baseline is accepted for developer
use through 2026-09-16 by `WAIVER-P1-4.1-S1-20260817`; production promotion is
prohibited. This directory remains a placeholder:
the executable Java verifier and DAG scenarios belong to `P1-4.3-V1` and have
not been implemented or claimed as verified.
