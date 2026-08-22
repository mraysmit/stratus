#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-22
source "$(dirname "$0")/../lib/airflow-spark-common.sh"

mkdir -p "$HARNESS_DIR/evidence"
suite_run_id="airflow-spark-$(date -u +%Y%m%dT%H%M%SZ)"
evidence_file="$HARNESS_DIR/evidence/${suite_run_id}.log"
started_ms="$(date +%s%3N)"
export STRATUS_RUN_ID="$suite_run_id"
export STRATUS_LOG_LEVEL="${STRATUS_LOG_LEVEL:-DEBUG}"
exec > >(tee "$evidence_file") 2>&1

phase_complete() {
  local phase="$1" phase_started_ms="$2"
  log "event=airflow_spark_phase_completed suiteRunId=$suite_run_id phase=$phase status=SUCCESS elapsedMs=$(( $(date +%s%3N) - phase_started_ms ))"
}

assert_not_logged() {
  local value="$1" label="$2"
  [[ -z "$value" ]] && return 0
  ! grep -Fq -- "$value" "$evidence_file" \
    || fail "Secret-redaction check failed for $label"
}

log "event=airflow_spark_suite_started suiteRunId=$suite_run_id logLevel=$STRATUS_LOG_LEVEL"
require_spark_cluster

phase_started_ms="$(date +%s%3N)"
bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-startup.sh"
phase_complete "airflow_startup" "$phase_started_ms"
trap 'bash "$HARNESS_DIR/scripts/lifecycle/airflow-compose-shutdown.sh"' EXIT

phase_started_ms="$(date +%s%3N)"
compose exec -T airflow-scheduler airflow connections delete spark_default >/dev/null 2>&1 || true
compose exec -T airflow-scheduler airflow connections add spark_default \
  --conn-type spark --conn-host spark://spark-master.stratus.local --conn-port 7077
compose exec -T airflow-scheduler airflow connections get spark_default >/dev/null
phase_complete "connection_bootstrap" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
host_dag_hash="$(sha256sum "$HARNESS_DIR/dags/stratus_spark_submission_probe.py" | awk '{print $1}')"
container_dag_hash="$(compose exec -T airflow-scheduler \
  sha256sum /opt/airflow/dags/stratus_spark_submission_probe.py | awk '{print $1}' | tr -d '\r')"
[[ "$host_dag_hash" == "$container_dag_hash" ]] \
  || fail "Mounted DAG digest differs from the tracked source"
compose exec -T airflow-scheduler test -r /opt/stratus/spark-conf/spark-defaults.conf
compose exec -T airflow-scheduler test -r /opt/stratus/certs/stratus-truststore.jks
compose exec -T airflow-scheduler test -r /opt/stratus/jobs/stratus-spark-jobs.jar
compose exec -T airflow-scheduler test -r /opt/stratus/runtime/stratus-iceberg-aws-runtime.jar
runtime_hash="$(sha256sum "${iceberg_runtime_candidates[0]}" | awk '{print $1}')"
grep -Fq "$runtime_hash  $(basename "${iceberg_runtime_candidates[0]}")" \
  "$REPO_DIR/platform/spark/image/artifact-lock.txt" \
  || fail "Mounted Iceberg/AWS runtime does not match artifact-lock.txt"
compose exec -T airflow-scheduler mkdir -p /opt/airflow/logs/spark-events
compose exec -T airflow-scheduler test -w /opt/airflow/logs/spark-events
phase_complete "immutable_inputs" "$phase_started_ms"

phase_started_ms="$(date +%s%3N)"
logical_date="$(date -u +%Y-%m-%dT%H:%M:%S+00:00)"
compose exec -T airflow-scheduler airflow dags test \
  stratus_spark_submission_probe "$logical_date"
phase_complete "spark_submission" "$phase_started_ms"

grep -Fq "SPARK SUBMISSION PROBE COMPLETE" "$evidence_file" \
  || fail "The packaged Spark probe completion marker is absent"
load_environment_file
assert_not_logged "$AIRFLOW_DB_PASSWORD" "Airflow database password"
assert_not_logged "$AIRFLOW_FERNET_KEY" "Airflow Fernet key"
assert_not_logged "$AIRFLOW_JWT_SECRET" "Airflow JWT secret"
assert_not_logged "$AIRFLOW_API_SECRET_KEY" "Airflow API secret"
assert_not_logged "$AIRFLOW_SPARK_RGW_SECRET_KEY" "Spark RGW secret key"

log "event=airflow_spark_suite_completed suiteRunId=$suite_run_id status=SUCCESS elapsedMs=$(( $(date +%s%3N) - started_ms )) evidence=$evidence_file"
