# Airflow role demonstrations

These demonstrations answer one question: what does Airflow contribute to
Stratus? Airflow is the orchestration and control plane. Spark performs the data processing.
Polaris supplies the catalog, and Ceph stores the Iceberg data.
Airflow detects or accepts work, supplies immutable run context, coordinates the
Spark tasks, enforces persisted quality evidence, exposes state through its UI
and REST API, and records retry, alert and timing information.

The demos are audience-facing entry points over the accepted live test
harnesses. They do not contain copied DAG or Spark business logic. Each command
starts any required developer services, executes the real proof, verifies its
completion markers, prints the expected-result document and performs checked
reverse-order shutdown. The raw transcript is written below the ignored
`demos/evidence/` directory and retains `suiteRunId`, task/run correlation, phase
names and `elapsedMs` timing fields.

Run the commands with Git Bash on Windows or Bash 4+ on Linux. The accepted
Airflow image and Spark artifacts must already have been prepared by their
normal build workflows.

## Choose a demonstration

| Demonstration | What the audience learns | Estimated duration | Expected result |
|---|---|---:|---|
| Customer landing-to-gold | Airflow coordinates the real landing, bronze, silver and gold jobs while Spark owns compute and Iceberg owns table state | 12-15 minutes | [`expected-results/customer-pipeline.md`](expected-results/customer-pipeline.md) |
| Fail-closed quality gate | Persisted blocking evidence prevents a downstream write, while accepted evidence permits promotion | 10-12 minutes | [`expected-results/quality-gate.md`](expected-results/quality-gate.md) |
| API-driven maintenance | A caller uses the Airflow REST API to schedule a policy-controlled maintenance run and inspect bounded task state and timing | 8-10 minutes | [`expected-results/api-maintenance.md`](expected-results/api-maintenance.md) |

## Demo 1: customer landing-to-gold

```bash
bash platform/airflow/developer/demos/scripts/airflow-customer-pipeline-demo.sh
```

Talk track:

1. Show [`fixtures/customers.csv`](fixtures/customers.csv): three small customer
   records are enough to make every table and quality assertion obvious.
2. Explain that the landing DAG waits for the object without occupying a worker,
   then submits the packaged ingestion and bronze-quality jobs to Spark.
3. Follow the same correlated batch through deterministic bronze-to-silver
   promotion, persisted silver checks and country-level gold materialisation.
4. Point out the deliberate blocked comparison: Airflow exposes the failure,
   while the independent verifier proves that no unapproved gold target exists.

## Demo 2: fail-closed quality gate

```bash
bash platform/airflow/developer/demos/scripts/airflow-quality-gate-demo.sh
```

Talk track:

1. Start with the accepted path and the three-row silver snapshot.
2. Highlight that the quality result is stored in
   `stratus.platform.quality_check_results`; it is not merely a task-local flag.
3. Show the blocking rule and failed Airflow transform task.
4. Finish with the independent `AIRFLOW BRONZE TO SILVER BLOCK VERIFIED` marker,
   which proves that orchestration failure also prevented the governed write.

## Demo 3: REST API-driven maintenance

```bash
bash platform/airflow/developer/demos/scripts/airflow-api-maintenance-demo.sh
```

Talk track:

1. The Java verifier authenticates to Airflow's public API and validates
   scheduler, metadata-database and DAG-registry health.
2. A caller-owned run ID triggers `stratus_table_maintenance`; Airflow records
   the DAG and task states while Spark executes the Iceberg rewrite.
3. The verifier reports both server-side Airflow duration and independently
   observed polling duration.
4. Independent data-plane verification proves three files became one with all
   three rows preserved. The same API session also demonstrates a fail-closed
   promotion so success and governed failure are both visible.

## Inspect the Airflow UI

Add `--keep-running` to any command:

```bash
bash platform/airflow/developer/demos/scripts/airflow-quality-gate-demo.sh --keep-running
```

After the proof and exact data cleanup, the script restarts Airflow and preserves
its metadata and logs. Open <http://127.0.0.1:8088> and inspect:

- the successful and deliberately failed DAG runs;
- graph dependencies and the point where downstream work stopped;
- task attempt counts and terminal states;
- task logs containing the run ID, target table, quality decision and timings;
- the corresponding `suiteRunId` in the printed harness-evidence path.

The data fixtures are deliberately removed after independent verification, so
`--keep-running` preserves UI history rather than leaving misleading test tables
in the catalog. The customer and quality demos retain their already-running data
plane for further engineering inspection. The API demo's indivisible harness
shuts its providers down before Airflow is restarted.

When inspection is complete, use the one-command checked shutdown:

```bash
bash platform/airflow/developer/demos/scripts/airflow-demo-shutdown.sh
```

Successful cleanup prints `remainingStratusContainers=0`. The shutdown uses the
same checked lifecycle scripts as acceptance; it does not remove Airflow's named
PostgreSQL or log volumes. OpenBao remains a disposable developer service, so
stopping it discards its generated in-memory development secrets.

## Reading the result

Every successful entry point ends with `AIRFLOW DEMO COMPLETE`, an exact harness
evidence path and the matching expected-result document. A demo fails if the
accepted harness fails, if any required observable marker is absent, or if
default cleanup leaves a Stratus container running. Do not treat presentation
success as a substitute for the canonical development acceptance suite:

```bash
bash platform/airflow/developer/scripts/tests/airflow-development-acceptance-suite.sh
```

The demos are intentionally narrower and faster; the canonical suite remains
the complete regression and gate evidence.
