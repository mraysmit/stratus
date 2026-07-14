# Stratus Maven Test and Build Commands

## Profile Architecture

Test-execution profiles are defined in exactly one place: the root `pom.xml`. Module POMs MUST NOT declare test-selection profiles. The shared `stratus-build-parent` consumes the root properties and applies them consistently through Maven Surefire and JaCoCo.

Always use the repository Maven wrapper. A machine-wide Maven installation is neither required nor accepted as the documented build path.

The default invocation applies these properties to every Java module:

| Property | Default | Meaning |
|---|---|---|
| `test.groups` | empty | Do not positively filter tests; run tagged and untagged tests unless explicitly excluded |
| `test.excludedGroups` | `ceph-integration` | Exclude tests requiring a live secured Ceph RGW endpoint |
| `coverage.skip` | `false` | Enforce 100% line and branch coverage |
| `ceph.integration.required` | `false` | Permit the live Ceph test to remain unselected |

Therefore, `mvnw clean verify` is the complete local regression command. It runs every test that does not require the live Ceph environment, including any accidentally untagged test, builds the deployable artifact, generates the JaCoCo report, and enforces 100% line and branch coverage.

## Available Profiles

| Profile | Included tags | Coverage gate | External requirements | Purpose |
|---|---|---:|---|---|
| none | `unit`, `protocol` | yes | none | Complete local build and regression gate |
| `-Punit-tests` | `unit` | no | none | Targeted diagnosis of a known unit-test failure |
| `-Pprotocol-tests` | `protocol` | no | local loopback networking | Targeted diagnosis of a known protocol-test failure |
| `-Pceph-integration-tests` | `ceph-integration` | no | live Ceph RGW configuration | Targeted live Ceph contract run |
| `-Pall-tests` | all tags | yes | live Ceph RGW configuration | Full local plus live regression and acceptance boundary |
| `-Puntagged-tests` | no known tag | no | none | Audit for tests missing an approved tag |

Targeted profiles deliberately skip the aggregate coverage gate because they execute only part of the production code. They are diagnostic commands, not completion evidence.

## Validation Rules

After any code change, the minimum acceptable validation is:

```text
clean verify
```

Use `clean` to prevent stale compiled classes from contaminating the result. `test` alone is useful while diagnosing a known failure but is not a completion command because it does not execute packaging and the `verify` quality gates.

Changes affecting Ceph endpoint behavior, TLS, credentials, request signing, path-style routing, bucket policy, object operations, multipart behavior, or cleanup MUST additionally pass `-Pall-tests` against the approved live Ceph environment.

The live profile requires all of the following:

```dotenv
CEPH_RGW_INTEGRATION=true
CEPH_RGW_ENDPOINT=https://object-store.stratus.local
CEPH_RGW_ACCESS_KEY=<scoped verification access key>
CEPH_RGW_SECRET_KEY=<matching verification secret>
CEPH_RGW_PROBE_BUCKET=stratus-landing
S3_PATH_STYLE_ACCESS=true
```

The endpoint CA must also be trusted by the JVM executing Maven. A selected live profile fails when `CEPH_RGW_INTEGRATION=true` is absent; it must never silently report success after skipping the Ceph test.

## PowerShell Commands

Create the ignored command-log directory once per workstation:

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
$timestamp = Get-Date -Format yyyyMMdd-HHmmss
```

### Complete local regression

```powershell
.\mvnw.cmd clean verify 2>&1 |
    Tee-Object -FilePath "logs\local-regression-$timestamp.txt"
```

### Full local and live Ceph regression

```powershell
.\mvnw.cmd clean verify -Pall-tests 2>&1 |
    Tee-Object -FilePath "logs\all-tests-$timestamp.txt"
```

### Targeted unit diagnosis

```powershell
.\mvnw.cmd test -Punit-tests -pl :stratus-storage-contract-verifier 2>&1 |
    Tee-Object -FilePath "logs\storage-contract-unit-$timestamp.txt"
```

### Targeted protocol diagnosis

```powershell
.\mvnw.cmd test -Pprotocol-tests -pl :stratus-storage-contract-verifier 2>&1 |
    Tee-Object -FilePath "logs\storage-contract-protocol-$timestamp.txt"
```

### Targeted live Ceph diagnosis

```powershell
.\mvnw.cmd test -Pceph-integration-tests -pl :stratus-storage-contract-verifier 2>&1 |
    Tee-Object -FilePath "logs\storage-contract-ceph-$timestamp.txt"
```

### Resume after a known reactor failure

Use resume only after correcting a failure from a complete command. Repeat the original complete command without `-rf` before declaring the change complete.

```powershell
.\mvnw.cmd verify -rf :stratus-storage-contract-verifier 2>&1 |
    Tee-Object -FilePath "logs\resume-storage-contract-$timestamp.txt"
```

### Tagging audit

```powershell
.\mvnw.cmd test -Puntagged-tests 2>&1 |
    Tee-Object -FilePath "logs\untagged-audit-$timestamp.txt"
```

A healthy tagging audit reports zero tests executed.

### Inspect the result

```powershell
Get-Content "logs\local-regression-$timestamp.txt" -Tail 40
```

Do not filter the live Maven stream through `Select-String` or `Select-Object`; doing so can hide context or delay failures. Capture the complete stream with `Tee-Object`, then inspect the saved file.

## Bash Commands

```bash
set -euo pipefail
mkdir -p logs
timestamp="$(date +%Y%m%d-%H%M%S)"
```

### Complete local regression

```bash
./mvnw clean verify 2>&1 | tee "logs/local-regression-${timestamp}.txt"
```

### Full local and live Ceph regression

```bash
./mvnw clean verify -Pall-tests 2>&1 | tee "logs/all-tests-${timestamp}.txt"
```

### Targeted profiles

```bash
./mvnw test -Punit-tests -pl :stratus-storage-contract-verifier 2>&1 \
  | tee "logs/storage-contract-unit-${timestamp}.txt"

./mvnw test -Pprotocol-tests -pl :stratus-storage-contract-verifier 2>&1 \
  | tee "logs/storage-contract-protocol-${timestamp}.txt"

./mvnw test -Pceph-integration-tests -pl :stratus-storage-contract-verifier 2>&1 \
  | tee "logs/storage-contract-ceph-${timestamp}.txt"

./mvnw test -Puntagged-tests 2>&1 \
  | tee "logs/untagged-audit-${timestamp}.txt"
```

### Inspect the result

```bash
tail -n 40 "logs/local-regression-${timestamp}.txt"
```

## Module Selection

Prefer Maven artifact IDs over filesystem paths:

```powershell
.\mvnw.cmd test -Punit-tests -pl :stratus-storage-contract-verifier
```

When a selected module requires upstream reactor modules, add `-am`:

```powershell
.\mvnw.cmd verify -pl :stratus-storage-contract-verifier -am
```

Do not use a targeted module command as final regression evidence. The complete reactor command remains mandatory.

## Profile Health Audit

### Confirm effective default selection

```powershell
.\mvnw.cmd help:effective-pom -pl :stratus-storage-contract-verifier 2>&1 |
    Select-String -Pattern 'test.groups|test.excludedGroups|coverage.skip'
```

Expected values:

```text
test.groups=(empty)
test.excludedGroups=ceph-integration
coverage.skip=false
```

### Confirm `all-tests` removes filters

```powershell
.\mvnw.cmd help:effective-pom -pl :stratus-storage-contract-verifier -Pall-tests 2>&1 |
    Select-String -Pattern 'test.groups|test.excludedGroups|ceph.integration.required'
```

Expected behavior: included and excluded group values are empty, and `ceph.integration.required=true`.

### Confirm profiles exist only in the root POM

```powershell
Get-ChildItem -Recurse -Filter pom.xml |
    Select-String -Pattern '<id>(unit-tests|protocol-tests|ceph-integration-tests|all-tests|untagged-tests)</id>'
```

Every match MUST refer to the repository root `pom.xml`. A match in a module POM means test-selection policy has been decentralized and must be corrected.

### Confirm every test class has an approved tag

```powershell
Get-ChildItem -Recurse -Filter '*Test.java' |
    ForEach-Object {
        if (-not (Select-String -Quiet -LiteralPath $_.FullName -Pattern '@Tag\("(unit|protocol|ceph-integration)"\)')) {
            $_.FullName
        }
    }
```

Expected output: none.

## Current Module Notes

- `stratus-storage-contract-verifier` owns unit, local S3-protocol, and live Ceph RGW contract tests.
- `S3ObjectStorageClientTest` uses a real in-process HTTP protocol endpoint and the actual SDK client. Mockito and all other mocking frameworks are prohibited.
- `CephRgwIntegrationTest` is the live product-compatibility boundary. It is not selected by the default local regression command.
- JaCoCo reports are generated under `verification/storage/target/site/jacoco/` by complete coverage-enforcing profiles.
- Surefire reports are generated under `verification/storage/target/surefire-reports/`.

## Completion Checklist

- [ ] The complete local regression command passed from a clean reactor.
- [ ] The saved Maven log contains `BUILD SUCCESS`.
- [ ] The saved Maven log contains no build, logging-binding, packaging, or test-fixture warnings.
- [ ] JaCoCo reports zero missed production lines and branches.
- [ ] INFO and DEBUG logging assertions passed.
- [ ] The untagged audit executes zero tests after adding or moving test classes.
- [ ] `-Pall-tests` passed when the change affects the live Ceph contract.
- [ ] No test was silently skipped by a selected profile.
- [ ] No module POM contains a test-selection profile.
- [ ] No child POM pins dependency or plugin versions.
- [ ] No mocking framework dependency or usage was introduced.
