#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-22
source "$(dirname "${BASH_SOURCE[0]}")/airflow-compose-common.sh"

SPARK_HARNESS_DIR="$REPO_DIR/platform/spark/compose-cluster"
CEPH_HARNESS_DIR="$REPO_DIR/platform/ceph/compose-cluster"
POLARIS_HARNESS_DIR="$REPO_DIR/platform/polaris/compose-service"
OPENBAO_HARNESS_DIR="$REPO_DIR/platform/openbao/compose-service"

[[ -f "$CEPH_HARNESS_DIR/connection.env" ]] \
  || fail "Ceph connection settings are absent; start the Ceph harness first"
[[ -f "$POLARIS_HARNESS_DIR/connection.env" ]] \
  || fail "Polaris connection settings are absent; start the Polaris harness first"

set -a
# shellcheck disable=SC1091
source <(sed 's/\r$//' "$CEPH_HARNESS_DIR/connection.env")
# shellcheck disable=SC1091
source <(sed 's/\r$//' "$POLARIS_HARNESS_DIR/connection.env")
set +a

: "${CEPH_HARNESS_NETWORK:?Ceph connection settings must publish CEPH_HARNESS_NETWORK}"

AIRFLOW_COMPOSE_OVERLAY="$HARNESS_DIR/compose.spark.yaml"
AIRFLOW_SPARK_DEFAULTS_FILE="$SPARK_HARNESS_DIR/config/spark-defaults.conf"
AIRFLOW_SPARK_LOG4J_FILE="$SPARK_HARNESS_DIR/config/log4j2.properties"
AIRFLOW_SPARK_TRUSTSTORE_FILE="$SPARK_HARNESS_DIR/certs/stratus-truststore.jks"
AIRFLOW_CEPH_CA_CERT="$CEPH_HARNESS_DIR/$CEPH_HARNESS_CA_CERT"
AIRFLOW_SPARK_JOBS_JAR="$REPO_DIR/jobs/spark/target/stratus-spark-jobs-1.0-SNAPSHOT.jar"
AIRFLOW_TEST_SCRIPTS_DIR="$HARNESS_DIR/scripts/tests"
iceberg_runtime_candidates=(
  "$REPO_DIR"/platform/spark/image/jars/stratus-iceberg-aws-runtime-*-runtime.jar
)
[[ ${#iceberg_runtime_candidates[@]} -eq 1 && -f "${iceberg_runtime_candidates[0]}" ]] \
  || fail "Expected exactly one resolved Stratus Iceberg/AWS runtime JAR"
AIRFLOW_ICEBERG_RUNTIME_JAR="${iceberg_runtime_candidates[0]}"

windows_mount_path() {
  local path="$1"
  if [[ -n "${MSYSTEM:-}" ]] && command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$path"
  else
    printf '%s' "$path"
  fi
}

AIRFLOW_SPARK_DEFAULTS_FILE="$(windows_mount_path "$AIRFLOW_SPARK_DEFAULTS_FILE")"
AIRFLOW_SPARK_LOG4J_FILE="$(windows_mount_path "$AIRFLOW_SPARK_LOG4J_FILE")"
AIRFLOW_SPARK_TRUSTSTORE_FILE="$(windows_mount_path "$AIRFLOW_SPARK_TRUSTSTORE_FILE")"
AIRFLOW_CEPH_CA_CERT="$(windows_mount_path "$AIRFLOW_CEPH_CA_CERT")"
AIRFLOW_SPARK_JOBS_JAR="$(windows_mount_path "$AIRFLOW_SPARK_JOBS_JAR")"
AIRFLOW_TEST_SCRIPTS_DIR="$(windows_mount_path "$AIRFLOW_TEST_SCRIPTS_DIR")"
AIRFLOW_ICEBERG_RUNTIME_JAR="$(windows_mount_path "$AIRFLOW_ICEBERG_RUNTIME_JAR")"
export AIRFLOW_COMPOSE_OVERLAY AIRFLOW_SPARK_DEFAULTS_FILE AIRFLOW_SPARK_LOG4J_FILE
export AIRFLOW_SPARK_TRUSTSTORE_FILE AIRFLOW_SPARK_JOBS_JAR CEPH_HARNESS_NETWORK
export AIRFLOW_ICEBERG_RUNTIME_JAR AIRFLOW_CEPH_CA_CERT CEPH_RGW_ENDPOINT
export AIRFLOW_TEST_SCRIPTS_DIR

fetch_spark_storage_identity() {
  [[ -f "$OPENBAO_HARNESS_DIR/connection.env" ]] \
    || fail "OpenBao connection settings are absent; start OpenBao first"
  set -a
  # shellcheck disable=SC1091
  source <(sed 's/\r$//' "$OPENBAO_HARNESS_DIR/connection.env")
  set +a
  local token_file="$OPENBAO_HARNESS_DIR/$OPENBAO_TOKEN_FILE"
  [[ -r "$token_file" ]] || fail "The OpenBao development token is absent; restart OpenBao"
  local response
  response="$(curl --silent --show-error --max-time 10 \
    -H "X-Vault-Token: $(cat "$token_file")" \
    "$OPENBAO_ENDPOINT/v1/$OPENBAO_KV_MOUNT/data/$OPENBAO_SERVICE_IDENTITY_PATH/svc-spark")"
  AIRFLOW_SPARK_RGW_ACCESS_KEY="$(printf '%s' "$response" \
    | sed -nE 's/.*"access_key" *: *"([^"]+)".*/\1/p')"
  AIRFLOW_SPARK_RGW_SECRET_KEY="$(printf '%s' "$response" \
    | sed -nE 's/.*"secret_key" *: *"([^"]+)".*/\1/p')"
  [[ -n "$AIRFLOW_SPARK_RGW_ACCESS_KEY" && -n "$AIRFLOW_SPARK_RGW_SECRET_KEY" ]] \
    || fail "The svc-spark object-store identity is absent from OpenBao"
  export AIRFLOW_SPARK_RGW_ACCESS_KEY AIRFLOW_SPARK_RGW_SECRET_KEY
  log "Fetched the svc-spark object-store identity from OpenBao for the Airflow driver"
}

fetch_airflow_storage_identity() {
  [[ -f "$OPENBAO_HARNESS_DIR/connection.env" ]] \
    || fail "OpenBao connection settings are absent; start OpenBao first"
  set -a
  # shellcheck disable=SC1091
  source <(sed 's/\r$//' "$OPENBAO_HARNESS_DIR/connection.env")
  set +a
  local token_file="$OPENBAO_HARNESS_DIR/$OPENBAO_TOKEN_FILE"
  [[ -r "$token_file" ]] || fail "The OpenBao development token is absent; restart OpenBao"
  local response
  response="$(curl --silent --show-error --max-time 10 \
    -H "X-Vault-Token: $(cat "$token_file")" \
    "$OPENBAO_ENDPOINT/v1/$OPENBAO_KV_MOUNT/data/$OPENBAO_SERVICE_IDENTITY_PATH/svc-airflow")"
  AIRFLOW_LANDING_RGW_ACCESS_KEY="$(printf '%s' "$response" \
    | sed -nE 's/.*"access_key" *: *"([^"]+)".*/\1/p')"
  AIRFLOW_LANDING_RGW_SECRET_KEY="$(printf '%s' "$response" \
    | sed -nE 's/.*"secret_key" *: *"([^"]+)".*/\1/p')"
  [[ -n "$AIRFLOW_LANDING_RGW_ACCESS_KEY" && -n "$AIRFLOW_LANDING_RGW_SECRET_KEY" ]] \
    || fail "The svc-airflow object-store identity is absent from OpenBao"
  export AIRFLOW_LANDING_RGW_ACCESS_KEY AIRFLOW_LANDING_RGW_SECRET_KEY
  log "Fetched the read-only svc-airflow landing identity from OpenBao"
}

require_spark_cluster() {
  fetch_spark_storage_identity
  fetch_airflow_storage_identity
  "$(compose_runtime)" network inspect "$CEPH_HARNESS_NETWORK" >/dev/null 2>&1 \
    || fail "The provider network is absent; start Ceph, Polaris, OpenBao, and Spark with their lifecycle scripts"
  "$(compose_runtime)" ps --filter name=stratus-spark-local-spark-master \
    --filter status=running --format '{{.Names}}' | grep -q spark-master \
    || fail "The Spark developer cluster is not running; use its checked-in startup script"
  for required_file in \
      "$SPARK_HARNESS_DIR/config/spark-defaults.conf" \
      "$SPARK_HARNESS_DIR/config/log4j2.properties" \
      "$SPARK_HARNESS_DIR/certs/stratus-truststore.jks" \
      "${iceberg_runtime_candidates[0]}" \
      "$REPO_DIR/jobs/spark/target/stratus-spark-jobs-1.0-SNAPSHOT.jar"; do
    [[ -r "$required_file" ]] || fail "Required Spark submission input is absent: $required_file"
  done
}
