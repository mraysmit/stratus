# Stratus Implementation Plan

## 1. Purpose

This document defines how the Stratus data fabric platform is built and verified, increment by increment.

The architecture document defines what the platform is and why each component exists. This document defines the order in which the platform is assembled, what each increment delivers, and how each increment is verified before the next begins.

The guiding principle is simple: **build the stack from the bottom up**. Storage before tables. Tables before compute. Compute before orchestration. Query before governance. Identity last, hardening what already works. Each increment should leave the platform in a working, demonstrable state.

Reference: [on_prem_data_fabric_architecture.md](on_prem_data_fabric_architecture.md)

---

## 2. Stack and Build Order

The Stratus platform is built in layers. Each layer depends on the one below it being stable.

```text
Layer 7 — Identity and Security      FreeIPA · Keycloak · TLS hardening
Layer 6 — Governance                 Apache Atlas · Apache Ranger
Layer 5 — Orchestration              Apache Airflow
Layer 4 — Query                      Trino
Layer 3 — Compute                    Apache Spark
Layer 2 — Tables and Catalog         Apache Iceberg · Apache Polaris
Layer 1 — Storage                    MinIO
```

Implementation proceeds layer by layer. No layer is considered complete until its verification tests pass and the functional outcome is demonstrated.

---

## 3. Increment 1 — Storage Foundation

### What we are building
MinIO object storage — the persistence substrate for all platform data.

### Why this is first
Every other component in the stack writes to or reads from object storage. Nothing else can be verified without it. MinIO must be running, accessible, and correctly structured before Iceberg, Polaris, Spark, or anything else is touched.

### What is delivered

- MinIO cluster deployed on-prem (single node for development; multi-node erasure-coded for production)
- Bucket structure created per the architecture domain and lifecycle zone model:
  - `stratus-landing` — raw source file ingestion zone
  - `stratus-bronze` — raw Iceberg data
  - `stratus-silver` — conformed Iceberg data
  - `stratus-gold` — curated Iceberg data
  - `stratus-platform` — platform-internal tables (quality results, audit, maintenance metadata)
- TLS enabled on MinIO endpoints
- Service account credentials created for platform services (Spark, Polaris, Airflow)
- MinIO Console accessible for operational visibility

### Verification

| Test | Pass condition |
|---|---|
| Bucket creation | All five buckets exist and are accessible |
| Write test | A test file can be written to each bucket using service account credentials |
| Read test | A written file can be read back and content verified |
| TLS | All connections enforce TLS; plaintext connection is rejected |
| Credential isolation | Service account A cannot read or write to a bucket it has no policy for |
| Console | MinIO Console is accessible and shows correct bucket state |

### Demonstrated outcome
Object storage is operational. A file written to `stratus-landing` can be read back. Service accounts are isolated. The storage foundation is ready for Iceberg.

---

## 4. Increment 2 — Tables and Catalog

### What we are building
Apache Iceberg table format and Apache Polaris REST catalog — the semantic layer that turns MinIO object storage into a governed, multi-engine table platform.

### Why this comes second
Without Iceberg and a catalog, the storage layer is just buckets and files. Polaris is the single metadata control point that all compute engines (Spark, Trino, Flink) will use to discover and access tables. It must be stable before any engine is added.

### What is delivered

- Apache Polaris deployed and reachable
- Polaris configured with namespaces matching the bronze / silver / gold / platform zone structure
- Polaris connected to MinIO as its underlying storage location
- A bronze Iceberg table created via Polaris to verify the end-to-end table creation path
- A silver Iceberg table and a gold Iceberg table created to verify namespace isolation
- `platform.quality.check_results` Iceberg table created in the platform namespace (schema per architecture §5.3)
- Iceberg table maintenance operations verified via the Iceberg Java API: snapshot expiry, file compaction, orphan cleanup

### Verification

| Test | Pass condition |
|---|---|
| Polaris reachable | Polaris REST API responds on configured endpoint |
| Namespace creation | Bronze, silver, gold, and platform namespaces exist in Polaris |
| Table creation | An Iceberg table can be created in each namespace via the Polaris REST API |
| Table resolution | A created table can be resolved by name and its location in MinIO confirmed |
| Data write | A parquet data file can be written to a table location and the Iceberg snapshot updated |
| Data read | Written data can be read back via the Iceberg Java API using the table's current snapshot |
| Schema enforcement | Writing a record that violates the table schema is rejected |
| Snapshot expiry | Expired snapshots are removed; data files for expired snapshots are eligible for cleanup |
| Compaction | Small files in a table can be rewritten into fewer larger files |
| Orphan cleanup | Orphaned files not referenced by any snapshot are identified and removed |
| `check_results` table | `platform.quality.check_results` exists with the correct schema and accepts a written record |

### Demonstrated outcome
Iceberg tables exist in MinIO, managed by Polaris. A parquet file written via the Iceberg API can be read back via the same API. The platform has a real table layer, not just storage.

---

## 5. Increment 3 — Batch Compute

### What we are building
Apache Spark — the primary batch ETL and transformation engine.

### Why this comes third
Spark is the engine that populates and transforms Iceberg tables. It depends on both MinIO (storage) and Polaris (catalog) being operational. Spark jobs are meaningless without a stable table layer to write to.

### What is delivered

- Apache Spark cluster deployed (standalone or YARN mode for initial deployment)
- Spark configured to use Apache Polaris as its Iceberg catalog
- Spark configured to read and write to MinIO using service account credentials
- An ingestion job: reads a CSV or JSON file from `stratus-landing`, writes it as an Iceberg table in `stratus-bronze`
- A transformation job: reads from bronze, applies type normalisation and deduplication, writes to `stratus-silver`
- A materialisation job: reads from silver, aggregates, writes a summary table to `stratus-gold`
- A maintenance job: runs snapshot expiry and compaction on a target table using Spark actions
- A data quality job: runs schema conformance, completeness, and uniqueness checks on a dataset; writes results to `platform.quality.check_results`
- A promotion gate: reads quality outcomes from `platform.quality.check_results` for a dataset run; blocks promotion if any blocking check failed

### Verification

| Test | Pass condition |
|---|---|
| Spark connects to Polaris | Spark session resolves tables via the Polaris REST catalog |
| Spark connects to MinIO | Spark can read and write parquet files in MinIO buckets |
| Ingestion job | Source file in landing zone produces a bronze Iceberg table with correct row count and schema |
| Transform job | Bronze table produces a silver table with deduplication applied; row counts match expectations |
| Materialisation job | Silver table produces a gold summary table with correct aggregates |
| Quality job — pass | A clean dataset produces PASS outcomes in `platform.quality.check_results` |
| Quality job — fail | A dataset with nulls in a mandatory column produces a FAIL outcome with correct detail |
| Promotion gate — pass | Dataset with all PASS outcomes is promoted |
| Promotion gate — block | Dataset with a FAIL blocking outcome is not promoted; failure reason is recorded |
| Maintenance job | Compaction reduces file count on a fragmented table; snapshot count is reduced by expiry |
| Atlas lineage payload | Each job produces a lineage event payload (logged; not yet sent to Atlas) |

### Demonstrated outcome
Data flows from a raw source file through bronze, silver, and gold Iceberg tables via Spark. Quality checks run and gate promotion. The batch data pipeline works end to end.

---

## 6. Increment 4 — Orchestration

### What we are building
Apache Airflow — the scheduler and control-plane for batch workflows.

### Why this comes fourth
Spark jobs exist but nothing runs them on a schedule or manages dependencies between them. Airflow provides the control plane that sequences ingestion, transformation, quality checks, promotion, and maintenance into reliable, observable workflows.

### What is delivered

- Apache Airflow deployed with PostgreSQL metadata database
- Airflow configured with service account credentials to submit Spark jobs
- DAGs created for:
  - **Ingestion DAG** — detect new files in landing zone → run ingestion job → run quality checks → promote to bronze
  - **Bronze-to-silver DAG** — run transform job → run quality checks → run promotion gate → promote to silver
  - **Silver-to-gold DAG** — run materialisation job → run quality checks → promote to gold
  - **Maintenance DAG** — daily: snapshot expiry + compaction for active tables; weekly: orphan file cleanup
- Each DAG emits structured success/failure events
- Airflow alerts configured for job failure and SLA breach

### Verification

| Test | Pass condition |
|---|---|
| Airflow reachable | Airflow web UI accessible; DAGs listed |
| Ingestion DAG | Triggered by new file in landing zone; completes with bronze table updated |
| Bronze-to-silver DAG | Runs on schedule; silver table updated; quality gate enforced |
| Silver-to-gold DAG | Runs on schedule; gold table updated |
| Promotion gate in DAG | A deliberately failed quality check halts the DAG at the gate task; downstream tasks do not run |
| Maintenance DAG | Snapshot count reduced on active tables after run |
| Retry behaviour | A transiently failed task retries and succeeds on second attempt |
| Alert | A permanently failed task triggers an alert |
| SLA | A DAG that exceeds its defined SLA duration triggers an SLA miss alert |

### Demonstrated outcome
The batch pipeline runs on a schedule without manual intervention. Quality gates are enforced automatically. A failed job alerts rather than silently producing bad data.

---

## 7. Increment 5 — Interactive Query

### What we are building
Trino — the shared interactive SQL query plane over governed Iceberg datasets.

### Why this comes fifth
Data now exists in curated Iceberg tables, orchestrated by Airflow. Before governance is added, the platform needs a verified query layer so analysts can access curated data. Trino is the default open SQL interface over Polaris-managed Iceberg tables.

### What is delivered

- Trino cluster deployed and configured with the Iceberg connector pointing at Apache Polaris
- Trino configured to read from MinIO using service account credentials
- Bronze, silver, and gold datasets queryable via Trino SQL
- `platform.quality.check_results` queryable via Trino
- Trino query latency and row count verified against known dataset sizes

### Verification

| Test | Pass condition |
|---|---|
| Trino connects to Polaris | Trino resolves Iceberg tables via the Polaris REST catalog |
| Bronze query | `SELECT count(*) FROM bronze.namespace.table` returns correct row count |
| Silver query | Silver table queryable; deduplicated row count matches Spark output |
| Gold query | Gold summary table queryable; aggregates match Spark output |
| Quality results query | `SELECT * FROM platform.quality.check_results WHERE status = 'FAILED'` returns correct rows |
| Schema enforcement | Query against a column that does not exist fails with a clear error |
| Cross-namespace query | A query joining bronze and silver tables returns correct results |

### Demonstrated outcome
An analyst can run SQL against curated Iceberg datasets via Trino without touching Spark or any file system. The query layer is operational and returns correct results.

---

## 8. Increment 6 — Metadata and Governance

### What we are building
Apache Atlas and Apache Ranger — the metadata, lineage, classification, and access control plane.

### Why this comes sixth
The data is flowing and queryable. Now it needs to be governed. Atlas provides the metadata and lineage record. Ranger enforces access policy. Both depend on the rest of the platform being stable — you cannot govern what does not yet exist.

### What is delivered

- Apache Atlas deployed with embedded JanusGraph (BerkeleyDB) and embedded Solr
- Atlas configured with FreeIPA LDAP for user authentication (placeholder — full FreeIPA in Increment 7)
- Apache Ranger deployed with usersync pointed at a local user store (placeholder — full FreeIPA in Increment 7)
- Atlas entity types registered for Iceberg datasets, namespaces, and pipeline runs
- Spark jobs updated to publish metadata and lineage payloads to Atlas on completion:
  - dataset registration on first write
  - lineage: source → bronze, bronze → silver, silver → gold
  - quality status update after each quality job run
- Ranger policies created for bronze, silver, and gold zones:
  - platform engineers: read/write all zones
  - domain analysts: read silver and gold for their domain only
  - service accounts (Spark, Flink, Airflow): write access to assigned zones
- Atlas classifications applied to sensitive test datasets: `PII`, `CONFIDENTIAL`
- Ranger tag-based policies verified: a user without PII access is denied a query on a PII-classified table

### Verification

| Test | Pass condition |
|---|---|
| Atlas reachable | Atlas UI accessible; entity search returns results |
| Dataset registration | A bronze table created by Spark appears in Atlas with correct owner, sourceSystem, zone, and classification |
| Lineage — ingest | Atlas shows lineage from external source to bronze table |
| Lineage — transform | Atlas shows lineage from bronze to silver |
| Lineage — materialise | Atlas shows lineage from silver to gold |
| Quality status | Atlas entity for a dataset reflects `quality_status = PASSED` after a passing quality run |
| Ranger policy — allow | A user in `analysts-<domain>` can query silver and gold tables for their domain via Trino |
| Ranger policy — deny | A user in `analysts-<domain>` cannot query bronze tables |
| Classification policy | A user without PII access is denied a Trino query on a PII-classified table |
| Lineage completeness | Every Spark job run produces a lineage event in Atlas; no job completes without publishing |

### Demonstrated outcome
Every dataset in the platform has an Atlas entry with ownership, lineage, and quality status. Access to sensitive datasets is enforced by Ranger classification policy, not by process convention alone.

---

## 9. Increment 7 — Identity and Security Hardening

### What we are building
FreeIPA and Keycloak — the Linux-native identity foundation — and full TLS hardening across all platform services.

### Why this comes last
Identity integration touches every component. Hardening it last means each component is already working correctly before authentication and authorisation complexity is layered on top. Debugging a broken Spark job while also debugging Kerberos is a reliable way to make both harder.

### What is delivered

- FreeIPA deployed: Kerberos KDC, LDAP directory, Dogtag PKI, DNS
- Platform service accounts registered in FreeIPA: `svc-spark`, `svc-flink`, `svc-airflow`, `svc-polaris`, `svc-trino`
- FreeIPA groups created per the architecture group model: `platform-admins`, `platform-engineers`, `data-stewards-<domain>`, `analysts-<domain>`, `consumers-gold`
- MIT Kerberos clients installed and configured on all compute nodes
- SSSD configured on all Linux hosts for FreeIPA integration
- Keycloak deployed and configured as OIDC broker backed by FreeIPA
- Polaris configured to authenticate via Keycloak OIDC tokens
- Airflow web UI configured to authenticate via Keycloak
- Ranger usersync pointed at FreeIPA LDAP; groups imported; policies migrated to group-based
- Atlas configured to use FreeIPA LDAP for user authentication
- All Spark and Flink jobs use Kerberos keytabs for service authentication
- Trino configured with Kerberos for internal cluster auth; OIDC via Keycloak for client access
- TLS certificates replaced with FreeIPA Dogtag-issued certificates across all services
- MinIO server-side encryption enabled for `stratus-gold` and `stratus-platform` buckets

### Verification

| Test | Pass condition |
|---|---|
| FreeIPA Kerberos | A service account can obtain a Kerberos ticket via keytab |
| FreeIPA LDAP | Ranger usersync imports all groups and users from FreeIPA |
| Keycloak OIDC | A Keycloak token can be obtained for a service principal and used to authenticate to Polaris |
| Polaris auth | A request to Polaris without a valid OIDC token is rejected with 401 |
| Airflow UI auth | Airflow web UI redirects unauthenticated users to Keycloak login |
| Spark Kerberos | A Spark job submitted with a valid keytab runs successfully; a job with no keytab is rejected |
| Ranger group policy | A user added to `analysts-<domain>` in FreeIPA gains silver/gold read access within minutes of group sync |
| TLS everywhere | All inter-service connections use TLS with FreeIPA-issued certificates; connections without TLS are rejected |
| Encryption at rest | A file written to `stratus-gold` is stored encrypted; direct file access without a valid key returns ciphertext |
| No shared credentials | No service uses a shared password; every service authenticates via keytab or OIDC token |

### Demonstrated outcome
The platform is fully secured. Every service authenticates via FreeIPA Kerberos or Keycloak OIDC. Access policy is enforced by Ranger against FreeIPA groups. No service uses shared credentials. Sensitive data is encrypted at rest and in transit.

---

## 10. Increment Summary

| Increment | Builds | Demonstrated outcome |
|---|---|---|
| **1 — Storage** | MinIO | Files written and read; buckets isolated; TLS enforced |
| **2 — Tables and Catalog** | Iceberg + Polaris | Iceberg tables created, written, and read via Polaris; maintenance operations work |
| **3 — Batch Compute** | Spark | Data flows from landing to bronze to silver to gold; quality gates enforced |
| **4 — Orchestration** | Airflow | Batch pipeline runs on schedule; failures alert; quality gates halt bad promotions |
| **5 — Interactive Query** | Trino | Analysts query curated Iceberg data via SQL without touching infrastructure |
| **6 — Governance** | Atlas + Ranger | Lineage recorded; classifications enforced; access policy controls data zone access |
| **7 — Identity** | FreeIPA + Keycloak | All services secured; Kerberos and OIDC authentication; no shared credentials |

---

## 11. What This Plan Does Not Cover

The following are explicitly deferred and belong to later increments:

- **Streaming and CDC** — Kafka, Kafka Connect, Debezium, and Flink are not part of this plan. They are the next major wave after the batch and governance foundation is stable and proven.
- **Firebolt Core** — optional serving acceleration. Not considered until the Iceberg and governance foundations are verified in production.
- **Multi-environment promotion** — development, staging, and production environment separation is a operational maturity concern for after the single-environment foundation works.
- **Self-service data discovery** — policy-driven glossary workflows and self-service onboarding follow after Atlas and Ranger are operating reliably.

---

## 12. Design Documents

- [on_prem_data_fabric_architecture.md](on_prem_data_fabric_architecture.md) — full architecture specification and component decisions
