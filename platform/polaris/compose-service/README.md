# Polaris Compose Service

A disposable developer harness running the pinned Apache Polaris release as
the Iceberg REST catalog, attached to the running Ceph Compose cluster over
internal DNS per
[ADR-P1-003](../../../docs/decisions/ADR-P1-003-composed-harness-internal-dns.md).

**Status: lifecycle validated.** Two full start/verify/stop cycles were
recorded on 2026-08-03 against the live `apache/polaris:1.5.0` image
(transcripts in this directory's `logs/`), satisfying the `P1-2.2-D1`
definition of done. The configuration property names in `.env.template` are
verified against the running release: `polaris.bootstrap.credentials`
consumed the generated root credential without echoing it, the token
endpoint issued an OAuth token for it (HTTP 200), and the unauthenticated
API correctly answers 401. Known open items:

- TLS termination for `polaris.stratus.local` (currently plain HTTP on the
  loopback port behind the explicit `POLARIS_ALLOW_HTTP=true` developer
  override)
- an immutable image digest pin with scan and SBOM evidence (`P1-2.2-S1`);
  the observed digest is recorded in `.env.template`
- the scoped `svc-polaris` RGW identity and catalog bootstrap (`P1-2.3-D1`)
- the in-memory metastore is test-only and loses all state on restart; the
  persistent `relational-jdbc` (PostgreSQL) backend belongs to the
  production track

## Prerequisites

- The Ceph Compose cluster must already be running; startup fails fast with
  the remediation command when its network is absent. This harness never
  starts a provider transitively.
- The OpenBao harness must be running with the `svc-polaris` identity
  published (by the Ceph provisioning step): the scripts pull the storage
  credentials from the store per ADR-P1-004 and fail fast with the
  remediation when the store or the secret is absent.
- Docker Desktop, Docker Engine, or Podman, as for the Ceph harness.

## Workflow

From `platform/polaris/compose-service`, in bash (after Ceph startup,
bucket bootstrap, OpenBao startup, and service-identity provisioning):

```bash
bash scripts/lifecycle/polaris-compose-startup.sh
bash scripts/verify/polaris-compose-verify-endpoint.sh
bash scripts/verify/polaris-compose-bootstrap-catalog.sh
bash scripts/lifecycle/polaris-compose-shutdown.sh
```

| Script | What it does |
|---|---|
| `lifecycle/polaris-compose-startup.sh` | Generates `.env` from the template once (disposable bootstrap credential), requires the Ceph harness network, validates Compose interpolation, starts the service |
| `verify/polaris-compose-verify-endpoint.sh` | Liveness smoke check: the Polaris API answers on the loopback port (an unauthenticated 401/403 counts as listening). Not a catalog conformance test |
| `verify/polaris-compose-bootstrap-catalog.sh` | Idempotently creates the `stratus` catalog (bound to the five Ceph buckets), grants `CATALOG_MANAGE_CONTENT` to `catalog_admin`, and creates the `bronze`, `silver`, `gold`, `platform` namespaces, then runs a positive listing check and a forged-token negative. The svc-polaris credentials are pulled from OpenBao automatically |
| `lifecycle/polaris-compose-shutdown.sh` | Idempotent stop; preserves the `polaris-data` volume and `.env`; never removes the external Ceph network |
| `lifecycle/polaris-compose-reset.sh` | Destroys the disposable catalog state (containers and data volume) for a fresh catalog next startup; prompts unless `--force` |
| `verify/polaris-compose-run-catalog-tests.sh` | Runs the live catalog conformance suite (`stratus-catalog-verifier`) against this service and the Ceph cluster behind it, supplying the environment, live opt-in switch, and CA truststore; per-run transcripts land in `logs/` |

## Known incompatibilities and verified workarounds

All verified live against `apache/polaris:1.5.0` and Ceph Tentacle 20.2.2 on
2026-08-04; re-test each when either product is upgraded.

| Issue | Effect | Resolution in this harness |
|---|---|---|
| Polaris's credential-vending session policy includes `kms:DescribeKey`; RGW's IAM policy parser rejects unknown actions with STS 400 "`kms:DescribeKey` is not a valid action" | AssumeRole-based credential vending fails even though RGW STS, the `svc-polaris` role, and role policies all work (proven up to exactly this parse error) | The catalog is created with `stsUnavailable: true` (Polaris's designed S3-compatible mode); clients supply their own scoped static credentials. Revisit when either side reconciles KMS action handling |
| Modern `keytool` creates PKCS12 by default regardless of a `.jks` filename, and a passwordless PKCS12 read yields zero trust anchors ("the trustAnchors parameter must be non-empty") | The Polaris JVM silently trusts nothing; every TLS call to RGW fails | Truststores are built with explicit `-storetype JKS`, whose certificate entries read without a password, so none reaches any command line |
| A `javax.net.ssl.trustStore` override replaces the JVM's default CA set entirely, and `JAVA_TOOL_OPTIONS` also applies to Maven | A lab-CA-only truststore breaks Maven Central downloads mid-build | The test runner copies the JVM's own `cacerts` and adds the lab CA to the copy |
| Purge-drops are doubly gated in Polaris: the per-catalog `polaris.config.drop-with-purge.enabled` property AND a `CATALOG_MANAGE_CONTENT` grant (the auto-created `catalog_admin` role manages metadata only) | `dropTable(purge)` fails with "not authorized for op DROP_TABLE_WITH_PURGE" even as root | `polaris-compose-bootstrap-catalog.sh` sets the property at catalog creation and grants `CATALOG_MANAGE_CONTENT` to `catalog_admin` |
| The in-memory metastore (the only test-mode persistence in 1.5.0) loses all catalog state on restart | Catalog, namespaces, and grants vanish with the container | Re-run `polaris-compose-bootstrap-catalog.sh` after any restart; it converges idempotently |

## What this harness consumes and publishes

Consumed from the Ceph harness: its network, S3 endpoint, and CA
certificate, all sourced by the harness scripts from
[`connection.env`](../../ceph/compose-cluster/connection.env). Consumed
from the OpenBao harness: the `svc-polaris` credentials, pulled at script
time from the store using the settings in
[its `connection.env`](../../openbao/compose-service/connection.env)
(ADR-P1-004) — no credential field exists in this harness's `.env.template`
and nothing is copied by hand. Each provider contributes exactly one
hardcoded repository-path anchor in
`scripts/lib/polaris-compose-common.sh`. No provider private key is ever
mounted here.

Published: the `polaris.stratus.local` alias on the shared network for the
engine harnesses of later increments, and the loopback port
`127.0.0.1:8181` for workstation processes.

Run transcripts belong in this directory's git-ignored `logs/`; generated
evidence in `evidence/` (only `.gitkeep` is tracked). Never commit `.env`,
keys, or certificates.
