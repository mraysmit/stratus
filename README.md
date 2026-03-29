# Stratus

An on-prem data fabric platform built on open standards, governed data lifecycle, and separated compute concerns.

The key design decision is that **Apache Iceberg is the foundational abstraction**. Every analytical dataset — bronze, silver, and gold — is an Iceberg table. Without that constraint the platform degenerates into a file swamp.

## Architecture Overview

```text
                         Users / Apps
                  BI · SQL · APIs · ML · AI
                            │
               ┌────────────┴────────────┐
               ▼                         ▼
        Firebolt Core            Spark SQL / Notebook
      (optional serving)        (engineering access)
               └────────────┬────────────┘
                            ▼
                    Apache Iceberg Tables
                   bronze / silver / gold
                            │
            ┌───────────────┼───────────────┐
            ▼               ▼               ▼
        Spark           Flink        Table Maintenance
      batch ETL      streaming       compaction etc.
            └───────────────┼───────────────┘
                            ▼
                  MinIO Object Storage
              raw files + Iceberg data/meta

  ┌──────────────────────────────────────────────────┐
  │ Governance / Control Plane                       │
  │ Atlas · glossary · lineage · classifications     │
  │ Airflow · scheduling · dependencies · operations │
  └──────────────────────────────────────────────────┘
```

## Core Components

| Component | Role |
|---|---|
| **MinIO** | S3-compatible durable object storage for raw files, Iceberg data and metadata |
| **Apache Iceberg** | Open table format — schema/partition evolution, snapshots, time travel, multi-engine access |
| **Apache Spark** | Batch ETL/ELT, backfills, historical reprocessing, silver/gold materialisation |
| **Apache Flink** | CDC ingestion, event streams, continuous enrichment, near-real-time Iceberg writes |
| **Apache Atlas** | Technical metadata catalog, business glossary, lineage, classification, ownership |
| **Apache Airflow** | Bounded workflow orchestration, Spark scheduling, promotion gates, table maintenance |
| **Firebolt Core** | Optional low-latency SQL serving over curated Iceberg datasets |
| **Kafka** | Recommended event backbone for streaming source data into Flink |

## Data Lifecycle

| Zone | Purpose | Typical Producers |
|---|---|---|
| **Bronze** | Raw / lightly normalised, append-biased, source-fidelity data | Batch file landing, CDC feeds, Flink ingestion |
| **Silver** | Conformed, deduplicated, typed, reference-enriched enterprise data | Spark transforms, Flink enrichment |
| **Gold** | Consumption-ready marts, KPIs, aggregates, semantic views | Spark/SQL materialisation |

All three zones are implemented as **Iceberg tables**, not folder conventions.

## Architecture Principles

1. **Open formats over proprietary lock-in** — Iceberg tables on object storage, not vendor-specific formats.
2. **Storage and table semantics are separated** — MinIO stores files; Iceberg provides the semantic contract.
3. **Streaming and batch are separate compute concerns** — Flink for unbounded streams, Spark for bounded workloads.
4. **Governance is first-class** — Metadata, lineage, ownership, and classification are built in from day one.
5. **Orchestration is not streaming** — Airflow orchestrates finite workflows; Flink runs continuous pipelines.
6. **Query serving is optional and pluggable** — Firebolt (or equivalent) sits northbound, replaceable without platform impact.

## Control Planes

The design explicitly separates three planes:

- **Data Plane** — MinIO, Iceberg tables, Spark, Flink, Firebolt
- **Metadata & Governance Plane** — Atlas, glossary, lineage, classification, stewardship
- **Orchestration & Operations Plane** — Airflow, retries, alerts, maintenance scheduling, promotions

## Phased Roadmap

| Phase | Delivers | Outcome |
|---|---|---|
| **1 — Foundation** | MinIO, Iceberg standard, Spark, Airflow, Atlas basics, bronze/silver/gold | Governed batch lakehouse |
| **2 — Streaming** | Kafka, Flink, CDC pipelines, lineage automation, Iceberg maintenance | Near-real-time ingestion |
| **3 — Serving** | Firebolt Core, curated business marts, semantic views | Low-latency consumption |
| **4 — Self-Service** | Policy-driven classification, dataset discovery, domain templates | Scalable operating model |

## Key Risks

- **File swamp** — mitigated by mandating Iceberg for all governed datasets.
- **Multi-engine contention** — mitigated by defining per-layer write ownership boundaries.
- **Governance shelfware** — mitigated by making metadata publication mandatory in every pipeline.
- **Orchestration sprawl** — mitigated by keeping Airflow focused on finite workflows only.

## Design Documents

- [docs-design/on_prem_data_fabric_architecture.md](docs-design/on_prem_data_fabric_architecture.md) — full architecture specification


## Status

Architecture and design baseline. Java scaffold is minimal (JDK 26, no runtime dependencies yet).
