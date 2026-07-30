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
  Ceph RGW, the Compose cluster in `platform/ceph/compose-cluster`.
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

Changes to the Ceph local harness are verified against a live local cluster
(ceph-compose-startup → ceph-compose-bootstrap-buckets → ceph-compose-verify-buckets
→ ceph-compose-verify-storage → ceph-compose-verify-security →
ceph-compose-shutdown), with transcripts and evidence recorded. Run the
`stratus-ceph-tests` after any Ceph script, Compose, or `.env.template` change,
and `stratus-repo-guardrails` after documentation or repository-wide naming
changes.
