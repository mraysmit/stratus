# Airflow and Spark Runtime Assembly Reassessment - 2026-08-18

**Current stage:** Development implementation and functional acceptance.

**Later stage:** Production deployment hardening and readiness.

**Document classification:** Accepted active decision. This is not an archived progress report;
the measured trigger evidence is historical, while the runtime assembly and validation-tier rules
remain current.

## 1. Status and decision

Status: **implemented and accepted for development on 2026-08-22**.

The original `P1-4.1-S1` Airflow image remains historical developer evidence under
`WAIVER-P1-4.1-S1-20260817`. Its local assembly path is not the target for new
builds. `P1-4.1-S2`, the `P1-4.1-D1` two-cycle lifecycle, and the
`P1-4.2-D1` live Spark submission proof have now passed. Exact image identity,
phase timings, runtime versions, vulnerability results, lifecycle observations,
and cross-component submission evidence are recorded in
[`platform/airflow/development-acceptance-20260822.md`](../../platform/airflow/development-acceptance-20260822.md).

The replacement design separates these concerns:

1. the Airflow control-plane and Python provider layer;
2. the Spark submission runtime and Java 21 layer;
3. development image assessment and later production publication; and
4. deployment and workflow validation.

An ordinary developer deployment or live test must consume an already-built local development
image. It must not resolve dependencies, assemble the image, or run a vulnerability scan as part of
startup. Registry publication, final SBOM/provenance/signing and digest-qualified promotion are
added later by the production deployment hardening stage.

## 2. Evidence that triggered the reassessment

The 2026-08-18 Java 21 rebuild attempt exposed a structural cost rather than a
slow query:

| Operation or input | Observed result |
|---|---|
| PySpark 4.1.3 source distribution | 455.5 MB |
| Spark 4.1.3 binary archive | 546.3 MB |
| Combined host-side Spark/PySpark payload | more than 1 GB before the remaining wheels |
| Artifact resolution | 663.689 seconds |
| Docker Desktop context transfer | stopped after about 305 seconds with only about 310 MB transferred |
| Accepted Airflow image from the prior baseline | not present locally, forcing reconstruction |

The PySpark source distribution duplicates a large part of the Spark runtime and
the current Dockerfile deletes its bundled JAR tree after installation. The
current path therefore downloads, verifies, transfers, and expands duplicate
content before discarding it. Passing that content from a Windows checkout to a
Linux BuildKit daemon makes the developer loop especially expensive.

No partial image was accepted. The interrupted build was stopped and the orphaned
test container was removed. The ignored verified downloads were retained as cache
evidence and may be removed later through an explicit cleanup operation.

## 3. Target image architecture

`P1-4.1-S2` implements the following contract:

- Pin the official Airflow 3.3.1 Python 3.14 base by immutable digest.
- Pin the official Spark 4.1.3 Scala 2.13, Java 21, Python 3 image by immutable
  digest.
- Obtain `/opt/spark` and its Java 21 runtime from the pinned Spark image through
  a multi-stage OCI build or an equivalently immutable registry-layer mechanism.
  Do not place the 546.3 MB Spark archive in the host build context.
- Keep the host build context to Dockerfile, locks, and small verified Python
  artifacts. The resolver must not recreate a second complete Spark runtime.
- Preserve one canonical Spark/Hadoop JAR tree and the existing hardening and
  scan controls.
- Record the local development image identity, smoke and scan evidence, and make
  the developer lifecycle consume it without rebuilding.
- After development-system acceptance, publish the same accepted build contract once through
  `P1-0.1`, record its digest, SBOM and provenance, and make production manifests consume that
  digest without rebuilding.
- Do not expose a container-engine control socket to Airflow tasks or scanners.

The multi-stage boundary is an assembly mechanism, not permission to use floating
tags. Both source images and the result remain content-addressed and auditable.

## 4. PySpark compatibility decision gate

The Spark provider 6.3.1 package declares `pyspark-client` as a normal dependency
and `pyspark` as an optional extra. Its upstream changelog explains that PySpark
was removed from the default provider installation because it is larger than
400 MB, while non-Spark-Connect modes are directed to install the extra.
`SparkSubmitOperator`, however, delegates execution to the `spark-submit` binary.

The implementation did not assume that the 455.5 MB PySpark source package was
either required or safely removable. Its focused compatibility proof covered:

1. provider and `SparkSubmitOperator` imports;
2. provider dependency validation;
3. `spark-submit --version` and executable discovery;
4. a real JAR submission to the Stratus standalone Spark cluster;
5. success, non-zero exit, status, logging, and secret-redaction behavior; and
6. any Python task or hook path that Stratus actually intends to support.

The accepted Stratus contract uses `SparkSubmitOperator` and the packaged Java
job through the OCI-sourced `spark-submit` client. Provider dependencies are
satisfied by the lightweight `pyspark-client` 4.1.3 package; the full PySpark
distribution and its duplicate JAR tree are absent. A live Spark 4.1.3 client to
Spark 4.1.2 cluster submission successfully performed distributed work, Polaris
catalog discovery, and an Iceberg create/write/read/drop cycle on Ceph. If a
future DAG introduces an in-process Python Spark API, its release must first add
a focused failing contract test and reassess whether a separately cached,
immutable PySpark layer is required. Unsupported dependency suppression and
untested `PYTHONPATH` workarounds remain prohibited.

## 5. Validation tiers and time budgets

Build, deployment, integration, and release evidence are separate gates:

| Tier | Scope | Execution policy | Initial budget objective |
|---|---|---|---|
| Repository guardrails | locks, digests, Compose structure, scripts, Java policy | ordinary offline `mvn verify`; no containers | under 60 seconds when dependencies are warm |
| Image smoke | imports, versions, Java, `spark-submit`, removed surfaces | once per candidate image | under 2 minutes after required image layers are cached |
| Developer lifecycle | PostgreSQL migration, Airflow health, two start/stop cycles | consume the already-built local development image; never build | under 3 minutes on the reference developer host |
| Spark submission integration | one shared live stack, one real packaged JAR, positive and negative outcomes | no repeated cluster recreation per assertion | under 5 minutes with the data plane already ready |
| Airflow API state contract | empty positive DAG, real fail-closed gate, exact task states and direct catalog no-write proof | no ingestion fixture and no Spark application | under 2 minutes after Airflow is healthy |
| Security and provenance | SBOM, archive scan, waiver/reachability review, publication | release/image pipeline, not ordinary developer startup | measured separately; no developer-loop budget |

These are objectives to be measured on the reference host, not reasons to hide
work or skip assertions. A budget breach fails the performance review and records
the slow phase separately from functional acceptance.

The later warm offline run of 65.475 seconds exceeded the under-60-second objective. Functional
acceptance remains valid, but the budget is not marked met; profiling and narrower routine feedback
remain required. The roughly 49-minute canonical Airflow suite is release/gate evidence and must
not be used as the inner development loop.

The lifecycle and Spark-submission tiers must emit phase timings. Tests should
share a suite-scoped environment where isolation permits it and use unique run
identifiers and tables rather than restarting Spark, Ceph, Polaris, or Airflow for
every trivial assertion.

## 6. Roadmap effect

- `P1-4.1-S1`: retains its dated evidence and developer-only waiver; superseded
  as the build approach for new images.
- `P1-4.1-S2`: accepted 2026-08-22. The OCI-stage Spark client, lightweight
  Python dependency contract, small build context, smoke test, zero-Critical
  scan gate, and phase timings passed.
- `P1-4.1-D1`: accepted 2026-08-22. Both LocalExecutor/PostgreSQL lifecycle
  cycles, migrations, health checks, and clean shutdowns passed.
- `P1-0.1` and `P1-4.1-P1`: later production-hardening tasks for approved build-service execution,
  publication, immutable digest, SBOM, provenance and hardened deployment.
- `P1-4.2-D1`: accepted 2026-08-22. The immutable Airflow DAG submitted the
  packaged Java probe to the existing Spark developer cluster and proved
  distributed execution, Polaris/Ceph trust, protected connection metadata,
  secret-redacted output, cleanup, and detailed phase timing.
- `P1-4.3-V1`: development-verified 2026-08-24. All live pipeline, maintenance,
  retry/alert, Deadline Alert, and public-API positive/fail-closed scenarios pass;
  the final 420.772-second full-stack run also passed independent side-effect,
  exact cleanup, secret, and zero-remaining-container checks.
- `P1-4.3-V2`: in progress from 2026-08-25. Promotion becomes an explicit Airflow
  task while each writer rechecks the same evidence; fresh live task-state proof is required.

### 2026-08-28 execution-model revision

Profiling showed that the long suites repeatedly paid Spark driver startup, executor allocation,
catalog initialization and cleanup costs to assert tiny control-plane facts. The revised contract
keeps one real accepted pipeline path, but evaluates the explicit Airflow promotion task through
Iceberg's REST catalog and generic reader without creating a `SparkContext`. The governed Spark
writer still rechecks the same evidence immediately before writing.

The blocked focused path now uses a deliberately unknown quality run, expects the gate task to fail
closed, skips all downstream Spark tasks, and proves the target is absent with a direct catalog
lookup. The public-API suite uses `stratus_api_contract_probe` for its positive trigger/state case
and the real bronze-to-silver gate for its negative case. It no longer seeds ingestion, quality or
maintenance fixtures and no longer starts independent Spark verifiers merely to re-prove API state.

Airflow live scripts default to `INFO`. The Spark runtime root logger defaults to `WARN`, while
`dev.stratus` remains at `INFO` unless `STRATUS_LOG_LEVEL=DEBUG` is requested for diagnosis. Spark
submission verbosity is disabled for routine DAG work so Airflow does not repeat the complete
rendered Spark configuration for every tiny job. Spark
event-log storage is created and write-checked by the single declared `airflow-init` service before
the long-running Airflow services start; startup no longer launches a second anonymous init
container.

The first revised focused run measured 75,496 ms for Airflow startup, 103,774 ms for landing plus
bronze quality, 106,165 ms for the accepted bronze-to-silver DAG, 50,674 ms for accepted verification
and cleanup, and 20,704 ms for the complete expected blocked DAG failure. The direct gate itself
returned the accepted verdict in about 8.5 seconds and the blocked verdict in about 7.3 seconds.
That run exposed only a missing direct-Java stdout marker in the final no-write assertion; the
catalog result and fail-closed task state were correct. A stable stdout contract replaced the
inactive logging-binding dependency before the formal rerun.

The formal rerun `airflow-bronze-to-silver-20260828T052459Z` passed in 410,717 ms.
Its accepted DAG took 101,985 ms, accepted verification and cleanup took 50,758 ms, the expected
blocked DAG failure took 22,243 ms, and the direct no-write catalog check took 4,578 ms. Airflow
startup took 125,430 ms while an offline Maven test was also running on the same workstation; that
contention-affected startup is retained as observed evidence and is not labelled as query latency.

The redesigned API run `airflow-api-orchestration-20260828T053205Z` passed in 212,751 ms, compared
with 420,772 ms for the historical V1 full-stack run. Airflow startup took 71,725 ms and the Java
API phase took 33,972 ms. Within that phase, the positive no-op scenario completed in 2,332 ms and
the real fail-closed scenario in 24,393 ms; the direct target-absence check took 3,916 ms. No Spark
application was submitted, exact downstream `upstream_failed` states passed, secret checks passed,
and checked reverse-order shutdown reported zero remaining Stratus containers.

The 2026-08-25 provider audit found Spark provider 6.3.2 and Amazon provider 9.35.0,
both released on 2026-08-23. Airflow 3.3.1's official Python 3.14 constraints still
select 6.3.1, 9.34.0 and boto3 1.43.56. Stratus retains that tested set until an
upgrade candidate passes a regenerated lock plus image, provider, Spark-submission,
landing-sensor and REST verification.

### 2026-08-25 stale-wheelhouse finding

A rebuild attempt found that the ignored local wheelhouse still held the superseded full
`pyspark-4.1.3.tar.gz` (455,504,008 bytes, SHA-256 `b600238e...`) while the tracked lock required
`pyspark-client==4.1.3` (SHA-256 `ff687ddd...`). Because the build checked only the wheelhouse's
self-generated manifest, this stale but internally consistent cache passed and produced a 458.59 MB
host context. The upstream `pyspark-client` artifact is 1.6 MB; it is distinct from the optional
455.5 MB `pyspark` distribution.

The resolver now stages downloads in a temporary directory, disables unnecessary PEP 517 build
isolation for the locked source archive, verifies all hashes, and promotes the wheelhouse only
after success, with immediate rollback and next-invocation recovery for an interrupted promotion.
The build now performs an offline `pip --dry-run` against `requirements.lock` and requires the
wheelhouse to contain exactly the artifacts selected by that report before sending any context.
The bad cache fails this check in 6.7 seconds. A refreshed resolution
completed in 33.2 seconds and restored a 9,931,122-byte wheelhouse; the next Docker build transferred
the 9.93 MB context in 1.2 seconds.

A same-day follow-up review found two limits in the first hardening pass. Pip's dry run rejected a
cache that lacked a locked requirement, but did not by itself reject an additional unreferenced
archive. The directory replacement also used two individually atomic moves without restoring the
previous directory if the second move failed. Four behavioral tests were added first and failed for
the absent exact-set, rollback and recovery behavior. The implementation now compares every
wheelhouse filename with pip's offline selection, restores the previous directory when candidate
promotion fails, and recovers an interrupted promotion when the resolver next starts. The focused
suite passed all 13 artifact tests, the pinned Airflow container selected and verified exactly seven
current artifacts, and the final 12-module offline reactor passed 305 tests with zero failures,
errors or skips. This is build-contract evidence; it does not supersede the pending image and live
V2 acceptance work below.

After both digest-pinned source images became locally available, the corrected checked-in build
completed on 2026-08-25 in 7,632 ms. It rechecked all seven hashes, reported
`wheelhouse_file_set_verified artifact_count=7`, retained a 9,931,122-byte wheelhouse, and produced
local development image ID
`sha256:27f05eb17bd3ad3504faf1c53089085ddd6e31fae48c2c47716b8bc3342f6a91`. The checked-in smoke
test then passed in 17,951 ms: Airflow 3.3.1, Python 3.14, both providers, boto3, aiohttp,
`pyspark-client`, zstandard, Java 21 and Spark 4.1.3 matched the lock; pip reported no broken
requirements; and full PySpark, LiteLLM, Ray, the Google provider, Derby server JAR, Docker client
and unused package-manager commands remained absent.

The earlier candidate attempt did not complete because Docker's first pull of the digest-pinned Spark OCI
layer ended in an external short read after 541 seconds at 420,930,690 of 463,020,878 bytes. The
later rebuilt image and smoke proof supersede this prerequisite failure; the event remains useful
only as build-history evidence.

After a later registry DNS failure and a pinned pull that made no terminal progress, an ephemeral
read-only Airflow 3.3.1 container imported both changed DAGs using the verified local provider
wheels. The resulting task IDs and edges matched the explicit-gate design. This is useful parse
evidence only; it is not a successful candidate-image build or deployed Airflow registry result.

The Java policy remains Java 21 for Stratus-owned builds and Spark/Airflow runtimes.
Component-mandated exceptions remain explicit and independently recorded in their owning plans;
the workstation runtime does not change a component's supported runtime contract.

## 7. Sources

- Airflow Spark provider changelog:
  https://airflow.apache.org/docs/apache-airflow-providers-apache-spark/stable/changelog.html
- Airflow `SparkSubmitOperator` API:
  https://airflow.apache.org/docs/apache-airflow-providers-apache-spark/stable/_api/airflow/providers/apache/spark/operators/spark_submit/index.html
- Official Apache Spark OCI images:
  https://hub.docker.com/r/apache/spark/tags
- Apache Spark 4.1.3 documentation:
  https://spark.apache.org/docs/4.1.3/
- PyPI `pyspark-client` 4.1.3 artifact metadata:
  https://pypi.org/project/pyspark-client/4.1.3/
- PyPI full `pyspark` 4.1.3 artifact metadata:
  https://pypi.org/project/pyspark/4.1.3/

