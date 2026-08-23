"""Shared immutable Spark submission contract for Stratus development DAGs.

Endpoint, OAuth, object-store, and trust settings intentionally do not live in DAG source. Airflow
resolves the Spark master through ``spark_default`` and the mounted Spark defaults resolve the
already-accepted Polaris/Ceph binding. Keeping one operator factory also prevents pipeline DAGs
from drifting away from the submission probe that proved this runtime boundary.
"""

from collections.abc import Callable, Sequence
from typing import Any

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
        verbose=True,
    )
