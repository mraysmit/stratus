# Expected result: REST API-driven maintenance

## Control-plane outcome

The Java verifier authenticates to Airflow 3.3.1, checks scheduler and metadata
health, confirms every Stratus DAG is registered and unpaused, submits
caller-owned run IDs, polls with a bounded timeout and reports Airflow and
observer timings through SLF4J.

The positive API run executes `stratus_table_maintenance` successfully on its
first attempt. The controlled negative API run executes
`stratus_bronze_to_silver`, fails the transform on its first attempt because of
persisted blocking evidence, and leaves its downstream quality task
`upstream_failed` without an attempt.

## Data-plane outcome

- Maintenance compacts three current Iceberg files into one.
- All three rows remain unchanged.
- The blocked promotion creates no silver target.
- Exact Iceberg tables, quality rows and landing objects are cleaned up.

## Required evidence markers

```text
event=airflow_orchestration_verification_completed status=SUCCESS
AIRFLOW TABLE MAINTENANCE RUN VERIFIED
AIRFLOW BRONZE TO SILVER BLOCK VERIFIED
event=airflow_api_orchestration_suite_completed ... status=SUCCESS ... elapsedMs=...
```

Use the positive and blocked run IDs printed by the completion event to locate
both runs in the Airflow UI. Their task states and attempt counts must match the
independent data-plane observations; an HTTP success response alone is not
sufficient evidence.
