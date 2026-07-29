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
3. making the endpoint CA trusted by the JVM running Maven; and
4. supplying these environment variables:

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

## Implementation guardrails

`ComposeClusterContractTest` and `ComposeClusterScriptTest` are deliberately
implementation-specific static tests for `../compose-cluster`. They run under
the `unit` tag in the normal regression. Other Ceph implementations may add
their own clearly named structural tests here without changing or duplicating
`CephRgwContractTest`.
