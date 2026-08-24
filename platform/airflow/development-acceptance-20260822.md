# Airflow development acceptance evidence - 2026-08-22

## Scope and decision

This record covers the development implementation of `P1-4.1-S2`, `P1-4.1-D1`,
and `P1-4.2-D1`. All three tasks are accepted for the current development stage.
This is not production-readiness, deployment-hardening, publication, provenance,
high availability, disaster recovery, or security-waiver evidence. Those controls
remain in the later production stage.

Generated transcripts and scan reports are intentionally ignored because they
can be large and can contain machine-local diagnostic state. This tracked record
preserves the stable identities, results, timings, and reproduction commands.

## P1-4.1-S2 - local development image

- Image: `stratus/airflow:dev`
- Local image ID:
  `sha256:27f05eb17bd3ad3504faf1c53089085ddd6e31fae48c2c47716b8bc3342f6a91`
- Image size: 3,732,066,621 bytes
- Airflow source: pinned Airflow 3.3.1 Python 3.14 OCI image
- Spark source: pinned `apache/spark:4.1.3-scala2.13-java21-python3-ubuntu`
  OCI image
- Host build context: 9.93 MB; the superseded path transferred more than 1 GB
  of duplicate Spark/PySpark inputs
- Python contract: `pyspark-client==4.1.3`; the 455.5 MB full PySpark source
  distribution and its duplicate JAR tree are absent
- Smoke result: Airflow 3.3.1, Python 3.14, Java 21.0.11, Spark 4.1.3, Scala
  2.13.17, provider imports, dependency consistency, and removed-surface checks
  all passed
- Scan result: 0 Critical, 61 High occurrences, and 38 unique package/CVE
  pairs. See
  [`image/development-vulnerability-review-s2.md`](image/development-vulnerability-review-s2.md).

The uninterrupted acceptance run ID was `20260822T083314Z`:

| Phase | Duration |
|---|---:|
| Resolve seven small locked Python artifacts | 20,666 ms |
| Warm/cache image build | 5,998 ms |
| Runtime smoke | 14,600 ms |
| Trivy archive scan | 212,949 ms |
| Total | 254,567 ms |

The first non-cached image build took 24,595 ms. The scanner remains outside
ordinary developer startup and lifecycle testing.

Reproduce with:

```bash
bash platform/airflow/image/scripts/tests/airflow-image-acceptance-test.sh
```

## P1-4.1-D1 - developer lifecycle

The checked-in lifecycle test completed two start, health, migration, and stop
cycles. Each cycle proved Airflow 3.3.1 API, metadata database, scheduler,
triggerer, and DAG-processor health; `LocalExecutor`; and PostgreSQL 17.10.
Shutdown preserved the PostgreSQL and Airflow log volumes.

Run ID: `airflow-lifecycle-20260822T084127Z`.

The first attempt exposed an Airflow CLI behavior: Python deprecation warnings
preceded the requested executor value on standard output. A failing repository
test was added first, and the health probe was corrected to select the final
result line without suppressing the diagnostics. Both full cycles then passed.

Reproduce with:

```bash
bash platform/airflow/developer/scripts/tests/airflow-compose-lifecycle-test.sh
```

## P1-4.2-D1 - Airflow-to-Spark submission

Run ID: `airflow-spark-20260822T090250Z`.

The checked-in integration test proved:

- the tracked DAG digest inside the scheduler exactly matched the repository
  source;
- the Spark endpoint came from the `spark_default` Airflow connection rather
  than a DAG literal;
- the scheduler mounted the rendered Spark defaults, Java truststore, packaged
  Stratus jobs JAR, and the hash-verified 112,772,254-byte Increment 3
  Iceberg/AWS driver runtime;
- the `svc-spark` object-store identity was fetched from OpenBao and injected
  only into the disposable driver environment;
- Airflow submitted a real packaged Java application using Spark 4.1.3 to the
  two-worker Spark 4.1.2 development cluster;
- distributed execution counted 1,000 records across the workers;
- Polaris TLS/OAuth succeeded and returned all four Stratus namespaces;
- an isolated one-row Iceberg table was created, written, read through Ceph
  TLS, and dropped in the `platform` namespace;
- Airflow marked the DAG run successful; and
- the transcript contained none of the generated Airflow database, Fernet,
  JWT, API, or RGW secret values.

Key observability evidence:

| Event or phase | Result |
|---|---|
| Spark application ID | `app-20260822090425-0003` |
| Distributed count | 3,607 ms; 1,000 expected and observed |
| Polaris catalog trust | 1,804 ms; four namespaces |
| Ceph/Iceberg object-store trust | 5,321 ms; one row written and read |
| Airflow DAG run | success in 23.719 seconds |
| Spark-submission phase | 25,668 ms |
| Complete integration suite | 110,629 ms |

Reproduce after starting Ceph, OpenBao, Polaris, and Spark with their checked-in
lifecycle scripts:

```bash
bash platform/airflow/developer/scripts/tests/airflow-spark-submission-test.sh
```

## Completed orchestration development work

`P1-4.3-V1` is development-verified. Its landing-to-bronze, bronze-to-silver and
silver-to-gold source contracts, metadata-policy maintenance contract,
Airflow-native four-DAG parse/registry proof, and
complete live accepted/blocked slices passed on 2026-08-23; see
[`pipeline-development-progress-20260822.md`](pipeline-development-progress-20260822.md).
The live runs proved protected landing detection, three-row ingestion, bronze
quality, quality-gated transformation and materialisation, deterministic silver
output, governed gold aggregation, silver/gold quality, deliberate promotion
blocking with no target write, independent verification, timed exact cleanup,
access/secret-key redaction, AWS SDK logging through Log4j2, and maintenance
run/skip behavior. Maintenance run
`airflow-table-maintenance-20260823T113447Z` explicitly skipped below threshold,
then compacted three files to one above threshold while preserving three rows;
its independent verification, purge cleanup, redaction and timing checks passed
in 326,099 ms. The
bronze-to-silver suite `airflow-bronze-to-silver-20260823T084502Z` completed in
356,572 ms; the full silver-to-gold accepted/blocked suite
`airflow-silver-to-gold-20260823T093453Z` completed in 716,033 ms. Retry/alert
run `airflow-retry-alert-20260824T040947Z` then proved a real transient failure
recovering on attempt two and a permanent failure emitting exactly one terminal
alert after retry exhaustion. Both retry callbacks and the terminal callback
carried numeric elapsed time and safe exception-class metadata; exception detail
was excluded from the alert, generated Airflow secrets were absent from the
transcript, and the isolated 72,759 ms suite cleaned up its Airflow stack.
Deadline run `airflow-deadline-alert-20260824T051734Z` then proved a one-second
run completed without an alert and an 18-second run exceeded its 12-second
expectation, emitted exactly one asynchronous triggerer callback, and still
completed successfully. The callback recorded 13,278 ms observed elapsed time
and a 1,278 ms breach with safe identity/timestamp fields. This proof also found
and corrected the shared LocalExecutor execution-API route from container
loopback to `airflow-api-server:8080`; both scheduled runs then passed. The
final API/orchestration run `airflow-api-orchestration-20260824T073836Z` then
passed on 2026-08-24. It
authenticated through Airflow's public REST API, passed scheduler/metadata
health, required all four Stratus DAGs from a five-DAG registry, and supplied
caller-owned run IDs. Maintenance succeeded on attempt one in 34,657 ms of
Airflow time. The deliberately blocked bronze-to-silver run failed in 18,056 ms:
the transform failed on attempt one and its downstream quality task was
`upstream_failed` without an attempt. Independent Spark verification proved the
maintenance table retained three rows after compaction from three files to one
and proved the blocked silver target was absent. The 420,772 ms suite removed
its exact Iceberg/quality/S3 fixtures, passed protected-secret checks, and stopped
all Stratus stacks with `remainingStratusContainers=0`.

Strict TDD captured four live compatibility defects before acceptance: Airflow
3.3.1 requires nullable `logical_date`; Java h2c upgrade requests are rejected by
the pinned Uvicorn listener, so the client uses HTTP/1.1; expected-failure retry
overrides must reach both the DAG processor and scheduler; and Ceph compose
commands must bind their project name explicitly so an inherited Airflow
`COMPOSE_PROJECT_NAME` cannot redirect shutdown. Each correction has an offline
regression. `P1-4.G-D` is now the next implementation-plan item; the Increment 4
developer gate remains open for its D1-D2 evidence matrix and development-state
manifest.

The post-Deadline offline reactor passed all 11 modules and 278 tests in 51.896
seconds with zero failures, errors, or skips. `git diff --check` was clean. The
live harness stopped Airflow; Spark, Polaris, OpenBao and Ceph were already
stopped, and the final Docker query found no running Stratus containers.

The final post-API offline reactor expanded to 12 modules and passed 290 tests in
1 minute 2 seconds with zero failures, errors, or skips. The live-only API test
is intentionally excluded from the ordinary offline count.
