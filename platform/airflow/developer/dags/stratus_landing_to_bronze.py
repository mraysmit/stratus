"""Detect one dated landing object, ingest it to bronze, then record bronze quality.

This DAG deliberately separates object availability from compute. The sensor reschedules instead
of occupying a worker, the Airflow run ID becomes both the ingestion batch and telemetry
correlation ID, and the quality job always records its result for the later promotion gate.
"""

import base64
import json
from datetime import datetime, timedelta, timezone

from airflow import DAG
from airflow.providers.amazon.aws.sensors.s3 import S3KeySensor

from stratus_alerts import stratus_failure_alert
from stratus_common import spark_submit_task

DAG_ID = "stratus_landing_to_bronze"
LANDING_CONNECTION_ID = "stratus_landing"
LANDING_BUCKET_VARIABLE = "stratus_landing_bucket"
LANDING_OBJECT_KEY = (
    "{{ dag_run.conf.get(\"landing_object_key\", "
    "\"customers/\" ~ ds ~ \"/customers.csv\") }}"
)
LANDING_BUCKET = (
    "{{ dag_run.conf.get(\"landing_bucket\", "
    "var.value.stratus_landing_bucket) }}"
)
LANDING_OBJECT_URI = (
    "s3a://{{ dag_run.conf.get(\"landing_bucket\", "
    "var.value.stratus_landing_bucket) }}/"
    "{{ dag_run.conf.get(\"landing_object_key\", "
    "\"customers/\" ~ ds ~ \"/customers.csv\") }}"
)
BRONZE_TABLE = "stratus.bronze.customers"
TARGET_TABLE = "{{ dag_run.conf.get(\"bronze_table\", \"" + BRONZE_TABLE + "\") }}"
INGESTION_CLASS = "dev.stratus.jobs.spark.IngestionJob"
QUALITY_CLASS = "dev.stratus.jobs.spark.QualityCheckJob"
PIPELINE_RUN_ID = "{{ dag_run.conf.get(\"pipeline_run_id\", run_id) }}"

QUALITY_CHECKS = [
    {"type": "row_count_min", "severity": "blocking", "threshold": 1},
]
QUALITY_CHECKS_BASE64 = base64.b64encode(
    json.dumps(QUALITY_CHECKS, separators=(",", ":"), sort_keys=True).encode("utf-8")
).decode("ascii")

DEFAULT_ARGS = {
    "owner": "platform",
    "retries": 2,
    "retry_delay": timedelta(minutes=5),
}

with DAG(
    dag_id="stratus_landing_to_bronze",
    description="Ingest the dated customer landing object and record bronze quality",
    default_args=DEFAULT_ARGS,
    start_date=datetime(2026, 1, 1, tzinfo=timezone.utc),
    schedule="*/15 * * * *",
    catchup=False,
    max_active_runs=1,
    tags=["stratus", "ingestion", "bronze"],
) as dag:
    wait_for_source_file = S3KeySensor(
        task_id="wait_for_source_file",
        bucket_key=LANDING_OBJECT_KEY,
        bucket_name=LANDING_BUCKET,
        aws_conn_id=LANDING_CONNECTION_ID,
        poke_interval=60,
        timeout=600,
        mode="reschedule",
        on_failure_callback=stratus_failure_alert,
    )

    run_ingestion = spark_submit_task(
        task_id="run_ingestion",
        java_class=INGESTION_CLASS,
        application_args=[
            "--sourceFile", LANDING_OBJECT_URI,
            "--targetTable", TARGET_TABLE,
            "--sourceSystem", "airflow",
            "--batchId", PIPELINE_RUN_ID,
            "--runId", PIPELINE_RUN_ID,
        ],
        on_failure_callback=stratus_failure_alert,
    )

    run_bronze_quality = spark_submit_task(
        task_id="run_bronze_quality",
        java_class=QUALITY_CLASS,
        application_args=[
            "--targetTable", TARGET_TABLE,
            "--runId", PIPELINE_RUN_ID,
            "--pipelineRunId", PIPELINE_RUN_ID,
            "--checksBase64", QUALITY_CHECKS_BASE64,
        ],
        on_failure_callback=stratus_failure_alert,
    )

    wait_for_source_file >> run_ingestion >> run_bronze_quality
