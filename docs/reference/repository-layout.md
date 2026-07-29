# Repository Layout

The repository is organized by stable capability, not implementation sequence. Phase and increment numbers are planning metadata and must not appear in artifact identities or runtime paths.

| Directory | Ownership | Current contents |
|---|---|---|
| `applications/` | Stratus-owned long-running services | Placeholder — no application modules yet |
| `jobs/` | Spark and Flink workloads | Placeholder — no job modules yet |
| `verification/` | executable component contract suites | One suite directory per capability (`storage`, `catalog`, `compute`, `orchestration`, `query`, `governance`, `identity`). `verification/storage/` holds the storage contract verifier, the current executable module |
| `platform/` | open-source product integration, deployment assets, and technology-owned tests | `platform/ceph/compose-cluster/` is the genuine Ceph Compose cluster; `platform/ceph/tests/` runs one shared Ceph RGW contract against any implementation and owns implementation-specific guardrails |
| `environments/` | environment inventory and overlays without secrets | `developer`, `acceptance`, and `production` |
| `operations/` | cross-platform operational assets and runbooks | Placeholder — operational acceptance documents currently live in `docs/operations/` |
| `testing/` | cross-component non-functional and end-to-end suites | `testing/repo-guardrails` contains only technology-neutral documentation and naming consistency checks |
| `schemas/` | shared governed contracts | Placeholder — no schemas yet |
| `build-support/` | dependency and build policy | `stratus-bom` owns dependency versions; `stratus-build-parent` owns build-plugin versions. Child module POMs pin neither |
| `docs/` | architecture, decisions, implementation, operations, and reference documentation | See the [documentation guide](../README.md) |
| `evidence/` | verification and acceptance evidence output | Kept in git as an empty anchor; generated evidence is not committed |
| `logs/` | local command logs (for example Maven `Tee-Object` captures) | Git-ignored; created per workstation |

Dot-directories (`.mvn`, `.idea`, `.claude`, and similar) are build- and tooling-internal and are not part of the repository layout.
