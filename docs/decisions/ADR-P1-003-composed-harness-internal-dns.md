# ADR-P1-003: Composed Harnesses Attach over Internal DNS

- Status: Accepted
- Date: 2026-08-03
- Decision owners: Platform architect and architecture owner
- Depends on: ADR-P1-001 (Ceph baseline), the repository layout rule that
  product lifecycle tooling lives under `platform/<product>/`

## Context

Increment 1 could be verified standalone: the Ceph Compose harness is
self-contained. Every subsequent product is a consumer of at least one other
running harness — Polaris's contract is "catalog over Ceph-backed Iceberg
tables", Spark's is "writes Iceberg to Ceph via Polaris", and so on. The
repository layout already fixes harness ownership (each product ships its own
harness under `platform/<product>/`; `environments/` holds inventory and
overlays, never lifecycle tooling), but it does not say how one product's
containers reach another product's running harness.

Two mechanisms were considered:

1. **Internal DNS**: the consumer harness joins the provider harness's Docker
   network and addresses the provider by a DNS alias through its TLS proxy.
2. **Published host endpoint**: the consumer's containers reach the provider
   through the host's published loopback port via a host-gateway mapping.

The Ceph harness already carries what option 1 needs: its `rgw-proxy` service
holds the network alias `object-store.stratus.local` on the harness network,
so any attached container resolves that name to the TLS proxy and validates
the certificate SAN — the same URL the workstation JVM tests use through the
hosts file. The in-cluster verifier and `s3client` containers already consume
exactly this path.

## Decision

Consumer harnesses attach to a provider harness's Docker network and address
the provider by internal DNS through its TLS proxy.

Each provider harness publishes an explicit **attachment contract**, documented
in its component README. For the Ceph Compose harness the contract is:

- network: `stratus-ceph-local_ceph` (subnet `172.28.0.0/24`)
- endpoint: `https://object-store.stratus.local:8443` (S3) and `:8444`
  (dashboard), terminating at the TLS proxy — never a backend daemon directly
- trust material: the disposable CA certificate at
  `platform/ceph/compose-cluster/certs/stratus-ca.crt`, mounted read-only by
  consumers; private keys are never shared
- The consumer depends only on this contract. Backend container names,
  internal addresses, and daemon topology remain private to the provider and
  may change without notice.

Lifecycle stays explicit and fail-fast: a consumer harness's startup script
verifies the provider network exists and exits with an actionable message
("start the Ceph harness first: `bash platform/ceph/compose-cluster/scripts/lifecycle/ceph-compose-startup.sh`")
rather than starting the provider transitively.

The published host endpoint option is rejected for container-to-container use:
it routes service traffic through a host-gateway indirection that exists only
on developer workstations, weakening fidelity to the production topology where
services reach each other over a network by DNS name. Loopback publication is
unaffected — it remains how workstation processes (the live Maven contracts, a
desktop S3 client) reach the harness.

## Consequences

- The same endpoint URL works in both contexts: workstation processes resolve
  `object-store.stratus.local` through the hosts file to loopback; attached
  containers resolve it through Docker DNS to the proxy. Product configuration
  templates need no per-context endpoint variants.
- The network name, subnet, proxy aliases, and CA location are now published
  contract values. Renaming any of them is a breaking change requiring a
  coordinated update across consumer harnesses; the pool-overlap guard in
  `ceph-compose-common.sh` already protects the subnet.
- Each new provider product (Polaris, Kafka, and so on) must publish its own
  attachment contract in the same form before its first consumer appears.
- Cross-product end-to-end suites exercising several attached harnesses belong
  in `testing/`, per the repository layout.
- TLS is preserved end to end on the container path: consumers validate the
  provider certificate against the mounted CA exactly as workstation clients
  do; no consumer may disable verification or bypass the proxy.
