# Repository Layout

The repository is organized by stable capability, not implementation sequence. Phase and increment numbers are planning metadata and must not appear in artifact identities or runtime paths.

| Directory | Ownership | Current contents |
|---|---|---|
| `applications/` | Stratus-owned long-running services | Placeholder — no application modules yet |
| `jobs/` | Spark and Flink workloads | Placeholder — no job modules yet |
| `verification/` | executable component conformance suites | One suite directory per capability (`storage`, `catalog`, `compute`, `orchestration`, `query`, `governance`, `identity`). `verification/storage/` holds the storage conformance verifier, the current executable module |
| `platform/` | open-source product integration, deployment assets, and technology-owned tests | `platform/ceph/compose-cluster/` is the genuine Ceph Compose cluster and `platform/ceph/tests/` its conformance suite and guardrails; `platform/polaris/compose-service/` is the Polaris catalog harness; `platform/openbao/compose-service/` is the developer secret store (ADR-P1-004) |
| `environments/` | environment inventory and overlays without secrets | `developer`, `acceptance`, and `production` |
| `operations/` | cross-platform operational assets and runbooks | Placeholder — operational acceptance documents currently live in `docs/operations/` |
| `testing/` | cross-component non-functional and end-to-end suites | `testing/repo-guardrails` contains only technology-neutral documentation and naming consistency checks |
| `schemas/` | shared governed contracts | Placeholder — no schemas yet |
| `build-support/` | dependency and build policy | `stratus-bom` owns dependency versions; `stratus-build-parent` owns build-plugin versions. Child module POMs pin neither |
| `docs/` | architecture, decisions, implementation, operations, and reference documentation, plus the image assets they reference in `docs/images/` | See the [documentation map](../../README.md#documentation) |
| `scripts/` | repository maintenance tooling that operates on the source tree itself, not on any running harness | License- and copyright-header maintenance scripts. Harness lifecycle and verification scripts do not belong here; they live with their product under `platform/<product>/` |
| `evidence/` | verification and acceptance evidence output | Kept in git as an empty anchor; generated evidence is not committed |
| `logs/` | local command logs (for example Maven `Tee-Object` captures) | Git-ignored; created per workstation |

Dot-directories (`.mvn`, `.idea`, `.claude`, and similar) are build- and tooling-internal and are not part of the repository layout.

The table above is the closed allowlist of top-level directories. `RepositoryLayoutTest` in `testing/repo-guardrails` fails the build when a tracked top-level directory is missing from the table or a documented directory disappears, so a new top-level directory requires a row here — and a deliberate decision that the content fits no existing directory.

## Where new artifacts go

The table describes what each directory is; this section is the placement rule for new work. Find the row for the *kind* of artifact, not the product it relates to:

| New artifact | Location | Example |
|---|---|---|
| Third-party product integration: images, configuration templates, Compose harnesses, bootstrap automation | `platform/<product>/` | `platform/ceph/compose-cluster/` |
| Tests owned by a specific product or its harness | `platform/<product>/tests/` | `platform/ceph/tests/` |
| Product-neutral capability conformance suites | `verification/<capability>/` | `verification/storage/` |
| Stratus-authored Spark or Flink workload code | `jobs/` | — |
| Stratus-authored long-running services | `applications/` | — |
| Cross-component end-to-end and non-functional suites | `testing/` | `testing/repo-guardrails/` |
| Environment inventory and overlays (no secrets) | `environments/<environment>/<product>/` | `environments/developer/ceph/` |
| Shared governed event and data contracts | `schemas/` | — |
| Run transcripts and generated evidence | the owning component's ignored `logs/` and `evidence/` directories | `platform/ceph/compose-cluster/logs/` |

Never create a new top-level directory for a product or a task; a product enters the repository as `platform/<product>/` plus, where applicable, rows in the trees above.
