# Expected result: customer landing-to-gold

## Business-visible outcome

The accepted scenario ingests the three records in `fixtures/customers.csv`,
produces three governed bronze rows, deterministically promotes three unique
customers to silver, and materialises three country groups in gold:

| country | customer_count |
|---|---:|
| GB | 1 |
| NL | 1 |
| US | 1 |

The comparison scenario records a blocking silver row-count rule requiring four
rows. Gold materialisation fails before writing its target, and the independent
verifier proves the target does not exist.

## Airflow-visible outcome

- `stratus_landing_to_bronze`: sensor, ingestion and bronze quality complete.
- `stratus_bronze_to_silver`: transform and two silver checks complete.
- `stratus_silver_to_gold`: silver checks, materialisation and gold checks
  complete for the accepted run.
- The blocked gold run fails at materialisation after reading persisted quality
  evidence; it is an expected governed failure, not a demo failure.

## Required evidence markers

```text
AIRFLOW SILVER TO GOLD VERIFIED
AIRFLOW SILVER TO GOLD BLOCK VERIFIED
event=airflow_silver_to_gold_suite_completed ... status=SUCCESS ... elapsedMs=...
```

Use the shared `suiteRunId` and pipeline run IDs to correlate the Airflow task
logs, Spark applications, quality-result rows and independent verifier output.
Exact demo tables, quality rows and landing objects are removed after proof.
