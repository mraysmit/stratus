"""Promote one quality-approved bronze batch into silver and verify silver quality.

Airflow exposes the bronze promotion decision as a distinct task. TransformJob receives the same
quality run and re-evaluates the evidence before writing as a defence-in-depth check. A successful
upsert is followed by independently persisted silver checks for the next promotion boundary.
"""

import base64
import json
import os
from datetime import datetime, timedelta, timezone

from airflow import DAG

from stratus_alerts import stratus_failure_alert
from stratus_common import catalog_promotion_gate_task, spark_submit_task

DAG_ID = "stratus_bronze_to_silver"
BRONZE_TABLE = "stratus.bronze.customers"
SILVER_TABLE = "stratus.silver.customers"
SOURCE_TABLE = "{{ dag_run.conf.get(\"bronze_table\", \"" + BRONZE_TABLE + "\") }}"
TARGET_TABLE = "{{ dag_run.conf.get(\"silver_table\", \"" + SILVER_TABLE + "\") }}"
SOURCE_BATCH = "{{ dag_run.conf.get(\"source_batch\", run_id) }}"
QUALITY_RUN_ID = "{{ dag_run.conf.get(\"quality_run_id\", run_id) }}"
PIPELINE_RUN_ID = "{{ dag_run.conf.get(\"pipeline_run_id\", run_id) }}"
TRANSFORM_CLASS = "dev.stratus.jobs.spark.TransformJob"
QUALITY_CLASS = "dev.stratus.jobs.spark.QualityCheckJob"
BUSINESS_KEY = "customer_id"
SEQUENCE_COLUMN = "updated_at"
DEFAULT_RETRIES = 2
RETRIES_ENVIRONMENT_VARIABLE = "STRATUS_BRONZE_TO_SILVER_RETRIES"


def configured_retries() -> int:
    """Keep normal retries while allowing the expected-failure live proof to finish promptly."""
    retries = int(os.environ.get(RETRIES_ENVIRONMENT_VARIABLE, str(DEFAULT_RETRIES)))
    if retries < 0:
        raise ValueError(f"{RETRIES_ENVIRONMENT_VARIABLE} must be zero or greater")
    return retries

SILVER_QUALITY_CHECKS = [
    {
        "name": "silver_has_rows",
        "type": "row_count_min",
        "severity": "blocking",
        "minRows": 1,
    },
    {
        "name": "silver_customer_is_unique",
        "type": "uniqueness",
        "severity": "blocking",
        "columns": [BUSINESS_KEY],
    },
]
SILVER_QUALITY_CHECKS_BASE64 = base64.b64encode(
    json.dumps(SILVER_QUALITY_CHECKS, separators=(",", ":"), sort_keys=True).encode("utf-8")
).decode("ascii")

DEFAULT_ARGS = {
    "owner": "platform",
    "retries": configured_retries(),
    "retry_delay": timedelta(minutes=5),
}

with DAG(
    dag_id="stratus_bronze_to_silver",
    description="Promote an approved bronze customer batch and record silver quality",
    default_args=DEFAULT_ARGS,
    start_date=datetime(2026, 1, 1, tzinfo=timezone.utc),
    schedule=None,
    catchup=False,
    max_active_runs=1,
    tags=["stratus", "transform", "bronze", "silver"],
) as dag:
    evaluate_bronze_promotion = catalog_promotion_gate_task(
        task_id="evaluate_bronze_promotion",
        run_id=QUALITY_RUN_ID,
        target_table=SOURCE_TABLE,
        on_failure_callback=stratus_failure_alert,
    )

    run_silver_transform = spark_submit_task(
        task_id="run_silver_transform",
        java_class=TRANSFORM_CLASS,
        application_args=[
            "--sourceTable", SOURCE_TABLE,
            "--targetTable", TARGET_TABLE,
            "--businessKey", BUSINESS_KEY,
            "--sequenceColumn", SEQUENCE_COLUMN,
            "--sourceBatch", SOURCE_BATCH,
            "--qualityRunId", QUALITY_RUN_ID,
            "--runId", PIPELINE_RUN_ID,
        ],
        on_failure_callback=stratus_failure_alert,
    )

    run_silver_quality = spark_submit_task(
        task_id="run_silver_quality",
        java_class=QUALITY_CLASS,
        application_args=[
            "--targetTable", TARGET_TABLE,
            "--runId", PIPELINE_RUN_ID,
            "--pipelineRunId", PIPELINE_RUN_ID,
            "--checksBase64", SILVER_QUALITY_CHECKS_BASE64,
        ],
        on_failure_callback=stratus_failure_alert,
    )

    evaluate_bronze_promotion >> run_silver_transform >> run_silver_quality
