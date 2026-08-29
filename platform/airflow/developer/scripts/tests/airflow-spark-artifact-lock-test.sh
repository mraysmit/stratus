#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-29
# Purpose: fail fast when the generated Spark JAR set no longer matches its reviewed lock.
#
# Airflow mounts these host-side JARs into its scheduler and uses them as the Spark driver
# classpath. The JAR directory is intentionally ignored because it is generated and hundreds of
# megabytes in size; artifact-lock.txt is tracked so reviewers can approve the exact inputs. A
# checkout can therefore retain stale generated JARs even though the source and lock have moved.
# This test validates the complete filename set and every SHA-256 before the expensive reactor,
# image scan, or provider startup. The Spark submission test invokes it again immediately before
# use so a file changed during a long acceptance run also fails closed.

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
readonly REPOSITORY_DIR="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
readonly SPARK_IMAGE_DIR="$REPOSITORY_DIR/platform/spark/image"
readonly SPARK_JAR_DIR="$SPARK_IMAGE_DIR/jars"
readonly SPARK_ARTIFACT_LOCK="$SPARK_IMAGE_DIR/artifact-lock.txt"
readonly RUN_ID="airflow-spark-artifact-lock-$(date -u +%Y%m%dT%H%M%SZ)"
readonly STARTED_MS="$(date +%s%3N)"

timestamp() {
  date -u +%Y-%m-%dT%H:%M:%S.%3NZ
}

fail() {
  printf '%s event=airflow_spark_artifact_lock_failed runId=%s status=FAILURE %s\n' \
    "$(timestamp)" "$RUN_ID" "$*" >&2
  printf '%s\n' \
    "Remediation: bash platform/spark/compose-cluster/scripts/lib/spark-compose-resolve-artifacts.sh" \
    "Then rebuild: docker build -f platform/spark/image/Dockerfile -t stratus/spark-runtime:dev platform/spark/image" >&2
  exit 1
}

[[ -r "$SPARK_ARTIFACT_LOCK" ]] || fail "reason=artifact_lock_absent path=$SPARK_ARTIFACT_LOCK"
[[ -d "$SPARK_JAR_DIR" ]] || fail "reason=artifact_directory_absent path=$SPARK_JAR_DIR"

mapfile -t locked_artifacts < <(
  sed -nE 's/^([0-9a-f]{64})  ([^[:space:]]+)$/\1 \2/p' "$SPARK_ARTIFACT_LOCK"
)
shopt -s nullglob
resolved_artifacts=("$SPARK_JAR_DIR"/*.jar)
shopt -u nullglob

(( ${#locked_artifacts[@]} > 0 )) \
  || fail "reason=artifact_lock_empty path=$SPARK_ARTIFACT_LOCK"
[[ ${#resolved_artifacts[@]} -eq ${#locked_artifacts[@]} ]] \
  || fail "reason=artifact_set_count_mismatch lockedCount=${#locked_artifacts[@]} resolvedCount=${#resolved_artifacts[@]}"

for locked_artifact in "${locked_artifacts[@]}"; do
  read -r expected_hash filename <<<"$locked_artifact"
  artifact="$SPARK_JAR_DIR/$filename"
  [[ -r "$artifact" ]] || fail "reason=locked_artifact_absent artifact=$filename"
  actual_hash="$(sha256sum "$artifact" | awk '{print $1}')"
  [[ "$actual_hash" == "$expected_hash" ]] \
    || fail "reason=artifact_digest_mismatch artifact=$filename expectedSha256=$expected_hash actualSha256=$actual_hash"
  printf '%s event=airflow_spark_artifact_verified runId=%s artifact=%s sha256=%s\n' \
    "$(timestamp)" "$RUN_ID" "$filename" "$actual_hash"
done

printf '%s event=airflow_spark_artifact_lock_completed runId=%s status=SUCCESS artifactCount=%s durationMs=%s\n' \
  "$(timestamp)" "$RUN_ID" "${#locked_artifacts[@]}" "$(( $(date +%s%3N) - STARTED_MS ))"
