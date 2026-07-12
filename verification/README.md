# Verification

This directory contains executable platform contract suites. Each verifier is an independently testable Maven module and may own an image under its local `image/` directory.

Verifiers test stable platform contracts rather than implementation increments. They must not contain administrator credentials, environment inventory, or third-party service deployment configuration.

Current modules:

- `storage-contract` - Ceph RGW bucket and object-operation contract

## Quality Gate

Run the complete verifier build from the repository root:

```powershell
.\mvnw.cmd clean verify
```

```bash
./mvnw clean verify
```

The centrally managed JaCoCo gate requires 100% line coverage and 100% branch coverage for every verifier module. Any uncovered production line or branch fails Maven's `verify` phase. The storage-contract HTML report is generated at `verification/storage-contract/target/site/jacoco/index.html`.

Tests must also exercise operational logging at both supported levels. `INFO` covers lifecycle results and `DEBUG` covers diagnostic operation detail; neither level may expose access keys, secret keys, or object payloads.
