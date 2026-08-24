"""Structured, secret-minimal development retry, failure, and deadline events.

Task callbacks write retry and terminal-failure records to the task log; the asynchronous Deadline
Alert callback writes missed timing expectations to the triggerer log. This makes the development
behavior observable before an external alert sink is selected. A later approved sink may route the
same fields, but it must not add credentials or arbitrary exception text to these records.
"""

import logging
from datetime import datetime, timezone
from typing import Any

LOGGER = logging.getLogger("stratus.airflow.alerts")
UNAVAILABLE = "unavailable"


def _render(value: Any) -> str:
    """Return a stable printable value without inspecting arbitrary objects."""
    return UNAVAILABLE if value is None else str(value).replace("\n", "_").replace("\r", "_")


def _duration_ms(task_instance: Any) -> str:
    """Render Airflow's task duration in milliseconds without failing the callback."""
    duration_seconds = getattr(task_instance, "duration", None)
    if duration_seconds is None:
        start_date = getattr(task_instance, "start_date", None)
        if start_date is not None:
            if start_date.tzinfo is None:
                start_date = start_date.replace(tzinfo=timezone.utc)
            duration_seconds = (datetime.now(timezone.utc) - start_date).total_seconds()
    if duration_seconds is None:
        return UNAVAILABLE
    try:
        return str(max(0, round(float(duration_seconds) * 1000)))
    except (TypeError, ValueError):
        return UNAVAILABLE


def _parse_datetime(value: Any) -> datetime | None:
    """Parse only the datetime shapes supplied by Airflow's Deadline context."""
    if isinstance(value, datetime):
        parsed = value
    elif isinstance(value, str):
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return None
    else:
        return None
    return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed


def _elapsed_ms(start: datetime | None, end: datetime) -> str:
    """Return a non-negative millisecond duration or the stable unavailable marker."""
    if start is None:
        return UNAVAILABLE
    return str(max(0, round((end - start).total_seconds() * 1000)))


async def stratus_deadline_alert(
    *, context: dict[str, Any], deadline_name: str, expected_interval_ms: int
) -> None:
    """Record one secret-minimal DAG deadline miss from Airflow's triggerer."""
    dag_run = context.get("dag_run") or {}
    deadline = context.get("deadline") or {}
    conf = dag_run.get("conf") or {}
    now = datetime.now(timezone.utc)
    queued_at = _parse_datetime(dag_run.get("queued_at"))
    deadline_time = _parse_datetime(deadline.get("deadline_time"))
    correlation_id = conf.get("correlation_id") if isinstance(conf, dict) else None
    LOGGER.error(
        "event=airflow_deadline_missed dag_id=%s run_id=%s correlation_id=%s "
        "deadline_name=%s deadline_time=%s queued_at=%s expected_interval_ms=%s "
        "observed_elapsed_ms=%s breach_ms=%s",
        _render(dag_run.get("dag_id")),
        _render(dag_run.get("dag_run_id")),
        _render(correlation_id),
        _render(deadline_name),
        _render(deadline.get("deadline_time")),
        _render(dag_run.get("queued_at")),
        _render(expected_interval_ms),
        _elapsed_ms(queued_at, now),
        _elapsed_ms(deadline_time, now),
    )


def stratus_retry_alert(context: dict[str, Any]) -> None:
    """Record an intermediate failed attempt that Airflow will retry."""
    task_instance = context.get("task_instance")
    dag_run = context.get("dag_run")
    LOGGER.warning(
        "event=airflow_task_retry dag_id=%s task_id=%s run_id=%s logical_date=%s "
        "try_number=%s log_url=%s duration_ms=%s exception_class=%s",
        _render(getattr(task_instance, "dag_id", None)),
        _render(getattr(task_instance, "task_id", None)),
        _render(getattr(dag_run, "run_id", context.get("run_id"))),
        _render(context.get("logical_date")),
        _render(getattr(task_instance, "try_number", None)),
        _render(getattr(task_instance, "log_url", None)),
        _duration_ms(task_instance),
        _render(type(context.get("exception")).__name__ if context.get("exception") else None),
    )


def stratus_failure_alert(context: dict[str, Any]) -> None:
    """Emit the minimum diagnostic fields required by the Increment 4 alert contract."""
    task_instance = context.get("task_instance")
    dag_run = context.get("dag_run")
    LOGGER.error(
        "event=airflow_task_failed dag_id=%s task_id=%s run_id=%s logical_date=%s "
        "try_number=%s log_url=%s duration_ms=%s exception_class=%s",
        _render(getattr(task_instance, "dag_id", None)),
        _render(getattr(task_instance, "task_id", None)),
        _render(getattr(dag_run, "run_id", context.get("run_id"))),
        _render(context.get("logical_date")),
        _render(getattr(task_instance, "try_number", None)),
        _render(getattr(task_instance, "log_url", None)),
        _duration_ms(task_instance),
        _render(type(context.get("exception")).__name__ if context.get("exception") else None),
    )
