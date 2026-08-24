# Expected result: fail-closed quality gate

## Accepted path

Three landed customer rows receive passing bronze evidence. Airflow coordinates
the Spark transform, the target contains exactly three rows with unique
`customer_id` values, and two persisted silver checks pass.

## Blocked path

The fixture records a real blocking quality result against the otherwise valid
bronze table. The Airflow transform task fails when the packaged Spark job reads
that evidence. The independent verifier establishes all of the following:

- the blocking check is persisted under the correlated quality run ID;
- promotion does not silently continue;
- the silver target is absent rather than partially written;
- cleanup removes only the isolated demo tables, results and landing object.

## Required evidence markers

```text
AIRFLOW BRONZE TO SILVER VERIFIED
AIRFLOW BRONZE TO SILVER BLOCK VERIFIED
event=airflow_bronze_to_silver_expected_failure ... elapsedMs=...
event=airflow_bronze_to_silver_suite_completed ... status=SUCCESS ... elapsedMs=...
```

In the Airflow UI, compare the successful and failed `stratus_bronze_to_silver`
runs. The important observation is not simply a red task: the failed task is
paired with independent no-write proof at the Iceberg boundary.
