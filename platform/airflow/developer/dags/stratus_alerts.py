"""Structured, secret-minimal development failure events for Stratus DAGs.

The development callback writes to the task log so retry exhaustion and permanent failure are
observable before an external alert sink is selected. The later production-hardening stage may
route the same fields to an approved sink; it must not add credentials or arbitrary exception text
to this record.
"""

import logging
from typing import Any

LOGGER = logging.getLogger("stratus.airflow.alerts")
UNAVAILABLE = "unavailable"


def _render(value: Any) -> str:
    """Return a stable printable value without inspecting arbitrary objects."""
    return UNAVAILABLE if value is None else str(value).replace("\n", "_").replace("\r", "_")


def stratus_failure_alert(context: dict[str, Any]) -> None:
    """Emit the minimum diagnostic fields required by the Increment 4 alert contract."""
    task_instance = context.get("task_instance")
    dag_run = context.get("dag_run")
    LOGGER.error(
        "event=airflow_task_failed dag_id=%s task_id=%s run_id=%s logical_date=%s "
        "try_number=%s log_url=%s exception_class=%s",
        _render(getattr(task_instance, "dag_id", None)),
        _render(getattr(task_instance, "task_id", None)),
        _render(getattr(dag_run, "run_id", context.get("run_id"))),
        _render(context.get("logical_date")),
        _render(getattr(task_instance, "try_number", None)),
        _render(getattr(task_instance, "log_url", None)),
        _render(type(context.get("exception")).__name__ if context.get("exception") else None),
    )
