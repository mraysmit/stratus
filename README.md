# Stratus

An on-prem data fabric platform built on open standards, governed data lifecycle, and separated compute concerns.

The foundational decision is that **Apache Iceberg is the mandatory table abstraction**. Every analytical dataset — bronze, silver, and gold — is an Iceberg table. Without that constraint the platform degenerates into a file swamp.

## Architecture Overview

```text
                    ┌───────────────────────────────────────────────┐
                    │                 Users / Apps                  │
                    │ BI / SQL / APIs / ML / Data Science / AI     │
                    └───────────────────────────────────────────────┘
                                          │
                         ┌────────────────┴────────────────┐
                         │                                 │
                         ▼               ▼                 ▼
          ┌─────────────────────┐  ┌──────────────┐  ┌──────────────────────┐
          │   Firebolt Core     │  │    Trino     │  │ Spark SQL / Notebook │
          │ low-latency serving │  │ shared query │  │ engineering access   │
          └─────────────────────┘  └──────────────┘  └──────────────────────┘
                         │                 │                 │
                         └─────────────────┴─────────────────┘
                                          │
                                          ▼
                              ┌─────────────────────────┐
                              │   Apache Iceberg Tables │
                              │ bronze / silver / gold  │
                              └─────────────────────────┘
                                          │
                         ┌────────────────┼────────────────┐
                         ▼                ▼                ▼
             ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
             │ Apache Spark     │  │ Apache Flink     │  │ Table Maintenance│
             │ batch ETL / ELT  │  │ streaming / CDC  │  │ compaction etc.  │
             └──────────────────┘  └──────────────────┘  └──────────────────┘
                                          │
                                          ▼
                          ┌───────────────────────────────┐
                          │       Ceph RGW Object Storage │
                          │  raw files + Iceberg data /   │
                          │  metadata files + manifests   │
                          └───────────────────────────────┘

  ┌─────────────────────────────────┐      ┌──────────────────────────────────────┐
  │      Apache Polaris             │      │   Kafka / Kafka Connect / Debezium   │
  │      REST Catalog               │      │   (Phase 2+ — CDC + event backbone) │
  │  metadata control plane         │      │                                      │
  │  consulted by all engines       │      │                                      │
  └─────────────────────────────────┘      └──────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────────────────────┐
  │ Governance / Control Plane                                                   │
  │ Apache Atlas (JanusGraph + embedded Solr) — metadata, lineage, glossary     │
  │ Apache Ranger — policy enforcement, classification-driven access control     │
  │ Airflow — orchestration, scheduling, promotion gates, maintenance            │
  │ FreeIPA — Kerberos, LDAP, PKI          Keycloak — OIDC for REST services   │
  └──────────────────────────────────────────────────────────────────────────────┘
```

## Core Components

| Component | Role |
|---|---|
| **Ceph RGW** | S3-compatible durable object storage for raw files, Iceberg data and metadata |
| **Apache Iceberg** | Open table format — schema/partition evolution, snapshots, time travel, multi-engine access |
| **Apache Polaris** | Central REST catalog — multi-engine metadata control point for Spark, Flink, Trino |
| **Apache Spark** | Batch ETL/ELT, backfills, historical reprocessing, quality checks, silver/gold materialisation |
| **Apache Flink** | CDC ingestion, event streams, continuous enrichment, near-real-time Iceberg writes |
| **Trino** | Default shared interactive SQL query plane over governed Iceberg datasets |
| **Apache Kafka** | Durable event backbone for CDC and streaming (Phase 2+) |
| **Kafka Connect** | Connector framework for source system integration (Phase 2+) |
| **Debezium** | CDC connector — captures database change events into Kafka (Phase 2+) |
| **Apache Atlas** | Technical metadata catalog, business glossary, lineage, classification, ownership |
| **Apache Ranger** | Policy enforcement — classification-driven access control across all engines |
| **Apache Airflow** | Bounded workflow orchestration, Spark scheduling, promotion gates, table maintenance |
| **FreeIPA** | Linux-native identity provider — Kerberos KDC, LDAP directory, PKI |
| **Keycloak** | OIDC broker for REST-facing services (Polaris, Airflow UI) |
| **Prometheus + Grafana** | Metrics collection, dashboards, and alerting |
| **Grafana Loki** | Log aggregation |
| **Firebolt Core** | Optional low-latency SQL serving over curated Iceberg datasets (Phase 3+) |

## Data Lifecycle

| Zone | Purpose | Typical Producers |
|---|---|---|
| **Bronze** | Raw / lightly normalised, append-biased, source-fidelity data | Batch file landing, CDC feeds, Flink ingestion |
| **Silver** | Conformed, deduplicated, typed, reference-enriched enterprise data | Spark transforms, Flink enrichment |
| **Gold** | Consumption-ready marts, KPIs, aggregates, semantic views | Spark/SQL materialisation |

All three zones are implemented as **Iceberg tables**, not folder conventions.

## Data Quality

The quality subsystem is built entirely from platform components — no additional framework.

- **Spark** executes quality checks as bounded jobs (schema, completeness, uniqueness, freshness, referential integrity, business rules)
- **Iceberg** stores quality results in `platform.quality.check_results` — append-only, permanent audit trail
- **Airflow** enforces promotion gates — blocking check failures halt the pipeline
- **Atlas** carries current quality status as a metadata attribute on every dataset
- **Trino** provides ad-hoc query access to quality results

Overrides are explicit, require a named data steward, and are written as auditable records.

## Architecture Principles

1. **Open formats over proprietary lock-in** — Iceberg tables on object storage, not vendor-specific formats.
2. **Storage and table semantics are separated** — Ceph RGW stores files through the S3 API; Iceberg provides the semantic contract.
3. **Streaming and batch are separate compute concerns** — Flink for unbounded streams, Spark for bounded workloads.
4. **Governance is first-class** — Metadata, lineage, ownership, and classification are built in from day one.
5. **Orchestration is not streaming** — Airflow orchestrates finite workflows; Flink runs continuous pipelines.
6. **Query serving is layered** — Trino is the default open SQL plane; Firebolt is optional acceleration northbound of Iceberg.

## Control Planes

The design explicitly separates three planes:

- **Data Plane** — Ceph RGW, Iceberg tables, Apache Polaris, Spark, Flink, Kafka + Kafka Connect + Debezium, Trino, Firebolt
- **Metadata & Governance Plane** — Atlas, Ranger, glossary, lineage, classification, stewardship
- **Orchestration & Operations Plane** — Airflow, retries, alerts, maintenance scheduling, promotion gates

## Identity and Security

The platform is Linux-only with no Windows dependencies.

- **FreeIPA** — Kerberos authentication, LDAP directory, Dogtag PKI; single identity source of truth
- **Keycloak** — OIDC broker backed by FreeIPA for REST-facing services
- **Ranger** — enforces data access policy backed by FreeIPA groups; tag-based policies driven by Atlas classifications
- **Polaris** — enforces catalog-level access at namespace and table level
- All inter-service communication is TLS; certificates issued by FreeIPA Dogtag PKI

## Phased Roadmap

| Phase | Delivers | Outcome |
|---|---|---|
| **1 — Foundation** | Ceph RGW, Iceberg, Polaris, Spark, Trino, Airflow, Atlas, Ranger, FreeIPA, Keycloak, bronze/silver/gold | Governed batch lakehouse |
| **2 — Streaming** | Kafka, Kafka Connect, Debezium, Flink, CDC pipelines, Atlas on platform Kafka, lineage automation | Near-real-time ingestion |
| **3 — Serving** | Firebolt Core, curated business marts, semantic views, domain data products | Low-latency consumption |
| **4 — Self-Service** | Policy-driven classification, dataset discovery, domain templates | Scalable operating model |

## Key Risks

- **File swamp** — mitigated by mandating Iceberg for all governed datasets.
- **Multi-engine write contention** — mitigated by one-writer-per-table ownership rules and explicit compaction assignment.
- **Governance shelfware** — mitigated by making metadata and lineage publication mandatory in every pipeline.
- **Orchestration sprawl** — mitigated by keeping Airflow focused on finite workflows only.
- **Firebolt too early** — mitigated by keeping it optional until the Iceberg and governance foundations are stable.
- **Atlas operational underestimation** — mitigated by running embedded JanusGraph/BerkeleyDB/Solr in Phase 1 with no external cluster dependencies.

## Design Documents

- [docs-design/stratus_on_prem_data_fabric_architecture.md](docs-design/stratus_on_prem_data_fabric_architecture.md) — full architecture specification

## Status

Architecture and design baseline. Java scaffold is minimal (JDK 26, no runtime dependencies yet).
