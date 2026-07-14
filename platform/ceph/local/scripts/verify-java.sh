#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_environment
mkdir -p "$HARNESS_DIR/evidence"
evidence="$HARNESS_DIR/evidence/storage-verification-$(date -u +%Y%m%dT%H%M%SZ).json"
compose run --rm --no-deps -T verifier java -jar /opt/stratus/storage-contract-verifier.jar | tee "$evidence"
printf 'Evidence: %s\n' "$evidence"
