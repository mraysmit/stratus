# Increment 4 Airflow developer-gate record - 2026-08-24

> Point-in-time evidence: this record describes the accepted V1 repository state on 2026-08-24.
> It does not accept later changes. The explicit promotion-task V2 decision is tracked in
> [`ADR-P1-007`](../../docs/decisions/ADR-P1-007-airflow-promotion-gate-boundary.md) and requires
> new evidence before it supersedes this record.

Gate: `P1-4.G-D`

**Status: Accepted.** The repository maintainer explicitly directed completion
of all Airflow development tasks on 2026-08-24. D1 and D2 are accepted against
the complete canonical suite `airflow-development-acceptance-20260824T103411Z`.

## Scope and decision boundary

This is the durable gate package for the Increment 4 Airflow development track.
It consolidates evidence already produced by `P1-4.1-S2`, `P1-4.1-D1`,
`P1-4.2-D1`, and `P1-4.3-V1`; it does not replace their raw transcripts or
create evidence retrospectively. D1 covers observable development behavior. D2
records the development-state shortcuts that must not silently cross the later
promotion boundary.

The acceptance direction was applied only after the canonical suite completed
all offline, image, lifecycle, DAG, alert, provider, pipeline, maintenance and
public-API phases successfully, repeated the offline reactor, and confirmed zero
remaining Stratus containers. No unresolved Airflow development defect
invalidates a cited result.

## D1 evidence matrix

| D1 behavior | Producing task | Durable evidence | Result |
|---|---|---|---|
| Pinned Airflow 3.3.1 development image, Spark command-line runtime, provider imports, smoke test, zero-Critical scan, dependency inventory and phase timings | `P1-4.1-S2` | [`development-acceptance-20260822.md`](development-acceptance-20260822.md); image acceptance and scan scripts under `platform/airflow/image/scripts/tests/` | Development accepted 2026-08-22 |
| Idempotent LocalExecutor/PostgreSQL migration, start, health and shutdown | `P1-4.1-D1` | two complete lifecycle cycles in [`development-acceptance-20260822.md`](development-acceptance-20260822.md); checked lifecycle scripts under `platform/airflow/developer/scripts/lifecycle/` | Development accepted 2026-08-22 |
| Protected Spark submission with immutable DAG/JAR/runtime inputs, distributed execution, Polaris/Ceph Iceberg create/write/read/drop, correlation and secret checks | `P1-4.2-D1` | run `airflow-spark-20260822T090250Z`; [`development-acceptance-20260822.md`](development-acceptance-20260822.md) | Development accepted 2026-08-22 |
| Landing detection, ingestion, bronze quality, independent snapshot/result verification and exact cleanup | `P1-4.3-V1` | run `airflow-pipeline-20260823T071231Z`; [archived progress record](archive/pipeline-development-progress-20260822.md) | Development verified 2026-08-23 |
| Bronze-to-silver accepted promotion and a real blocking-quality failure with independent no-write proof | `P1-4.3-V1` | run `airflow-bronze-to-silver-20260823T084502Z`; [archived progress record](archive/pipeline-development-progress-20260822.md) | Development verified 2026-08-23 |
| Silver-to-gold materialisation, quality, deterministic aggregate proof and deliberately blocked no-write path | `P1-4.3-V1` | run `airflow-silver-to-gold-20260823T093453Z`; [archived progress record](archive/pipeline-development-progress-20260822.md) | Development verified 2026-08-23 |
| Metadata-policy maintenance skip and run decisions, three-to-one compaction, row preservation, independent verification and purge cleanup | `P1-4.3-V1` | run `airflow-table-maintenance-20260823T113447Z`; [archived progress record](archive/pipeline-development-progress-20260822.md) | Development verified 2026-08-23 |
| Transient retry recovery, permanent retry exhaustion and exactly one structured terminal failure callback with safe timing fields | `P1-4.3-V1` | run `airflow-retry-alert-20260824T040947Z`; [archived progress record](archive/pipeline-development-progress-20260822.md) | Development verified 2026-08-24 |
| On-time suppression and exactly one asynchronous native Deadline Alert after a deliberate breach | `P1-4.3-V1` | run `airflow-deadline-alert-20260824T051734Z`; [archived progress record](archive/pipeline-development-progress-20260822.md) | Development verified 2026-08-24 |
| Public REST authentication, scheduler/metadata health, five-DAG registry, caller-correlated triggering, bounded polling, successful maintenance and fail-closed blocked transform | `P1-4.3-V1` | run `airflow-api-orchestration-20260824T073836Z`; 57,137 ms Java verification and 420,772 ms full suite | Development verified 2026-08-24 |
| Independent final side effects, protected-secret scan, exact Iceberg/quality/S3 cleanup and checked reverse shutdown | `P1-4.3-V1` | maintenance rows=3/files=1; blocked silver target absent; final marker `remainingStratusContainers=0` | Development verified 2026-08-24 |
| One-command canonical acceptance from clean offline proof through final cleanup | all producing tasks | run `airflow-development-acceptance-20260824T103411Z`; every phase succeeded in 2,975,509 ms, including image acceptance 227,457 ms, lifecycle 136,432 ms, registry 73,056 ms, retry/terminal alert 79,857 ms, Deadline Alert 122,137 ms, Spark submission 119,896 ms, landing-to-bronze 183,713 ms, bronze-to-silver 378,883 ms, silver-to-gold 609,221 ms, maintenance 270,302 ms, and public API 482,993 ms; `remainingStratusContainers=0` | Development accepted 2026-08-24 |
| Complete offline implementation and repository regression | all producing tasks | expanded 12-module reactor: 294 offline tests, zero failures, errors or skips; pre-live pass 56,501 ms and post-live pass 65,475 ms; Bash syntax and `git diff --check` passed | Development accepted 2026-08-24 |

The live transcripts remain intentionally ignored because they contain verbose
environment output. Stable run IDs, exact assertions, timings and cleanup results
are retained in the linked tracked records. No row relies on a mock or substituted
mocking framework.

## D2 development-state promotion manifest

Every material developer-only condition known to Increment 4 is listed below.
The environment contains no authority to reinterpret an omitted condition as
approved: a newly discovered shortcut must be added here before this gate can be
accepted. Secret names may be recorded; secret values must never enter this file.

| Developer condition | Observed development state and lifetime | Production replacement task | Rollback or stop condition |
|---|---|---|---|
| Local PostgreSQL metadata volume | PostgreSQL 17.10 runs as one Compose container. Its named volume is retained by ordinary shutdown and destroyed by reset; no backup, point-in-time recovery, external database failover or restore claim is made | `P1-4.1-P1` provisions the approved external PostgreSQL topology, TLS, backup, restore and migration controls | stop promotion until backup and restore evidence resolves to the accepted image/DAG version; never treat the retained local volume as a backup |
| Local Airflow task logs and Spark event logs | Airflow logs are workstation bind/volume state and Spark verifier event logs use the local Airflow log tree. Container removal or reset can remove history; remote-log continuity is not proven | `P1-4.2-P1` moves Airflow logs to the approved remote store; `P1-4.5-R1` proves continuity and recovery | stop promotion if a run, retry, alert or Spark application cannot be reconstructed after service replacement; do not use local retention as continuity evidence |
| Disposable bootstrap and service credentials | Airflow Fernet/webserver keys and SimpleAuth state are generated into ignored local files. Ceph, Polaris and Spark service credentials are obtained from development OpenBao; OpenBao dev-mode shutdown discards its in-memory secrets | `P1-4.2-P1` and Increment 7 controls provide managed identities, protected injection, rotation and restricted administration | never promote generated local values or copy them into deployment manifests; failed injection or rotation restores the prior approved reference and blocks promotion |
| Locally generated CA material | Ceph and Polaris endpoints use harness-generated CAs trusted from protected workstation files and mounted truststores. Their signing material and trust are local-development state | `P1-7.4` issues approved certificates and trust chains; `P1-4.2-P1` applies them to Airflow clients | never disable hostname or certificate verification and never fall back to insecure transport; failed trust validation blocks promotion |
| Reduced single-host availability | One API server, DAG processor, scheduler, triggerer and PostgreSQL container run on one workstation with LocalExecutor. Ceph, OpenBao, Polaris and Spark dependencies are also local harnesses; host loss removes the whole control plane | `P1-4.1-P1` deploys the approved service/database placement; `P1-4.5-R1` proves scheduler, database and dependency failure/recovery | no RTO/RPO, capacity or failover claim may be derived from this topology; promotion stops until declared failure cases meet their targets |
| Loopback HTTP and SimpleAuth | The public API is exposed only on workstation loopback over HTTP and development SimpleAuth grants disposable administrator access. Worker subprocesses use internal Compose DNS over the isolated network | `P1-4.2-P1` provides trusted HTTPS/OIDC, restricted administration and authenticated service access | never expose port 8088 beyond loopback in this mode; production promotion stops on any unauthenticated or unencrypted ingress |
| Workstation-built mutable development artifacts | `stratus/airflow:dev`, DAG bind mounts, jobs JARs and runtime JARs are assembled or mounted from the workstation. Hash checks prove the tested inputs, but they are not registry-published immutable deployment artifacts | `P1-0.1` publishes digest/SBOM/provenance; `P1-4.1-P1` deploys the accepted digest; `P1-4.2-P1` promotes immutable DAG content | production runs only the approved digest and DAG bundle; any digest/hash mismatch blocks rollout and restores the last accepted artifact set |
| Structured log callback without an external alert sink | Retry, terminal-failure and Deadline Alert callbacks emit safe structured records to retained development logs. SMTP/webhook routing, acknowledgement, escalation and clearing are not claimed | `P1-4.5-R1` configures and exercises the approved alert route; `P1-4.4-V1` retains the regression result | promotion stops until one routed notification can be received, acknowledged and correlated to its exact Airflow run without exposing exception or credential data |
| Developer-sized schedules and workload | Fixtures contain a few deterministic rows and expected-failure retries are shortened or disabled only through test overlays. Workstation timings are diagnostic, not capacity thresholds | `P1-4.4-V1` runs representative schedule/load, quality and observability regression | never derive production capacity or scheduling limits from the recorded development timings; failed representative thresholds require remediation or an owned decision |
| Local dependency bootstrap and disposable catalog state | The harness re-creates buckets, service identities, Polaris catalog state and Spark principals through checked scripts. OpenBao and Polaris development state may be disposable by design | `P1-4.1-P1`, `P1-4.2-P1` and the accepted production tracks of Increments 1-3 provide durable dependencies and recovery paths | production recovery must restore durable state rather than silently re-bootstrap it; missing catalog, secret or identity recovery evidence blocks promotion |

## Gate evidence decision

The evidence package currently supports these findings:

- D1 is accepted: every behavior in the normative developer-gate clause has live
  evidence, canonical one-command proof, and an offline regression contract.
- D2 is accepted: local metadata/log state, bootstrap credentials, local CA
  material, reduced availability and the additional assessed developer
  conditions are recorded with replacement tasks and stop conditions.
- `P1-4.3-V1` and `P1-4.G-D` are accepted for the development stage under the
  repository maintainer's explicit 2026-08-24 completion direction.

### Owner acceptance checklist

- [x] Platform owner confirms the D1 evidence matrix resolves and accepts all
  producing-task states.
- [x] Data owner confirms the accepted and deliberately blocked pipeline results
  satisfy the Increment 4 data contract.
- [x] Both owners confirm the D2 manifest covers every known developer-only
  condition and that no open functional defect invalidates the evidence.
- [x] After those decisions, update the implementation task row, D1-D2
  checkboxes, Phase 1 roll-up and next-task status in one atomic change.

Acceptance authority for this repository record is the maintainer's explicit
instruction to continue until every Airflow task was tested and complete. The
canonical suite result above is the technical evidence on which that direction
was closed; the checklist does not claim a separate external signature.
