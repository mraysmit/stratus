#!/usr/bin/env bash
#
# Stratus Airflow wheelhouse integrity library
#
# Purpose:
#   Provide the shared fail-closed boundary between pip's locked selection, the files
#   admitted to the Docker build context, and recoverable wheelhouse replacement.
#   This is a sourced library and deliberately performs no top-level filesystem work.
#
# Inputs:
#   - verify_wheelhouse_exact_file_set EXPECTED_FILE WHEELHOUSE_DIR, where EXPECTED_FILE
#     contains one selected artifact basename per line; the checksum manifest is the
#     only wheelhouse file excluded from comparison;
#   - pip_report_selected_artifacts PIP_REPORT_JSON SELECTED_FILE;
#   - recover_wheelhouse_promotion ACTIVE_DIR PREVIOUS_DIR; and
#   - promote_wheelhouse_candidate ACTIVE_DIR CANDIDATE_DIR PREVIOUS_DIR.
#   Promotion directories are expected to share one parent filesystem.
#
# Outputs:
#   Functions return zero only when their contract is satisfied, emit stable event=...
#   records, and may write the selected filename list or move the three explicitly
#   supplied wheelhouse directories. No function accepts a glob as a deletion target.
#
# Failure and recovery:
#   Missing or unexpected artifacts fail exact-set verification. Candidate move failure
#   restores the known-good directory immediately. If a process stops after backing up
#   the active directory, recover_wheelhouse_promotion restores it on cleanup or on the
#   next resolver invocation; if both active and previous exist, active is the completed
#   candidate and the stale backup is finalized away.
#
# Usage:
#   source platform/airflow/image/scripts/lib/airflow-wheelhouse-integrity.sh
#   Functions are then called by airflow-image-resolve-artifacts.sh and
#   airflow-image-build.sh; this file is not a standalone command.
#
# Maintenance:
#   Keep the public function names and argument order stable, retain zero top-level side
#   effects, and keep diagnostics free of file contents. Update
#   AirflowWheelhouseBehaviorTest first when changing exact-set or recovery semantics.

verify_wheelhouse_exact_file_set() {
  local expected_file="$1"
  local wheelhouse_dir="$2"
  local comparison_dir expected_sorted actual_sorted missing_file unexpected_file
  local missing unexpected artifact artifact_name artifact_count

  if [[ ! -f "${expected_file}" || ! -d "${wheelhouse_dir}" ]]; then
    printf 'event=wheelhouse_file_set_invalid missing_input=true expected=%s wheelhouse=%s\n' \
      "${expected_file}" "${wheelhouse_dir}" >&2
    return 1
  fi

  comparison_dir="$(mktemp -d "${TMPDIR:-/tmp}/stratus-wheelhouse-set.XXXXXX")"
  expected_sorted="${comparison_dir}/expected.txt"
  actual_sorted="${comparison_dir}/actual.txt"
  missing_file="${comparison_dir}/missing.txt"
  unexpected_file="${comparison_dir}/unexpected.txt"

  sed '/^[[:space:]]*$/d' "${expected_file}" | LC_ALL=C sort -u > "${expected_sorted}"
  (
    shopt -s nullglob
    for artifact in "${wheelhouse_dir}"/*; do
      [[ -f "${artifact}" ]] || continue
      artifact_name="$(basename "${artifact}")"
      [[ "${artifact_name}" == "resolved-artifacts.sha256" ]] && continue
      printf '%s\n' "${artifact_name}"
    done
  ) | LC_ALL=C sort -u > "${actual_sorted}"

  comm -23 "${expected_sorted}" "${actual_sorted}" > "${missing_file}"
  comm -13 "${expected_sorted}" "${actual_sorted}" > "${unexpected_file}"
  missing="$(paste -sd, "${missing_file}")"
  unexpected="$(paste -sd, "${unexpected_file}")"

  if [[ -n "${missing}" || -n "${unexpected}" ]]; then
    printf 'event=wheelhouse_file_set_invalid missing=%s unexpected=%s\n' \
      "${missing:-none}" "${unexpected:-none}" >&2
    rm -rf -- "${comparison_dir}"
    return 1
  fi

  artifact_count="$(wc -l < "${actual_sorted}" | tr -d ' ')"
  rm -rf -- "${comparison_dir}"
  printf 'event=wheelhouse_file_set_verified artifact_count=%s\n' \
    "${artifact_count}"
}

pip_report_selected_artifacts() {
  local report_file="$1"
  local selected_file="$2"

  python -c '
import json
import pathlib
import sys
import urllib.parse

with open(sys.argv[1], encoding="utf-8") as source:
    report = json.load(source)
names = sorted({
    pathlib.PurePosixPath(
        urllib.parse.unquote(urllib.parse.urlparse(item["download_info"]["url"]).path)
    ).name
    for item in report.get("install", [])
})
if not names:
    raise SystemExit("pip dry-run selected no wheelhouse artifacts")
with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as target:
    target.write("\n".join(names) + "\n")
' "${report_file}" "${selected_file}"
}

recover_wheelhouse_promotion() {
  local active_dir="$1"
  local previous_dir="$2"

  [[ -e "${previous_dir}" ]] || return 0
  if [[ -e "${active_dir}" ]]; then
    rm -rf -- "${previous_dir}"
    printf 'event=wheelhouse_promotion_finalized active=%s\n' "${active_dir}"
    return 0
  fi
  if mv "${previous_dir}" "${active_dir}"; then
    printf 'event=wheelhouse_promotion_recovered active=%s\n' "${active_dir}"
    return 0
  fi
  printf 'event=wheelhouse_promotion_recovery_failed active=%s previous=%s\n' \
    "${active_dir}" "${previous_dir}" >&2
  return 1
}

promote_wheelhouse_candidate() {
  local active_dir="$1"
  local candidate_dir="$2"
  local previous_dir="$3"
  local had_active=false move_status

  if [[ ! -d "${candidate_dir}" ]]; then
    printf 'event=wheelhouse_promotion_failed reason=candidate_missing candidate=%s\n' \
      "${candidate_dir}" >&2
    return 1
  fi
  recover_wheelhouse_promotion "${active_dir}" "${previous_dir}" || return 1

  if [[ -d "${active_dir}" ]]; then
    mv "${active_dir}" "${previous_dir}"
    had_active=true
  fi

  if mv "${candidate_dir}" "${active_dir}"; then
    rm -rf -- "${previous_dir}"
    printf 'event=wheelhouse_promotion_completed active=%s\n' "${active_dir}"
    return 0
  else
    move_status=$?
  fi

  if [[ "${had_active}" == true && ! -e "${active_dir}" ]] \
      && mv "${previous_dir}" "${active_dir}"; then
    printf 'event=wheelhouse_promotion_rolled_back active=%s\n' "${active_dir}" >&2
    return "${move_status}"
  fi
  printf 'event=wheelhouse_promotion_rollback_failed active=%s previous=%s\n' \
    "${active_dir}" "${previous_dir}" >&2
  return 1
}
