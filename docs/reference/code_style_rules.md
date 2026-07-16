# Stratus Code Style and Engineering Rules

## 1. Purpose

This guide defines the mandatory coding and engineering conventions for Stratus-owned source code, tests, build files, containers, scripts, configuration, and technical documentation.

The terms **MUST**, **MUST NOT**, **SHOULD**, and **MAY** are normative. A deviation from a **MUST** requires an architecture decision record or an explicitly approved compatibility exception with supporting evidence.

## 2. Core Principles

1. Test observable behavior, not implementation choreography.
2. Prefer real protocol and integration boundaries over simulated confidence.
3. Keep production readiness and developer pragmatism as explicit, compatible tracks.
4. Use stable capability names for runtime artifacts; phase and increment numbers are planning metadata only.
5. Centralize policy and versions instead of repeating them in child projects.
6. Fail early on invalid, insecure, or ambiguous configuration.
7. Never expose secrets through source code, logs, exceptions, reports, examples, or test evidence.

## 3. Repository and Artifact Naming

- Runtime code MUST live under the capability boundary defined in [repository-layout.md](repository-layout.md).
- Applications, jobs, verifiers, images, deployment paths, Java packages, and Maven artifact IDs MUST use stable capability names.
- Runtime names MUST NOT contain phase or increment identifiers such as `phase1`, `increment1`, or `p1-1`.
- Phase and increment identifiers MAY appear in implementation plans, task tracking, acceptance evidence metadata, and historical records.
- Java group and package names MUST use the `dev.stratus` namespace.
- Directories and artifact IDs MUST use lowercase kebab case, for example `build-support` and `stratus-storage-verifier`.
- Java package segments MUST use lowercase words without underscores or hyphens.
- Environment-specific inventory MUST live under `environments/`; reusable product integration belongs under `platform/`.

## 4. Java Source Style

### 4.1 Language and structure

- Stratus Java modules MUST compile against the Java release selected by `stratus-build-parent`.
- Source files MUST use UTF-8 and SHOULD remain ASCII unless the content requires Unicode.
- One top-level public type SHOULD be declared per source file.
- Types not designed for inheritance SHOULD be `final`.
- Immutable data carriers SHOULD use records when record semantics are appropriate.
- Constructor injection MUST be used for required collaborators. Hidden service locators and mutable global dependencies MUST NOT be introduced.
- Production behavior SHOULD depend on small Stratus-owned interfaces at genuine ownership boundaries.
- Methods SHOULD be short, single-purpose, and named for observable intent.
- `var` MAY be used when the assigned expression makes the type immediately obvious. Explicit types SHOULD be used when they improve API or algorithm comprehension.
- Wildcard imports MUST NOT be used.
- Comments MUST explain non-obvious constraints, protocol behavior, or design reasoning. Comments MUST NOT narrate self-explanatory statements.

### 4.2 Naming

- Classes, records, enums, and interfaces MUST use `UpperCamelCase`.
- Methods, fields, parameters, and local variables MUST use `lowerCamelCase`.
- Constants MUST use `UPPER_SNAKE_CASE`.
- Test names MUST describe behavior and outcome, for example `abortsMultipartUploadWhenTheEndpointRejectsAPart`.
- Names such as `manager`, `helper`, `util`, `data`, and `processor` SHOULD NOT be used without a precise domain qualifier.
- Cloud-provider terminology MUST NOT be invented for on-premises concepts. Third-party API names MAY retain their upstream terminology when documented as external identifiers.

### 4.3 Exceptions and resources

- Invalid configuration MUST fail before network or storage operations begin.
- Exception messages MUST identify the invalid field or failed operation and MUST NOT include credentials or sensitive payloads.
- Exceptions MUST retain their cause when translated across an ownership boundary.
- Broad `catch (Exception)` blocks SHOULD NOT be used. Catch the narrowest meaningful type.
- `AutoCloseable` resources MUST use try-with-resources or an explicit `finally` lifecycle.
- Cleanup failures MUST be reported; they MUST NOT silently replace the original failure.
- Command-line entry points MUST return stable, documented exit semantics for success, configuration failure, and execution failure.

## 5. Configuration and Secrets

- Configuration keys MUST use uppercase snake case and a `STRATUS_` or capability-specific prefix such as `CEPH_RGW_`.
- Every configuration key MUST be documented with requirement status, default, valid values, ownership, and security implications.
- Defaults MUST be safe for the declared environment. Insecure transport MUST require an explicit disposable-development override.
- Secrets MUST be supplied at runtime through the approved secret mechanism and MUST NOT be committed.
- `.env` files are local working material, not a production secret-management mechanism.
- Secret values MUST be redacted from `toString()`, logs, exceptions, test assertions, and evidence.
- Administrator credentials MUST NOT be substituted for scoped application identities.
- Validation MUST reject malformed endpoints, embedded credentials, unexpected paths, unsupported schemes, and internally inconsistent options.

## 6. Logging and Observability

- Stratus services and executable verifiers MUST emit operational logs at `INFO` and diagnostic logs at `DEBUG`.
- Tests MUST exercise and visibly emit both INFO and DEBUG records through the production logging path.
- INFO messages SHOULD describe lifecycle transitions, outcomes, and stable identifiers.
- DEBUG messages SHOULD describe diagnostic protocol or workflow detail needed to investigate a failure.
- Credentials, private keys, authorization headers, object payloads, and sensitive records MUST NOT be logged at any level.
- Executable components MUST support persistent, bounded rolling logs where local file logging is part of the deployment contract.
- Rolling-log size, retention count, path, and level MUST be configurable and documented.
- Log handlers MUST be flushed and closed on every success and failure path.
- Console logging MAY coexist with persistent logs for container diagnostics.
- Logs and test evidence MUST be reviewed and sanitized before intentional publication.

## 7. Testing Rules

### 7.1 Test-driven implementation

- New behavior and defect fixes MUST begin with an executable failing test or contract reproduction whenever practical.
- Tests MUST assert externally observable state, output, protocol traffic, persistence, or failure semantics.
- Tests MUST be deterministic, isolated, and repeatable.
- Time-dependent code MUST use an injected `Clock` or equivalent controllable time source.
- Tests MUST clean up files, processes, network listeners, containers, and remote probe objects they create.

### 7.2 Mocking and simulation prohibition

- Mockito is prohibited in every Stratus project.
- No other mocking framework may be introduced as a substitute.
- Tests MUST NOT verify call choreography against framework-generated mocks.
- Tests MUST NOT substitute hand-written test doubles for Stratus-owned interfaces; behavior MUST be exercised through the real production implementation.
- Tests MUST NOT simulate a product's protocol endpoint. In-process or scripted stand-ins for Ceph, Iceberg, Spark, Airflow, Trino, Kafka, Flink, Atlas, Ranger, Keycloak, or any other deployed product are prohibited: a test against a simulated product is worthless as verification.
- Behavior that depends on a product MUST be tested against the live product — for Ceph RGW, the local Docker cluster in `platform/ceph/local`.
- Real environmental failures (an unreachable address, a closed port, an unwritable path) are not simulations and MAY be used to test failure handling.

### 7.3 Coverage and test layers

- Coverage is reported by every `verify` build for visibility; it is NOT a gate. A coverage gate rewards simulated endpoints, and simulated endpoints are prohibited by 7.2.
- Coverage MUST NOT be raised through simulated endpoints or test doubles. Branches reachable only through a misbehaving product remain uncovered by JVM tests and are proven by live drills and harness verification instead.
- Production classes MUST NOT be excluded from coverage reporting.
- Coverage proves execution, not correctness. Assertions and integration evidence remain mandatory.
- Unit tests SHOULD cover pure validation, transformations, reports, orchestration decisions, and real environmental failures.
- Integration tests MUST exercise the selected product release with its real security and network configuration — for Ceph, the `ceph-integration` tag against the local cluster.
- End-to-end and operational tests MUST prove cross-component contracts, recovery, observability, and cleanup.
- Test output MUST make failures diagnosable without exposing secrets.

## 8. Maven and Dependency Management

- Dependency versions MUST be owned by `build-support/stratus-bom`.
- Java, compiler, test, packaging, and quality-plugin versions MUST be owned by `build-support/stratus-build-parent`.
- Capability child POMs MUST NOT pin dependency versions, plugin versions, or local version properties.
- Child modules MUST inherit centrally managed build policy and declare only the dependencies and plugins they use.
- New dependencies require a demonstrated need, license review where applicable, and evidence that the selected release is current and supported.
- Open-source component and library versions MUST be checked against current primary project documentation before selection or upgrade.
- Repository Maven wrappers MUST be used for reproducible local and CI builds.
- The standard Java quality command is:

```powershell
.\mvnw.cmd clean verify
```

```bash
./mvnw clean verify
```

## 9. Containers and Compose

- Images MUST run as a non-root user unless the component demonstrably requires elevated privileges.
- Containers SHOULD use a read-only root filesystem, explicit writable mounts, bounded temporary filesystems, and `no-new-privileges` where supported.
- Build tools and source trees MUST NOT be present in runtime images unless they are required at runtime.
- Runtime artifacts MUST use stable capability names.
- Shared and production environments MUST use approved immutable image digests.
- Developer Compose files MAY use current approved release tags for usability, but image variables and the production promotion path MUST be documented.
- Docker Desktop, Docker Engine, and Podman behavior MUST remain explicit where all are supported.
- Compose configuration MUST fail when required values are absent.
- Health checks MUST validate component contracts where possible, not merely process existence.
- Persistent data, logs, and evidence MUST use explicit mounts with documented ownership and retention.

## 10. Shell and PowerShell

- Supported operational workflows MUST provide equivalent Bash and PowerShell entry points unless the target environment is explicitly OS-specific.
- Scripts MUST be idempotent: repeating startup, shutdown, bootstrap, or prerequisite installation must converge safely.
- Bash scripts MUST use `set -euo pipefail`.
- PowerShell scripts MUST use `$ErrorActionPreference = 'Stop'`.
- Scripts MUST quote filesystem paths and variable expansions correctly.
- Scripts MUST validate prerequisites and print an actionable remediation command when one is missing.
- Scripts MUST return a non-zero exit status on failure.
- Destructive operations MUST validate their resolved target before deletion or movement.
- Secrets MUST NOT be printed, passed in command history unnecessarily, or written to world-readable files.

## 11. Documentation Style

- Documentation MUST be written for a capable reader who has no hidden project context.
- Setup instructions MUST explain where every endpoint, credential, certificate, and configuration value comes from.
- Procedures MUST include prerequisites, exact commands, expected success output, validation, cleanup, and troubleshooting.
- Developer and production tracks MUST be distinguished explicitly.
- Documentation MUST NOT use vague instructions such as "configure the endpoint" without naming the owner, source, format, destination, and verification method.
- Local links MUST be relative and resolve within the repository.
- Code fences MUST identify the language where practical.
- Tables SHOULD be used for configuration contracts, ownership, acceptance criteria, and task tracking.
- Implementation documents MUST contain actionable task tracking with owner, dependency, deliverable, evidence, acceptance criteria, and status.
- Claims about product behavior or supported versions SHOULD cite current primary documentation in the relevant design document.

## 12. Review and Completion Gate

A change is not complete until all applicable checks pass:

- code follows the stable repository and naming boundaries
- no mocking framework and no simulated product endpoint has been introduced
- child POMs contain no dependency or plugin version pins
- unit, live integration, and operational tests appropriate to the change pass
- product-dependent behavior is proven against the live product, not a stand-in
- INFO and DEBUG logging behavior is tested
- persistent logs and resources are flushed and released
- secrets are absent from source, logs, reports, examples, and Git status
- container and Compose configuration validates for supported runtimes
- scripts are idempotent and have actionable prerequisite failures
- documentation explains configuration sources and both developer and production implications
- `git diff --check` reports no whitespace errors

Reviewers MUST reject a change that achieves a numeric quality gate while failing to prove the real behavior of the system boundary.
