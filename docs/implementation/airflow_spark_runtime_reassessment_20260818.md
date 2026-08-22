# Airflow and Spark Runtime Assembly Reassessment - 2026-08-18

**Current stage:** Development implementation and functional acceptance.

**Later stage:** Production deployment hardening and readiness.

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
| Security and provenance | SBOM, archive scan, waiver/reachability review, publication | release/image pipeline, not ordinary developer startup | measured separately; no developer-loop budget |

These are objectives to be measured on the reference host, not reasons to hide
work or skip assertions. A budget breach fails the performance review and records
the slow phase separately from functional acceptance.

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
- `P1-4.3-V1`: is in progress. Its first landing-to-bronze source contract and
  Airflow parse/registry proof pass; live execution, the remaining DAG behavior,
  and the executable orchestration verifier remain.

The Java policy remains Java 21 for Stratus-owned builds and Spark/Airflow
runtimes. Component-mandated exceptions, including the selected Trino release's
Java requirement, remain explicit and independently recorded.

## 7. Sources

- Airflow Spark provider changelog:
  https://airflow.apache.org/docs/apache-airflow-providers-apache-spark/stable/changelog.html
- Airflow `SparkSubmitOperator` API:
  https://airflow.apache.org/docs/apache-airflow-providers-apache-spark/stable/_api/airflow/providers/apache/spark/operators/spark_submit/index.html
- Official Apache Spark OCI images:
  https://hub.docker.com/r/apache/spark/tags
- Apache Spark 4.1.3 documentation:
  https://spark.apache.org/docs/4.1.3/

