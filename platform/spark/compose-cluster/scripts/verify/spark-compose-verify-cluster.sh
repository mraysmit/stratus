#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-08
source "$(dirname "$0")/../lib/spark-compose-common.sh"

# Proves the reduced developer cluster is serving: the master is up and both
# workers have registered and report ALIVE (P1-3.1-D1). Registration is what
# matters — a worker container that is running but has not joined the master
# contributes nothing, and the master UI is the only place that distinction is
# visible.

load_environment

evidence_dir="$HARNESS_DIR/evidence"
mkdir -p "$evidence_dir"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
evidence_file="$evidence_dir/spark-cluster-verification-${timestamp}.json"

expected_workers="${SPARK_EXPECTED_WORKERS:-2}"
deadline_seconds="${SPARK_REGISTRATION_DEADLINE_SECONDS:-120}"

# The master registers workers a moment after they start, so poll to a bounded
# deadline rather than reading once and calling a slow join a failure.
elapsed=0
alive=0
state=''
while (( elapsed < deadline_seconds )); do
  state="$(compose exec -T spark-master curl --silent --max-time 10 \
    http://localhost:8080/json/ || true)"
  # One "state" : "ALIVE" per registered worker in the master's JSON view.
  alive="$(printf '%s' "$state" | grep -o '"state" *: *"ALIVE"' | wc -l | tr -d ' ')"
  (( alive >= expected_workers )) && break
  sleep 3
  elapsed=$((elapsed + 3))
done

# Sum the per-worker core counts, taken only from inside the workers array.
# The master repeats a cluster-wide "cores" total after that array, so summing
# every occurrence in the document double-counts the capacity.
cores="$(printf '%s' "$state" | sed -n '/"workers"/,/} ]/p' \
  | grep -o '"cores" *: *[0-9]\+' | grep -o '[0-9]\+$' \
  | awk '{total += $1} END {print total + 0}')"
{
  printf '{\n'
  printf '  "checkedAtUtc": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '  "expectedWorkers": %s,\n' "$expected_workers"
  printf '  "aliveWorkers": %s,\n' "${alive:-0}"
  printf '  "clusterCores": %s,\n' "${cores:-0}"
  printf '  "waitedSeconds": %s\n' "$elapsed"
  printf '}\n'
} >"$evidence_file"

(( alive >= expected_workers )) \
  || fail "Only ${alive:-0} of $expected_workers workers reported ALIVE within ${deadline_seconds}s; evidence: $evidence_file"

log "PASS spark-cluster workers=$alive/$expected_workers clusterCores=${cores:-unknown} waitedSeconds=$elapsed"
log "Evidence: $evidence_file"
