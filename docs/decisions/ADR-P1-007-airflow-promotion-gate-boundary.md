# ADR-P1-007: Explicit Airflow Promotion-Gate Boundary

- Status: Accepted
- Date: 2026-08-25
- Task: `P1-4.3-V2`
- Decision owners: Data owner and platform owner
- Supersedes: the implicit-only gate shape accepted under `P1-4.3-V1`

## Context

The Stratus architecture requires promotion between governed zones to be an explicit Airflow task. The first accepted development DAGs passed a quality-run identifier directly to `TransformJob` and `MaterialisationJob`; those jobs evaluated `PromotionGate` immediately before writing. That implementation failed closed, but Airflow exposed the combined transform or materialisation outcome rather than a distinct promotion decision.

Combining the gate with the writer weakens control-plane visibility. Operators cannot distinguish a denied promotion from a compute failure by task identity alone, and an approval or override cannot be attributed to a dedicated workflow step. Removing the in-job check, however, would create a time-of-check/time-of-use gap between the Airflow decision and the governed write.

## Decision

Every bronze-to-silver and silver-to-gold workflow has a dedicated `SparkSubmitOperator` task running `dev.stratus.jobs.spark.PromotionGate` before the writer task. The task consumes the exact quality-run identifier and source table that authorize the transition.

`TransformJob` and `MaterialisationJob` continue receiving `--qualityRunId` and re-evaluate the same evidence immediately before writing. This is defense in depth, not a substitute for the explicit Airflow task.

The development DAGs do not accept override values. A production override workflow must authenticate a named steward, require a non-blank reason, pass both values only to the explicit gate task, persist the override record, and retain the Airflow task and quality-result evidence. An override must never disable the writer's evidence recheck.

## Consequences

- Airflow records a separately named promotion task and its success or failure.
- A blocking or missing quality result stops the DAG before the writer task is scheduled.
- The writer still fails closed if evidence changes or the explicit gate is bypassed.
- The extra Spark application adds latency to deep semantic tests and must be measured separately from ordinary fast feedback.
- Existing accepted evidence remains point-in-time evidence for `P1-4.3-V1`; `P1-4.3-V2` requires new offline, parse, accepted-path, and blocked-path proof.

## Reconsideration triggers

Re-open this decision if Airflow gains a supported lightweight task mechanism that can evaluate the governed Iceberg evidence without duplicating Spark runtime startup, or if promotion decisions move to an independently authenticated policy service with equivalent audit and fail-closed guarantees.

## Authoritative references

- Stratus architecture, Data Quality Subsystem and Orchestration Model
- `docs/implementation/airflow_orchestration.md`
- `jobs/spark/src/main/java/dev/stratus/jobs/spark/PromotionGate.java`
- `platform/airflow/developer/dags/stratus_bronze_to_silver.py`
- `platform/airflow/developer/dags/stratus_silver_to_gold.py`
