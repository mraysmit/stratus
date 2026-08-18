# Airflow and Spark Runtime Assembly Reassessment - 2026-08-18

## 1. Status and decision

Status: **accepted direction; implementation and live acceptance pending**.

The original `P1-4.1-S1` Airflow image remains historical developer evidence under
`WAIVER-P1-4.1-S1-20260817`. Its local assembly path is not the target for new
builds. `P1-4.1-D1` live acceptance is paused until `P1-4.1-S2` replaces that path.

The replacement design separates these concerns:

1. the Airflow control-plane and Python provider layer;
2. the Spark submission runtime and Java 21 layer;
3. image publication and security assessment; and
4. deployment and workflow validation.

An ordinary developer deployment or live test must consume an already-published,
digest-qualified image. It must not resolve dependencies, assemble the image, or
run a vulnerability scan as part of startup.

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
- Publish the completed image once, record its digest and provenance, and make
  developer and production manifests consume that digest without rebuilding.
- Do not expose a container-engine control socket to Airflow tasks or scanners.

The multi-stage boundary is an assembly mechanism, not permission to use floating
tags. Both source images and the result remain content-addressed and auditable.

## 4. PySpark compatibility decision gate

The Spark provider 6.3.1 package declares `pyspark-client` as a normal dependency
and `pyspark` as an optional extra. Its upstream changelog explains that PySpark
was removed from the default provider installation because it is larger than
400 MB, while non-Spark-Connect modes are directed to install the extra.
`SparkSubmitOperator`, however, delegates execution to the `spark-submit` binary.

The refactor must not assume that the 455.5 MB PySpark source package is either
required or safely removable. Before locking `requirements.lock`, run a focused
compatibility proof that covers:

1. provider and `SparkSubmitOperator` imports;
2. provider dependency validation;
3. `spark-submit --version` and executable discovery;
4. a real JAR submission to the Stratus standalone Spark cluster;
5. success, non-zero exit, status, logging, and secret-redaction behavior; and
6. any Python task or hook path that Stratus actually intends to support.

If the supported contract truly requires the PySpark Python distribution, it
must be supplied as a separately cached, immutable layer without copying another
Spark JAR tree through the host context. An unsupported dependency suppression or
an untested `PYTHONPATH` workaround is not acceptable.

## 5. Validation tiers and time budgets

Build, deployment, integration, and release evidence are separate gates:

| Tier | Scope | Execution policy | Initial budget objective |
|---|---|---|---|
| Repository guardrails | locks, digests, Compose structure, scripts, Java policy | ordinary offline `mvn verify`; no containers | under 60 seconds when dependencies are warm |
| Image smoke | imports, versions, Java, `spark-submit`, removed surfaces | once per candidate image | under 2 minutes after required image layers are cached |
| Developer lifecycle | PostgreSQL migration, Airflow health, two start/stop cycles | consume a prebuilt digest; never build | under 3 minutes on the reference developer host |
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
- `P1-4.1-S2`: new shared remediation task for the registry-layer Spark client,
  PySpark compatibility decision, publication, scan, smoke test, and timings.
- `P1-4.1-D1`: harness implementation and offline guardrails are complete; live
  two-cycle acceptance is paused until a `P1-4.1-S2` digest is available.
- `P1-4.2-D1`: remains pending and supplies the real Spark submission proof.

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

