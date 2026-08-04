# Catalog Conformance Verifier

`stratus-catalog-verifier` is the product-neutral conformance suite for the
Stratus table-catalog capability: an Iceberg REST catalog (Apache Polaris)
over the Stratus object storage (Ceph RGW). It proves, against the live
products only, the chain every compute engine will rely on: the zone
namespaces resolve, a table can be created, written, read back, and
purge-dropped through the catalog, the files land inside the governed zone
location, and a forged principal credential is refused.

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
`iceberg-orc` is required; and parquet-java needs Hadoop's `Configuration`
class, supplied by the shaded `hadoop-client-api`/`-runtime` pair. All
versions are owned by `build-support/stratus-bom`.

Open under the Increment 2 task track: provisioning the permanent
`platform.quality_check_results` table, table maintenance verification
(snapshot expiry, compaction, orphan cleanup), and engine principals.
Prerequisite: `storage` verification passed against a live cluster.
