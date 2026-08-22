"""Development acceptance DAG for the real Airflow-to-Spark submission boundary.

The master endpoint is intentionally absent from this immutable DAG. Airflow resolves it through
the ``spark_default`` connection that the developer acceptance harness records in the encrypted
metadata database. The mounted Spark defaults and truststore supply the already-validated
Polaris/Ceph runtime binding without duplicating provider values here.
"""

from datetime import datetime, timezone

from airflow import DAG
from airflow.providers.apache.spark.operators.spark_submit import SparkSubmitOperator

SPARK_CONNECTION_ID = "spark_default"
SPARK_JOBS_JAR = "/opt/stratus/jobs/stratus-spark-jobs.jar"
SPARK_PROBE_CLASS = "dev.stratus.jobs.spark.SparkSubmissionProbeJob"

with DAG(
    dag_id="stratus_spark_submission_probe",
    description="Submit a packaged distributed-count probe to the Stratus Spark developer cluster",
    start_date=datetime(2026, 1, 1, tzinfo=timezone.utc),
    schedule=None,
    catchup=False,
    tags=["stratus", "development-acceptance", "spark"],
) as dag:
    submit_probe = SparkSubmitOperator(
        task_id="submit_probe",
        conn_id=SPARK_CONNECTION_ID,
        application=SPARK_JOBS_JAR,
        java_class=SPARK_PROBE_CLASS,
        application_args=["--runId", "{{ run_id }}", "--expectedCount", "1000"],
        conf={
            "spark.driver.host": "airflow-scheduler.stratus.local",
            "spark.driver.bindAddress": "0.0.0.0",
            "spark.driver.extraClassPath": "/opt/stratus/runtime/stratus-iceberg-aws-runtime.jar",
            "spark.eventLog.dir": "file:///opt/airflow/logs/spark-events",
            "spark.local.dir": "/tmp/stratus-spark-local",
            "spark.cores.max": "2",
            "spark.executor.cores": "1",
        },
        verbose=True,
    )
