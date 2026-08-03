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
  starts the Ceph harness transitively.
- Docker Desktop, Docker Engine, or Podman, as for the Ceph harness.

## Workflow

From `platform/polaris/compose-service`, in bash:

```bash
bash scripts/lifecycle/polaris-compose-startup.sh
bash scripts/verify/polaris-compose-verify-endpoint.sh
bash scripts/lifecycle/polaris-compose-shutdown.sh
```

| Script | What it does |
|---|---|
| `lifecycle/polaris-compose-startup.sh` | Generates `.env` from the template once (disposable bootstrap credential), requires the Ceph harness network, validates Compose interpolation, starts the service |
| `verify/polaris-compose-verify-endpoint.sh` | Liveness smoke check: the Polaris API answers on the loopback port (an unauthenticated 401/403 counts as listening). Not a catalog contract test |
| `lifecycle/polaris-compose-shutdown.sh` | Idempotent stop; preserves the `polaris-data` volume and `.env`; never removes the external Ceph network |
| `lifecycle/polaris-compose-reset.sh` | Destroys the disposable catalog state (containers and data volume) for a fresh catalog next startup; prompts unless `--force` |

## What this harness consumes and publishes

Consumed (Ceph attachment contract): the `stratus-ceph-local_ceph` network,
the `https://object-store.stratus.local:8443` S3 endpoint over Docker DNS,
and the CA certificate `platform/ceph/compose-cluster/certs/stratus-ca.crt`
mounted read-only. No Ceph private key is ever mounted here.

Published: the `polaris.stratus.local` alias on the shared network for the
engine harnesses of later increments, and the loopback port
`127.0.0.1:8181` for workstation processes.

Run transcripts belong in this directory's git-ignored `logs/`; generated
evidence in `evidence/` (only `.gitkeep` is tracked). Never commit `.env`,
keys, or certificates.
