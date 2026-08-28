"""Fast positive-path probe for the developer Airflow REST API acceptance suite."""

from datetime import datetime, timezone

from airflow import DAG
from airflow.providers.standard.operators.empty import EmptyOperator


with DAG(
    dag_id="stratus_api_contract_probe",
    description="Prove API-triggered Airflow execution without starting a data-processing engine",
    start_date=datetime(2026, 1, 1, tzinfo=timezone.utc),
    schedule=None,
    catchup=False,
    tags=["stratus", "development-acceptance", "api"],
) as dag:
    complete_api_contract_probe = EmptyOperator(task_id="complete_api_contract_probe")
