// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Offline repository contract for the Airflow image dependency, hardening, evidence, and release
 * baseline.
 *
 * <h2>Rationale</h2>
 *
 * <p>The Airflow image combines independently versioned inputs from Airflow, Python, providers,
 * Spark, Java, and the vulnerability scanner. A locally successful image build is not sufficient
 * evidence that these inputs are approved, reproducible, or safe to promote. An unreviewed edit to
 * a version, URL, hash, Dockerfile command, verification script, waiver, or status document could
 * otherwise create dependency drift while still producing a runnable container.
 *
 * <p>This test therefore treats the checked-in image definition as a release contract. It verifies
 * that external inputs are immutable and hash checked, image assembly does not perform mutable
 * dependency resolution, required runtime compatibility checks remain present, deliberately
 * removed components do not silently return, and vulnerability acceptance is explicit, scoped,
 * time bounded, and consistently represented in implementation-status documents.
 *
 * <p>The expected versions and acceptance values are deliberately declared in this test rather
 * than derived entirely from the files under test. They form the independent test oracle: reading
 * a version from {@code artifact-lock.properties} and merely asserting that the same file contains
 * it would be tautological and would not detect an accidental baseline change. These constants are
 * private because this class is currently the only Java consumer. Introduce a shared test fixture
 * only when another Java test needs the same release contract; production code must not depend on
 * test constants.
 *
 * <h2>What this test proves</h2>
 *
 * <ul>
 *   <li>Required lock, build, smoke, scan, review, and waiver artifacts remain at stable paths.</li>
 *   <li>The approved Airflow/provider/Python/Spark/Java versions agree across dependency intent,
 *       exact hash locks, image inputs, and verification scripts.</li>
 *   <li>Image assembly consumes prepared artifacts and cannot download mutable dependencies.</li>
 *   <li>Smoke and scan scripts retain the checks needed to detect runtime or hardening regressions.</li>
 *   <li>The recorded vulnerability waiver is bound to a specific image, scope, finding count, and
 *       expiry, and the implementation documents preserve the same acceptance boundary.</li>
 * </ul>
 *
 * <p>This is an offline structural guardrail. It does not build or scan an image, start Airflow,
 * execute database migrations, schedule a DAG, submit Spark work, validate external services, or
 * prove UAT or production readiness. Passing this class is necessary evidence, never sufficient
 * release acceptance.
 *
 * <h2>Maintaining the contract for a new version</h2>
 *
 * <ol>
 *   <li>Review upstream Airflow constraints, provider compatibility, Python support, Spark/Java
 *       compatibility, release notes, and known vulnerabilities. Record the selected baseline and
 *       any intentional version skew in the owning implementation document.</li>
 *   <li>Change the constants in this class first or in the same atomic change as the baseline.
 *       Observe the relevant assertions fail before updating the image inputs; do not weaken or
 *       delete an assertion merely to make a new version pass.</li>
 *   <li>Update {@code requirements.in}, {@code requirements.lock},
 *       {@code artifact-lock.properties}, immutable image digests, archive hashes, Dockerfile,
 *       resolver, smoke test, and scan test as one coherent version set. Resolve artifacts only in
 *       the approved preparation step; runtime hosts and image assembly remain offline.</li>
 *   <li>For development, build the image from a clean context, run the complete runtime smoke and
 *       pinned vulnerability tests, and record timings and the local image identity.</li>
 *   <li>In the later production-hardening stage, repeat the accepted build on the approved worker,
 *       generate the SBOM and provenance, publish and sign the image, and record the immutable
 *       registry digest. A local image ID is development evidence, not a promotable digest.</li>
 *   <li>Replace the vulnerability review for the new digest. Close, supersede, or reapprove every
 *       waiver explicitly; a waiver never transfers automatically to another image or version.</li>
 *   <li>Update the Airflow increment plan, Phase 1 tracker, readiness document, verification index,
 *       and release evidence together. Run this class, the complete repository guardrail module,
 *       and the full offline reactor before requesting environment acceptance.</li>
 * </ol>
 *
 * <h2>Development first, production hardening later</h2>
 *
 * <p><strong>Development:</strong> build from pinned inputs, verify the local development image, and
 * prove repeatable startup and shutdown, PostgreSQL migration and health, provider imports, DAG
 * discovery, Spark submission compatibility, secret redaction, and cleanup. A development-only
 * waiver may unblock engineering only when its scope, controls, owner, evidence, invalidation
 * conditions, and expiry are recorded.
 *
 * <p><strong>Later production-hardening stage:</strong> reproduce the accepted build contract on the
 * approved worker, publish it by immutable digest, and add signature/provenance, SBOM, production
 * scan policy, managed configuration and secrets, backup/restore, durable logs, monitoring,
 * capacity, change approval and rollback evidence. Any UAT environment is part of this later
 * promotion process. Production acceptance belongs to the separate hardening/readiness plan and
 * its named owners, not to this unit test.
 *
 * <p>This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-16
 * @version 1.2.0
 */
@Tag("unit")
final class AirflowArtifactBaselineTest {

    private static final Path IMAGE_ROOT = Repo.root().resolve(Path.of("platform", "airflow", "image"));
    private static final Path DOCKERFILE_PATH = Path.of("Dockerfile");
    private static final Path REQUIREMENTS_IN_PATH = Path.of("requirements.in");
    private static final Path REQUIREMENTS_LOCK_PATH = Path.of("requirements.lock");
    private static final Path ARTIFACT_LOCK_PATH = Path.of("artifact-lock.properties");
    private static final Path VULNERABILITY_REVIEW_PATH = Path.of("vulnerability-review.md");
    private static final Path VULNERABILITY_WAIVER_PATH = Path.of("vulnerability-waiver.md");
    private static final Path IMAGE_GITIGNORE_PATH = Path.of(".gitignore");
    private static final Path IMAGE_DOCKERIGNORE_PATH = Path.of(".dockerignore");
    private static final Path ARTIFACT_RESOLVER_PATH = Path.of(
            "scripts", "build", "airflow-image-resolve-artifacts.sh");
    private static final Path IMAGE_BUILD_PATH = Path.of(
            "scripts", "build", "airflow-image-build.sh");
    private static final Path IMAGE_SMOKE_TEST_PATH = Path.of(
            "scripts", "tests", "airflow-image-smoke-test.sh");
    private static final Path VULNERABILITY_SCAN_TEST_PATH = Path.of(
            "scripts", "tests", "airflow-image-vulnerability-scan-test.sh");
    private static final Path IMAGE_ACCEPTANCE_TEST_PATH = Path.of(
            "scripts", "tests", "airflow-image-acceptance-test.sh");

    private static final Path PHASE_PLAN_PATH = Path.of(
            "docs", "implementation", "stratus_implementation_plan_phase1.md");
    private static final Path INCREMENT_PLAN_PATH = Path.of(
            "docs", "implementation", "airflow_orchestration.md");
    private static final Path TASK_AUDIT_PATH = Path.of(
            "docs", "implementation", "task_track_audit.md");
    private static final Path READINESS_PATH = Path.of(
            "docs", "operations", "stratus_phase1_operational_readiness.md");
    private static final Path VERIFIER_INDEX_PATH = Path.of("verification", "README.md");

    private static final String AIRFLOW_DISTRIBUTION = "apache-airflow";
    private static final String AIRFLOW_IMAGE_REPOSITORY = "apache/airflow";
    private static final String AIRFLOW_VERSION = "3.3.1";
    private static final String SUPERSEDED_AIRFLOW_VERSION = "3.3.0";
    private static final String SPARK_PROVIDER = "apache-airflow-providers-apache-spark";
    private static final String SPARK_PROVIDER_VERSION = "6.3.1";
    private static final String AMAZON_PROVIDER = "apache-airflow-providers-amazon";
    private static final String AMAZON_PROVIDER_VERSION = "9.34.0";
    private static final String BOTO3 = "boto3";
    private static final String BOTO3_VERSION = "1.43.56";
    private static final String AIOHTTP = "aiohttp";
    private static final String AIOHTTP_VERSION = "3.14.3";
    private static final String PYSPARK = "pyspark";
    private static final String PYSPARK_CLIENT = "pyspark-client";
    private static final String ZSTANDARD = "zstandard";
    private static final String ZSTANDARD_VERSION = "0.25.0";
    private static final String SPARK_VERSION = "4.1.3";
    private static final String SPARK_IMAGE_REPOSITORY = "apache/spark";
    private static final String SPARK_IMAGE_VARIANT = "scala2.13-java21-python3-ubuntu";
    private static final String SPARK_IMAGE_DIGEST =
            "sha256:bf9d035a7c32a8ca46aa58d6348182ffd7d2dff6409206ecfbb3915ff1fef211";
    private static final String PYTHON_VERSION = "3.14";
    private static final String JAVA_VERSION = "21";
    private static final String TRIVY_VERSION = "0.74.0";

    private static final String WAIVER_ID = "WAIVER-P1-4.1-S1-20260817";
    private static final String WAIVER_EFFECTIVE_DATE = "2026-08-17";
    private static final String WAIVER_EXPIRY_DATE = "2026-09-16";
    private static final String WAIVED_IMAGE_DIGEST =
            "sha256:89db37a79b60dd9224874afca3a4b57afadbeab0a205f8835958fecea259bc97";
    private static final int EXPECTED_HIGH_OCCURRENCES = 84;
    private static final int EXPECTED_UNIQUE_PACKAGE_CVE_PAIRS = 35;
    private static final String CURRENT_IMAGE_TASK_ID = "P1-4.1-S2";
    private static final String CURRENT_SUBMISSION_TASK_ID = "P1-4.2-D1";
    private static final String NEXT_DEVELOPMENT_TASK_ID = "P1-4.3-V1";
    private static final String DEVELOPMENT_ACCEPTANCE_DATE = "2026-08-22";
    private static final String CURRENT_STAGE_MARKER =
            "Current stage:** Development implementation and functional acceptance";
    private static final String LATER_STAGE_MARKER =
            "Later stage:** Production deployment hardening and readiness";
    private static final String PRODUCTION_ACCEPTED_CLAIM = "production readiness accepted";
    private static final String SHELL_CONJUNCTION = Character.toString('&') + '&';

    @Test
    void imageInputsAndVerificationScriptsHaveStableLocations() {
        assertAll(
                () -> assertFile(DOCKERFILE_PATH),
                () -> assertFile(REQUIREMENTS_IN_PATH),
                () -> assertFile(REQUIREMENTS_LOCK_PATH),
                () -> assertFile(ARTIFACT_LOCK_PATH),
                () -> assertFile(VULNERABILITY_REVIEW_PATH),
                () -> assertFile(VULNERABILITY_WAIVER_PATH),
                () -> assertFile(IMAGE_GITIGNORE_PATH),
                () -> assertFile(IMAGE_DOCKERIGNORE_PATH),
                () -> assertFile(ARTIFACT_RESOLVER_PATH),
                () -> assertFile(IMAGE_BUILD_PATH),
                () -> assertFile(IMAGE_SMOKE_TEST_PATH),
                () -> assertFile(VULNERABILITY_SCAN_TEST_PATH),
                () -> assertFile(IMAGE_ACCEPTANCE_TEST_PATH));
    }

    @Test
    void dependencyIntentMatchesTheApprovedAirflowMatrix() {
        String requirements = read(REQUIREMENTS_IN_PATH);
        assertAll(
                () -> assertTrue(requirements.contains(
                        requirement(AIRFLOW_DISTRIBUTION, AIRFLOW_VERSION))),
                () -> assertTrue(requirements.contains(
                        requirement(SPARK_PROVIDER, SPARK_PROVIDER_VERSION))),
                () -> assertTrue(requirements.contains(
                        requirement(AMAZON_PROVIDER, AMAZON_PROVIDER_VERSION))),
                () -> assertTrue(requirements.contains(requirement(BOTO3, BOTO3_VERSION))),
                () -> assertTrue(requirements.contains(requirement(AIOHTTP, AIOHTTP_VERSION))),
                () -> assertTrue(requirements.contains(requirement(PYSPARK_CLIENT, SPARK_VERSION))),
                () -> assertTrue(requirements.contains(requirement(ZSTANDARD, ZSTANDARD_VERSION))),
                () -> assertFalse(requirements.lines()
                        .anyMatch(line -> line.startsWith(PYSPARK + "==")),
                        "SparkSubmitOperator mode must not download the full PySpark distribution"),
                () -> assertTrue(requirements.lines()
                                .noneMatch(line -> line.trim().equals(
                                        requirement(AIRFLOW_DISTRIBUTION,
                                                SUPERSEDED_AIRFLOW_VERSION))),
                        "The superseded Airflow patch must not remain in dependency intent"));
    }

    @Test
    void lockedPythonArtifactsAreExactAndHashVerified() {
        String lock = read(REQUIREMENTS_LOCK_PATH);
        assertAll(
                () -> assertLocked(lock, AIRFLOW_DISTRIBUTION, AIRFLOW_VERSION),
                () -> assertLocked(lock, SPARK_PROVIDER, SPARK_PROVIDER_VERSION),
                () -> assertLocked(lock, AMAZON_PROVIDER, AMAZON_PROVIDER_VERSION),
                () -> assertLocked(lock, BOTO3, BOTO3_VERSION),
                () -> assertLocked(lock, AIOHTTP, AIOHTTP_VERSION),
                () -> assertLocked(lock, PYSPARK_CLIENT, SPARK_VERSION),
                () -> assertLocked(lock, ZSTANDARD, ZSTANDARD_VERSION),
                () -> assertFalse(lock.lines().anyMatch(line -> line.startsWith(PYSPARK + "==")),
                        "The 455 MB PySpark source distribution must not enter the wheelhouse"));
    }

    @Test
    void artifactLockIdentifiesEveryExternalBuildInput() {
        String lock = read(ARTIFACT_LOCK_PATH);
        assertAll(
                () -> assertTrue(lock.contains(property("airflow.version", AIRFLOW_VERSION))),
                () -> assertTrue(lock.contains(property("python.version", PYTHON_VERSION))),
                () -> assertTrue(lock.contains(property("spark.version", SPARK_VERSION))),
                () -> assertTrue(lock.contains(property("java.version", JAVA_VERSION))),
                () -> assertTrue(lock.contains("constraints.url=https://raw.githubusercontent.com/")),
                () -> assertTrue(lock.matches("(?s).*constraints.sha256=[0-9a-f]{64}.*")),
                () -> assertTrue(lock.contains(property("spark.image",
                        SPARK_IMAGE_REPOSITORY + ":" + SPARK_VERSION + "-" + SPARK_IMAGE_VARIANT))),
                () -> assertTrue(lock.contains(property("spark.image.digest", SPARK_IMAGE_DIGEST))),
                () -> assertFalse(lock.contains("spark.archive."),
                        "S2 obtains Spark from OCI layers, not a host-side archive"),
                () -> assertTrue(lock.contains("constraints-" + AIRFLOW_VERSION
                        + "/constraints-" + PYTHON_VERSION + ".txt")),
                () -> assertTrue(lock.contains(property("airflow.image",
                        AIRFLOW_IMAGE_REPOSITORY + ":" + AIRFLOW_VERSION
                                + "-python" + PYTHON_VERSION))),
                () -> assertFalse(lock.contains("java.image="),
                        "The pinned Spark OCI stage supplies its matched Java runtime"));
    }

    @Test
    void imageAssemblyCannotResolveMutableDependencies() {
        String dockerfile = read(DOCKERFILE_PATH);
        assertAll(
                () -> assertTrue(dockerfile.contains("ARG AIRFLOW_BASE_IMAGE=")),
                () -> assertTrue(dockerfile.contains("ARG SPARK_SOURCE_IMAGE="
                        + SPARK_IMAGE_REPOSITORY + ":" + SPARK_VERSION + "-"
                        + SPARK_IMAGE_VARIANT + "@" + SPARK_IMAGE_DIGEST)),
                () -> assertTrue(dockerfile.contains("FROM ${SPARK_SOURCE_IMAGE} AS spark")),
                () -> assertTrue(dockerfile.contains("COPY --from=spark /opt/spark /opt/spark")),
                () -> assertTrue(dockerfile.contains(
                        "COPY --from=spark /opt/java/openjdk /opt/java/openjdk")),
                () -> assertTrue(dockerfile.contains("artifacts/wheelhouse/")),
                () -> assertFalse(dockerfile.contains("COPY artifacts/spark-"),
                        "The Spark archive must not enter the host build context"),
                () -> assertTrue(dockerfile.contains("--no-index")),
                () -> assertTrue(dockerfile.contains("--no-deps")),
                () -> assertTrue(dockerfile.contains("--require-hashes")),
                () -> assertTrue(dockerfile.contains("USER airflow")),
                () -> assertTrue(dockerfile.contains(ARTIFACT_LOCK_PATH.toString())),
                () -> assertFalse(dockerfile.matches("(?s).*RUN\\s+.*(curl|wget).*"),
                        "Image assembly must consume only pre-resolved artifacts"));
    }

    @Test
    void resolverAndSmokeTestCoverIntegrityAndRuntimeCompatibility() {
        String resolver = read(ARTIFACT_RESOLVER_PATH);
        String build = read(IMAGE_BUILD_PATH);
        String smoke = read(IMAGE_SMOKE_TEST_PATH);
        String scan = read(VULNERABILITY_SCAN_TEST_PATH);
        String acceptance = read(IMAGE_ACCEPTANCE_TEST_PATH);
        String dockerignore = read(IMAGE_DOCKERIGNORE_PATH);
        assertAll(
                () -> assertTrue(read(IMAGE_GITIGNORE_PATH).contains("artifacts/")),
                () -> assertTrue(read(IMAGE_GITIGNORE_PATH).contains("!scripts/build/"),
                        "The root build/ ignore rule must not hide Airflow's build scripts"),
                () -> assertTrue(dockerignore.contains("**")),
                () -> assertTrue(dockerignore.contains("!artifacts/wheelhouse/**")),
                () -> assertFalse(dockerignore.contains("spark-4.1.3-bin-hadoop3.tgz"),
                        "The S2 build context must never include the Spark archive"),
                () -> assertTrue(resolver.contains(REQUIREMENTS_LOCK_PATH.toString())),
                () -> assertTrue(resolver.contains(ARTIFACT_LOCK_PATH.toString())),
                () -> assertTrue(resolver.contains("sub(/\\r$/"),
                        "The resolver must strip CRLF endings from lock values on Windows"),
                () -> assertTrue(resolver.contains(requirement(AIOHTTP, AIOHTTP_VERSION)),
                        "Resolution must verify the fixed aiohttp version against Airflow's constraint"),
                () -> assertTrue(resolver.contains(
                                "find \"${WHEELHOUSE_DIR}\" -maxdepth 1 -type f -delete"),
                        "Resolution must remove superseded artifacts before populating the wheelhouse"),
                () -> assertTrue(resolver.contains("sha256sum --check")),
                () -> assertFalse(resolver.contains("sha512sum"),
                        "The resolver must not download or hash a Spark archive"),
                () -> assertTrue(smoke.contains("airflow version")),
                () -> assertTrue(smoke.contains("SparkSubmitOperator")),
                () -> assertTrue(smoke.contains("S3KeySensor")),
                () -> assertTrue(smoke.contains("pyspark.__version__")),
                () -> assertTrue(smoke.contains("python -m pip check")),
                () -> assertTrue(smoke.contains("pyspark-client")),
                () -> assertTrue(smoke.contains("version(\"pyspark\")"),
                        "The smoke test must prove the full PySpark distribution is absent"),
                () -> assertTrue(smoke.contains(
                                "\"" + AIOHTTP + "\": \"" + AIOHTTP_VERSION + "\""),
                        "The runtime smoke must prove that the vulnerable upstream aiohttp was replaced"),
                () -> assertTrue(smoke.contains("java -version")),
                () -> assertTrue(smoke.contains("spark-submit --version")),
                () -> assertTrue(scan.contains("aquasec/trivy:" + TRIVY_VERSION + "@sha256:")),
                () -> assertTrue(scan.contains("docker save")),
                () -> assertTrue(scan.contains("--input")),
                () -> assertTrue(scan.contains("tar -xOf \"${ARCHIVE}\" manifest.json")),
                () -> assertTrue(scan.contains("]] " + SHELL_CONJUNCTION
                        + " archive_matches_image; then")),
                () -> assertTrue(scan.contains("cd \"${ARTIFACT_DIR}\"")),
                () -> assertTrue(scan.contains(
                        "docker save --output \"${ARCHIVE_NAME}\" \"${IMAGE_TAG}\"")),
                () -> assertTrue(build.contains("development-image-id.txt")),
                () -> assertTrue(scan.contains("scan-archive-image-id.txt")),
                () -> assertTrue(scan.contains("trap cleanup EXIT")),
                () -> assertTrue(scan.contains("trap 'exit 130' INT")),
                () -> assertTrue(scan.contains("docker rm --force")),
                () -> assertFalse(scan.contains("/var/run/docker.sock"),
                        "The scanner must not receive control of the host Docker daemon"),
                () -> assertTrue(acceptance.contains(ARTIFACT_RESOLVER_PATH.getFileName().toString())),
                () -> assertTrue(acceptance.contains("airflow-image-build.sh")),
                () -> assertTrue(acceptance.contains(IMAGE_SMOKE_TEST_PATH.getFileName().toString())),
                () -> assertTrue(acceptance.contains(VULNERABILITY_SCAN_TEST_PATH.getFileName().toString())),
                () -> assertTrue(acceptance.contains("phase_completed")),
                () -> assertTrue(acceptance.contains("duration_ms")));
    }

    @Test
    void imageHardeningRemovesCriticalComponentsAndEnforcesTheScanPolicy() {
        String dockerfile = read(DOCKERFILE_PATH);
        String smoke = read(IMAGE_SMOKE_TEST_PATH);
        String scan = read(VULNERABILITY_SCAN_TEST_PATH);
        assertAll(
                () -> assertTrue(dockerfile.contains(
                                "pip uninstall --yes litellm ray apache-airflow-providers-google"),
                        "The unused Google/AI bundle inherited from Airflow must be removed as one unit"),
                () -> assertFalse(dockerfile.contains("PYSPARK_PACKAGE"),
                        "S2 must not install and then delete a duplicate PySpark runtime"),
                () -> assertTrue(dockerfile.contains("rm /opt/spark/jars/derby-10.16.1.1.jar"),
                        "The unused vulnerable Derby server JAR must not be shipped"),
                () -> assertTrue(dockerfile.contains("rm -f /usr/bin/docker"),
                        "The runtime image must not ship an unused Docker client"),
                () -> assertTrue(dockerfile.contains("/home/airflow/.local/bin/uvx"),
                        "The runtime image must remove unused package-manager binaries"),
                () -> assertTrue(smoke.contains("PackageNotFoundError")),
                () -> assertTrue(smoke.contains("litellm")),
                () -> assertTrue(smoke.contains("version(\"ray\")"),
                        "The runtime smoke must prove that the unused Ray distribution is absent"),
                () -> assertTrue(smoke.contains("version(\"apache-airflow-providers-google\")"),
                        "The runtime smoke must prove the unused Google provider is absent"),
                () -> assertTrue(smoke.contains("/opt/spark/jars/derby-10.16.1.1.jar")),
                () -> assertTrue(smoke.contains("test ! -e /usr/bin/docker")),
                () -> assertTrue(smoke.contains("test ! -e /home/airflow/.local/bin/uv")),
                () -> assertTrue(smoke.contains("test ! -e /home/airflow/.local/bin/uvx")),
                () -> assertTrue(scan.contains("critical_findings_detected")),
                () -> assertTrue(scan.contains("critical_policy_passed")),
                () -> assertTrue(scan.contains("Severity.*CRITICAL"),
                        "The scan must inventory all findings and then independently enforce zero Criticals"));
    }

    @Test
    void residualHighWaiverIsExplicitlyDeveloperOnlyAndTimeBounded() {
        String review = read(VULNERABILITY_REVIEW_PATH);
        String waiver = read(VULNERABILITY_WAIVER_PATH);
        assertAll(
                () -> assertTrue(waiver.contains(WAIVER_ID)),
                () -> assertTrue(waiver.contains(WAIVED_IMAGE_DIGEST)),
                () -> assertTrue(waiver.contains("Effective date: " + WAIVER_EFFECTIVE_DATE)),
                () -> assertTrue(waiver.contains("Expiry date: " + WAIVER_EXPIRY_DATE)),
                () -> assertTrue(waiver.contains(EXPECTED_HIGH_OCCURRENCES + " High occurrences")),
                () -> assertTrue(waiver.matches("(?s).*" + EXPECTED_UNIQUE_PACKAGE_CVE_PAIRS
                        + "\\s+unique\\s+package/CVE\\s+pairs.*")),
                () -> assertTrue(waiver.contains("Developer use only")),
                () -> assertTrue(waiver.contains("Production promotion is prohibited")),
                () -> assertTrue(waiver.contains("No automatic renewal")),
                () -> assertTrue(review.contains(VULNERABILITY_WAIVER_PATH.toString())),
                () -> assertFalse(review.contains("No waiver granted"),
                        "The review must not contradict the approved developer waiver"));
    }

    @Test
    void implementationStatusDocumentsPreserveTheDevelopmentFirstBoundary() {
        String phasePlan = readRepo(PHASE_PLAN_PATH);
        String incrementPlan = readRepo(INCREMENT_PLAN_PATH);
        String taskAudit = readRepo(TASK_AUDIT_PATH);
        String readiness = readRepo(READINESS_PATH);
        String verifierIndex = readRepo(VERIFIER_INDEX_PATH);

        assertAll(
                () -> assertCurrentDeveloperBoundary(phasePlan),
                () -> assertCurrentDeveloperBoundary(incrementPlan),
                () -> assertCurrentDeveloperBoundary(taskAudit),
                () -> assertCurrentDeveloperBoundary(readiness),
                () -> assertCurrentDeveloperBoundary(verifierIndex),
                () -> assertCurrentAirflowStatus(phasePlan),
                () -> assertCurrentAirflowStatus(incrementPlan),
                () -> assertCurrentAirflowStatus(taskAudit),
                () -> assertCurrentAirflowStatus(readiness),
                () -> assertCurrentAirflowStatus(verifierIndex));
    }

    private static void assertLocked(String lock, String distribution, String version) {
        String lineStart = distribution + "==" + version;
        assertTrue(lock.lines().anyMatch(line -> line.startsWith(lineStart)
                        && line.matches(".*--hash=sha256:[0-9a-f]{64}.*")),
                () -> lineStart + " is not protected by a SHA-256 hash in requirements.lock");
    }

    private static void assertFile(Path relative) {
        assertTrue(Files.isRegularFile(IMAGE_ROOT.resolve(relative)),
                () -> "Missing Airflow image artifact: " + relative);
    }

    private static String read(Path relative) {
        Path file = IMAGE_ROOT.resolve(relative);
        assertTrue(Files.isRegularFile(file), () -> "Missing Airflow image artifact: " + relative);
        return Repo.read(file);
    }

    private static String readRepo(Path relative) {
        Path file = Repo.root().resolve(relative);
        assertTrue(Files.isRegularFile(file), () -> "Missing repository document: " + relative);
        return Repo.read(file);
    }

    private static void assertCurrentDeveloperBoundary(String document) {
        assertAll(
                () -> assertTrue(document.contains(CURRENT_STAGE_MARKER),
                        "The document must identify development as the current stage"),
                () -> assertTrue(document.contains(LATER_STAGE_MARKER),
                        "The document must identify production hardening as the later stage"),
                () -> assertFalse(document.toLowerCase().contains(PRODUCTION_ACCEPTED_CLAIM),
                        "Current implementation status must not claim production acceptance"));
    }

    private static void assertCurrentAirflowStatus(String document) {
        assertAll(
                () -> assertTrue(document.contains(markdownCode(CURRENT_IMAGE_TASK_ID))),
                () -> assertTrue(document.contains(markdownCode(CURRENT_SUBMISSION_TASK_ID))),
                () -> assertTrue(document.contains(markdownCode(NEXT_DEVELOPMENT_TASK_ID))),
                () -> assertTrue(document.contains(DEVELOPMENT_ACCEPTANCE_DATE)));
    }

    private static String requirement(String distribution, String version) {
        return distribution + "==" + version;
    }

    private static String property(String name, String value) {
        return name + "=" + value;
    }

    private static String markdownCode(String value) {
        return "`" + value + "`";
    }
}
