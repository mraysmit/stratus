# Stratus — project instructions

Stratus is an on-premises data fabric platform. Build with `./mvnw` (wrapper,
never a global Maven).

## Read before working

- [docs/reference/code_style_rules.md](docs/reference/code_style_rules.md) —
  binding style and testing rules. Read it before writing or reviewing any
  Java or tests.
- [docs/reference/maven_test_commands.md](docs/reference/maven_test_commands.md) —
  test selection profiles and the review checklist.
- [README.md](README.md) — project overview, documentation map, and naming
  conventions.

## Non-negotiable testing rules (from code_style_rules.md §7.2, §7.3)

- Mockito is prohibited in every Stratus project. No other mocking framework
  may be introduced as a substitute.
- NO mocks, NO fakes, NO simulated product endpoints — anywhere, in any
  form. A test against a simulated Ceph (or any other product) is worthless
  as verification. Product behavior is tested against the live product: for
  Ceph RGW, the Compose cluster in `platform/ceph/compose-cluster`.
- Tests MUST NOT substitute hand-written test doubles for Stratus-owned
  interfaces; exercise the real production implementation.
- Real environmental failures (unreachable address, closed port, unwritable
  path) are not simulations and may be used to test failure handling.
- Coverage is reported, never gated — a coverage gate rewards simulation.
  Never introduce a stand-in to raise coverage.
- Tests select by JUnit tag: `unit` (offline: pure logic and real
  environmental failures), `ceph-integration` (live Ceph cluster),
  `catalog-integration` (live Polaris + Ceph harnesses), and
  `secrets-integration` (live OpenBao harness). Live tags are excluded by
  default; run with the matching `-P<tag>-tests` profile or `-Pall-tests`
  while the harnesses are up. Profiles are defined in the root pom.
- A change is complete only when the §12 completion gate passes, including
  `git diff --check` and tested INFO/DEBUG logging behavior; storage-affecting
  changes must additionally pass the live suite.

## Where new artifacts go

The top-level directory set is closed and enforced by `RepositoryLayoutTest`
in `stratus-repo-guardrails` — never create a new top-level directory.
Placement follows the kind of artifact, not the product it relates to
(authoritative rules: [docs/reference/repository-layout.md](docs/reference/repository-layout.md)):

- Product integration, harnesses, bootstrap → `platform/<product>/`
- Product-owned tests → `platform/<product>/tests/` (e.g. Ceph tests go in
  `platform/ceph/tests`, never a root `tests/` directory)
- Product-neutral capability conformance suites → `verification/<capability>/`
- Stratus Spark/Flink job code → `jobs/`; Stratus services → `applications/`
- Cross-component e2e suites → `testing/`; environment inventory →
  `environments/<environment>/<product>/`; event/data contracts → `schemas/`

## Repository conventions

- Harness scripts are bash-only (`.sh`, no PowerShell twins; ADR-P1-002),
  grouped under `scripts/lifecycle/`, `scripts/verify/`, `scripts/lib/`.
  On Windows, invoke them from Git Bash (`bash scripts/lifecycle/ceph-compose-startup.sh`).
- No increment-numbered document names; retired names must not reappear
  (see `testing/repo-guardrails/src/test/resources/retired-names.txt`).
- Published harness ports bind to loopback by default. Never track `.env`,
  keys, or certificates. Ceph-specific conventions are enforced by
  `platform/ceph/tests`; technology-neutral conventions remain in
  `testing/repo-guardrails`.
- Run transcripts belong in the component's own `logs/` directory (e.g.
  `platform/ceph/compose-cluster/logs/`); the repository-root `logs/` is reserved for
  Maven build logs.

## Verification culture

Changes to the Ceph local harness are verified against a live local cluster,
with transcripts and evidence recorded. Every harness script is prefixed
`ceph-compose-`; the full sequence is:

1. `lifecycle/ceph-compose-startup`
2. `verify/ceph-compose-bootstrap-buckets`
3. `verify/ceph-compose-provision-service-identities` — provisions the
   platform service identities and bucket policies declared in
   `service-identities.conf` (developer harness only; production provisions
   identities through the approved secret-management process)
4. `verify/ceph-compose-verify-buckets`
5. `verify/ceph-compose-verify-storage`
6. `verify/ceph-compose-verify-security`
7. `verify/ceph-compose-verify-dashboard`
8. `verify/ceph-compose-verify-dataset`
9. `verify/ceph-compose-run-live-tests` — the live JVM conformance tests; unlike the
   steps above it runs on the workstation, so it also needs a hosts-file entry
   for `object-store.stratus.local` and a CA truststore, both of which the
   script itself supplies
10. `lifecycle/ceph-compose-shutdown`

`verify/ceph-compose-validate-cluster` runs steps 2–9 as one command against a
running cluster and writes a per-step transcript; with `--full` it runs the
whole sequence 1–10.

`verify/ceph-compose-verify-harness` is separate: it requires a fully stopped
harness with no cluster volumes, so it destroys and rebuilds local state.

Run the `stratus-ceph-tests` after any Ceph script, Compose, or `.env.template`
change, and `stratus-repo-guardrails` after documentation or repository-wide
naming changes.
