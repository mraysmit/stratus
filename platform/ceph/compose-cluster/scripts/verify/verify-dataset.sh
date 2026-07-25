#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-07-25
source "$(dirname "$0")/../lib/common.sh"

# Live dataset round-trip against Ceph RGW: create a real multi-file dataset,
# upload it, read every byte back, and prove the content is identical. The
# work runs inside the s3client container so it exercises the same TLS and
# credential path as every other S3 client in the harness. Read-back is
# proven two independent ways: rclone check --download re-downloads each
# object and byte-compares it against the source, and a full copy into a
# second local tree is hash-compared against the original. The remote prefix
# is purged afterwards and the purge is asserted, so repeated runs converge
# and leave no probe objects behind.

load_environment

dataset_bucket="${CEPH_DATASET_BUCKET:-stratus-landing}"
dataset_files=24
evidence_dir="${HARNESS_DIR}/evidence"
mkdir -p "$evidence_dir"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
dataset_prefix="verification/dataset-${timestamp}"
evidence_file="${evidence_dir}/dataset-verification-${timestamp}.json"

log "Dataset round-trip: ${dataset_files} files -> cephrgw:${dataset_bucket}/${dataset_prefix} -> read back -> purge"

set +e
result="$(printf '%s\n%s\n%s\n' "$dataset_bucket" "$dataset_prefix" "$dataset_files" \
  | compose exec -T s3client sh -c '
set -eu
IFS= read -r bucket; IFS= read -r prefix; IFS= read -r files
src=/tmp/dataset-src-$$
back=/tmp/dataset-readback-$$
remote="cephrgw:$bucket/$prefix"
trap "rm -rf $src $back; rclone --ca-cert /certs/stratus-ca.crt purge \"$remote\" >/dev/null 2>&1 || true" EXIT

rc() { rclone --ca-cert /certs/stratus-ca.crt "$@"; }
json_field() { sed -n "s/.*\"$2\":\([0-9]*\).*/\1/p" "$1"; }

echo "creating $files seeded files under $src" >&2
rclone test makefiles --seed 20260725 --files "$files" --files-per-directory 6 \
  --min-file-size 1k --max-file-size 64k "$src" >&2
rclone size --json "$src" > /tmp/size-src-$$
echo "SRC_COUNT=$(json_field /tmp/size-src-$$ count)"
echo "SRC_BYTES=$(json_field /tmp/size-src-$$ bytes)"

echo "uploading the dataset to $remote" >&2
rc copy "$src" "$remote"
rc size --json "$remote" > /tmp/size-remote-$$
echo "REMOTE_COUNT=$(json_field /tmp/size-remote-$$ count)"
echo "REMOTE_BYTES=$(json_field /tmp/size-remote-$$ bytes)"

echo "downloading every object and byte-comparing it against the source" >&2
rc check --download "$src" "$remote" >&2
echo "DOWNLOAD_CHECK=pass"

echo "copying the dataset back into $back and hash-comparing the trees" >&2
rc copy "$remote" "$back"
rclone check "$src" "$back" >&2
echo "READBACK_CHECK=pass"

echo "purging $remote" >&2
rc purge "$remote"
rc size --json "$remote" > /tmp/size-after-$$
echo "CLEANUP_COUNT=$(json_field /tmp/size-after-$$ count)"
rm -f /tmp/size-src-$$ /tmp/size-remote-$$ /tmp/size-after-$$
')"
verify_exit=$?
set -e

value_of() { printf '%s\n' "$result" | grep "^$1=" | cut -d= -f2; }
src_count="$(value_of SRC_COUNT)"; src_bytes="$(value_of SRC_BYTES)"
remote_count="$(value_of REMOTE_COUNT)"; remote_bytes="$(value_of REMOTE_BYTES)"
download_check="$(value_of DOWNLOAD_CHECK)"
readback_check="$(value_of READBACK_CHECK)"
cleanup_count="$(value_of CLEANUP_COUNT)"

created_pass=false; [[ "$src_count" == "$dataset_files" ]] && created_pass=true
upload_pass=false
[[ -n "$src_count" && "$remote_count" == "$src_count" && "$remote_bytes" == "$src_bytes" ]] && upload_pass=true
download_pass=false; [[ "$download_check" == pass ]] && download_pass=true
readback_pass=false; [[ "$readback_check" == pass ]] && readback_pass=true
cleanup_pass=false; [[ "$cleanup_count" == 0 ]] && cleanup_pass=true

overall=false
[[ "$verify_exit" -eq 0 ]] && $created_pass && $upload_pass && $download_pass && $readback_pass && $cleanup_pass \
  && overall=true

cat > "$evidence_file" <<EOF
{
  "description": "Stratus dataset round-trip evidence: success=true means a generated dataset was uploaded to Ceph RGW, read back byte-for-byte identical, and cleaned up",
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "success": $overall,
  "bucket": "$dataset_bucket",
  "prefix": "$dataset_prefix",
  "checks": [
    {"name": "dataset-created", "passed": $created_pass, "detail": "Generated ${src_count:-0} of $dataset_files files (${src_bytes:-0} bytes) in the s3client container"},
    {"name": "dataset-uploaded", "passed": $upload_pass, "detail": "Remote listing reports ${remote_count:-0} objects and ${remote_bytes:-0} bytes, matching the source"},
    {"name": "dataset-download-verified", "passed": $download_pass, "detail": "rclone check --download re-downloaded every object and matched the source bytes"},
    {"name": "dataset-readback-verified", "passed": $readback_pass, "detail": "A full copy back from the bucket hash-matched the original tree"},
    {"name": "dataset-cleanup", "passed": $cleanup_pass, "detail": "Purged the remote prefix; ${cleanup_count:-unknown} objects remain"}
  ]
}
EOF

cat "$evidence_file"
if ! $overall; then
  fail "Dataset round-trip verification failed; evidence: $evidence_file"
fi
log "PASS dataset-round-trip evidence=$evidence_file"
