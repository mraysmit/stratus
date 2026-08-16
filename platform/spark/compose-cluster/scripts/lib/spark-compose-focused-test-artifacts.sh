#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-16

# Revision-bound artifact state for the focused Spark live-test path. This
# library expects spark-compose-maven-common.sh to have defined HARNESS_DIR,
# REPO_DIR, log, fail, and repository_maven.

FOCUSED_TEST_STATE_DIR="$HARNESS_DIR/private/focused-tests"
FOCUSED_TEST_STATE_FILE="$FOCUSED_TEST_STATE_DIR/artifacts.state"
FOCUSED_TEST_VERSION="1.0-SNAPSHOT"

focused_test_input_fingerprint() {
  local path object count=0
  local -a inputs=(
    pom.xml
    build-support/stratus-bom
    build-support/stratus-build-parent
    platform/spark/aws-runtime
    jobs/spark/pom.xml
    jobs/spark/src/main
  )

  {
    while IFS= read -r -d '' path; do
      count=$((count + 1))
      if [[ -f "$REPO_DIR/$path" ]]; then
        object="$(git -C "$REPO_DIR" hash-object -- "$path")"
      else
        object="MISSING"
      fi
      printf '%s\0%s\0' "$path" "$object"
    done < <(git -C "$REPO_DIR" ls-files -z --cached --others --exclude-standard -- "${inputs[@]}")
    [[ "$count" -gt 0 ]] || fail "No focused-test artifact inputs were found under $REPO_DIR"
  } | git -C "$REPO_DIR" hash-object --stdin
}

focused_test_maven_repository() {
  local repository
  if [[ -n "${STRATUS_MAVEN_LOCAL_REPOSITORY:-}" ]]; then
    repository="$STRATUS_MAVEN_LOCAL_REPOSITORY"
  else
    repository="$(cd "$REPO_DIR" && repository_maven -q help:evaluate \
      -Dexpression=settings.localRepository -DforceStdout)"
    repository="$(printf '%s\n' "$repository" | tr -d '\r' | tail -n 1)"
  fi
  [[ -n "$repository" ]] || fail "Maven did not report its local repository"
  if [[ -n "${MSYSTEM:-}" ]] && command -v cygpath >/dev/null 2>&1; then
    repository="$(cygpath -u "$repository")"
  fi
  printf '%s\n' "$repository"
}

focused_test_locate_artifacts() {
  local repository="${1:-}"
  if [[ -z "$repository" ]]; then
    repository="$(focused_test_maven_repository)"
  fi
  FOCUSED_MAVEN_REPOSITORY="$repository"
  FOCUSED_MAVEN_REPOSITORY_FINGERPRINT="$(printf '%s' "$repository" | git hash-object --stdin)"

  FOCUSED_AWS_TARGET="$REPO_DIR/platform/spark/aws-runtime/target/stratus-iceberg-aws-runtime-$FOCUSED_TEST_VERSION-runtime.jar"
  FOCUSED_JOBS_TARGET="$REPO_DIR/jobs/spark/target/stratus-spark-jobs-$FOCUSED_TEST_VERSION.jar"
  FOCUSED_REACTOR_POM="$repository/dev/stratus/stratus-reactor/$FOCUSED_TEST_VERSION/stratus-reactor-$FOCUSED_TEST_VERSION.pom"
  FOCUSED_BOM_POM="$repository/dev/stratus/stratus-bom/$FOCUSED_TEST_VERSION/stratus-bom-$FOCUSED_TEST_VERSION.pom"
  FOCUSED_PARENT_POM="$repository/dev/stratus/stratus-build-parent/$FOCUSED_TEST_VERSION/stratus-build-parent-$FOCUSED_TEST_VERSION.pom"
  FOCUSED_AWS_JAR="$repository/dev/stratus/stratus-iceberg-aws-runtime/$FOCUSED_TEST_VERSION/stratus-iceberg-aws-runtime-$FOCUSED_TEST_VERSION-runtime.jar"
  FOCUSED_AWS_POM="$repository/dev/stratus/stratus-iceberg-aws-runtime/$FOCUSED_TEST_VERSION/stratus-iceberg-aws-runtime-$FOCUSED_TEST_VERSION.pom"
  FOCUSED_JOBS_JAR="$repository/dev/stratus/stratus-spark-jobs/$FOCUSED_TEST_VERSION/stratus-spark-jobs-$FOCUSED_TEST_VERSION.jar"
  FOCUSED_JOBS_POM="$repository/dev/stratus/stratus-spark-jobs/$FOCUSED_TEST_VERSION/stratus-spark-jobs-$FOCUSED_TEST_VERSION.pom"
}

focused_test_require_file() {
  local description="$1" path="$2"
  [[ -f "$path" ]] || fail "$description is missing: $path. Prepare focused tests again: bash platform/spark/compose-cluster/scripts/tests/spark-compose-prepare-focused-tests.sh"
}

focused_test_state_value() {
  local key="$1"
  sed -n "s/^${key}=//p" "$FOCUSED_TEST_STATE_FILE" | tail -n 1
}

focused_test_record_artifacts() {
  local input_fingerprint temporary_state
  focused_test_locate_artifacts
  focused_test_require_file "AWS runtime target" "$FOCUSED_AWS_TARGET"
  focused_test_require_file "Spark jobs target" "$FOCUSED_JOBS_TARGET"
  focused_test_require_file "installed reactor POM" "$FOCUSED_REACTOR_POM"
  focused_test_require_file "installed BOM POM" "$FOCUSED_BOM_POM"
  focused_test_require_file "installed build-parent POM" "$FOCUSED_PARENT_POM"
  focused_test_require_file "installed AWS runtime" "$FOCUSED_AWS_JAR"
  focused_test_require_file "installed AWS runtime POM" "$FOCUSED_AWS_POM"
  focused_test_require_file "installed Spark jobs artifact" "$FOCUSED_JOBS_JAR"
  focused_test_require_file "installed Spark jobs POM" "$FOCUSED_JOBS_POM"

  [[ "$(git hash-object "$FOCUSED_AWS_TARGET")" == "$(git hash-object "$FOCUSED_AWS_JAR")" ]] \
    || fail "The installed AWS runtime does not match the current target output"
  [[ "$(git hash-object "$FOCUSED_JOBS_TARGET")" == "$(git hash-object "$FOCUSED_JOBS_JAR")" ]] \
    || fail "The installed Spark jobs artifact does not match the current target output"

  input_fingerprint="$(focused_test_input_fingerprint)"
  mkdir -p "$FOCUSED_TEST_STATE_DIR"
  temporary_state="$FOCUSED_TEST_STATE_FILE.tmp.$$"
  umask 077
  {
    printf 'FORMAT=2\n'
    printf 'INPUT_FINGERPRINT=%s\n' "$input_fingerprint"
    printf 'MAVEN_REPOSITORY=%s\n' "$FOCUSED_MAVEN_REPOSITORY"
    printf 'MAVEN_REPOSITORY_FINGERPRINT=%s\n' "$FOCUSED_MAVEN_REPOSITORY_FINGERPRINT"
    printf 'REACTOR_POM=%s\n' "$(git hash-object "$FOCUSED_REACTOR_POM")"
    printf 'BOM_POM=%s\n' "$(git hash-object "$FOCUSED_BOM_POM")"
    printf 'PARENT_POM=%s\n' "$(git hash-object "$FOCUSED_PARENT_POM")"
    printf 'AWS_RUNTIME=%s\n' "$(git hash-object "$FOCUSED_AWS_JAR")"
    printf 'AWS_POM=%s\n' "$(git hash-object "$FOCUSED_AWS_POM")"
    printf 'SPARK_JOBS=%s\n' "$(git hash-object "$FOCUSED_JOBS_JAR")"
    printf 'SPARK_JOBS_POM=%s\n' "$(git hash-object "$FOCUSED_JOBS_POM")"
    printf 'PREPARED_AT_UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'GIT_REVISION=%s\n' "$(git -C "$REPO_DIR" rev-parse --short=12 HEAD)"
  } > "$temporary_state"
  mv "$temporary_state" "$FOCUSED_TEST_STATE_FILE"
}

focused_test_assert_hash() {
  local state_key="$1" description="$2" path="$3" expected actual
  focused_test_require_file "$description" "$path"
  expected="$(focused_test_state_value "$state_key")"
  actual="$(git hash-object "$path")"
  [[ -n "$expected" && "$actual" == "$expected" ]] \
    || fail "$description changed after preparation. Prepare focused tests again: bash platform/spark/compose-cluster/scripts/tests/spark-compose-prepare-focused-tests.sh"
}

focused_test_validate_artifacts() {
  local current_fingerprint recorded_fingerprint recorded_repository
  [[ -f "$FOCUSED_TEST_STATE_FILE" ]] \
    || fail "Focused-test artifacts have not been prepared. Run: bash platform/spark/compose-cluster/scripts/tests/spark-compose-prepare-focused-tests.sh"
  [[ "$(focused_test_state_value FORMAT)" == "2" ]] \
    || fail "Focused-test artifact state has an unsupported format. Prepare focused tests again"

  recorded_repository="$(focused_test_state_value MAVEN_REPOSITORY)"
  [[ -n "$recorded_repository" ]] \
    || fail "Focused-test artifact state does not name its Maven repository. Prepare focused tests again"
  focused_test_locate_artifacts "$recorded_repository"
  [[ "$(focused_test_state_value MAVEN_REPOSITORY_FINGERPRINT)" == "$FOCUSED_MAVEN_REPOSITORY_FINGERPRINT" ]] \
    || fail "Maven's local repository changed after preparation. Prepare focused tests again"

  current_fingerprint="$(focused_test_input_fingerprint)"
  recorded_fingerprint="$(focused_test_state_value INPUT_FINGERPRINT)"
  [[ -n "$recorded_fingerprint" && "$current_fingerprint" == "$recorded_fingerprint" ]] \
    || fail "Focused-test artifact source inputs changed after preparation. Prepare focused tests again"

  focused_test_assert_hash REACTOR_POM "installed reactor POM" "$FOCUSED_REACTOR_POM"
  focused_test_assert_hash BOM_POM "installed BOM POM" "$FOCUSED_BOM_POM"
  focused_test_assert_hash PARENT_POM "installed build-parent POM" "$FOCUSED_PARENT_POM"
  focused_test_assert_hash AWS_RUNTIME "installed AWS runtime" "$FOCUSED_AWS_JAR"
  focused_test_assert_hash AWS_POM "installed AWS runtime POM" "$FOCUSED_AWS_POM"
  focused_test_assert_hash SPARK_JOBS "installed Spark jobs artifact" "$FOCUSED_JOBS_JAR"
  focused_test_assert_hash SPARK_JOBS_POM "installed Spark jobs POM" "$FOCUSED_JOBS_POM"
  focused_test_assert_hash AWS_RUNTIME "AWS runtime target" "$FOCUSED_AWS_TARGET"
  focused_test_assert_hash SPARK_JOBS "Spark jobs target" "$FOCUSED_JOBS_TARGET"
}

focused_test_maven_repository_argument() {
  local repository="$FOCUSED_MAVEN_REPOSITORY"
  if [[ -n "${MSYSTEM:-}" ]] && command -v cygpath >/dev/null 2>&1; then
    repository="$(cygpath -w "$repository")"
  elif [[ -n "${WSL_DISTRO_NAME:-}" ]] && command -v wslpath >/dev/null 2>&1; then
    repository="$(wslpath -w "$repository")"
  fi
  printf '%s\n' "$repository"
}

focused_test_prepare_artifacts() {
  local before after
  before="$(focused_test_input_fingerprint)"
  log "Preparing exact AWS runtime and Spark jobs snapshots for focused live tests"
  (
    cd "$REPO_DIR"
    repository_maven install -DskipTests \
      -pl :stratus-bom,:stratus-iceberg-aws-runtime,:stratus-spark-jobs -am
  )
  after="$(focused_test_input_fingerprint)"
  [[ "$before" == "$after" ]] \
    || fail "Focused-test artifact inputs changed while Maven was building; no freshness state was written"
  focused_test_record_artifacts
  log "Focused-test artifacts prepared at revision $(git -C "$REPO_DIR" rev-parse --short=12 HEAD)"
  log "Run one selection with: bash platform/spark/compose-cluster/scripts/tests/spark-compose-run-focused-tests.sh -Dtest=SparkClientConformanceTest"
}
