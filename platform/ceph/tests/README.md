# Ceph Tests

This directory owns tests of the Stratus Ceph integration. The Maven module is
`stratus-ceph-tests`.

## Shared live contract

`CephRgwContractTest` is deployment-neutral. It connects to the endpoint and
credentials supplied through its environment and tests observable Ceph RGW
behavior: the S3 contract, required buckets, credential isolation, TLS,
read/list consistency, pagination, multipart-abort cleanup, and evidence
sanitization. It does not start, inspect, or otherwise depend on Compose,
cephadm, or a particular infrastructure layout.

Every Ceph service implementation is responsible for:

1. deploying a live Ceph RGW endpoint;
2. provisioning the five required Stratus buckets and the two isolated test
   identities;
3. making the endpoint hostname resolvable by the machine running Maven;
4. making the endpoint CA trusted by the JVM running Maven; and
5. supplying these environment variables:

```dotenv
CEPH_RGW_INTEGRATION=true
CEPH_RGW_ENDPOINT=https://ceph-rgw.example.test
CEPH_RGW_ACCESS_KEY=<primary test identity access key>
CEPH_RGW_SECRET_KEY=<primary test identity secret key>
CEPH_RGW_PROBE_BUCKET=stratus-landing
CEPH_DENIED_ACCESS_KEY=<isolated identity access key>
CEPH_DENIED_SECRET_KEY=<isolated identity secret key>
CEPH_RGW_DENIED_BUCKET=stratus-denied
S3_PATH_STYLE_ACCESS=true
```

Run only the live shared contract from the repository root:

```bash
./mvnw test -Pceph-integration-tests -pl :stratus-ceph-tests -am
```

Selecting the live profile without `CEPH_RGW_INTEGRATION=true` fails instead of
silently skipping the contract.

These tests run in the Maven JVM on your workstation, not inside a container, so
requirements 3 and 4 are theirs alone. A Ceph implementation whose own
verification scripts run inside containers satisfies neither by passing those
scripts. For the Compose cluster this means a one-time hosts-file entry mapping
`object-store.stratus.local` to `127.0.0.1`; confirm it with
`Resolve-DnsName object-store.stratus.local` (PowerShell) or
`getent hosts object-store.stratus.local` (bash) before running the live profile,
because without it every test here fails on connection rather than on Ceph
behavior.

## REST API contracts

Three further live contracts prove REST surfaces that the SDK-based contract
above does not reach. All are deployment-neutral and driven by the same
environment.

| Class | Surface | Why it exists |
| --- | --- | --- |
| `CephS3RestContractTest` | S3 data API over raw AWS Signature Version 4 REST | The AWS SDK absorbs wire-level defects. Signing, payload hashing, path-style routing, and TLS are asserted directly, with no SDK in the call path. |
| `CephAdminOpsRestContractTest` | RGW Admin Operations API (`/admin/...`) | Bucket inventory and usage administration, plus proof that the caps boundary holds in both directions. |
| `CephDashboardRestContractTest` | Ceph Dashboard REST API (`/api/auth`, `/api/rgw/...`) | Token authentication and real bucket create, read, and delete through the management API. |

`CephS3RestContractTest` requires path-style addressing. Virtual-host
addressing needs wildcard DNS for the endpoint domain, which an on-premises
deployment does not necessarily publish, so `S3_PATH_STYLE_ACCESS=false` fails
with that reason rather than skipping.

### Additional environment

Beyond the variables listed above, the REST contracts require:

```dotenv
CEPH_ADMIN_OPS_ACCESS_KEY=<scoped Admin Operations reader access key>
CEPH_ADMIN_OPS_SECRET_KEY=<matching secret>
CEPH_DASHBOARD_ENDPOINT=https://ceph-dashboard.example.test:8444
CEPH_DASHBOARD_USER=<dashboard sign-in user>
CEPH_DASHBOARD_PASSWORD=<matching password>
```

The Admin Operations identity MUST hold only `buckets=read` and `usage=read`
caps. Granting it `users` or `metadata` read caps would let it retrieve other
identities' access and secret keys; `scopedCapsCannotReachTheEndpointThatWouldExposeIdentityKeys`
exists to prove that it cannot, and will fail if the caps are widened.

The `/api/rgw` endpoints additionally require the dashboard to hold RGW
credentials of its own. A Ceph deployment supplies these however it chooses; the
Compose cluster configures them during startup, after the RGW daemons are
healthy, and logs a warning if it cannot.

## Implementation guardrails

`ComposeClusterContractTest` and `ComposeClusterScriptTest` are deliberately
implementation-specific static tests for `../compose-cluster`. They run under
the `unit` tag in the normal regression. Other Ceph implementations may add
their own clearly named structural tests here without changing or duplicating
`CephRgwContractTest`.
