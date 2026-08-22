# Stratus Production Deployment Hardening and Readiness Plan

> **Status: deferred later implementation stage.** This plan is deliberately inactive while the
> complete Stratus system is being implemented and functionally accepted in development.

**Current stage:** Development implementation and functional acceptance.

**Later stage:** Production deployment hardening and readiness.

## 1. Purpose

This document owns the later conversion of the development-proven Stratus system into an
operationally supportable production deployment. It does not own application features, data-flow
semantics, component selection, protocol behavior, or integration discovery. Those must already be
working and accepted in development before this stage starts.

The development environment uses the same component versions, application artifacts, APIs, data
contracts, security semantics, integration paths, and verification suites intended for production.
Production changes deployment qualities around that proven system: immutable publication and
promotion, supported host topology, availability, capacity, managed identity and secret sources,
trusted PKI, backup and restore, disaster recovery, monitoring, alerting, audit retention, patching,
rollback, support ownership, and formal operational acceptance.

Production requirements must not block development implementation or a developer gate unless they
reveal a fundamental functional or architectural incompatibility. Such an exception requires an
explicit decision record naming the incompatibility and the affected development task.

## 2. Deferred entry gate

This plan may become active only when:

- all applicable development gates are accepted;
- the integrated development verification suite passes across the implemented Stratus scope;
- a development-system acceptance record identifies the exact component versions, artifacts,
  configuration contracts, data set, test results, known functional limitations, and owners;
- every development-only deployment condition has a mapped production replacement task;
- no unresolved functional defect prevents the system from performing its intended end-to-end
  workload in development.

Neither a missing production registry, production certificate authority, HA topology, production
backup system, production monitoring platform, nor production change process prevents development
acceptance. Those are outputs of this later stage. A version or protocol combination that cannot be
made production-capable is different: it is an architectural incompatibility and must be resolved
in development.

## 3. Stage boundary

| Proven before this stage | Implemented in this stage |
|---|---|
| component versions and compatibility | approved build service and artifact repositories |
| application and verifier behavior | immutable image publication, signing, SBOM and provenance |
| service APIs and protocol integrations | production host placement and failure domains |
| end-to-end data and control flows | HA, capacity, RTO and RPO controls |
| authentication and authorization semantics using development identities | managed identities, secret injection, rotation and break-glass controls |
| TLS behavior using development trust material | production PKI issuance, renewal, expiry monitoring and rollback |
| functional metrics, logs and correlation | platform ingestion, retention, dashboards, alerts and on-call routing |
| component-local recovery behavior where practical | production backup, restore, DR and timed failure drills |
| deterministic developer lifecycle and verification | environment promotion, release approval, rollback and operational signoff |

The production stage must retain the accepted functional contracts. Hardening is not permission to
change application semantics without returning the affected work to development verification.

## 4. Workstreams

### 4.1 Artifact and release hardening

Activate and decompose `P1-0.1` into assignable tasks for the build service, artifact repository,
container registry, publishing identity, read-only deployment identity, vulnerability policy, SBOM,
provenance, signing or attestation, immutable promotion, rollback, and durable evidence export.

### 4.2 Component deployment hardening

Activate the existing production (`P`) and production recovery (`R`) task rows in the component
plans. They remain recorded there because they preserve component-specific knowledge, but they are
inactive backlog until this plan's entry gate passes. Their dependencies must point to accepted
development artifacts and gates, not to superseded development evidence.

### 4.3 Security and identity hardening

Replace development credentials, local trust material, and reduced identity fixtures with the
approved FreeIPA, Keycloak, PKI, secret-management, least-privilege, rotation, audit and break-glass
controls. Rerun the same functional suites after replacement.

### 4.4 Availability, recovery and operations

Deploy supported failure-domain topologies, monitoring, logging, alerting, backup, restore,
capacity, patching and incident procedures. Execute the component recovery tasks and preserve timed
evidence, defects and reruns.

### 4.5 Final readiness acceptance

Execute [stratus_phase1_operational_readiness.md](../operations/stratus_phase1_operational_readiness.md)
only after the applicable component hardening tasks are accepted. That checklist is the final
acceptance instrument; it is not a prerequisite for development implementation.

## 5. Activation order

1. Freeze the accepted development-system manifest and verification baseline.
2. Complete artifact and release hardening under decomposed `P1-0.1` tasks.
3. Apply component production deployment tasks in dependency order.
4. Apply production identity, secrets and PKI controls.
5. Rerun the unchanged functional suites on the hardened deployment.
6. Execute availability, backup, restore, DR, capacity and observability drills.
7. Complete the operational-readiness checklist and obtain formal acceptance.

No task in this activation order should be reported as current development work before the deferred
entry gate passes.

## 6. Source plans

- [Phase 1 implementation plan](stratus_implementation_plan_phase1.md)
- [Phase 2 implementation plan](stratus_implementation_plan_phase2.md)
- [Phase 3 planning baseline](stratus_implementation_plan_phase3.md)
- [Phase 1 operational readiness](../operations/stratus_phase1_operational_readiness.md)
- [Task-track audit](task_track_audit.md)

