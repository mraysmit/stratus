#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-16

script_dir="$(cd "$(dirname "$0")" && pwd)"
source "$script_dir/../lib/spark-compose-maven-common.sh"
source "$script_dir/../lib/spark-compose-focused-test-artifacts.sh"

selection=""
for argument in "$@"; do
  case "$argument" in
    -Dtest=*) selection="${argument#-Dtest=}" ;;
    -Dmaven.repo.local|-Dmaven.repo.local=*) fail "The focused runner pins Maven to the repository used during preparation" ;;
    -DskipTests|-DskipTests=*|\
    -Dmaven.test.skip|-Dmaven.test.skip=*|\
    -Dsurefire.skip|-Dsurefire.skip=*|\
    -Dtest.groups|-Dtest.groups=*|\
    -Dtest.excludedGroups|-Dtest.excludedGroups=*|\
    -Dgroups|-Dgroups=*|\
    -DexcludedGroups|-DexcludedGroups=*|\
    -DfailIfNoTests|-DfailIfNoTests=*|\
    -Dsurefire.failIfNoSpecifiedTests|-Dsurefire.failIfNoSpecifiedTests=*)
      fail "Maven property must not override focused live-test execution: $argument" ;;
    -D*) ;;
    *) fail "The focused runner accepts Maven -D properties only, found: $argument" ;;
  esac
done
[[ -n "$selection" ]] \
  || fail "Select a focused test with -Dtest=ClassName or -Dtest=ClassName#methodName"

focused_test_validate_artifacts
log "Focused-test artifacts match the current build inputs and local Maven repository"
maven_repository="$(focused_test_maven_repository_argument)"

# Run the current test sources through their normal test lifecycle, but do not
# rebuild upstream modules. The validated snapshots satisfy those dependencies.
exec "$script_dir/spark-compose-run-live-tests.sh" \
  test -Pspark-integration-tests -pl :stratus-spark-tests \
  "-Dmaven.repo.local=$maven_repository" "$@"
