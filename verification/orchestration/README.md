# Orchestration Verification

Verifies that Apache Airflow is deployed with its PostgreSQL metadata database, that DAGs are registered and schedulable, and that the batch pipeline runs end to end without manual intervention. Verification covers the ingestion, bronze-to-silver, silver-to-gold, and maintenance DAGs; enforcement of the quality promotion gate as a DAG task that halts downstream work on failure; retry behaviour on transient failures; and alert emission on permanent failure and SLA breach. A deliberately failed quality check must halt the DAG and leave downstream tasks unexecuted.

Prerequisite: `compute` verification passed against a live cluster.

Implementation status (2026-08-16): the shared Airflow image baseline now has
hash/digest locks plus build, provider-import smoke, and daemon-isolated scan
scripts under `platform/airflow/image/`. The hardened image has zero Critical
findings; remaining High findings are tracked for remediation or waiver before
the shared baseline is accepted. This directory remains a placeholder:
the executable Java verifier and DAG scenarios belong to `P1-4.3-V1` and have
not been implemented or claimed as verified.
