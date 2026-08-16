# Spark Compose cluster

The disposable developer Spark cluster: one standalone master and two workers,
attached to the Ceph harness network so they resolve both the object store and
the Polaris catalog by their published names.

This is the reduced topology `P1-3.1-D1` defines. It is not a production
deployment and cannot become one: there is no master recovery, no Spark
authentication, no transport encryption, and no restricted submission. Those
are named with their replacement tasks in the promotion manifest in
[spark_compute.md](../../../docs/implementation/spark_compute.md).

## Layout

| Path | Purpose |
|---|---|
| `compose.yaml` | Master and two workers, loopback-published, on the external Ceph network |
| `config/spark-defaults.conf.template` | Catalog and storage binding, rendered at startup from the providers' `connection.env` |
| `scripts/lifecycle/` | Start and stop |
| `scripts/verify/` | Cluster check, catalog principal bootstrap, live test wrapper |
| `scripts/lib/` | Shared library and the image artifact resolver |
| `logs/`, `evidence/`, `certs/`, `.env` | Local disposable state; all ignored |

## Prerequisites

The Ceph, OpenBao, and Polaris harnesses must be running: this harness attaches
to a network it does not own and pulls its credentials from a secret store it
does not run (ADR-P1-003, ADR-P1-004). Startup fails with the exact command to
run when a provider is missing.

Two build steps the harness deliberately does not perform, because `P1-0.1`
keeps image and artifact assembly out of the harness scripts:

```bash
bash scripts/lib/spark-compose-resolve-artifacts.sh
docker build -f ../image/Dockerfile -t stratus/spark-runtime:dev ../image
./mvnw -pl :stratus-spark-jobs -am package -DskipTests
```

## Ports

Published on loopback only. The master web UI moves to 8090 on the host
because the Ceph dashboard proxy already publishes 8080; the in-container
ports remain the documented 8080, 8081, and 7077.

| Host | Container | Purpose |
|---|---|---|
| 7077 | 7077 | Master RPC, for submissions |
| 8090 | 8080 | Master web UI |
| 8091, 8092 | 8081 | Worker web UIs |

## Configuration is rendered, not written

`config/spark-defaults.conf` is generated at startup from
`spark-defaults.conf.template` by filling the providers' published endpoint,
catalog name, and network values. It is ignored by git, and the template
carries placeholders rather than endpoints: ADR-P1-003 forbids a consumer from
copying a provider's values into its own files, because the copy goes stale
silently.

The rendered file also holds the catalog principal secret, which is the second
reason it is never committed.

## Trust

Spark trusts two disposable lab CAs, held in one JVM truststore built at
startup: Ceph's, which signs the object-store certificate, and Polaris's,
which signs the catalog certificate. Neither harness signs for the other,
because a signing key never crosses a harness boundary.

## What the tests cover

`scripts/verify/spark-compose-run-live-tests.sh` runs the `spark-integration`
suites: cluster registration and capacity, runtime image contents and its
artifact lock, catalog namespace resolution, an Iceberg write and read landing
in the governed zone, an S3A raw object round trip, a forged principal secret
being refused, and the full batch pipeline through ingestion, quality,
promotion blocking, transform, materialisation, and maintenance.

Offline guardrails on this harness run in every build and need nothing
running: loopback-only ports, no credential in a tracked file, no provider
endpoint copied into the template, a pinned image, teardown that survives a
missing `.env`, and no provider started transitively.

## Test observability

The runner defaults `STRATUS_LOG_LEVEL` to `DEBUG` and writes one complete,
timestamped transcript under `logs/`. Stratus code logs through SLF4J 2.x with
Spark's Log4j2 provider. The run ID is propagated into the host test JVM and
container-side submissions so records from both driver paths can be correlated.

INFO includes test/class/suite boundaries, client connection and shutdown,
every SQL completion, catalog refreshes, platform-job phases, external command
outcomes, and p50/p95 timing summaries. DEBUG adds sanitized SQL and command
detail plus job diagnostic records. SQL values and command output are bounded,
and credential-bearing options, configuration keys, bearer tokens and URI user
information are redacted before they reach a record.
