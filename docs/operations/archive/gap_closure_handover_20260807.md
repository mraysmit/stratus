# Stratus handover — gap closure session, 2026-08-07

## 1. One-paragraph summary

The session began with "let's get back to the implementation" and, after an
audit, was redirected to "close all the gaps before starting on increment 3
spark". No Increment 3 work was started — that was the explicit instruction.
The session audited everything standing between the current state and Spark,
found eleven distinct gaps rather than the two that were expected, and closed
all of them: three stale status records, a documentation set that described
Java classes which do not exist, a developer gate whose D2 clause was
unsatisfiable because the manifest it required had never been written, a
missing developer task, a conformance check the plan demanded that no test
covered, three stale placeholder READMEs, an untracked image directory that
would have broken two documents on commit, and the two rotation defects
carried over from the 2026-08-05 session. **Nothing was committed.** `HEAD` is
still `b9aec63`.

---

## 2. Where the work is

| | |
|---|---|
| Commit | none — all work is uncommitted in the working tree |
| `HEAD` | `b9aec63` — *Add Quality Check Results Table Definition and Conformance Tests* |
| Branch | `master` |
| Offline gate | `./mvnw clean verify` → BUILD SUCCESS, 92 tests, 0 failures |
| Transcript | `logs/gap-closure-final-gate-20260807T102155Z.txt` |
| `git diff --check` | clean |

### The working tree holds two sessions' work

This matters more than anything else in this document. The tree mixes an
earlier architecture/design session with this one, and the two must not be
confused when reviewing or committing.

**From the earlier design session, not touched here except where noted:**

| Path | Note |
|---|---|
| `CLAUDE.md` | untouched this session |
| `docs/implementation/flink_streaming_iceberg.md` | untouched this session |
| `testing/repo-guardrails/.../NamingConventionTest.java` | untouched this session |
| `docs/README.md` (deleted, staged) | untouched this session |
| `README.md` | design-session rewrite; this session added the `docs/images/` row and fixed the logo path |
| `docs/architecture/stratus_on_prem_data_fabric_architecture.md` | design-session rewrite; this session changed only the logo `<img src>` |
| `docs/reference/repository-layout.md` | design-session edit; this session amended the `docs/` row |
| `docs/architecture/kafka_to_onprem_lakehouse_design_notes.md` | new, untracked, untouched here |
| `docs/decisions/ADR-P1-005-event-backbone-selection.md` | new, untracked, untouched here |
| `docs/operations/harness_operations_runbook.md` | new, untracked; this session added the rotation-recovery section and two troubleshooting rows |

**From this session:**

| Path | Change |
|---|---|
| `docs/implementation/iceberg_polaris_catalog.md` | §9 rewritten, promotion manifest and traceability rule added, `P1-2.5-D1` added, gate wording corrected |
| `docs/implementation/stratus_implementation_plan_phase1.md` | `P1-2.2`, `P1-2.3`, `P1-2.4`, `P1-2.5` rollup rows corrected |
| `docs/operations/harness_verification_handover.md` | status banner plus closure notes on all four §7 open items |
| `platform/ceph/compose-cluster/scripts/lifecycle/ceph-compose-rotate-secrets.sh` | stale-lock reclamation and `--repair-keys` |
| `platform/ceph/tests/.../ComposeClusterConformanceTest.java` | two new tests; one existing assertion anchored |
| `verification/catalog/.../IcebergRestCatalogConformanceTest.java` | schema-enforcement negative added |
| `verification/catalog/README.md` | coverage and open-items text corrected |
| `verification/{compute,query,identity}/README.md` | MinIO and `platform.quality.check_results` corrected |
| `docs/images/` | renamed from the untracked `docs/other/` |
| `docs/operations/gap_closure_handover_20260807.md` | this document |

---

## 3. The gaps, and what closed each

### 3.1 Three stale status records

`P1-2.4-V1` was completed in `b9aec63` on 2026-08-06, but three records still
described the world before it:

- The `P1-2.4` portfolio row said `platform.quality_check_results` provisioning
  "remains". It does not.
- **`P1-2.2` and `P1-2.3` both said `Not started`** while their developer
  children `P1-2.2-D1` and `P1-2.3-D1` were `Verified` on 2026-08-03 and
  2026-08-04. This was not in the expected gap list and was found by reading
  the child track against the rollup.
- `harness_verification_handover.md` §7 item 3 still called `P1-2.4-V1` "the
  real next task".

All four rollup rows now carry the developer-track verification date and name
what remains on the production track. The 2026-08-05 handover was **not**
rewritten — it is a point-in-time session record. It gained a status banner and
per-item closure notes instead, which preserves what was true then while
removing the misleading claim.

### 3.2 The Increment 2 document described code that does not exist

[iceberg_polaris_catalog.md](../implementation/iceberg_polaris_catalog.md) §9
carried two inline Java blocks:

- `PolarisTestClient.java` — the real class is `LiveCatalog`
- `IcebergPolarisVerificationTest.java` — the real classes are
  `IcebergRestCatalogConformanceTest` and `QualityCheckResultsConformanceTest`

The inline `PolarisTestClient` was also wrong on substance, not just naming: it
set `WAREHOUSE_LOCATION` to `s3://stratus-bronze`, where the real code sets
`warehouse` to the *catalog name* (Polaris interprets that property as a
catalog, not a location), and it omitted `rest.auth.type`, `oauth2-server-uri`,
and the `X-Iceberg-Access-Delegation: none` header the verifier actually sends.
Anyone building from that block would have produced a client that fails.

**Increment 1's `ceph_storage.md` contains zero inline Java** — it references
module paths. §9 was brought to that pattern: a class table, a numbered live
coverage list, and the real run commands. The configuration table gained the
four environment variables the code reads and the document omitted
(`STRATUS_CATALOG_INTEGRATION`, `S3_PATH_STYLE_ACCESS`, and the two
`*_ALLOW_HTTP` overrides). §13 P9 and the §14 troubleshooting line, which both
named the non-existent classes, were corrected.

### 3.3 Developer gate D2 was unsatisfiable

D2 requires that developer-only conditions be "labelled developer-only in the
promotion manifest". **No promotion manifest existed for Increment 2.**
Increment 1 has one — a *Developer-to-production promotion controls* table in
its §17 — and Increment 2 had neither that table nor the gate traceability rule
that governs when a checkbox may be ticked.

Both were added. The promotion manifest names ten developer conditions with the
production task that replaces each and the condition under which promotion
stops: in-memory persistence, plain-HTTP loopback, single-container topology,
the disposable bootstrap credential, dev-mode OpenBao secrets, local CA
material, script re-bootstrap after restart, the tag-not-digest image pin,
workstation-built verifier execution, and the absent engine principals.

**D1, D2, and P3 also all said "H2".** §6 of the same document verifies against
the live image that Polaris 1.5.0 has no embedded H2 backend — its test-only
metastore is `in-memory` and its persistent backend is `relational-jdbc`. An H2
reading of those gates was not satisfiable against the deployed release. All
three now name the real modes, with a note stating why.

### 3.4 A conformance check the plan required, that no test performed

The Phase 1 plan §5 verification table for Increment 2 requires: *"Schema
enforcement — writing a record that violates the table schema is rejected."*
Reading the eleven live test methods against that table showed no such check
existed.

`rejectsARowThatLeavesARequiredColumnNull` was added to
`IcebergRestCatalogConformanceTest`. It asserts not only that the write is
refused but that the rejection **neither advances the snapshot nor adds rows** —
a refusal that still committed would be the real defect. The record is built
inside the `assertThrows` lambda deliberately, because either layer may enforce
the schema (the record API on assignment, or the Parquet writer on append) and
both are the platform correctly refusing the row.

**This is the one change in the session that has not run.** See §5.

### 3.5 `P1-2.5` had no developer task

The `P1-2.4` rollup named maintenance verification as outstanding, but
`P1-2.5` carried only the production child `P1-2.5-P1`. `P1-2.5-D1` was added
for the developer track: metadata-table queries and a proven threshold decision
for files, manifests, delete files, and orphan files. Snapshot expiry is
already covered by `P1-2.4-V1`, so it is excluded explicitly. The task states
that orphan-file detection must be proven before any destructive action is
wired.

### 3.6 Both rotation defects from the 2026-08-05 session

**Stale lock (item 1).** The lock was a bare directory released only in the
`EXIT` trap, which `SIGKILL` does not run, so a killed rotation blocked every
later run until an operator removed the directory by hand. The lock now records
its owning PID. On a failed acquisition the next run tests that process with
`kill -0`; if it is gone the lock is stale, and the run reclaims it and removes
the `rotate.*` stage directories the dead run left behind — never its own. A
lock held by a *live* rotation still fails closed, unchanged.

**Key drift (item 2).** A rolled-back rotation could leave `.env` disagreeing
with RGW. Preflight correctly refused to run but offered no repair.
`--repair-keys` now reconciles RGW with `.env`: it attaches each `.env` key
pair, verifies the attachment against RGW rather than assuming it, and removes
every *other* key on those two identities — a key left by a failed rotation is
an un-revoked credential, so removing it is the point of the repair. It rotates
nothing and exits before any rotation state is generated. The preflight failure
message now names the command.

**Runbook clause (item 4).** The 90-second key-propagation behaviour is now
written into [harness_operations_runbook.md](harness_operations_runbook.md) §4
under *Recovering from an interrupted rotation*, so an operator rotating twice
in quick succession is told the failure is expected. `P1-7.5` still owns the
production rotation runbook; only the harness-behaviour clause is closed.

### 3.7 Stale placeholders and an untracked image directory

`verification/compute/README.md` and `verification/query/README.md` referenced
`platform.quality.check_results` — a table name with a dot where the real name
has an underscore, which would not resolve. `compute` and
`verification/identity/README.md` still named MinIO, which Ceph RGW replaced.

`docs/other/` was untracked but referenced by `README.md` and the architecture
document through `<img>` tags. Because `DocumentationLinkTest` only inspects
markdown link syntax in *tracked* files, neither reference was checked, and
committing the documents without the directory would have shipped two broken
images. It was renamed to `docs/images/` — "other" is exactly the vague naming
this repository's retired-names culture rejects — both references updated, and
the directory documented in the README map and the layout table.

---

## 4. Verification evidence

| Check | Result |
|---|---|
| `./mvnw clean verify` | BUILD SUCCESS — 92 offline tests, 0 failures, 0 errors |
| `git diff --check` | clean |
| `bash -n` on the rotation script | passes |
| `ComposeClusterConformanceTest` | 8 tests → 10 |
| `IcebergRestCatalogConformanceTest` | 10 `@Test` → 11 |
| Live catalog checks total | 14 → 15 |

Transcripts are in the repository-root `logs/`, which is git-ignored and
Maven-only: `gap-audit-unit-20260807T100133Z.txt` (the opening baseline, taken
before any change) through `gap-closure-final-gate-20260807T102155Z.txt`.

### The two harness tests were proven red before they were made green

The rotation script was reverted with `git checkout --`, the suite re-run, and
the result recorded: **10 tests run, exactly 2 failures** — the two new tests —
with every assertion in both firing, and the other 8 tests unaffected. The
script was then restored. The assertions match strings that could not have
existed before the change (`kill -0 "$owner_pid"`,
`--repair-keys) mode=repair-keys ;;`), so the red result is structural rather
than incidental.

These are script-text conformance assertions, matching how this suite already
tests the rotation script. They are static analysis of the real script, not a
simulation of it. They cannot prove the runtime behaviour — only a live
rotation can, and that is recorded as outstanding in §5.

---

## 5. Open items

1. **`P1-2.G-D` is not accepted, and was not ticked.** The gate traceability
   rule — added this session — states a checkbox may be marked complete only
   when every mapped task is `Accepted`. `P1-2.2-D1`, `P1-2.3-D1`, and
   `P1-2.4-V1` are `Verified`. Moving them to `Accepted` is the platform
   owner's action and was deliberately not taken on the owner's behalf. The
   readiness note in §13 of the Increment 2 document states the evidence, the
   two conditions, and the open TLS clause.

2. **TLS for `polaris.stratus.local` is still open.** `POLARIS_ALLOW_HTTP=true`
   in the harness template; `P1-2.2-D1` is `Verified` with this clause
   outstanding. The owner either accepts it as a recorded deferral, as
   Increment 1 did for `P1-0.1`, or closes it before the gate.

3. **The schema-enforcement check has never run.** It compiles and is wired
   into the suite, but the harnesses are down, so it is the only check in the
   catalog suite without a transcript. Recorded as an addendum on `P1-2.4-V1`
   and in the verifier README. Run
   `polaris-compose-run-catalog-tests.sh` against a live stack and attach the
   transcript before accepting `P1-2.G-D`.

4. **Both rotation fixes are unproven at runtime.** Offline conformance passes
   and the red-then-green sequence is recorded, but neither the stale-lock
   reclamation nor `--repair-keys` has executed against a live cluster.
   Verifying the lock fix means killing a rotation with `SIGKILL` and running
   another; verifying repair means inducing drift and repairing it.

5. **`P1-2.5-D1` is `Not started`.** It was created this session, not executed.

6. **Nothing is committed, and `docs/images/` plus the three new design
   documents are untracked.** They need `git add` or the commit will ship
   broken image references and dangling links.

---

## 6. What was deliberately not done

- **No Increment 3 / Spark work.** The instruction was to close gaps first.
  `platform/spark/` does not exist and all ten `P1-3.x` child tasks remain
  `Not started`.
- **No gate acceptance.** See §5 item 1.
- **No commits, no branches, no pushes.** Consistent with the process note in
  the 2026-08-05 handover.
- **The 2026-08-05 handover was not rewritten.** Point-in-time records are
  annotated, not edited to look correct in hindsight.
- **The design session's README and architecture changes were left alone**,
  including its conversion of several markdown links to plain backticked
  paths. That is the author's choice and no guardrail objects to it.

---

## 7. Environment state

Unchanged from the 2026-08-05 handover: **all harnesses are down.** Nothing was
started or stopped this session; every check run here was offline. OpenBao is
dev-mode and in-memory, so `svc-polaris` must be re-published by
`ceph-compose-provision-service-identities` before any Polaris work, and the
Polaris catalog bootstrap must be re-run after any Polaris restart because the
1.5.0 in-memory metastore loses all catalog state.

To restore a working stack, follow the sequence in
[harness_operations_runbook.md](harness_operations_runbook.md) §2.

**The environment anomaly recorded on 2026-08-05 still stands and was not
re-investigated:** local commits in this repository have been observed reaching
`origin` without an explicit push. Treat every commit as immediately published.

---

## 8. Next task

Increment 3, Spark — `P1-3.1` (runtime and cluster), following the Ceph and
Polaris harness patterns under `platform/spark/`, then `P1-3.2` to bind Spark
to Polaris and Ceph RGW through `svc-spark`.

Two things should happen first, and both need the harnesses up:

1. Run the catalog suite so the fifteenth check has a transcript (§5 item 3).
2. Exercise the two rotation fixes live (§5 item 4).

Neither blocks Spark engineering. `P1-2.G-D` acceptance does — the developer
gate is what unblocks Increment 3, and it is one owner decision plus one live
run away.
