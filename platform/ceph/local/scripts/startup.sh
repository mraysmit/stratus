#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
if [[ ! -f "$HARNESS_DIR/.env" ]]; then
  cp "$HARNESS_DIR/.env.template" "$HARNESS_DIR/.env"
  chmod 600 "$HARNESS_DIR/.env"
fi
if [[ ! -f "$HARNESS_DIR/certs/stratus-ca.crt" ]]; then
  "$(dirname "$0")/generate-lab-certificates.sh"
fi
load_environment
mkdir -p "$HARNESS_DIR/evidence"
compose config --quiet
compose up --detach --remove-orphans --wait
compose ps
