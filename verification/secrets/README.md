# Secrets Conformance Verifier

`stratus-secrets-verifier` is the product-neutral conformance suite for the
platform secret store (OpenBao, per
[ADR-P1-004](../../docs/decisions/ADR-P1-004-openbao-secret-distribution.md)).
Against the live store only, it proves the behaviors the platform relies
on: authenticated KV v2 round trips, version increments on overwrite (the
rotation primitive), refusal of forged and missing tokens without echoing
real material, and the published service-identity layout that producer and
consumer harnesses use.

Tests are tagged `secrets-integration` and excluded from the default build.
Run them against the running harness through the wrapper, which supplies
the environment and the live opt-in switch:

```bash
bash platform/openbao/compose-service/scripts/verify/openbao-compose-run-secrets-tests.sh
```

The published-identity test requires `svc-polaris` in the store; publish it
by running the Ceph service-identity provisioning step with OpenBao up.
Configuration comes from the environment (`OPENBAO_ENDPOINT`,
`OPENBAO_TOKEN`, optional mount and path overrides); validation fails by
name before any network operation, and plain HTTP requires the explicit
disposable-development override. The logging API is shaped so secret values
cannot pass through it — paths, field names, versions, and statuses only.
Offline unit tests cover the configuration and the logging behavior at both
levels.
