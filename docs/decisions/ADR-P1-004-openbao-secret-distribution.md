# ADR-P1-004: OpenBao for Platform Secret Distribution

- Status: Accepted
- Date: 2026-08-04
- Decision owners: Platform architect and architecture owner
- Depends on: ADR-P1-003 (harness composition), the service-identity
  provisioning in `platform/ceph/compose-cluster/service-identities.conf`

## Context

Cross-service credentials currently reach their consumers through
environment files: the Ceph harness generates a service identity's keys into
its own ignored `.env`, and an operator manually copies them into the
consumer harness's `.env`. With two harnesses this is already a documented
manual step that can be done wrong; each future engine (Spark, Trino,
Flink) consumes several identities, so the copy-graph grows with every
increment. Environment-variable custody also has structural weaknesses the
harness work has demonstrated live: values are immutable per process
(rotation means restarts), visible to `docker inspect` and `/proc`, and
inherited by child processes. The style rules already scope `.env` files as
local working material and the production track requires an approved
secret-management process (`P1-1.4-P1`) without naming one.

Two harnesses is the cheapest retrofit point there will ever be: every
later increment copies the pattern it finds.

## Decision

Apache-ecosystem-aligned **OpenBao** (the MPL-licensed open-source fork of
HashiCorp Vault) is the platform secret store. The developer harness runs
the pinned `openbao/openbao` image (2.6.1 at adoption, verified on Docker
Hub) as `platform/openbao/compose-service`, following the established
harness conventions: pinned image, loopback-only publication, generated
per-machine secrets, `connection.env` published settings, fail-fast
lifecycle scripts.

Distribution becomes pull-based:

- The Ceph provisioning step (the identity *producer*) publishes each
  declared service identity's key pair into OpenBao under
  `secret/stratus/service-identities/<uid>` when the OpenBao harness is
  running, and says so when it is not.
- Consumer harnesses (Polaris now, engines later) read their identity from
  OpenBao at script time, failing fast with the exact remediation when the
  store or the secret is absent. The manual copy step is deleted, not
  documented around.
- Secret-zero is the OpenBao token, held in the OpenBao harness's ignored
  `private/` directory with owner-only permissions; `connection.env`
  publishes its *path*, never its value.

HashiCorp Vault was rejected on licensing: it moved to the BUSL, which the
platform's open-formats-over-lock-in principle argues against. Compose
file-based secrets were rejected as the primary mechanism: they improve
custody but remain copy-based, which is the actual defect.

## Consequences

- The developer OpenBao runs in dev mode: in-memory, auto-unsealed, plain
  HTTP on loopback behind an explicit disposable-development override.
  Secrets vanish on restart and are restored by re-running the (idempotent)
  Ceph provisioning step. None of this is production posture: production
  requires durable storage, an unseal strategy, TLS from the platform CA,
  audit devices, and scoped policies instead of the root token — owned by
  the production track alongside `P1-1.4-P1`.
- Ceph-only workflows stay standalone: provisioning publishes to OpenBao
  when it is up and logs an explicit skip when it is not; the gap then
  surfaces at the consumer with a fail-fast remediation, never silently.
- Rotation becomes re-provision plus consumer re-read, no restarts of the
  store's clients required beyond the services actually holding the
  credential.
- Every future engine harness follows the Polaris pattern: one anchor to
  the OpenBao harness directory, settings from its `connection.env`, and no
  service credential ever written into a consumer `.env` by hand.
