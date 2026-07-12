# Repository Layout

| Directory | Ownership |
|---|---|
| `applications/` | Stratus-owned long-running services |
| `jobs/` | Spark and Flink workloads |
| `verification/` | executable component contract suites |
| `platform/` | open-source product integration and deployment assets |
| `environments/` | environment inventory and overlays without secrets |
| `operations/` | cross-platform operational assets and runbooks |
| `testing/` | cross-component non-functional and end-to-end suites |
| `schemas/` | shared governed contracts |
| `build-support/` | dependency and build policy |
| `docs/` | architecture, decisions, implementation, operations, and reference documentation |

The repository is organized by stable capability. Phase and increment numbers are planning metadata and must not appear in artifact identities or runtime paths.
