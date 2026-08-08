#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-08
source "$(dirname "$0")/../lib/spark-compose-common.sh"

# Creates the svc-spark principal in Polaris and grants it the catalog role
# this engine needs (P1-3.2-D1). Idempotent: an existing principal is reset to
# the secret this harness holds rather than duplicated, because the catalog is
# the authority on the credential and .env is the only place the harness keeps
# it.
#
# Each engine's own increment creates its principal — the Increment 2
# promotion manifest assigns svc-spark here rather than to the catalog
# bootstrap. Production principals come from the approved identity process
# (P1-3.2-P1); nothing here is promoted.

load_environment

: "${SPARK_POLARIS_CLIENT_ID:?SPARK_POLARIS_CLIENT_ID is required in .env}"
: "${SPARK_POLARIS_CLIENT_SECRET:?SPARK_POLARIS_CLIENT_SECRET is required in .env}"

# The catalog's root credential lives in the Polaris harness's .env. This is
# the one value this harness reads from a provider's private file rather than
# its connection.env, and it is read-only, never copied, and never logged:
# creating a principal is a privileged catalog operation and the developer
# harness has no other administrative path to it.
polaris_env="$POLARIS_HARNESS_DIR/.env"
[[ -f "$polaris_env" ]] \
  || fail "Missing $polaris_env; start the Polaris harness first"
root_credentials="$(sed -nE 's/^POLARIS_BOOTSTRAP_CREDENTIALS=(.*)$/\1/p' "$polaris_env")"
root_id="$(printf '%s' "$root_credentials" | cut -d, -f2)"
root_secret="$(printf '%s' "$root_credentials" | cut -d, -f3)"
[[ -n "$root_id" && -n "$root_secret" ]] \
  || fail "Could not read the Polaris root credential from $polaris_env"

api="$POLARIS_ENDPOINT/api"

token="$(spark_curl -X POST "$api/catalog/v1/oauth/tokens" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials' \
  -d "client_id=$root_id" -d "client_secret=$root_secret" \
  -d 'scope=PRINCIPAL_ROLE:ALL' \
  | sed -nE 's/.*"access_token" *: *"([^"]+)".*/\1/p')"
[[ -n "$token" ]] || fail "Could not obtain a Polaris admin token from $api"
log "Authenticated as $root_id"

principal="$SPARK_POLARIS_CLIENT_ID"
principal_role="${principal}_role"

# Principal: create when absent, otherwise reset its credential to the one
# this harness holds. A principal whose secret the harness does not know is
# useless to it, and rotating on every run keeps .env authoritative.
status="$(spark_curl -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $token" "$api/management/v1/principals/$principal")"
if [[ "$status" == 200 ]]; then
  reset="$(spark_curl -X POST "$api/management/v1/principals/$principal/reset" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d "{\"clientId\": \"$principal\", \"clientSecret\": \"$SPARK_POLARIS_CLIENT_SECRET\"}" \
    -w '\n%{http_code}')"
  [[ "${reset##*$'\n'}" == 200 ]] \
    || fail "Resetting the $principal credential failed (HTTP ${reset##*$'\n'})"
  log "READY principal=$principal (credential reset to this harness's secret)"
else
  created="$(spark_curl -X POST "$api/management/v1/principals" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d "{\"principal\": {\"name\": \"$principal\", \"clientId\": \"$principal\"}, \"credentialRotationRequired\": false}" \
    -w '\n%{http_code}')"
  case "${created##*$'\n'}" in
    200|201) log "READY principal=$principal (created)" ;;
    *) fail "Creating principal $principal failed (HTTP ${created##*$'\n'}): $(printf '%s' "${created%$'\n'*}" | head -c 300)" ;;
  esac
  # A freshly created principal carries a catalog-generated secret the harness
  # does not know, so it is immediately reset to the one in .env.
  reset="$(spark_curl -X POST "$api/management/v1/principals/$principal/reset" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d "{\"clientId\": \"$principal\", \"clientSecret\": \"$SPARK_POLARIS_CLIENT_SECRET\"}" \
    -w '\n%{http_code}')"
  [[ "${reset##*$'\n'}" == 200 ]] \
    || fail "Setting the $principal credential failed (HTTP ${reset##*$'\n'})"
fi

create_or_converge() {
  local description="$1" url="$2" payload="$3" response
  response="$(spark_curl -X POST "$url" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d "$payload" -w '\n%{http_code}')"
  case "${response##*$'\n'}" in
    200|201|409) return 0 ;;
    *) fail "$description failed (HTTP ${response##*$'\n'}): $(printf '%s' "${response%$'\n'*}" | head -c 300)" ;;
  esac
}

create_or_converge "Creating principal role $principal_role" \
  "$api/management/v1/principal-roles" \
  "{\"principalRole\": {\"name\": \"$principal_role\"}}"
log "READY principal-role=$principal_role"

assign_status="$(spark_curl -o /dev/null -w '%{http_code}' \
  -X PUT "$api/management/v1/principals/$principal/principal-roles" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d "{\"principalRole\": {\"name\": \"$principal_role\"}}")"
[[ "$assign_status" == 201 || "$assign_status" == 200 ]] \
  || fail "Assigning $principal_role to $principal failed (HTTP $assign_status)"
log "GRANT principal=$principal principal-role=$principal_role"

# catalog_admin carries the metadata and content privileges an engine needs to
# create, write, and drop tables in the Stratus catalog. Narrowing this to a
# least-privilege engine role belongs to P1-3.2-P1.
catalog_assign_status="$(spark_curl -o /dev/null -w '%{http_code}' \
  -X PUT "$api/management/v1/principal-roles/$principal_role/catalog-roles/$POLARIS_CATALOG" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d '{"catalogRole": {"name": "catalog_admin"}}')"
[[ "$catalog_assign_status" == 201 || "$catalog_assign_status" == 200 ]] \
  || fail "Assigning catalog_admin to $principal_role failed (HTTP $catalog_assign_status)"
log "GRANT principal-role=$principal_role catalog=$POLARIS_CATALOG catalog-role=catalog_admin"

# Prove the identity works before declaring success: an engine principal that
# cannot obtain its own token is not provisioned, whatever the grants say.
engine_token="$(spark_curl -X POST "$api/catalog/v1/oauth/tokens" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials' \
  -d "client_id=$SPARK_POLARIS_CLIENT_ID" -d "client_secret=$SPARK_POLARIS_CLIENT_SECRET" \
  -d 'scope=PRINCIPAL_ROLE:ALL' \
  | sed -nE 's/.*"access_token" *: *"([^"]+)".*/\1/p')"
[[ -n "$engine_token" ]] \
  || fail "$principal could not obtain its own token; the principal is not usable"

namespaces="$(spark_curl -H "Authorization: Bearer $engine_token" \
  "$api/catalog/v1/$POLARIS_CATALOG/namespaces")"
for zone in bronze silver gold platform; do
  printf '%s' "$namespaces" | grep -q "\"$zone\"" \
    || fail "$principal cannot see the $zone namespace: ${namespaces:0:300}"
done
log "PASS principal-access principal=$principal namespaces=bronze,silver,gold,platform"
log "Spark catalog principal provisioning complete: principal=$principal catalog=$POLARIS_CATALOG"
