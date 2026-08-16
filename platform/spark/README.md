# Apache Spark

Product integration for Apache Spark, the batch compute engine of the Stratus
platform. Spark reads and writes Iceberg tables through the Polaris catalog
and reaches object storage through Ceph RGW.

**Status: developer track live-revalidated 2026-08-16; production track not
started.** The reduced cluster, its bindings to Polaris and Ceph, the full batch
pipeline, and correlated telemetry passed the authoritative 46-test live run in
`compose-cluster/logs/spark-conformance-tests-20260816T071313Z.log`. Remaining
phase metrics, feedback-tier separation and production telemetry export are
tracked explicitly in the implementation plan. The implementation plan and
task track are owned by
[spark_compute.md](../../docs/implementation/spark_compute.md) (Increment 3),
whose `P1-3.1-D1`, `P1-3.1-V1` through `P1-3.1-V4`, `P1-3.2-D1`,
`P1-3.3-V1`, and `P1-3.6-P2` record the state.

Production placement, master recovery, Spark authentication and transport
encryption, and the Ceph-backed history server are on the production track and
are named in that document's promotion manifest. Nothing here is production
ready.

## Directory map

| Directory | Purpose | State |
|---|---|---|
| [`compose-cluster/`](compose-cluster/README.md) | Disposable developer harness: one master and two workers attached to the Ceph harness network per [ADR-P1-003](../../docs/decisions/ADR-P1-003-composed-harness-internal-dns.md) | Live-validated |
| `aws-runtime/` | Iceberg Spark/AWS runtime with Iceberg's AWS SDK isolated from Hadoop S3A | Offline-validated |
| `image/` | Runtime image: one Hadoop 3.4.3 line plus the isolated Iceberg runtime, with a checksum lock | Image-inspected; live revalidation pending |
| `tests/` | Live cluster, binding, worker-distribution, latency, and pipeline suites, plus offline guardrails | Offline-validated; live revalidation pending |

The batch jobs themselves are not here. Stratus-authored workload code lives
under [`jobs/spark/`](../../jobs/spark/) by the repository layout rules; this
directory holds the product integration that runs it.

## Quick start

The Spark harness is a consumer: it never starts Ceph, OpenBao, or Polaris on
your behalf and fails with a remediation command when one is missing. Bring
those up first (see the
[operations runbook](../../docs/operations/harness_operations_runbook.md) §2),
then:

```bash
# once: resolve the runtime artifacts and build the image
bash compose-cluster/scripts/lib/spark-compose-resolve-artifacts.sh
docker build -f image/Dockerfile -t stratus/spark-runtime:dev image

# once per job change: build the jar the cluster mounts
./mvnw -pl :stratus-spark-jobs -am package -DskipTests

bash compose-cluster/scripts/lifecycle/spark-compose-startup.sh
bash compose-cluster/scripts/verify/spark-compose-bootstrap-principal.sh

# worker registration and everything else is proven by the live suite
bash compose-cluster/scripts/tests/spark-compose-run-live-tests.sh
```

## Identity

Spark runs as `svc-spark`, which exists in two places and is created by this
increment rather than inherited:

- an RGW identity with bucket policies on the five Stratus buckets, provisioned
  by the Ceph harness from its `service-identities.conf` and published to
  OpenBao ([ADR-P1-004](../../docs/decisions/ADR-P1-004-openbao-secret-distribution.md))
- a Polaris principal created by `spark-compose-bootstrap-principal.sh`

No credential is written to a tracked file. The RGW key pair is pulled from
the secret store and the catalog secret is generated into the ignored `.env`.
