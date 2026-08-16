# Stratus Maven Test and Build Commands

## Profile Architecture

Test-execution profiles are defined in exactly one place: the root `pom.xml`. Module POMs MUST NOT declare test-selection profiles. The shared `stratus-build-parent` consumes the root properties and applies them consistently through Maven Surefire and JaCoCo.

Always use the repository Maven wrapper. A machine-wide Maven installation is neither required nor accepted as the documented build path.

The default invocation applies these properties to every Java module:

| Property | Default | Meaning |
|---|---|---|
| `test.groups` | empty | Do not positively filter tests; run tagged and untagged tests unless explicitly excluded |
| `test.excludedGroups` | `ceph-integration \| catalog-integration \| secrets-integration \| spark-integration` | Exclude every suite that requires a live product harness |
| `coverage.skip` | `false` | Generate the JaCoCo coverage report |
| `ceph.integration.required` | `false` | Permit the live Ceph tests to remain unselected |
| `catalog.integration.required` | `false` | Permit the live catalog tests to remain unselected |
| `secrets.integration.required` | `false` | Permit the live secret-store tests to remain unselected |
| `spark.integration.required` | `false` | Permit the live Spark tests to remain unselected |

The approved tags are exactly `unit`, `ceph-integration`, `catalog-integration`, `secrets-integration`, and `spark-integration`. Every test class carries one.

Therefore, `mvnw clean verify` is the complete local regression command. It runs every test that does not require a live product harness, including any accidentally untagged test, builds the deployable artifact, and generates the JaCoCo report. Coverage is reported, never gated: simulated product endpoints are prohibited (code_style_rules.md 7.2), so product behavior — and the coverage it produces — is proven against the live local harnesses instead.

## Available Profiles

| Profile | Included tags | Coverage report | External requirements | Purpose |
|---|---|---:|---|---|
| none | `unit` plus untagged | yes | none | Complete local build and offline regression |
| `-Punit-tests` | `unit` | no | none | Targeted diagnosis of a known unit-test failure |
| `-Pceph-integration-tests` | `ceph-integration` | no | running local Ceph harness | Targeted live Ceph suite run |
| `-Pcatalog-integration-tests` | `catalog-integration` | no | running local Ceph and Polaris harnesses | Targeted live catalog conformance run (use `verify/polaris-compose-run-catalog-tests.sh`) |
| `-Psecrets-integration-tests` | `secrets-integration` | no | running local OpenBao harness | Targeted live secret-store conformance run (use `verify/openbao-compose-run-secrets-tests.sh`) |
| `-Pspark-integration-tests` | `spark-integration` | no | running local Ceph, OpenBao, Polaris and Spark harnesses | Targeted live Spark cluster and batch pipeline run (use `tests/spark-compose-run-live-tests.sh`) |
| `-Pall-tests` | all tags | yes | running local Ceph, OpenBao, Polaris and Spark harnesses | Removes every tag filter; see the warning below before using it |
| `-Puntagged-tests` | no known tag | no | none | Audit for tests missing an approved tag |

Targeted profiles deliberately skip the aggregate coverage report because they execute only part of the production code. They are diagnostic commands, not completion evidence.

## No Single Command Runs Everything

`-Pall-tests` only removes the tag filters. It supplies no endpoint, credential, or truststore, so invoking `./mvnw ... -Pall-tests` bare will fail. Each live layer obtains its environment from its own harness wrapper script, and no single wrapper supplies the environment of another — the Ceph wrapper knows nothing of Polaris.

A complete sweep is therefore the offline regression followed by each live layer through its own wrapper, as set out in [harness_operations_runbook.md](../operations/harness_operations_runbook.md) section 3. `-Pall-tests` is useful passed *through* a wrapper, to widen that layer's run (see Running Live Profiles Through a Wrapper below).

## Validation Rules

After any code change, the minimum acceptable validation is:

```text
clean verify
```

Use `clean` to prevent stale compiled classes from contaminating the result. `test` alone is useful while diagnosing a known failure but is not a completion command because it does not execute packaging and the `verify` quality gates.

A change MUST additionally pass the live layer it affects, run through that layer's wrapper:

| A change affecting | must also pass | wrapper |
|---|---|---|
| Ceph endpoint behavior, TLS, credentials, request signing, path-style routing, bucket policy, object operations, multipart behavior, cleanup | `ceph-integration` | `platform/ceph/compose-cluster/scripts/verify/ceph-compose-run-live-tests.sh` |
| Iceberg table behavior, zone namespaces, schema evolution, snapshot expiry, partition layout, catalog credentials | `catalog-integration` | `platform/polaris/compose-service/scripts/verify/polaris-compose-run-catalog-tests.sh` |
| Secret storage, retrieval, or the secret-store client | `secrets-integration` | `platform/openbao/compose-service/scripts/verify/openbao-compose-run-secrets-tests.sh` |
| Spark jobs, batch pipeline semantics, client submission, principal separation | `spark-integration` | `platform/spark/compose-cluster/scripts/tests/spark-compose-run-live-tests.sh` |

A change spanning several layers must pass each of them.

## Live Environment

Each wrapper supplies its own layer's environment and asserts its own enabling flag, so a live suite can never silently report success after skipping:

| Layer | Enabling flag the wrapper sets | Module it selects |
|---|---|---|
| Ceph | `CEPH_RGW_INTEGRATION=true` | `:stratus-ceph-tests` |
| Catalog | `STRATUS_CATALOG_INTEGRATION` | `:stratus-catalog-verifier` |
| Secrets | `STRATUS_SECRETS_INTEGRATION` | `:stratus-secrets-verifier` |
| Spark | `STRATUS_SPARK_INTEGRATION` | `:stratus-spark-tests` |

The catalog, secrets, and Spark wrappers also default `STRATUS_LOG_LEVEL=DEBUG`, so their transcripts prove both operational log levels.

The Ceph live profile requires all of the following:

```dotenv
CEPH_RGW_INTEGRATION=true
CEPH_RGW_ENDPOINT=https://object-store.stratus.local
CEPH_RGW_ACCESS_KEY=<scoped verification access key>
CEPH_RGW_SECRET_KEY=<matching verification secret>
CEPH_RGW_PROBE_BUCKET=stratus-landing
CEPH_DENIED_ACCESS_KEY=<isolated identity access key>
CEPH_DENIED_SECRET_KEY=<isolated identity secret key>
CEPH_RGW_DENIED_BUCKET=stratus-denied
S3_PATH_STYLE_ACCESS=true
```

The REST API conformance tests (`CephS3RestConformanceTest`, `CephAdminOpsRestConformanceTest`, `CephDashboardRestConformanceTest`) additionally require:

```dotenv
CEPH_ADMIN_OPS_ACCESS_KEY=<scoped Admin Operations reader access key>
CEPH_ADMIN_OPS_SECRET_KEY=<matching secret>
CEPH_DASHBOARD_ENDPOINT=https://object-store.stratus.local:8444
CEPH_DASHBOARD_USER=<dashboard sign-in user>
CEPH_DASHBOARD_PASSWORD=<matching password>
```

The Admin Operations identity is scoped to `buckets=read` and `usage=read` caps and MUST NOT be granted `users` or `metadata` read caps, which would expose other identities' keys. See [platform/ceph/tests/README.md](../../platform/ceph/tests/README.md) for the full REST conformance test.

The endpoint hostname must resolve on the machine executing Maven, and the endpoint CA must be trusted by the JVM executing Maven. Both are the caller's responsibility: these tests run in the Maven JVM on the workstation, not inside a container, so a Ceph harness whose own verification scripts pass from inside containers satisfies neither. For the Compose cluster this means a one-time hosts-file entry; see [platform/ceph/compose-cluster/README.md](../../platform/ceph/compose-cluster/README.md).

## Running Live Profiles Through a Wrapper

Against the Compose cluster, do not wire the variables above by hand. Each wrapper supplies its layer's environment — for Ceph, that includes building the CA truststore — and passes its arguments through to Maven, so a targeted or widened run keeps that environment:

```bash
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-run-live-tests.sh
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-run-live-tests.sh clean verify -Pall-tests
bash platform/polaris/compose-service/scripts/verify/polaris-compose-run-catalog-tests.sh test -Pcatalog-integration-tests
```

To run the complete Ceph harness verification sequence — including the live Maven
conformance tests — as one command with a per-step transcript, use
`verify/ceph-compose-validate-cluster.sh` (add `--full` to wrap the run in
startup and shutdown).

A selected live profile fails when its enabling flag is absent; it must never silently report success after skipping the live test.

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

### Live layers

Live suites are not run from PowerShell. The harness wrappers are bash-only
(ADR-P1-002) and are the only supported way to supply a live environment; invoke
them from Git Bash. `.\mvnw.cmd clean verify -Pall-tests` from PowerShell selects
the live tests without any endpoint or credential and fails.

### Targeted unit diagnosis

```powershell
.\mvnw.cmd test -Punit-tests -pl :stratus-storage-verifier 2>&1 |
    Tee-Object -FilePath "logs\storage-verifier-unit-$timestamp.txt"
```

### Resume after a known reactor failure

Use resume only after correcting a failure from a complete command. Repeat the original complete command without `-rf` before declaring the change complete.

```powershell
.\mvnw.cmd verify -rf :stratus-storage-verifier 2>&1 |
    Tee-Object -FilePath "logs\resume-storage-verifier-$timestamp.txt"
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

### Live layers, each through its own wrapper

The wrappers write their own transcripts into the component's `logs/` directory; the repository-root `logs/` is for Maven build logs only.

```bash
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-run-live-tests.sh
bash platform/polaris/compose-service/scripts/verify/polaris-compose-run-catalog-tests.sh
bash platform/openbao/compose-service/scripts/verify/openbao-compose-run-secrets-tests.sh
bash platform/spark/compose-cluster/scripts/tests/spark-compose-run-live-tests.sh
```

### Targeted offline profiles

```bash
./mvnw test -Punit-tests -pl :stratus-storage-verifier 2>&1 \
  | tee "logs/storage-verifier-unit-${timestamp}.txt"

./mvnw test -Puntagged-tests 2>&1 \
  | tee "logs/untagged-audit-${timestamp}.txt"
```

To narrow a live run to one module, append the Maven arguments to the wrapper rather than calling `./mvnw` directly:

```bash
bash platform/ceph/compose-cluster/scripts/verify/ceph-compose-run-live-tests.sh \
  test -Pceph-integration-tests -pl :stratus-ceph-tests -am
```

### Inspect the result

```bash
tail -n 40 "logs/local-regression-${timestamp}.txt"
```

## Module Selection

Prefer Maven artifact IDs over filesystem paths:

```powershell
.\mvnw.cmd test -Punit-tests -pl :stratus-storage-verifier
```

When a selected module requires upstream reactor modules, add `-am`:

```powershell
.\mvnw.cmd verify -pl :stratus-storage-verifier -am
```

Do not use a targeted module command as final regression evidence. The complete reactor command remains mandatory.

## Profile Health Audit

### Confirm effective default selection

```powershell
.\mvnw.cmd help:effective-pom -pl :stratus-ceph-tests 2>&1 |
    Select-String -Pattern 'test.groups|test.excludedGroups|coverage.skip'
```

Expected values:

```text
test.groups=(empty)
test.excludedGroups=ceph-integration | catalog-integration | secrets-integration | spark-integration
coverage.skip=false
```

### Confirm `all-tests` removes filters

```powershell
.\mvnw.cmd help:effective-pom -pl :stratus-ceph-tests -Pall-tests 2>&1 |
    Select-String -Pattern 'test.groups|test.excludedGroups|integration.required'
```

Expected behavior: included and excluded group values are empty, and each `<layer>.integration.required` is `true`.

### Confirm profiles exist only in the root POM

```powershell
Get-ChildItem -Recurse -Filter pom.xml |
    Select-String -Pattern '<id>(unit-tests|ceph-integration-tests|catalog-integration-tests|secrets-integration-tests|spark-integration-tests|all-tests|untagged-tests)</id>'
```

Every match MUST refer to the repository root `pom.xml`. A match in a module POM means test-selection policy has been decentralized and must be corrected.

### Confirm every test class has an approved tag

```powershell
$approved = 'unit|ceph-integration|catalog-integration|secrets-integration|spark-integration'
Get-ChildItem -Recurse -Filter '*Test.java' |
    ForEach-Object {
        if (-not (Select-String -Quiet -LiteralPath $_.FullName -Pattern "@Tag\(`"($approved)`"\)")) {
            $_.FullName
        }
    }
```

Expected output: none. This is a static cross-check on the source; `-Puntagged-tests` is the executing equivalent and must report zero tests.

## Current Module Notes

- `stratus-storage-verifier` owns the executable verifier and its offline unit
  tests: pure logic and real environmental failures only.
- `stratus-catalog-verifier` and `stratus-secrets-verifier` own the
  product-neutral catalog and secret-store conformance suites.
- `stratus-ceph-tests` under `platform/ceph/tests` owns
  `CephRgwConformanceTest`, the implementation-neutral product-compatibility
  boundary. It runs the full S3 conformance, missing-bucket detection, both
  security negatives, consistency, pagination, multipart cleanup, evidence
  writing, and exit semantics against whichever live Ceph RGW implementation
  the environment supplies. It is not selected by the default local regression.
- `stratus-spark-tests` under `platform/spark/tests` owns the live cluster,
  client-submission, batch-pipeline, incremental-load, and principal-separation
  suites. Under `-Pspark-integration-tests` Surefire sets `reuseForks=true` and
  keeps one JVM. A JUnit root-store extension owns one suite-scoped, two-core
  `SparkContext`; every live client class receives an isolated `SparkSession`
  with class-specific catalog configuration and identity. Closing a class
  session clears its cache without stopping the context, and the root store
  stops the context after the complete launcher run.
- `stratus-spark-jobs` under `jobs/spark` owns the platform job code and its
  offline unit tests.
- Mockito, all other mocking frameworks, and simulated product endpoints of any
  kind are prohibited. Tests against a simulated product are worthless as
  verification (code_style_rules.md 7.2).
- JaCoCo reports are generated under each verifier module's
  `target/site/jacoco/`.
- Surefire reports are generated under each module's `target/surefire-reports/`,
  including `platform/ceph/tests/target/surefire-reports/`.

## Completion Checklist

- [ ] The complete local regression command passed from a clean reactor.
- [ ] The saved Maven log contains `BUILD SUCCESS`.
- [ ] The saved Maven log contains no build, logging-binding, packaging, or test-fixture warnings.
- [ ] INFO and DEBUG logging assertions passed.
- [ ] The untagged audit executes zero tests after adding or moving test classes.
- [ ] Every live layer the change affects passed through its own wrapper, per the Validation Rules table.
- [ ] No test was silently skipped by a selected profile.
- [ ] No module POM contains a test-selection profile.
- [ ] No child POM pins dependency or plugin versions.
- [ ] No mocking framework, test double, or simulated product endpoint was introduced.
