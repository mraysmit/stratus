# Documentation

This tree holds all Stratus design, decision, implementation, operations, and reference documentation. Start with the [architecture](architecture/stratus_on_prem_data_fabric_architecture.md) for the system design, then the phase implementation plans for sequencing, then the increment document for the component you are working on.

The repository's top-level directory structure is documented in [Repository Layout](reference/repository-layout.md).

## Documentation Directories

| Directory | Contents |
|---|---|
| [`architecture/`](architecture/) | The system architecture and enduring design constraints. [stratus_on_prem_data_fabric_architecture.md](architecture/stratus_on_prem_data_fabric_architecture.md) is the full architecture specification and records the component selection decisions. |
| [`decisions/`](decisions/) | Accepted architecture decision records, named `ADR-NNN-short-title.md`. Currently [ADR-P1-001](decisions/ADR-P1-001-ceph-baseline.md), which selects Ceph RGW as the Phase 1 storage baseline. |
| [`implementation/`](implementation/) | Phase implementation plans and per-increment implementation documents (see below). Each increment document carries its own implementation task track, verification steps, and acceptance gates. |
| [`operations/`](operations/) | Operational acceptance documents. [stratus_phase1_operational_readiness.md](operations/stratus_phase1_operational_readiness.md) is the Phase 1 production-readiness checklist and acceptance gate. |
| [`reference/`](reference/) | Stable repository and platform references that outlive any single phase or increment. |

## Implementation Documents

The phase plans own portfolio-level sequencing and roll-up tracking:

- [stratus_implementation_plan_phase1.md](implementation/stratus_implementation_plan_phase1.md) — Phase 1 foundation (Increments 1-7)
- [stratus_implementation_plan_phase2.md](implementation/stratus_implementation_plan_phase2.md) — Phase 2 streaming and operational maturity (Increments 8-13)
- [stratus_implementation_plan_phase3.md](implementation/stratus_implementation_plan_phase3.md) — Phase 3 query acceleration and data products

Increment documents are named for the capability they describe. The increment-to-document mapping is:

| Increment | Document | Capability |
|---|---|---|
| 1 | [ceph_storage.md](implementation/ceph_storage.md) | Ceph RGW object storage foundation |
| 2 | [iceberg_polaris_catalog.md](implementation/iceberg_polaris_catalog.md) | Iceberg table format and Polaris catalog |
| 3 | [spark_compute.md](implementation/spark_compute.md) | Spark batch compute |
| 4 | [airflow_orchestration.md](implementation/airflow_orchestration.md) | Airflow orchestration |
| 5 | [trino_query.md](implementation/trino_query.md) | Trino interactive query |
| 6 | [atlas_ranger_governance.md](implementation/atlas_ranger_governance.md) | Atlas metadata and Ranger governance |
| 7 | [freeipa_keycloak_identity.md](implementation/freeipa_keycloak_identity.md) | FreeIPA and Keycloak identity and security hardening |
| 8 | [kafka_event_backbone.md](implementation/kafka_event_backbone.md) | Kafka event backbone |
| 9 | [kafka_connect_debezium_cdc.md](implementation/kafka_connect_debezium_cdc.md) | Kafka Connect and Debezium CDC |
| 10 | [flink_streaming_compute.md](implementation/flink_streaming_compute.md) | Flink streaming compute |
| 11 | [flink_streaming_iceberg.md](implementation/flink_streaming_iceberg.md) | Streaming writes to Iceberg |
| 12 | [atlas_streaming_lineage.md](implementation/atlas_streaming_lineage.md) | Atlas event bus and lineage automation |
| 13 | [streaming_production_readiness.md](implementation/streaming_production_readiness.md) | Streaming operations and production readiness |

Also in `implementation/`:

- [minio_storage.md](implementation/minio_storage.md) and [ozone_storage.md](implementation/ozone_storage.md) — superseded Increment 1 storage alternatives, retained for historical reference after the Ceph baseline decision
- [task_track_audit.md](implementation/task_track_audit.md) — audit of the increment task tracks for implementation readiness

## Stable Engineering References

- [Code Style and Engineering Rules](reference/code_style_rules.md)
- [Maven Test and Build Commands](reference/maven_test_commands.md)
- [Repository Layout](reference/repository-layout.md)

## Naming Conventions

Phase-level plan documents may use phase numbers (for example `stratus_implementation_plan_phase1.md`). Increment documents are named for the capability they describe (for example `ceph_storage.md`, not `increment1_ceph.md`); increment numbers appear only inside plan and tracking documents. Runtime artifacts, Java packages, Maven modules, images, and deployment paths must use stable capability names as well.
