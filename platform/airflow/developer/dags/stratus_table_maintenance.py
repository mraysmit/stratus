"""Apply a versioned metadata policy to an Iceberg table through the packaged Spark job.

The DAG deliberately names no maintenance procedure. TableMaintenanceJob measures the table's
published metadata, records every observed value and threshold, and selects run or skip from the
versioned policy before delegating any selected operation to the established maintenance primitive.
"""

from datetime import datetime, timedelta, timezone

from airflow import DAG

from stratus_alerts import stratus_failure_alert
from stratus_common import spark_submit_task

DAG_ID = "stratus_table_maintenance"
DEFAULT_TABLE = "stratus.bronze.customers"
DEFAULT_POLICY = "bronze-default-v1"
TARGET_TABLE = "{{ dag_run.conf.get(\"target_table\", \"" + DEFAULT_TABLE + "\") }}"
POLICY = "{{ dag_run.conf.get(\"policy\", \"" + DEFAULT_POLICY + "\") }}"
RUN_ID = "{{ dag_run.conf.get(\"run_id\", run_id) }}"
TABLE_MAINTENANCE_CLASS = "dev.stratus.jobs.spark.TableMaintenanceJob"

DEFAULT_ARGS = {
    "owner": "platform",
    "retries": 1,
    "retry_delay": timedelta(minutes=10),
}

with DAG(
    dag_id="stratus_table_maintenance",
    description="Apply versioned metadata-table maintenance policy",
    default_args=DEFAULT_ARGS,
    start_date=datetime(2026, 1, 1, tzinfo=timezone.utc),
    schedule="@daily",
    catchup=False,
    max_active_runs=1,
    tags=["stratus", "maintenance", "iceberg"],
) as dag:
    apply_table_policy = spark_submit_task(
        task_id="apply_table_maintenance_policy",
        java_class=TABLE_MAINTENANCE_CLASS,
        application_args=[
            "--targetTable", TARGET_TABLE,
            "--policy", POLICY,
            "--runId", RUN_ID,
        ],
        on_failure_callback=stratus_failure_alert,
    )
