"""Development-only proof of Airflow's DAG-level Deadline Alert behavior.

The test overlay mounts only this DAG. Its two accepted durations are deliberately bounded: one
finishes comfortably inside the deadline, while the other remains active long enough for the
scheduler and triggerer to exercise the real missed-deadline callback path.
"""

import logging
import time
from datetime import datetime, timedelta, timezone
from typing import Any

from airflow.sdk import AsyncCallback, DAG, DeadlineAlert, DeadlineReference
from airflow.sdk.bases.operator import BaseOperator
from airflow.sdk.exceptions import AirflowException

DAG_ID = "stratus_deadline_alert_probe"
TASK_ID = "exercise_deadline_contract"
DEADLINE_NAME = "stratus-development-dag-deadline"
DEADLINE_INTERVAL = timedelta(seconds=12)
ON_TIME_SLEEP_SECONDS = 1
MISSED_SLEEP_SECONDS = 18
ALLOWED_SLEEP_SECONDS = {ON_TIME_SLEEP_SECONDS, MISSED_SLEEP_SECONDS}
LOGGER = logging.getLogger("stratus.airflow.deadline_alert_probe")


class DeadlineAlertProbeOperator(BaseOperator):
    """Run for one of two bounded durations selected by the live development proof."""

    template_fields = ("sleep_seconds", "correlation_id", "mode")

    def __init__(
        self, *, sleep_seconds: str, correlation_id: str, mode: str, **kwargs: Any
    ) -> None:
        super().__init__(**kwargs)
        self.sleep_seconds = sleep_seconds
        self.correlation_id = correlation_id
        self.mode = mode

    def execute(self, context: dict[str, Any]) -> None:
        dag_run = context["dag_run"]
        sleep_seconds = int(self.sleep_seconds)
        if sleep_seconds not in ALLOWED_SLEEP_SECONDS:
            raise AirflowException("unsupported deadline probe duration")
        LOGGER.info(
            "event=airflow_deadline_alert_probe_started correlationId=%s mode=%s "
            "sleepSeconds=%s dagId=%s taskId=%s runId=%s",
            self.correlation_id,
            self.mode,
            sleep_seconds,
            dag_run.dag_id,
            self.task_id,
            dag_run.run_id,
        )
        time.sleep(sleep_seconds)
        LOGGER.info(
            "event=airflow_deadline_alert_probe_completed correlationId=%s mode=%s "
            "sleepSeconds=%s runId=%s",
            self.correlation_id,
            self.mode,
            sleep_seconds,
            dag_run.run_id,
        )


with DAG(
    dag_id=DAG_ID,
    start_date=datetime(2026, 1, 1, tzinfo=timezone.utc),
    schedule=None,
    catchup=False,
    max_active_runs=1,
    deadline=DeadlineAlert(
        reference=DeadlineReference.DAGRUN_QUEUED_AT,
        interval=DEADLINE_INTERVAL,
        callback=AsyncCallback(
            "stratus_alerts.stratus_deadline_alert",
            kwargs={
                "deadline_name": DEADLINE_NAME,
                "expected_interval_ms": int(DEADLINE_INTERVAL.total_seconds() * 1000),
            },
        ),
        name=DEADLINE_NAME,
    ),
    tags=["stratus", "development-verification", "deadline-alert"],
) as dag:
    DeadlineAlertProbeOperator(
        task_id=TASK_ID,
        sleep_seconds='{{ dag_run.conf.get("sleep_seconds", 0) }}',
        correlation_id='{{ dag_run.conf.get("correlation_id", "unscoped") }}',
        mode='{{ dag_run.conf.get("mode", "unsupported") }}',
    )
