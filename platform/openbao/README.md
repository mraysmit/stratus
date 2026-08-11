# OpenBao

Product integration for OpenBao, the platform secret store selected by
[ADR-P1-004](../../docs/decisions/ADR-P1-004-openbao-secret-distribution.md):
service credentials are published by their producers and pulled by their
consumers, replacing operator-copied environment files.

**Status: developer harness.** `compose-service/` runs the pinned
`openbao/openbao` image in dev mode — in-memory, auto-unsealed, plain HTTP
on loopback — which is disposable by design: secrets vanish on restart and
are restored by re-running the (idempotent) Ceph service-identity
provisioning step. Production posture (durable storage, unseal strategy,
TLS from the platform CA, audit devices, scoped policies instead of the
root token) is owned by the production track.

## Workflow

From `platform/openbao/compose-service`, in bash:

```bash
bash scripts/lifecycle/openbao-compose-startup.sh
bash scripts/verify/openbao-compose-verify-endpoint.sh
bash scripts/lifecycle/openbao-compose-shutdown.sh
```

| Script | What it does |
|---|---|
| `lifecycle/openbao-compose-startup.sh` | Generates `.env` once with a per-machine dev root token, writes the token to `private/root-token` (owner-only) for consumer scripts, starts the store, waits for health |
| `verify/openbao-compose-verify-endpoint.sh` | Liveness only: the store answers on the loopback port and the token file is on disk. KV behavior belongs to the conformance suite below |
| `verify/openbao-compose-run-secrets-tests.sh` | Runs the live secret-store conformance suite (`stratus-secrets-verifier`): KV round trips, rotation versioning, forged/missing-token refusals, and the published service-identity layout; per-run transcripts land in `logs/` |
| `lifecycle/openbao-compose-shutdown.sh` | Idempotent stop; dev-mode secrets are discarded by design |

## What this harness publishes

[`connection.env`](compose-service/connection.env) carries the values
consumers may rely on: the endpoint, the token *file path* (never its
content), the KV mount, and the service-identity base path. Producers and
consumers each hardcode one anchor — this harness's repository path — and
source everything else, per the established connection-settings pattern.

Current integration: `ceph-compose-provision-service-identities.sh`
publishes each declared identity's key pair to
`secret/stratus/service-identities/<uid>`; the Polaris harness scripts pull
`svc-polaris` from there at run time. No service credential is copied
between `.env` files by hand.
