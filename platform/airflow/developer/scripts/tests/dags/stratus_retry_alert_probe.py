"""Development-only proof of Airflow retry recovery and terminal failure callbacks.

The test overlay mounts this file as a fifth, temporary DAG. It uses only Airflow's persisted
attempt number: no marker file, external service, or mutable process-global state can make the
second attempt pass accidentally. The permanent mode deliberately includes detail in its raised
exception so the live test can prove the structured alert emits only the exception class.
"""

import logging
from datetime import datetime, timedelta, timezone
from typing import Any

from airflow import DAG
from airflow.exceptions import AirflowException
from airflow.sdk.bases.operator import BaseOperator

from stratus_alerts import stratus_failure_alert, stratus_retry_alert

DAG_ID = "stratus_retry_alert_probe"
TASK_ID = "exercise_retry_contract"
TRANSIENT_MODE = "transient"
PERMANENT_MODE = "permanent"
LOGGER = logging.getLogger("stratus.airflow.retry_alert_probe")


class RetryAlertProbeOperator(BaseOperator):
    """Fail from Airflow attempt state according to one explicitly selected test mode."""

    template_fields = ("mode", "correlation_id")

    def __init__(self, *, mode: str, correlation_id: str, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.mode = mode
        self.correlation_id = correlation_id

    def execute(self, context: dict[str, Any]) -> None:
        task_instance = context["task_instance"]
        dag_run = context["dag_run"]
        try_number = task_instance.try_number
        mode = self.mode
        LOGGER.info(
            "event=airflow_retry_alert_probe_attempt correlationId=%s mode=%s "
            "tryNumber=%s dagId=%s taskId=%s runId=%s",
            self.correlation_id,
            mode,
            try_number,
            dag_run.dag_id,
            task_instance.task_id,
            dag_run.run_id,
        )
        if mode == TRANSIENT_MODE and try_number == 1:
            raise AirflowException("controlled transient retry probe failure")
        if mode == PERMANENT_MODE:
            raise AirflowException("controlled_detail_must_not_enter_alert")
        if mode != TRANSIENT_MODE:
            raise AirflowException("unsupported retry probe mode")
        LOGGER.info(
            "event=airflow_retry_alert_probe_succeeded correlationId=%s mode=%s "
            "tryNumber=%s runId=%s",
            self.correlation_id,
            mode,
            try_number,
            dag_run.run_id,
        )


with DAG(
    dag_id=DAG_ID,
    start_date=datetime(2026, 1, 1, tzinfo=timezone.utc),
    schedule=None,
    catchup=False,
    max_active_runs=1,
    default_args={
        "retries": 1,
        "retry_delay": timedelta(seconds=1),
    },
    tags=["stratus", "development-verification", "retry", "alert"],
) as dag:
    RetryAlertProbeOperator(
        task_id=TASK_ID,
        mode='{{ dag_run.conf.get("mode", "unsupported") }}',
        correlation_id='{{ dag_run.conf.get("correlation_id", "unscoped") }}',
        on_retry_callback=stratus_retry_alert,
        on_failure_callback=stratus_failure_alert,
    )
