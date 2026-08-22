# Stratus Airflow platform

This directory owns the Stratus Airflow runtime and its developer and production
deployments. The target client image uses Airflow 3.3.1 on Python 3.14 and the
Spark and Amazon providers required to orchestrate the Stratus data plane.

`P1-4.1-S2`, `P1-4.1-D1`, and `P1-4.2-D1` were accepted for development on
2026-08-22. The current image uses a pinned official Spark Java 21 OCI source
stage, a small host context, the `spark-submit` command-line runtime, and
lightweight `pyspark-client`; it does not install the full PySpark distribution.
The LocalExecutor/PostgreSQL deployment passed two lifecycle cycles, and an
immutable Airflow DAG submitted a packaged Java job that exercised distributed
Spark plus Polaris/Ceph-backed Iceberg. Exact identities, timings, scan results
and limitations are in
[`development-acceptance-20260822.md`](development-acceptance-20260822.md).
Production publication and deployment hardening remain a separate later stage.

The current `P1-4.1-S2` image boundary is split into three operations:

1. `image/scripts/build/airflow-image-resolve-artifacts.sh` downloads and
   verifies the approved small Python inputs; Spark arrives through its pinned
   OCI source stage rather than the host context.
2. `image/scripts/build/airflow-image-build.sh` assembles the image without
   resolving or downloading dependencies.
3. `image/scripts/tests/airflow-image-acceptance-test.sh` orchestrates the smoke
   and daemon-isolated vulnerability scan and records phase timings.

Run the current development image acceptance from any directory with Bash 4+:

```bash
bash platform/airflow/image/scripts/build/airflow-image-resolve-artifacts.sh
bash platform/airflow/image/scripts/build/airflow-image-build.sh
bash platform/airflow/image/scripts/tests/airflow-image-acceptance-test.sh
```

The resolver writes only to the ignored `image/artifacts/` directory. Release
automation must override both Docker build arguments with approved
digest-qualified references; the checked-in defaults are already pinned to the
multi-platform digests recorded in `image/artifact-lock.properties`.

The accepted `P1-4.1-S2` build pulls digest-qualified Airflow and Spark source
layers directly through the container engine and records the verified local
development image identity. Airflow lifecycle startup consumes that result and
never invokes image resolution, image assembly, or security scanning. Registry
publication, final SBOM/provenance and immutable promotion belong to the later
production deployment hardening stage.

The scan writes JSON evidence below `image/artifacts/trivy-output/`. It always
inventories fixable High/Critical findings, then applies an explicit acceptance
gate: any Critical occurrence fails the script. High findings remain visible in
the report for reachability analysis, remediation, or a time-bounded waiver.
The current S2 inventory and development disposition are recorded in
[`image/development-vulnerability-review-s2.md`](image/development-vulnerability-review-s2.md).
The older [`image/vulnerability-review.md`](image/vulnerability-review.md) and
[`image/vulnerability-waiver.md`](image/vulnerability-waiver.md) apply only to
the superseded S1 image and remain historical evidence.

The 2026-08-17 Trivy 0.74.0 scan of image
`sha256:89db37a79b60dd9224874afca3a4b57afadbeab0a205f8835958fecea259bc97`
passed with zero Critical findings. It retained 84 High occurrences representing
35 unique package/CVE pairs, all in the upstream Spark/Hadoop JAR set. Debian,
Python, Go-binary, and Rust-binary High findings are zero. The residual JAR
findings remain open for upstream upgrade and reachability analysis. Their
developer-only acceptance is time-bounded by the recorded waiver; the
zero-Critical result alone does not accept them.

The current 2026-08-22 S2 image is
`sha256:27f05eb17bd3ad3504faf1c53089085ddd6e31fae48c2c47716b8bc3342f6a91`.
Its acceptance scan passed with zero Critical and reported 61 High occurrences
across 38 unique package/CVE pairs. Those High findings remain visible and must
be reassessed with upstream runtime changes; the historical S1 waiver neither
applies to nor silently accepts them.

The official Airflow 3.3.1 Python 3.14 image currently embeds Python 3.14.3.
Upstream Python 3.14.6 is newer, so the patch-level lag is monitored as an
upstream base-image dependency rather than hidden by a bespoke Python rebase.

The Airflow submission client is Spark 4.1.3, while the separately validated
Increment 3 developer cluster remains on Spark 4.1.2. `P1-4.2-D1` accepted this
pairing through a live packaged job that completed distributed execution and
Polaris/Ceph Iceberg create/write/read/drop operations.

The image removes unused vulnerable surfaces inherited from its pinned upstream
distributions: LiteLLM, Ray and its private vendored dependencies, the Docker
client, the `uv`/`uvx` package-manager binaries, PySpark's duplicate bundled JAR
tree, and the Derby server JAR in the canonical Spark distribution. The smoke
test proves those components are absent while still exercising the required
Airflow, provider, Java, and Spark client interfaces.

The full Java orchestration verifier and its DAG execution scenarios remain the
scope of `P1-4.3-V1`. The image smoke test is the provider/import evidence for
the shared `P1-4.1-S2` artifact baseline, and the submission probe is its
cross-component compatibility evidence; neither substitutes for that verifier.
