# Catalog Conformance Verifier

`stratus-catalog-verifier` is the product-neutral conformance suite for the
Stratus table-catalog capability: an Iceberg REST catalog (Apache Polaris)
over the Stratus object storage (Ceph RGW). It proves, against the live
products only, the chain every compute engine will rely on: the zone
namespaces resolve, a table can be created, written, read back, evolved in
place, and purge-dropped through the catalog in every data zone, superseded
snapshots expire while the current one stays readable, the files land
inside the governed zone location, a row leaving a required column null is
refused without advancing the snapshot, and a forged principal credential is
refused.

It also verifies the permanent `platform.quality_check_results` table
(architecture §5.3): the harness bootstrap
(`polaris-compose-bootstrap-catalog.sh`) must have provisioned it with
exactly the fourteen documented columns, the zone and checked-at-day
partitioning, and the append-only marker property, and it must accept a
quality result record and serve it back. The documented shape lives in
`QualityCheckResultsTableDefinition` in this module's main tree (plain
Java, no Iceberg dependency), pinned offline by a unit test; the deployed
table is compared against it live. The record appended by the write-path
check is a genuine quality result of the conformance run and is retained —
the table is an append-only audit trail.

Tests are tagged `catalog-integration` and excluded from the default build.
Run them against the running Compose harnesses through the wrapper, which
supplies the environment, the live opt-in switch, and the CA truststore:

```bash
bash platform/polaris/compose-service/scripts/verify/polaris-compose-run-catalog-tests.sh
```

Configuration comes from the environment (`STRATUS_POLARIS_URI`,
`STRATUS_POLARIS_CLIENT_ID`/`_SECRET`, `STRATUS_POLARIS_CATALOG`,
`CEPH_RGW_ENDPOINT`, `CEPH_RGW_ACCESS_KEY`/`_SECRET_KEY`); validation fails
by name before any network operation, and plain HTTP requires the explicit
disposable-development overrides. Offline unit tests cover the configuration
and client-property mapping.

Classpath notes, verified against Iceberg 1.11.0: the REST catalog client
ships inside `iceberg-core` (there is no separate client artifact); the
generic record readers dispatch on ORC even for Parquet-only use, so
`iceberg-orc` is required; and Iceberg's own `Parquet$WriteBuilder`
constructor requires Hadoop's `Configuration` class, supplied by the
shaded `hadoop-client-api`/`-runtime` pair. That attribution is proven,
not assumed: removing the pair (2026-08-06) fails every write with
`NoClassDefFoundError` at `Parquet.java:182` — in the builder constructor,
before codec or compression settings are reached — so neither uncompressed
writes nor parquet-java's `withCodecFactory` injection point can avoid the
dependency at this layer. The coupling is upstream and deliberate there:
apache/iceberg#10180 (writing Parquet without Hadoop's `Configuration`)
is closed as not planned, and the codec-layer coupling
apache/parquet-java#2818 remains open with no fix version. At runtime the
`Configuration` feeds parquet-java's `CodecFactory`; which Hadoop classes
actually load then depends on the codec (see the compression note below).
The Iceberg main branch (checked 2026-08-06) carries the identical
constructor, so an Iceberg upgrade will not change this. No Hadoop
service or filesystem is used at runtime. All versions are owned by
`build-support/stratus-bom`. The client's
`rest.auth.type` and `oauth2-server-uri` properties are set explicitly:
leaving them to be inferred logs a warning per connection, and Iceberg's
automatic token-endpoint fallback is deprecated for removal
(apache/iceberg#10537), so an upgrade would break implicit configuration.

Compression: the suite writes zstd explicitly, matching the Iceberg 1.4+
engine default. This is load-bearing on Windows workstations: a bare
write builder falls back to legacy gzip, whose Hadoop codec chain
(`CodecFactory` → `CodecPool` → `GzipCodec` → `DataChecksum` →
`Shell`) triggers the `Did not find winutils.exe` warning and stack
trace at `Shell` class-load; zstd (zstd-jni) never touches that chain,
and switching removed the warning from transcripts entirely (verified
2026-08-06). Anyone adding a gzip-compressed write will reintroduce it —
it is benign (Hadoop falls back to builtin Java classes), but zstd is
both quieter and representative of engine behavior.

Remaining transcript warnings, assessed 2026-08-06: single JDK notices
about `sun.misc.Unsafe` use (caffeine, via Iceberg) and a native-library
load (zstd-jni). Both are upstream-owned and tracked through BOM version
upgrades, not suppressed locally.

Open under the Increment 2 task track: table maintenance verification
beyond snapshot expiry — compaction, manifest rewrite, delete files, and
orphan cleanup — which is carried by `P1-2.5-D1`, and engine principals,
which belong to the increment that introduces each engine. The
schema-enforcement negative added 2026-08-07 has not yet run against a live
catalog; it is the one check in this suite without a transcript.
Prerequisite: `storage` verification passed against a live cluster.
