# Stratus Implementation Plan - Phase 3

> **Status: planning baseline only.** Increments 14-19 are scoped here but their implementation documents have not been written or authorized. This plan is not an executable runbook, and no Phase 3 increment should begin until its dedicated document applies the Stratus developer/production profile contract and passes architecture review.

## 1. Purpose

This document defines how Stratus Phase 3 is built and verified.

Phase 1 establishes the governed batch lakehouse foundation. Phase 2 adds streaming and CDC. Phase 3 turns those platform capabilities into consumption-ready data products and, where justified by evidence, adds a query acceleration layer northbound of curated Iceberg datasets.

Phase 3 should not be treated as "install Firebolt." Firebolt Core is optional. The phase succeeds when curated data products are reliable, governed, discoverable, measurable, and useful to consuming teams. Query acceleration is added only where Trino and Iceberg evidence show a real serving need.

References:
- [stratus_on_prem_data_fabric_architecture.md](stratus_on_prem_data_fabric_architecture.md)
- [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)
- [stratus_implementation_plan_phase2.md](stratus_implementation_plan_phase2.md)

---

## 2. Phase 3 Entry Criteria

Phase 3 should not begin until:

- Phase 1 operational acceptance has passed.
- Phase 2 streaming operations and production readiness have passed, if streaming data products are in scope.
- Trino has measurable query performance baselines for the candidate gold datasets.
- Iceberg table maintenance is stable enough that query performance issues can be separated from table-health issues.
- Atlas ownership, stewardship, classification, and lineage are current for the candidate datasets.
- Ranger policies already enforce access through the open query plane.
- At least one domain has a named data product owner and a clear consumer use case.
- Product success criteria are defined before acceleration technology is selected.

If the platform cannot yet explain who owns a dataset, what consumer workflow it serves, what its freshness promise is, or how quality is measured, Phase 3 should pause and fix those gaps before adding serving technology.

---

## 3. Phase 3 Build Order

Phase 3 builds from product contract to acceleration:

```text
Increment 14 - Data Product Readiness
Increment 15 - Curated Business Marts
Increment 16 - Semantic Serving Views
Increment 17 - Query Acceleration Evaluation
Increment 18 - Firebolt Core Serving Layer, if justified
Increment 19 - Phase 3 Operational Readiness
```

The ordering is intentional. Data products and marts come before acceleration because acceleration should serve a known product shape. Semantic views come before serving-engine adoption because consumers need stable contracts. Firebolt Core comes only after evidence shows Trino plus Iceberg does not meet agreed serving goals.

---

## 4. Reference Documentation Audit

Reference baseline: 2026-07-10.

Before implementation, review current upstream and vendor documentation for the selected serving stack and record the approved version matrix in the Phase 3 runbook.

At minimum, review:

- Trino Iceberg connector and materialized view behavior
- Apache Iceberg table maintenance and branching/tagging features
- Apache Polaris catalog behavior for serving consumers
- Apache Atlas glossary, classifications, and lineage APIs
- Apache Ranger policy model for serving access
- Firebolt Core documentation, if Firebolt is evaluated
- BI or downstream consumption tools used by the first domain product

No Phase 3 increment should introduce a serving engine, BI access pattern, semantic layer, or data product workflow that bypasses Polaris, Atlas, Ranger, or the Iceberg table contract.

---

## 5. Increment 14 - Data Product Readiness

### What we are building

The operating contract for domain-owned data products.

### Why this is first

Phase 3 is about consumption. Before building more marts or adding acceleration, the platform needs a clear definition of what a data product is, who owns it, what quality and freshness it promises, and how consumers discover and use it.

### What is delivered

- Data product template covering owner, steward, domain, source, contract, freshness, quality, access, lineage, and consumer SLA.
- Minimum Atlas metadata requirements for a published data product.
- Ranger access policy pattern for product consumers.
- Gold table readiness checklist.
- Consumer onboarding checklist.
- Product scorecard stored in a platform-visible location.
- Pilot data product selected from an existing gold dataset.

### Verification

| Test | Pass condition |
|---|---|
| Ownership | product owner and steward are recorded in Atlas |
| Contract | schema, freshness, quality checks, and support path are documented |
| Access | Ranger policies grant access to the intended consumer group and deny others |
| Lineage | Atlas shows upstream batch or streaming lineage |
| Quality | quality results are visible for the product table |
| Consumer test | a named consumer can query the product through the approved query path |

### Demonstrated outcome

The platform has a repeatable definition of a data product and one pilot product that meets the contract.

---

## 6. Increment 15 - Curated Business Marts

### What we are building

Curated business marts over gold Iceberg data.

### Why this comes second

Business marts provide stable consumer-facing structures. They should be built from governed gold datasets, not directly from raw or intermediate tables. This gives analytics users predictable tables without weakening the platform's lineage, quality, and access-control model.

### What is delivered

- First domain mart namespace under the Polaris catalog.
- Mart table naming and ownership standard.
- Mart build jobs using Spark or Trino according to workload shape.
- Quality checks for mart row counts, dimensional keys, freshness, and aggregate parity.
- Atlas metadata and lineage for mart tables.
- Ranger policy pattern for mart consumers.
- Trino performance baseline for mart queries.

### Verification

| Test | Pass condition |
|---|---|
| Namespace | mart namespace exists in Polaris and maps to approved Ceph RGW location |
| Build | mart build job creates Iceberg tables from governed gold sources |
| Quality | mart quality checks write to `platform.quality_check_results` |
| Lineage | Atlas shows gold-to-mart lineage |
| Authorization | only approved consumer groups can query mart tables |
| Query baseline | representative Trino queries have recorded latency and resource metrics |

### Demonstrated outcome

Consumers have curated business marts that preserve the governance and quality contracts established in earlier phases.

---

## 7. Increment 16 - Semantic Serving Views

### What we are building

Stable semantic views and consumption contracts over curated marts.

### Why this comes third

Serving views let consumers work with consistent business terms and metrics while the underlying Iceberg tables continue to evolve under controlled governance. This is also where metric definitions, dimensional joins, and naming standards become product contracts.

### What is delivered

- Semantic view naming standard.
- Approved metric definitions and dimensional keys.
- View ownership model.
- Consumer-facing SQL views through Trino or the approved serving engine.
- Atlas glossary alignment for business terms and metrics.
- Ranger policies for views and underlying tables.
- Compatibility policy for changing views.

### Verification

| Test | Pass condition |
|---|---|
| Metric parity | semantic view metrics match expected mart aggregates |
| Schema stability | incompatible view changes require approval |
| Glossary link | Atlas glossary terms map to semantic fields |
| Access | consumer can access approved views without gaining unintended base-table access |
| BI smoke test | representative dashboard or client query works through the approved endpoint |

### Demonstrated outcome

Consumers can query stable business-facing views without depending on internal table layout or transformation details.

---

## 8. Increment 17 - Query Acceleration Evaluation

### What we are building

A formal evaluation of whether Trino plus Iceberg meets serving requirements, and whether Firebolt Core or another acceleration layer is justified.

### Why this comes fourth

Acceleration should be evidence-driven. If table layout, partitioning, clustering, compaction, caching, query design, and Trino configuration can meet the service goal, adding another engine increases operational surface without enough benefit.

### What is delivered

- Candidate workload inventory.
- Query latency, concurrency, cost, and freshness targets.
- Trino and Iceberg tuning pass before external acceleration.
- Benchmark dataset and query pack.
- Evaluation criteria for Firebolt Core.
- Go/no-go decision record.

### Verification

| Test | Pass condition |
|---|---|
| Workload definition | representative query pack is approved by consumers |
| Baseline | Trino latency and concurrency metrics are recorded |
| Tuning | Iceberg maintenance and Trino tuning are attempted before adding acceleration |
| Gap analysis | unmet requirements are tied to measured evidence |
| Decision | Firebolt go/no-go decision has owner, evidence, and risk assessment |

### Demonstrated outcome

The platform makes an evidence-based serving decision instead of adopting acceleration by default.

---

## 9. Increment 18 - Firebolt Core Serving Layer, If Justified

### What we are building

Firebolt Core as an optional serving/query acceleration layer over curated Iceberg datasets.

### Why this is conditional

Firebolt should sit northbound of Iceberg and serve curated data products. It should not become the foundational storage layer, ingestion layer, governance catalog, or source of truth.

### What is delivered

- Firebolt Core deployment design, if approved by Increment 17.
- Connectivity to curated Iceberg datasets.
- Serving dataset selection criteria.
- Query acceleration configuration for approved marts or views.
- Access-control integration model aligned with Ranger/Atlas policy.
- Operational dashboards and query latency alerts.
- Backout plan that preserves Trino as the open query path.

### Verification

| Test | Pass condition |
|---|---|
| Dataset access | Firebolt reads only approved curated Iceberg datasets |
| Query parity | accelerated query results match Trino results for the same snapshot |
| Performance | benchmark meets approved latency and concurrency target |
| Governance | access model does not bypass approved Ranger/Atlas controls |
| Freshness | serving layer freshness meets product contract |
| Backout | consumers can fall back to Trino for approved queries |

### Demonstrated outcome

If Firebolt is deployed, it accelerates known curated products without weakening the open Iceberg, Polaris, Atlas, Ranger, and Trino foundation.

---

## 10. Increment 19 - Phase 3 Operational Readiness

### What we are building

The operational acceptance gate for Phase 3 data products and serving.

### Why this comes last

Data products need operational ownership just like platform services. Consumers should know what they can rely on, operators should know what to monitor, and owners should know how changes are approved.

### What is delivered

- Data product runbooks.
- Consumer support and escalation model.
- Query performance dashboards.
- Product freshness, quality, and access dashboards.
- Change management procedure for marts and semantic views.
- Serving-layer incident drills if Firebolt is deployed.
- Product retirement and deprecation procedure.

### Verification

| Test | Pass condition |
|---|---|
| Product readiness | product owner, steward, SLA, quality checks, access policy, and lineage are complete |
| Consumer support | consumer can find owner, contract, and support path |
| Performance alert | slow-query or freshness alert identifies product and owner |
| Access review | product access can be reviewed by group and user |
| Change drill | compatible and incompatible view changes follow the documented process |
| Serving incident | Firebolt incident drill passes if Firebolt is deployed |

### Demonstrated outcome

Phase 3 products are operationally supportable, not just technically queryable.

---

## 11. Phase 3 Cross-Increment Traceability

| Increment | Produces | Consumed by | Cross-check required |
|---|---|---|---|
| 14 - Product readiness | product contract, owner, SLA, policy pattern | marts and serving views | Atlas owner, Ranger policy, quality evidence |
| 15 - Business marts | curated mart tables | semantic views and query evaluation | lineage, quality, Trino baseline |
| 16 - Semantic views | stable business-facing contracts | BI and serving engines | metric parity, glossary alignment, access control |
| 17 - Acceleration evaluation | go/no-go decision | Firebolt deployment, if approved | benchmark evidence and risk review |
| 18 - Firebolt serving | optional accelerated query path | product consumers | result parity, freshness, governance, backout |
| 19 - Readiness | runbooks, drills, dashboards | production product onboarding | support path, alerts, access review, change drill |

---

## 12. Phase 3 Completion Gate

Phase 3 is complete when:

- [ ] at least one pilot data product meets the approved product contract
- [ ] curated marts are built from governed gold or streaming-owned tables
- [ ] semantic views expose approved business metrics and terms
- [ ] Atlas metadata, glossary links, lineage, and ownership are current
- [ ] Ranger policies govern product and view access
- [ ] Trino query baselines are recorded for representative workloads
- [ ] Firebolt Core is either explicitly rejected with evidence or deployed with result-parity, freshness, governance, and backout evidence
- [ ] product freshness, quality, access, and query performance are observable
- [ ] product runbooks and consumer support paths exist
- [ ] product owners, platform operations, security, and governance sign off

---

## 13. What Phase 3 Does Not Cover

The following remain deferred to Phase 4 or later:

- **Self-service data marketplace workflows** - full browse/request/approval/onboarding workflows belong to Phase 4 governance maturity.
- **Enterprise-wide product rollout** - Phase 3 proves the pattern with controlled domains before scaling broadly.
- **Policy-driven classification automation** - Phase 4 expands governance workflows after product contracts are stable.
- **Replacing the open query path** - Trino remains the default open SQL path even if Firebolt is deployed for acceleration.
- **Using serving views as source-of-truth tables** - Iceberg product tables remain the governed source of truth.

---

## 14. Design Documents

- [stratus_on_prem_data_fabric_architecture.md](stratus_on_prem_data_fabric_architecture.md) - full architecture specification and component decisions
- [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md) - Phase 1 foundation implementation plan
- [stratus_implementation_plan_phase2.md](stratus_implementation_plan_phase2.md) - Phase 2 streaming implementation plan
- [stratus_phase1_operational_readiness.md](stratus_phase1_operational_readiness.md) - Phase 1 operational acceptance gate

---

## 15. Source References

- Trino Iceberg connector: https://trino.io/docs/current/connector/iceberg.html
- Apache Iceberg documentation: https://iceberg.apache.org/docs/latest/
- Apache Polaris documentation: https://polaris.apache.org/
- Apache Atlas: https://atlas.apache.org/
- Apache Ranger: https://ranger.apache.org/
- Firebolt external data and Iceberg: https://docs.firebolt.io/performance-and-observability/iceberg-and-external-data
