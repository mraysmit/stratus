"""Quality-gate a silver customer table before rebuilding its gold summary.

Airflow exposes the silver promotion decision as a distinct task. MaterialisationJob receives the
same quality run and re-evaluates the evidence before replacing gold as a defence-in-depth check.
A successful rebuild is followed by independently persisted gold quality checks.
"""

import base64
import json
import os
from datetime import datetime, timedelta, timezone

from airflow import DAG

from stratus_alerts import stratus_failure_alert
from stratus_common import catalog_promotion_gate_task, spark_submit_task

DAG_ID = "stratus_silver_to_gold"
SILVER_TABLE = "stratus.silver.customers"
GOLD_TABLE = "stratus.gold.customer_summary"
SOURCE_TABLE = "{{ dag_run.conf.get(\"silver_table\", \"" + SILVER_TABLE + "\") }}"
TARGET_TABLE = "{{ dag_run.conf.get(\"gold_table\", \"" + GOLD_TABLE + "\") }}"
QUALITY_RUN_ID = "{{ dag_run.conf.get(\"quality_run_id\", run_id) }}"
PIPELINE_RUN_ID = "{{ dag_run.conf.get(\"pipeline_run_id\", run_id) }}"
QUALITY_CLASS = "dev.stratus.jobs.spark.QualityCheckJob"
MATERIALISATION_CLASS = "dev.stratus.jobs.spark.MaterialisationJob"
DEFAULT_RETRIES = 2
RETRIES_ENVIRONMENT_VARIABLE = "STRATUS_SILVER_TO_GOLD_RETRIES"


def configured_retries() -> int:
    """Keep normal retries while allowing the expected-failure live proof to finish promptly."""
    retries = int(os.environ.get(RETRIES_ENVIRONMENT_VARIABLE, str(DEFAULT_RETRIES)))
    if retries < 0:
        raise ValueError(f"{RETRIES_ENVIRONMENT_VARIABLE} must be zero or greater")
    return retries


SILVER_QUALITY_CHECKS = [
    {"name": "silver_has_rows_for_gold", "type": "row_count_min",
     "severity": "blocking", "minRows": 1},
    {"name": "silver_customer_is_unique_for_gold", "type": "uniqueness",
     "severity": "blocking", "columns": ["customer_id"]},
]
GOLD_QUALITY_CHECKS = [
    {"name": "gold_has_rows", "type": "row_count_min",
     "severity": "blocking", "minRows": 1},
    {"name": "gold_country_is_unique", "type": "uniqueness",
     "severity": "blocking", "columns": ["country"]},
]


def encode_checks(checks: list[dict]) -> str:
    """Encode a stable rule document for the packaged quality job."""
    return base64.b64encode(
        json.dumps(checks, separators=(",", ":"), sort_keys=True).encode("utf-8")
    ).decode("ascii")


SILVER_QUALITY_CHECKS_BASE64 = encode_checks(SILVER_QUALITY_CHECKS)
GOLD_QUALITY_CHECKS_BASE64 = encode_checks(GOLD_QUALITY_CHECKS)
GOLD_SQL = "SELECT country, COUNT(*) AS customer_count FROM " + SOURCE_TABLE + " GROUP BY country"

DEFAULT_ARGS = {
    "owner": "platform",
    "retries": configured_retries(),
    "retry_delay": timedelta(minutes=5),
}

with DAG(
    dag_id="stratus_silver_to_gold",
    description="Rebuild a country customer summary from quality-approved silver data",
    default_args=DEFAULT_ARGS,
    start_date=datetime(2026, 1, 1, tzinfo=timezone.utc),
    schedule=None,
    catchup=False,
    max_active_runs=1,
    tags=["stratus", "materialisation", "silver", "gold"],
) as dag:
    run_silver_quality = spark_submit_task(
        task_id="run_silver_quality_for_gold",
        java_class=QUALITY_CLASS,
        application_args=[
            "--targetTable", SOURCE_TABLE,
            "--runId", QUALITY_RUN_ID,
            "--pipelineRunId", PIPELINE_RUN_ID,
            "--checksBase64", SILVER_QUALITY_CHECKS_BASE64,
        ],
        on_failure_callback=stratus_failure_alert,
    )

    evaluate_silver_promotion = catalog_promotion_gate_task(
        task_id="evaluate_silver_promotion",
        run_id=QUALITY_RUN_ID,
        target_table=SOURCE_TABLE,
        on_failure_callback=stratus_failure_alert,
    )

    run_gold_materialisation = spark_submit_task(
        task_id="run_gold_materialisation",
        java_class=MATERIALISATION_CLASS,
        application_args=[
            "--sourceTables", SOURCE_TABLE,
            "--targetTable", TARGET_TABLE,
            "--sql", GOLD_SQL,
            "--qualityRunId", QUALITY_RUN_ID,
            "--runId", PIPELINE_RUN_ID,
        ],
        on_failure_callback=stratus_failure_alert,
    )

    run_gold_quality = spark_submit_task(
        task_id="run_gold_quality",
        java_class=QUALITY_CLASS,
        application_args=[
            "--targetTable", TARGET_TABLE,
            "--runId", PIPELINE_RUN_ID,
            "--pipelineRunId", PIPELINE_RUN_ID,
            "--checksBase64", GOLD_QUALITY_CHECKS_BASE64,
        ],
        on_failure_callback=stratus_failure_alert,
    )

    run_silver_quality >> evaluate_silver_promotion >> run_gold_materialisation >> run_gold_quality
