# Stratus Airflow platform

This directory owns the Stratus Airflow runtime and its developer and production
deployments. The target client image uses Airflow 3.3.1 on Python 3.14 and the
Spark and Amazon providers required to orchestrate the Stratus data plane.

The original `P1-4.1-S1` scripts below reproduce the developer-only image accepted
on 2026-08-17. They are retained as historical evidence, but their host-side
Spark archive and PySpark source-distribution path is superseded for new builds.
Do not run them to unblock the developer deployment. `P1-4.1-S2` will replace
that path with a pinned official Spark Java 21 registry layer, a small build
context, a focused PySpark compatibility gate, and publication by immutable
digest. The decision and timings are recorded in
[`docs/implementation/airflow_spark_runtime_reassessment_20260818.md`](../../docs/implementation/airflow_spark_runtime_reassessment_20260818.md).

The superseded `P1-4.1-S1` image boundary is split into four operations:

1. `image/scripts/build/airflow-image-resolve-artifacts.sh` downloads and
   verifies the approved Python and Spark inputs.
2. `image/scripts/build/airflow-image-build.sh` assembles the image without
   resolving or downloading dependencies.
3. `image/scripts/tests/airflow-image-smoke-test.sh` proves the runtime versions,
   provider imports, Java client, and Spark client.
4. `image/scripts/tests/airflow-image-vulnerability-scan-test.sh` exports the
   image and scans the archive without exposing the Docker daemon socket to the
   scanner container.

The following commands reproduce historical evidence only. They are not the
current developer workflow:

```bash
bash platform/airflow/image/scripts/build/airflow-image-resolve-artifacts.sh
bash platform/airflow/image/scripts/build/airflow-image-build.sh
bash platform/airflow/image/scripts/tests/airflow-image-smoke-test.sh
bash platform/airflow/image/scripts/tests/airflow-image-vulnerability-scan-test.sh
```

The resolver writes only to the ignored `image/artifacts/` directory. Release
automation must override both Docker build arguments with approved
digest-qualified references; the checked-in defaults are already pinned to the
multi-platform digests recorded in `image/artifact-lock.properties`.

The replacement `P1-4.1-S2` build must pull digest-qualified Airflow and Spark
source layers directly through the container engine, publish the accepted result,
and record its digest. Airflow lifecycle startup consumes that result and never
invokes image resolution, image assembly, or security scanning.

The scan writes JSON evidence below `image/artifacts/trivy-output/`. It always
inventories fixable High/Critical findings, then applies an explicit acceptance
gate: any Critical occurrence fails the script. High findings remain visible in
the report for reachability analysis, remediation, or a time-bounded waiver.
The tracked residual inventory and disposition requirements are recorded in
[`image/vulnerability-review.md`](image/vulnerability-review.md). Developer use
is accepted through 2026-09-16 by
[`image/vulnerability-waiver.md`](image/vulnerability-waiver.md); production
promotion is explicitly prohibited.

The 2026-08-17 Trivy 0.74.0 scan of image
`sha256:89db37a79b60dd9224874afca3a4b57afadbeab0a205f8835958fecea259bc97`
passed with zero Critical findings. It retained 84 High occurrences representing
35 unique package/CVE pairs, all in the upstream Spark/Hadoop JAR set. Debian,
Python, Go-binary, and Rust-binary High findings are zero. The residual JAR
findings remain open for upstream upgrade and reachability analysis. Their
developer-only acceptance is time-bounded by the recorded waiver; the
zero-Critical result alone does not accept them.

The official Airflow 3.3.1 Python 3.14 image currently embeds Python 3.14.3.
Upstream Python 3.14.6 is newer, so the patch-level lag is monitored as an
upstream base-image dependency rather than hidden by a bespoke Python rebase.

The Airflow submission client is Spark 4.1.3, while the separately validated
Increment 3 developer cluster remains on Spark 4.1.2. Both stay on Spark's 4.1
compatibility line, but `P1-4.2-D1` must prove live submission compatibility or
align the exact patch versions before the orchestration developer gate passes.

The image removes unused vulnerable surfaces inherited from its pinned upstream
distributions: LiteLLM, Ray and its private vendored dependencies, the Docker
client, the `uv`/`uvx` package-manager binaries, PySpark's duplicate bundled JAR
tree, and the Derby server JAR in the canonical Spark distribution. The smoke
test proves those components are absent while still exercising the required
Airflow, provider, Java, and Spark client interfaces.

The full Java orchestration verifier and its DAG execution scenarios remain the
scope of `P1-4.3-V1`. The image smoke test is the provider/import evidence for
the shared `P1-4.1-S1` artifact baseline, not a substitute for that verifier.
