# Cross-System Testing

End-to-end, performance, resilience, security, and upgrade suites that span more than one owned component belong here. Single-contract verifiers belong under `verification/`.

## Modules

- `repo-guardrails/` — `stratus-repo-guardrails`, unit-tagged static consistency checks that run in the default `mvnw clean verify` regression: documentation link and anchor integrity, naming conventions (capability-named implementation documents, retired-name deny list, documented Maven module selectors), the Ceph developer-harness contract (`.env.template` ↔ `compose.yaml` variable contract, loopback port binding, service restart/healthcheck/hardening policy, secret ignore rules), and PowerShell/bash script-pair parity.
