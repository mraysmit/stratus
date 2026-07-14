#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_environment
compose --profile verification down --volumes --remove-orphans
printf 'Removed the disposable local Ceph containers, network, configuration volume, and data volume.\n'
