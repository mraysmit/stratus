#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_environment
mkdir -p "$HARNESS_DIR/evidence"
"$REPO_DIR/mvnw" -f "$REPO_DIR/pom.xml" --batch-mode --no-transfer-progress \
  --projects verification/storage-contract --also-make clean package
compose config --quiet
compose up --detach --build --remove-orphans
compose ps
