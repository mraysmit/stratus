# Developer OpenBao Overlay

The selected developer profile uses the disposable OpenBao dev-mode service
in `platform/openbao/compose-service/compose.yaml` as the platform secret
store (ADR-P1-004). It runs in-memory and auto-unsealed on loopback only:
secrets vanish on restart and are restored by re-running the Ceph
service-identity provisioning step.

## Verified implementation status

On 2026-08-04 the profile passed the live secret-store conformance suite
(`stratus-secrets-verifier`): authenticated KV v2 round trips, version
increments on overwrite, forged- and missing-token refusals without echoing
real material, and the published `stratus/service-identities` layout that
the Ceph provisioning step writes and the Polaris harness reads.

The root token lives in the harness's ignored `private/` directory with
owner-only permissions. Do not record secret values here.
