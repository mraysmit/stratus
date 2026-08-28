"""Shared immutable Spark submission contract for Stratus development DAGs.

Endpoint, OAuth, object-store, and trust settings intentionally do not live in DAG source. Airflow
resolves the Spark master through ``spark_default`` and the mounted Spark defaults resolve the
already-accepted Polaris/Ceph binding. Keeping one operator factory also prevents pipeline DAGs
from drifting away from the submission probe that proved this runtime boundary.
"""

import os
from collections.abc import Callable, Sequence
from typing import Any

from airflow.providers.standard.operators.bash import BashOperator
from airflow.providers.apache.spark.operators.spark_submit import SparkSubmitOperator

SPARK_CONNECTION_ID = "spark_default"
SPARK_JOBS_JAR = "/opt/stratus/jobs/stratus-spark-jobs.jar"
SPARK_RUNTIME_JAR = "/opt/stratus/runtime/stratus-iceberg-aws-runtime.jar"
HADOOP_AWS_JAR = "/opt/stratus/runtime/hadoop-aws.jar"
AWS_SDK_BUNDLE_JAR = "/opt/stratus/runtime/aws-sdk-bundle.jar"
S3_ACCELERATOR_JAR = "/opt/stratus/runtime/analyticsaccelerator-s3.jar"
AWS_BUNDLE_LOGGING_BRIDGE_JAR = "/opt/stratus/runtime/log4j-slf4j-impl.jar"
SPARK_DRIVER_EXTRA_CLASSPATH = ":".join(
    [
        SPARK_RUNTIME_JAR,
        HADOOP_AWS_JAR,
        AWS_SDK_BUNDLE_JAR,
        S3_ACCELERATOR_JAR,
        AWS_BUNDLE_LOGGING_BRIDGE_JAR,
    ]
)
SPARK_EVENT_LOG_DIRECTORY = "file:///opt/airflow/logs/spark-events"
SPARK_DRIVER_HOST = "airflow-scheduler.stratus.local"
CATALOG_PROPERTIES_FILE = "/opt/stratus/spark-conf/spark-defaults.conf"
CATALOG_PROMOTION_GATE_CLASS = "dev.stratus.jobs.spark.CatalogPromotionGateJob"
CATALOG_PROMOTION_GATE_CLASSPATH = ":".join(
    [SPARK_JOBS_JAR, SPARK_RUNTIME_JAR, "/opt/spark/jars/*"]
)


def test_isolated_schedule(schedule: str) -> str | None:
    """Disable automatic schedules only when a live-test overlay requests isolation."""
    disabled = os.getenv("STRATUS_DISABLE_DAG_SCHEDULES", "false").strip().lower()
    return None if disabled in {"1", "true", "yes"} else schedule

SPARK_SUBMISSION_CONF = {
    "spark.driver.host": SPARK_DRIVER_HOST,
    "spark.driver.bindAddress": "0.0.0.0",
    "spark.driver.extraClassPath": SPARK_DRIVER_EXTRA_CLASSPATH,
    "spark.eventLog.dir": SPARK_EVENT_LOG_DIRECTORY,
    "spark.local.dir": "/tmp/stratus-spark-local",
    "spark.cores.max": "2",
    "spark.executor.cores": "1",
}


def spark_submit_task(
    *,
    task_id: str,
    java_class: str,
    application_args: Sequence[str],
    on_failure_callback: Callable[[dict[str, Any]], None],
) -> SparkSubmitOperator:
    """Create the single supported packaged-Java submission shape for pipeline DAGs."""
    return SparkSubmitOperator(
        task_id=task_id,
        conn_id=SPARK_CONNECTION_ID,
        application=SPARK_JOBS_JAR,
        java_class=java_class,
        application_args=list(application_args),
        conf=dict(SPARK_SUBMISSION_CONF),
        on_failure_callback=on_failure_callback,
        verbose=False,
    )


def catalog_promotion_gate_task(
    *,
    task_id: str,
    run_id: str,
    target_table: str,
    on_failure_callback: Callable[[dict[str, Any]], None],
) -> BashOperator:
    """Evaluate quality evidence without starting a Spark application."""
    return BashOperator(
        task_id=task_id,
        bash_command="""
set -euo pipefail
exec java \
  -Djavax.net.ssl.trustStore=/opt/stratus/certs/stratus-truststore.jks \
  -cp "$STRATUS_GATE_CLASSPATH" \
  "$STRATUS_GATE_CLASS" \
  --runId "$STRATUS_GATE_RUN_ID" \
  --targetTable "$STRATUS_GATE_TARGET_TABLE" \
  --catalogProperties "$STRATUS_GATE_CATALOG_PROPERTIES"
""".strip(),
        env={
            "STRATUS_GATE_CLASS": CATALOG_PROMOTION_GATE_CLASS,
            "STRATUS_GATE_CLASSPATH": CATALOG_PROMOTION_GATE_CLASSPATH,
            "STRATUS_GATE_RUN_ID": run_id,
            "STRATUS_GATE_TARGET_TABLE": target_table,
            "STRATUS_GATE_CATALOG_PROPERTIES": CATALOG_PROPERTIES_FILE,
        },
        append_env=True,
        do_xcom_push=False,
        on_failure_callback=on_failure_callback,
    )
