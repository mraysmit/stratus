# Stratus Airflow platform

This directory owns the Stratus Airflow runtime and, in later increments, its
developer and production deployments. The first increment establishes a
reproducible client image for Airflow 3.3.1 on Python 3.14 with the Spark and
Amazon providers required to orchestrate the Stratus data plane.

The image boundary is intentionally split into three operations:

1. `image/scripts/build/airflow-image-resolve-artifacts.sh` downloads and
   verifies the approved Python and Spark inputs.
2. `image/scripts/build/airflow-image-build.sh` assembles the image without
   resolving or downloading dependencies.
3. `image/scripts/tests/airflow-image-smoke-test.sh` proves the runtime versions,
   provider imports, Java client, and Spark client.
4. `image/scripts/tests/airflow-image-vulnerability-scan-test.sh` exports the
   image and scans the archive without exposing the Docker daemon socket to the
   scanner container.

Run all scripts from any directory using Git Bash or another Bash 4+ shell:

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
