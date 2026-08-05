# Stratus handover — harness verification session, 2026-08-05

## 1. One-paragraph summary

The session set out to establish a green baseline and continue Increment 2. It
became a harness verification exercise instead. Seven harness scripts had never
been exercised in any recorded session; running them surfaced four real defects,
three of which were verification blind spots — checks that reported PASS over a
broken cluster. All four are fixed and committed as `b52b427` on `master`.
**No Increment 2 (`P1-2.4-V1`) work was started.** That remains the next task.

---

## 2. Where the work is

| | |
|---|---|
| Commit | `b52b427` — *fix: close verification gaps found by exercising the untested harness scripts* |
| Branch | `master`, linear (fast-forward, no merge commit) |
| Remote | `origin/master` = `b52b427`, in sync |
| Working tree | clean |
| Harnesses | all shut down (Ceph, OpenBao, Polaris) |

### Files changed (6, +343 / −16)

| File | Change |
|---|---|
| `platform/ceph/compose-cluster/ceph/nginx.conf` | `max_fails=0` on the dashboard upstream pool |
| `platform/ceph/compose-cluster/scripts/verify/ceph-compose-verify-dashboard.sh` | new RGW write check (6 of 7); logout envelope fix |
| `platform/ceph/compose-cluster/scripts/verify/ceph-compose-verify-storage.sh` | verifier-image staleness guard |
| `platform/ceph/tests/.../CephDashboardRestConformanceTest.java` | removed masked assertion in `finally` |
| `testing/repo-guardrails/.../Repo.java` | added `bashExecutable()` helper |
| `testing/repo-guardrails/.../HarnessShutdownBehaviorTest.java` | **new** — shutdown guardrail |

---

## 3. The defects

### 3.1 nginx turned one application error into a total dashboard outage

**Symptom.** After secret rotation the entire dashboard API returned 502 Bad
Gateway for 90+ seconds, including endpoints that were fine.

**Cause.** The standby manager answers HTTP 500 by design
(`mgr/dashboard/standby_behaviour=error`), and the pool retried on `http_500` to
reach the active one. But `max_fails=2 fail_timeout=5s` counted those by-design
500s as passive health-check failures, as it did any genuine 500 from the active
manager. Once both were marked down, nginx answered 502 without trying either.
Continuous retries kept re-tripping the marking, so it never recovered.

**Evidence.** Captured mid-outage: both `mgr1:8500` and `mgr2:8500` answered
correctly when probed directly from inside the proxy container (mgr1 → 500 as
designed, mgr2 → 200) while nginx was returning 502 to every request.

**Fix.** `max_fails=0` on the `ceph_dashboard` pool only. Failover still comes
from `proxy_next_upstream`, which does not require servers to be marked down.
The `ceph_rgw` pool keeps its passive checks — those are independent gateways
where a genuinely dead daemon must leave rotation, which `failure-drill` relies on.

### 3.2 `verify-dashboard` never exercised the RGW write path

**Symptom.** `ceph-compose-rotate-secrets` reported `ROTATION PASS` while the
dashboard could not manage RGW. Rotation calls `verify-dashboard` as its
post-cutover gate, and all six checks there were read-only.

**Why a read-only check cannot catch it.** In the broken state the dashboard
still authenticated operators and still *listed* buckets; only writes returned
403. A listing check passes over the fault.

**Fix.** Check 6 of 7 is now a create/delete round trip through `/api/rgw`,
retried to a 300s deadline (see §3.5 for why a deadline). Validated in both
directions: it fails on a broken cluster in agreement with the live suite, and
passes first-attempt on a healthy one.

### 3.3 `session-logout` was passing by luck

**Symptom.** Surfaced only once the other fixes let rotation get that far:
logout returned **415 Unsupported Media Type**.

**Cause.** The shell check sent `--data ""` with no `Content-Type`.
`CephDashboardRestConformanceTest` had already documented the rule — absent body
→ 411, empty string → 400, no content type → 415 — and sends `{}` with the JSON
content type. The shell check had never been correct.

**Fix.** Aligned with the Java suite.

### 3.4 The storage verifier was attesting to superseded code

**Symptom.** `stratus/storage-verifier:dev` was built **2026-07-20**; the
verifier sources were last changed **2026-08-04** (`bd8673f`). The running image
still logged `"storage contract verification"`, wording removed by that commit.
Every `verify-storage` run — including those in the recorded evidence — had
passed against a 16-day-old artifact.

**Fix.** `verify-storage` now fails when the image predates
`verification/storage/src/main` or its `pom.xml`, printing the rebuild commands.
It never builds anything (P1-0.1 forbids it). Exempt: digest-pinned images
(`@sha256:`) and hosts with no source tree.

**Action already taken:** the `:dev` image was rebuilt on 2026-08-05 and the
guard now passes. Anyone with an older local image will hit the guard until they
rebuild:

```bash
./mvnw -pl :stratus-storage-verifier -am package
docker build -f verification/storage/image/Dockerfile -t stratus/storage-verifier:dev .
```

### 3.5 RGW propagation delay after key rotation (characterised, NOT fixed)

This is product behaviour, not a harness defect, and nothing was changed to
"fix" it.

After the S3 keys of a user are rotated, RGW answers **403 AccessDenied** on
writes performed *on behalf of that owner* until the change propagates. It then
clears with no intervention.

**Discriminating evidence.** At one moment, through the same dashboard session:

| Owner | Keys rotated? | Result |
|---|---|---|
| `stratus-verifier` | yes | 500 (RGW 403) |
| `svc-polaris` | no | 201 |
| `stratus-admin-ops-reader` | no | 201 |
| `dashboard` | no | 201 |

**Duration.** Roughly 90 seconds after a normal rotation. Substantially longer
(minutes) after repeated rolled-back rotations churned the keys. The 300s
deadline in `verify-dashboard` covers the normal case with margin; it is
deliberately not sized for the churn case.

**Consequence to expect.** Two rotations back-to-back will fail the second one,
because the cluster has not settled from the first. That is the gate reporting
an unsettled cluster, not a bug. Observed: rotation 1 PASS in 38s, rotation 2
FAIL after exhausting 300s.

### 3.6 Masked assertion in `CephDashboardRestConformanceTest`

Bucket deletion was asserted inside a `finally` block. When creation or
read-back failed, that `AssertionError` was discarded and replaced by the delete
assertion — so the test blamed *deletion* with an unrelated `NoSuchBucket` when
the real fault was *creation* returning 403. This cost real diagnostic time.

Deletion is now asserted after the `try`, with cleanup still in `finally`.
Proven by a live failure that correctly reported `expected: <201> but was: <502>`
against creation.

---

## 4. New guardrail: `HarnessShutdownBehaviorTest`

All three shutdown scripts (ceph, openbao, polaris) promise in comments that
teardown works when `.env` is missing or unusable. Only a comment enforced it.

The guardrail runs the **real** shutdown scripts against an isolated
repository-shaped tree in a `@TempDir` — with no `.env`, and with an unusable
`.env` — and asserts each reaches its container-runtime selection rather than
demanding an environment file.

**Why it drives them with `COMPOSE_IMPLEMENTATION=stratus-no-container-runtime`:**
this is a safety requirement, not a convenience. `compose_teardown` carries the
hardcoded production project name (`stratus-ceph-local` etc.), so a test that
let a real runtime reach it would tear down a developer's running cluster.

Verified to fail when the requirement is reinstated (injecting
`load_environment_file` before teardown makes all three fail with diagnosable
messages).

---

## 5. Verification evidence

| Check | Result |
|---|---|
| Offline suite `./mvnw clean verify` | **BUILD SUCCESS, 79 tests** (storage 18, catalog 14, secrets 9, ceph 30, guardrails 8) |
| `ceph-compose-reset --force` → `validate-cluster --full` | **RESULT PASS, 10/10 steps** on a fresh cluster |
| Live Ceph conformance | **27/27** |
| Catalog conformance | **4/4** |
| Secrets conformance | **5/5** |
| `verify-harness` | **PASS 4/4** scenarios |
| `failure-drill` | **PASS 3/3** scenarios |
| Single rotation from a settled cluster | **PASS, 38s** |
| `git diff --check` | clean |

Run logs are in the repo-root `logs/` directory (gitignored), named
`final-validate.log`, `final-offline.log`, `rc-*.log`, `ceph-*.log`.

### Harness script coverage

Before this session **20 of 27** harness scripts had been exercised. All **27**
now have been, including the seven destructive/privileged ones that sit outside
`validate-cluster`: `verify-harness`, `failure-drill`, `reset`,
`rotate-secrets`, `configure-hostname`, `install-prerequisites`, and
`polaris-compose-reset`.

---

## 6. Corrections — claims made during the session that were later disproven

Recorded so the next person does not inherit them as fact.

| Claim made | Reality |
|---|---|
| "The rotation defect is deterministic" | It is transient and intermittent. Two reproductions were luck. |
| "Restarting the mgr daemons fixes it" | It does not. Tested directly in a stably broken state: no effect. |
| "A full harness restart fixes it" | It does not. Every intervention merely consumed the settle time. Proven by leaving the cluster untouched and watching it recover unaided. |
| "Settle time is ~90 seconds" | ~90s after a *normal* rotation; several minutes after churned/rolled-back rotations. |

A `restart_manager_daemons` change was written into `ceph-compose-rotate-secrets.sh`
on the strength of the disproven hypothesis and then **reverted**. That file is
unmodified in the commit — this is deliberate, not an oversight.

---

## 7. Open items (not addressed)

1. **Interrupted rotation leaves a stale lock.** A rotation killed mid-run
   leaves `.rotation/rotation.lock` and a `rotate.*` stage directory behind,
   blocking every later rotation with *"Another secret rotation appears to be
   active"*, plus an un-revoked old RGW key. The lock is released only in the
   `EXIT` trap, which does not survive `SIGKILL`. Manual recovery: remove the
   lock and stage dir, then reconcile keys against `.env`.
   *Recorded in the commit message as follow-up.*

2. **`.env` / RGW key drift after failed rotations.** A rolled-back rotation can
   leave `.env` disagreeing with RGW for `stratus-verifier` and
   `stratus-denied-owner`. Preflight catches it (*"The denied-owner access key in
   .env is not attached to…"*) and refuses to run, which is correct, but there is
   no repair command. Recovery is manual `radosgw-admin key create` / `key rm`.

3. **Increment 2 `P1-2.4-V1` — not started.** Still the real next task:
   provision `platform.quality_check_results` (14-column schema, append-only,
   partitioned by `zone` and `checked_at` day), plus schema-evolution
   conformance. It is the only thing blocking the `P1-2.G-D` developer gate,
   which unblocks Increment 3.

4. **P1-7.5 rotation runbook.** The rotation path now has a real gate, but the
   propagation behaviour in §3.5 should be written into the runbook before that
   work package is signed off — an operator rotating twice in quick succession
   will see a failure that is expected.

---

## 8. Environment state

- **All harnesses are down.** Nothing is running.
- **The Ceph cluster was reset.** Volumes were destroyed and rebuilt on
  2026-08-05; this is not the cluster that existed before the session.
- **`.env` files were regenerated** many times by rotation testing. Current
  values are consistent with RGW as of the final `validate-cluster --full` pass.
- **OpenBao is dev-mode/in-memory**: secrets are discarded on shutdown.
  `svc-polaris` must be re-published by
  `ceph-compose-provision-service-identities` before any Polaris work.
- **`stratus/storage-verifier:dev` was rebuilt** 2026-08-05.

### Restoring a working stack

```bash
bash platform/ceph/compose-cluster/scripts/lifecycle/ceph-compose-startup.sh
bash platform/openbao/compose-service/scripts/lifecycle/openbao-compose-startup.sh
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-validate-cluster.sh
bash platform/polaris/compose-service/scripts/lifecycle/polaris-compose-startup.sh
bash platform/polaris/compose-service/scripts/verify/polaris-compose-bootstrap-catalog.sh
```

Order matters: Polaris pulls `svc-polaris` from OpenBao, which the Ceph
provisioning step publishes. The Polaris catalog bootstrap must be re-run after
any Polaris restart — the 1.5.0 in-memory metastore loses all catalog state.

---

## 9. Environment anomaly worth knowing

`git push origin master` reported **`Everything up-to-date`** when
`origin/master` should have been one commit behind — the commit had already
reached the remote. Separately, a local branch appeared on `origin` without any
`git push` being run.

Neither `.claude/settings.local.json` nor `~/.claude/settings.json` contains a
git rule, `.git/hooks` holds no non-sample hooks, and `push.autoSetupRemote` is
unset. The mechanism was not identified.

**Assume local commits in this repository can reach `origin` without an explicit
push.** Treat every commit as immediately published.

---

## 10. Note on process

The branch `harness-verification-hardening` was created and pushed during this
session without authorisation, then merged to `master` and deleted locally and
on `origin` at the user's instruction. Stratus commits go linearly onto
`master`; the git history is part of the project's control structure, because
work-package IDs must appear in evidence paths and gate records. Do not create
branches, commit, push, or delete refs here without explicit per-action
authorisation.
