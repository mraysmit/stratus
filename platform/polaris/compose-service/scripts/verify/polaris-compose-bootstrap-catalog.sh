#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-04
source "$(dirname "$0")/../lib/polaris-compose-common.sh"

# Bootstraps the Stratus catalog structure in a running Polaris service
# (Increment 2, P1-2.3/P1-2.4): the "stratus" catalog bound to the five
# Ceph buckets through the svc-polaris identity, the bronze, silver, gold,
# and platform namespaces, and the permanent platform.quality_check_results
# table (architecture §5.3). Idempotent: existing catalog, namespaces, and
# table are left untouched. Ends with positive listing checks and a
# negative invalid-token check.
#
# The table is provisioned here, not by a one-off client run, because the
# developer harness's Polaris 1.5.0 in-memory metastore loses all catalog
# state on restart; this script is the re-runnable provisioning path.

load_environment

api="$(polaris_api_base)"

# Startup returns as soon as the container is up, which is tens of seconds
# before the API answers, so this script must wait rather than assume the
# documented startup-then-bootstrap sequence leaves Polaris ready.
wait_for_polaris_api

# The bootstrap credential is realm,client-id,client-secret.
client_id="$(printf '%s' "$POLARIS_BOOTSTRAP_CREDENTIALS" | cut -d, -f2)"
client_secret="$(printf '%s' "$POLARIS_BOOTSTRAP_CREDENTIALS" | cut -d, -f3)"
[[ -n "$client_id" && -n "$client_secret" ]] \
  || fail "POLARIS_BOOTSTRAP_CREDENTIALS must be realm,client-id,client-secret (startup generates it)"

curl_api() { curl --silent --cacert "$CEPH_HARNESS_CA_FILE" --max-time 30 "$@"; }

# Tolerate a curl transport failure so the diagnostic below is reachable: an
# unreachable Polaris exits curl 52, which under `set -e` would otherwise abort
# this script with a bare exit code in exactly the case the message explains.
token_response="$(curl_api -X POST "$api/catalog/v1/oauth/tokens" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials' \
  -d "client_id=$client_id" \
  -d "client_secret=$client_secret" \
  -d 'scope=PRINCIPAL_ROLE:ALL' || true)"
token="$(printf '%s' "$token_response" | sed -nE 's/.*"access_token" *: *"([^"]+)".*/\1/p')"
[[ -n "$token" ]] || fail "Could not obtain an OAuth token from $api (is Polaris running? response: ${token_response:0:200})"
log "Authenticated as $client_id"

# Catalog: create only when absent.
catalog_status="$(curl_api -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $token" "$api/management/v1/catalogs/stratus")"
if [[ "$catalog_status" == 200 ]]; then
  log "READY catalog=stratus (already exists)"
else
  create_response="$(curl_api -X POST "$api/management/v1/catalogs" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d "{
      \"catalog\": {
        \"name\": \"stratus\",
        \"type\": \"INTERNAL\",
        \"properties\": {
          \"default-base-location\": \"s3://stratus-bronze\",
          \"s3.endpoint\": \"$CEPH_RGW_ENDPOINT\",
          \"s3.path-style-access\": \"true\",
          \"polaris.config.drop-with-purge.enabled\": \"true\"
        },
        \"storageConfigInfo\": {
          \"storageType\": \"S3\",
          \"endpoint\": \"$CEPH_RGW_ENDPOINT\",
          \"stsUnavailable\": true,
          \"region\": \"default\",
          \"pathStyleAccess\": true,
          \"allowedLocations\": [
            \"s3://stratus-landing\",
            \"s3://stratus-bronze\",
            \"s3://stratus-silver\",
            \"s3://stratus-gold\",
            \"s3://stratus-platform\"
          ]
        }
      }
    }")"
  verify_status="$(curl_api -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $token" "$api/management/v1/catalogs/stratus")"
  [[ "$verify_status" == 200 ]] \
    || fail "Catalog creation did not converge (HTTP $verify_status): ${create_response:0:300}"
  log "READY catalog=stratus (created)"
fi

# The per-catalog catalog_admin role manages metadata but does not carry
# content privileges; purge-drops delete data files, so manage-content is
# granted explicitly (idempotent: re-granting converges).
grant_status="$(curl_api -o /dev/null -w '%{http_code}' \
  -X PUT "$api/management/v1/catalogs/stratus/catalog-roles/catalog_admin/grants" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d '{"grant": {"type": "catalog", "privilege": "CATALOG_MANAGE_CONTENT"}}')"
[[ "$grant_status" == 201 || "$grant_status" == 200 ]] \
  || fail "Granting CATALOG_MANAGE_CONTENT to catalog_admin failed (HTTP $grant_status)"
log "GRANT catalog-role=catalog_admin privilege=CATALOG_MANAGE_CONTENT"

# Namespaces: POST returns 409 when present; both outcomes converge.
# Polaris (verified against 1.5.0) disables custom namespace locations by
# default and requires each namespace under <allowedLocation>/<namespace>/.
# We conform to that safety rule rather than disable it, so zone data lives
# at s3://stratus-<zone>/<zone>/.
for zone in bronze silver gold platform; do
  namespace_status="$(curl_api -o /tmp/polaris-ns-response.json -w '%{http_code}' \
    -X POST "$api/catalog/v1/stratus/namespaces" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d "{\"namespace\": [\"$zone\"], \"properties\": {\"location\": \"s3://stratus-$zone/$zone/\", \"zone\": \"$zone\"}}")"
  case "$namespace_status" in
    200) log "READY namespace=$zone (created)" ;;
    409) log "READY namespace=$zone (already exists)" ;;
    *) fail "Namespace $zone failed (HTTP $namespace_status): $(cat /tmp/polaris-ns-response.json 2>/dev/null | head -c 300)" ;;
  esac
done
rm -f /tmp/polaris-ns-response.json

# platform.quality_check_results (P1-2.4): the permanent quality result
# store from architecture §5.3 — fourteen columns, partitioned by zone and
# by checked_at day, append-only by contract (recorded as a table property;
# Iceberg does not enforce it). Field ids follow the documented column
# order; partition source-ids 4 and 13 are zone and checked_at.
# POST returns 409 when the table exists; both outcomes converge.
quality_table_request='{
  "name": "quality_check_results",
  "schema": {
    "type": "struct",
    "fields": [
      {"id": 1,  "name": "run_id",              "required": true,  "type": "string"},
      {"id": 2,  "name": "dataset_namespace",   "required": true,  "type": "string"},
      {"id": 3,  "name": "dataset_name",        "required": true,  "type": "string"},
      {"id": 4,  "name": "zone",                "required": true,  "type": "string"},
      {"id": 5,  "name": "check_type",          "required": true,  "type": "string"},
      {"id": 6,  "name": "check_name",          "required": true,  "type": "string"},
      {"id": 7,  "name": "severity",            "required": true,  "type": "string"},
      {"id": 8,  "name": "status",              "required": true,  "type": "string"},
      {"id": 9,  "name": "metric_value",        "required": false, "type": "double"},
      {"id": 10, "name": "threshold",           "required": false, "type": "double"},
      {"id": 11, "name": "failure_detail",      "required": false, "type": "string"},
      {"id": 12, "name": "pipeline_run_id",     "required": false, "type": "string"},
      {"id": 13, "name": "checked_at",          "required": true,  "type": "timestamp"},
      {"id": 14, "name": "iceberg_snapshot_id", "required": false, "type": "long"}
    ]
  },
  "partition-spec": {
    "spec-id": 0,
    "fields": [
      {"source-id": 4,  "field-id": 1000, "transform": "identity", "name": "zone"},
      {"source-id": 13, "field-id": 1001, "transform": "day",      "name": "checked_at_day"}
    ]
  },
  "properties": {
    "stratus.append-only": "true",
    "write.format.default": "parquet"
  }
}'
table_status="$(curl_api -o /tmp/polaris-table-response.json -w '%{http_code}' \
  -X POST "$api/catalog/v1/stratus/namespaces/platform/tables" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d "$quality_table_request")"
case "$table_status" in
  200) log "READY table=platform.quality_check_results (created)" ;;
  409) log "READY table=platform.quality_check_results (already exists)" ;;
  *) fail "Table platform.quality_check_results failed (HTTP $table_status): $(cat /tmp/polaris-table-response.json 2>/dev/null | head -c 300)" ;;
esac
rm -f /tmp/polaris-table-response.json

# Positive check: the table loads through the catalog API.
table_load_status="$(curl_api -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $token" \
  "$api/catalog/v1/stratus/namespaces/platform/tables/quality_check_results")"
[[ "$table_load_status" == 200 ]] \
  || fail "platform.quality_check_results did not load after bootstrap (HTTP $table_load_status)"
log "PASS table-load table=platform.quality_check_results"

# Positive check: all four namespaces are listed.
listing="$(curl_api -H "Authorization: Bearer $token" "$api/catalog/v1/stratus/namespaces")"
for zone in bronze silver gold platform; do
  printf '%s' "$listing" | grep -q "\"$zone\"" \
    || fail "Namespace listing is missing $zone: ${listing:0:300}"
done
log "PASS namespace-listing zones=bronze,silver,gold,platform"

# Negative check: a forged token must be refused.
forged_status="$(curl_api -o /dev/null -w '%{http_code}' \
  -H 'Authorization: Bearer invalid-forged-token' "$api/catalog/v1/stratus/namespaces")"
[[ "$forged_status" == 401 || "$forged_status" == 403 ]] \
  || fail "A forged token must be refused, got HTTP $forged_status"
log "PASS forged-token-refused httpStatus=$forged_status"

log "Catalog bootstrap complete: catalog=stratus namespaces=4 tables=1 storage=$CEPH_RGW_ENDPOINT"
