# Stratus — project instructions

Stratus is an on-premises data fabric platform. Build with `./mvnw` (wrapper,
never a global Maven).

## Read before working

- [docs/reference/code_style_rules.md](docs/reference/code_style_rules.md) —
  binding style and testing rules. Read it before writing or reviewing any
  Java or tests.
- [docs/reference/maven_test_commands.md](docs/reference/maven_test_commands.md) —
  test selection profiles and the review checklist.
- [docs/README.md](docs/README.md) — documentation index and naming
  conventions.

## Non-negotiable testing rules (from code_style_rules.md §7.2, §7.3)

- Mockito is prohibited in every Stratus project. No other mocking framework
  may be introduced as a substitute.
- NO mocks, NO fakes, NO simulated product endpoints — anywhere, in any
  form. A test against a simulated Ceph (or any other product) is worthless
  as verification. Product behavior is tested against the live product: for
  Ceph RGW, the developer cluster in `platform/ceph/developer`.
- Tests MUST NOT substitute hand-written test doubles for Stratus-owned
  interfaces; exercise the real production implementation.
- Real environmental failures (unreachable address, closed port, unwritable
  path) are not simulations and may be used to test failure handling.
- Coverage is reported, never gated — a coverage gate rewards simulation.
  Never introduce a stand-in to raise coverage.
- Tests select by JUnit tag: `unit` (offline: pure logic and real
  environmental failures) and `ceph-integration` (live cluster, excluded by
  default; run with `-Pceph-integration-tests` or `-Pall-tests` while the
  harness is up). Profiles are defined in the root pom.
- A change is complete only when the §12 completion gate passes, including
  `git diff --check` and tested INFO/DEBUG logging behavior; storage-affecting
  changes must additionally pass the live suite.

## Repository conventions (enforced by testing/repo-guardrails)

- Every harness script ships as a `.ps1`/`.sh` pair with identical behavior,
  grouped under `scripts/lifecycle/`, `scripts/verify/`, `scripts/lib/`.
- No increment-numbered document names; retired names must not reappear
  (see `testing/repo-guardrails/src/test/resources/retired-names.txt`).
- Published harness ports bind to loopback by default. Never track `.env`,
  keys, or certificates.
- Run transcripts belong in the component's own `logs/` directory (e.g.
  `platform/ceph/developer/logs/`); the repository-root `logs/` is reserved for
  Maven build logs.

## Verification culture

Changes to the Ceph local harness are verified against a live local cluster
(startup → bootstrap-buckets → check → verify-java → verify-security →
shutdown), with transcripts and evidence recorded. Run the
`stratus-repo-guardrails` module after any change to docs, scripts, compose,
or `.env.template`.
