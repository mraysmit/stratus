# Apache Polaris

Product integration for Apache Polaris, the central Iceberg REST catalog and
metadata control point for every Stratus compute engine (Spark, Trino, Flink).

**Status: developer harness lifecycle validated (2026-08-03).** Two live
start/verify/stop cycles against the pinned `apache/polaris:1.5.0` image are
recorded in `compose-service/logs/`; catalog bootstrap, TLS, and the
verification suite remain open. The implementation plan and task track are
owned by
[iceberg_polaris_catalog.md](../../docs/implementation/iceberg_polaris_catalog.md)
(Increment 2), whose `P1-2.2-D1` records the harness validation state.

## Directory map

| Directory | Purpose | State |
|---|---|---|
| [`compose-service/`](compose-service/README.md) | Disposable developer harness: pinned Polaris service attached to the Ceph Compose cluster per [ADR-P1-003](../../docs/decisions/ADR-P1-003-composed-harness-internal-dns.md) | Scaffold, not live-validated |
| `image/` | Verifier/image build assets (reserved; owned by `P1-2.2-S1`) | Not created |
| `config/` | Catalog bootstrap: namespaces, Ceph locations, scoped credentials (reserved; owned by `P1-2.3-D1`) | Not created |
| `database/` | Production external metadata store assets (reserved; production track) | Not created |
| `tests/` | Product-owned live conformance tests, mirroring `platform/ceph/tests` (reserved) | Not created |

## Related locations

- Product-neutral catalog capability conformance suite: [verification/catalog/](../../verification/catalog/README.md) (placeholder)
- Developer environment profile: [environments/developer/polaris/](../../environments/developer/polaris/README.md)
- Ceph connection settings this harness consumes:
  [Ceph Compose cluster README — Connection settings](../ceph/compose-cluster/README.md#connection-settings-for-other-harnesses)
- Secret store the harness pulls its storage credentials from:
  [OpenBao harness](../openbao/README.md) (ADR-P1-004)

## What this harness publishes for other harnesses

When the harness is running, engine harnesses attached to the shared
`stratus-ceph-local_ceph` network resolve the catalog as
`polaris.stratus.local:8181`. TLS termination for that name is pending
`P1-2.2-D1`; until it lands, the endpoint is plain HTTP behind an explicit
disposable-development override and loopback-only host publication.
