# Cross-System Testing

End-to-end, performance, resilience, security, and upgrade suites that span more than one owned component belong here. Single-contract verifiers belong under `verification/`.

## Modules

- `repo-guardrails/` — `stratus-repo-guardrails`, technology-neutral static
  consistency checks that run in the default `mvnw clean verify` regression:
  documentation link and anchor integrity, capability-named implementation
  documents, the retired-name deny list, and valid documented Maven selectors.

Technology-owned tests do not belong here. Ceph contract and implementation
tests are under [`platform/ceph/tests`](../platform/ceph/tests/).
