# ADR-P1-002: Single Bash Implementation for Harness Scripts

- Status: Accepted
- Date: 2026-07-22
- Decision owners: Platform architect and architecture owner
- Supersedes: the `.ps1`/`.sh` script-pair convention for the Ceph Compose harness

## Context

Every Ceph Compose harness script has shipped as a PowerShell/bash pair with
identical behavior, enforced by `ScriptParityTest` in `stratus-repo-guardrails`.
As the harness grew (credential generation, certificate lifecycle, dashboard
provisioning, failure drills), each behavior change had to be implemented twice.
The guardrail catches missing twins and mismatched runtime artifacts, but it
cannot prove behavioral equivalence; the scripts are now complex enough that
silent drift between the twins is a realistic and serious failure mode.

## Decision

Harness scripts ship as a single bash implementation. The `.ps1` twins are
removed and must not reappear.

- Bash is the common denominator: Git Bash ships with Git on Windows
  workstations, and Linux hosts and CI run it natively. Several scripts already
  perform their real work inside Linux containers via bash.
- Windows-only hardening is preserved inside the bash scripts behind an OS
  check: on Windows, the certificate script applies `icacls` restrictions to
  the private-key directory, where `chmod` alone is ineffective on NTFS.
- PowerShell users invoke the scripts as `bash scripts/lifecycle/startup.sh`.

## Consequences

- One implementation per script; a behavior change is written and reviewed once.
- `ScriptParityTest` no longer asserts pairing or twin parity. It now asserts
  the single-implementation convention: bash fail-fast preambles are retained,
  and no `.ps1` script may reappear under the harness script tree.
- Documentation and runbooks present bash invocations only.
- The disallowed-`.ps1` rule applies to the harness script tree, not the whole
  repository; unrelated future tooling may still choose PowerShell with its own
  justification.
