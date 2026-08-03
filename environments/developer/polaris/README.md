# Developer Polaris Overlay

The selected developer profile uses the disposable Polaris service in
`platform/polaris/compose-service/compose.yaml`, attached to the developer
Ceph Compose cluster over internal DNS per ADR-P1-003. It requires the Ceph
harness to be running first and publishes the catalog API on loopback only.

## Verified implementation status

On 2026-08-03 the profile passed two full start/verify/stop lifecycle cycles
against the pinned `apache/polaris:1.5.0` image: bootstrap-credential
consumption via `polaris.bootstrap.credentials` without stdout echo, OAuth
token issuance for the generated root principal, unauthenticated requests
refused with 401, idempotent shutdown, and the ADR-P1-003 fail-fast when the
Ceph harness network is absent. The in-memory metastore is test-only;
catalog bootstrap, TLS for `polaris.stratus.local`, and the catalog
verification suite remain open under the Increment 2 task track.

Local credentials and generated values remain in ignored files or
container volumes. Do not record secret values here.
