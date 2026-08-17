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
 * Offline contract for the Airflow image dependency and verification baseline.
 *
 * <p>The runtime host must never resolve Python, Spark, or Java dependencies.
 * The checked-in lock files identify every approved input; the build resolver
 * populates ignored artifacts ahead of image assembly; and the image smoke test
 * proves the installed versions and the imports the orchestration layer uses.
 *
 * <p>This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-16
 * @version 1.0.0
 */
@Tag("unit")
final class AirflowArtifactBaselineTest {

    private static final Path IMAGE = Repo.root().resolve(Path.of("platform", "airflow", "image"));

    @Test
    void imageInputsAndVerificationScriptsHaveStableLocations() {
        assertAll(
                () -> assertFile("Dockerfile"),
                () -> assertFile("requirements.in"),
                () -> assertFile("requirements.lock"),
                () -> assertFile("artifact-lock.properties"),
                () -> assertFile("vulnerability-review.md"),
                () -> assertFile("vulnerability-waiver.md"),
                () -> assertFile(".gitignore"),
                () -> assertFile(Path.of("scripts", "build", "airflow-image-resolve-artifacts.sh")),
                () -> assertFile(Path.of("scripts", "tests", "airflow-image-smoke-test.sh")),
                () -> assertFile(Path.of("scripts", "tests",
                        "airflow-image-vulnerability-scan-test.sh")));
    }

    @Test
    void dependencyIntentMatchesTheApprovedAirflowMatrix() {
        String requirements = read("requirements.in");
        assertAll(
                () -> assertTrue(requirements.contains("apache-airflow==3.3.1")),
                () -> assertTrue(requirements.contains(
                        "apache-airflow-providers-apache-spark[pyspark]==6.3.1")),
                () -> assertTrue(requirements.contains(
                        "apache-airflow-providers-amazon==9.34.0")),
                () -> assertTrue(requirements.contains("boto3==1.43.56")),
                () -> assertTrue(requirements.contains("aiohttp==3.14.3")),
                () -> assertTrue(requirements.contains("pyspark==4.1.3")),
                () -> assertTrue(requirements.contains("py4j==0.10.9.9")),
                () -> assertTrue(requirements.lines()
                                .noneMatch(line -> line.trim().equals("apache-airflow==3.3.0")),
                        "The superseded Airflow patch must not remain in dependency intent"));
    }

    @Test
    void lockedPythonArtifactsAreExactAndHashVerified() {
        String lock = read("requirements.lock");
        assertAll(
                () -> assertLocked(lock, "apache-airflow", "3.3.1"),
                () -> assertLocked(lock, "apache-airflow-providers-apache-spark", "6.3.1"),
                () -> assertLocked(lock, "apache-airflow-providers-amazon", "9.34.0"),
                () -> assertLocked(lock, "boto3", "1.43.56"),
                () -> assertLocked(lock, "aiohttp", "3.14.3"),
                () -> assertLocked(lock, "pyspark", "4.1.3"),
                () -> assertLocked(lock, "py4j", "0.10.9.9"));
    }

    @Test
    void artifactLockIdentifiesEveryExternalBuildInput() {
        String lock = read("artifact-lock.properties");
        assertAll(
                () -> assertTrue(lock.contains("airflow.version=3.3.1")),
                () -> assertTrue(lock.contains("python.version=3.14")),
                () -> assertTrue(lock.contains("spark.version=4.1.3")),
                () -> assertTrue(lock.contains("java.version=21")),
                () -> assertTrue(lock.contains("constraints.url=https://raw.githubusercontent.com/")),
                () -> assertTrue(lock.matches("(?s).*constraints.sha256=[0-9a-f]{64}.*")),
                () -> assertTrue(lock.contains("spark.archive.url=https://archive.apache.org/")),
                () -> assertTrue(lock.matches("(?s).*spark.archive.sha512=[0-9a-f]{128}.*")),
                () -> assertTrue(lock.contains("constraints-3.3.1/constraints-3.14.txt")),
                () -> assertTrue(lock.contains("airflow.image=apache/airflow:3.3.1-python3.14")),
                () -> assertTrue(lock.contains("java.image=eclipse-temurin:21-jre")));
    }

    @Test
    void imageAssemblyCannotResolveMutableDependencies() {
        String dockerfile = read("Dockerfile");
        assertAll(
                () -> assertTrue(dockerfile.contains("ARG AIRFLOW_BASE_IMAGE=")),
                () -> assertTrue(dockerfile.contains("ARG TEMURIN_21_IMAGE=")),
                () -> assertTrue(dockerfile.contains("artifacts/wheelhouse/")),
                () -> assertTrue(dockerfile.contains("COPY artifacts/spark-4.1.3-bin-hadoop3.tgz")),
                () -> assertTrue(dockerfile.contains("--no-index")),
                () -> assertTrue(dockerfile.contains("--no-deps")),
                () -> assertTrue(dockerfile.contains("--require-hashes")),
                () -> assertTrue(dockerfile.contains("USER airflow")),
                () -> assertTrue(dockerfile.contains("artifact-lock.properties")),
                () -> assertFalse(dockerfile.matches("(?s).*RUN\\s+.*(curl|wget).*"),
                        "Image assembly must consume only pre-resolved artifacts"));
    }

    @Test
    void resolverAndSmokeTestCoverIntegrityAndRuntimeCompatibility() {
        String resolver = read(Path.of("scripts", "build", "airflow-image-resolve-artifacts.sh"));
        String smoke = read(Path.of("scripts", "tests", "airflow-image-smoke-test.sh"));
        String scan = read(Path.of("scripts", "tests", "airflow-image-vulnerability-scan-test.sh"));
        assertAll(
                () -> assertTrue(read(".gitignore").contains("artifacts/")),
                () -> assertTrue(read(".gitignore").contains("!scripts/build/"),
                        "The root build/ ignore rule must not hide Airflow's build scripts"),
                () -> assertTrue(resolver.contains("requirements.lock")),
                () -> assertTrue(resolver.contains("artifact-lock.properties")),
                () -> assertTrue(resolver.contains("aiohttp==3.14.3"),
                        "Resolution must verify the fixed aiohttp version against Airflow's constraint"),
                () -> assertTrue(resolver.contains(
                                "find \"${WHEELHOUSE_DIR}\" -maxdepth 1 -type f -delete"),
                        "Resolution must remove superseded artifacts before populating the wheelhouse"),
                () -> assertTrue(resolver.contains("sha256sum --check")),
                () -> assertTrue(resolver.contains("sha512sum --check")),
                () -> assertTrue(smoke.contains("airflow version")),
                () -> assertTrue(smoke.contains("SparkSubmitOperator")),
                () -> assertTrue(smoke.contains("S3KeySensor")),
                () -> assertTrue(smoke.contains("pyspark.__version__")),
                () -> assertTrue(smoke.contains("\"aiohttp\": \"3.14.3\""),
                        "The runtime smoke must prove that the vulnerable upstream aiohttp was replaced"),
                () -> assertTrue(smoke.contains("java -version")),
                () -> assertTrue(smoke.contains("spark-submit --version")),
                () -> assertTrue(scan.contains("aquasec/trivy:0.74.0@sha256:")),
                () -> assertTrue(scan.contains("docker save")),
                () -> assertTrue(scan.contains("--input")),
                () -> assertFalse(scan.contains("/var/run/docker.sock"),
                        "The scanner must not receive control of the host Docker daemon"));
    }

    @Test
    void imageHardeningRemovesCriticalComponentsAndEnforcesTheScanPolicy() {
        String dockerfile = read("Dockerfile");
        String smoke = read(Path.of("scripts", "tests", "airflow-image-smoke-test.sh"));
        String scan = read(Path.of("scripts", "tests", "airflow-image-vulnerability-scan-test.sh"));
        assertAll(
                () -> assertTrue(dockerfile.contains("pip uninstall --yes litellm ray"),
                        "LiteLLM and Ray are inherited from the Airflow base but are not Stratus runtime dependencies"),
                () -> assertTrue(dockerfile.contains("rm -rf \"${PYSPARK_PACKAGE}/jars\""),
                        "The PySpark wheel JAR tree must not duplicate the canonical Spark archive"),
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
                () -> assertTrue(smoke.contains("pyspark_package / \"jars\"")),
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
        String review = read("vulnerability-review.md");
        String waiver = read("vulnerability-waiver.md");
        assertAll(
                () -> assertTrue(waiver.contains("WAIVER-P1-4.1-S1-20260817")),
                () -> assertTrue(waiver.contains(
                        "sha256:89db37a79b60dd9224874afca3a4b57afadbeab0a205f8835958fecea259bc97")),
                () -> assertTrue(waiver.contains("Effective date: 2026-08-17")),
                () -> assertTrue(waiver.contains("Expiry date: 2026-09-16")),
                () -> assertTrue(waiver.contains("84 High occurrences")),
                () -> assertTrue(waiver.matches("(?s).*35\\s+unique\\s+package/CVE\\s+pairs.*")),
                () -> assertTrue(waiver.contains("Developer use only")),
                () -> assertTrue(waiver.contains("Production promotion is prohibited")),
                () -> assertTrue(waiver.contains("No automatic renewal")),
                () -> assertTrue(review.contains("vulnerability-waiver.md")),
                () -> assertFalse(review.contains("No waiver granted"),
                        "The review must not contradict the approved developer waiver"));
    }

    @Test
    void implementationStatusDocumentsPreserveTheDeveloperOnlyAcceptanceBoundary() {
        String phasePlan = readRepo(Path.of(
                "docs", "implementation", "stratus_implementation_plan_phase1.md"));
        String incrementPlan = readRepo(Path.of(
                "docs", "implementation", "airflow_orchestration.md"));
        String taskAudit = readRepo(Path.of(
                "docs", "implementation", "task_track_audit.md"));
        String readiness = readRepo(Path.of(
                "docs", "operations", "stratus_phase1_operational_readiness.md"));
        String verifierIndex = readRepo(Path.of("verification", "README.md"));

        assertAll(
                () -> assertCurrentDeveloperBoundary(phasePlan),
                () -> assertCurrentDeveloperBoundary(incrementPlan),
                () -> assertCurrentDeveloperBoundary(taskAudit),
                () -> assertCurrentDeveloperBoundary(readiness),
                () -> assertCurrentDeveloperBoundary(verifierIndex),
                () -> assertTrue(phasePlan.contains("developer deployment has not started")),
                () -> assertTrue(incrementPlan.contains("`P1-4.1-D1`")
                        && incrementPlan.contains("LocalExecutor")
                        && incrementPlan.contains("Not started")),
                () -> assertTrue(taskAudit.contains("`P1-4.1-D1` is the next engineering task")),
                () -> assertTrue(readiness.contains("`P1-4.1-D1` has not started")),
                () -> assertTrue(verifierIndex.contains(
                        "deployment and executable verification have not started")));
    }

    private static void assertLocked(String lock, String distribution, String version) {
        String lineStart = distribution + "==" + version;
        assertTrue(lock.lines().anyMatch(line -> line.startsWith(lineStart)
                        && line.matches(".*--hash=sha256:[0-9a-f]{64}.*")),
                () -> lineStart + " is not protected by a SHA-256 hash in requirements.lock");
    }

    private static void assertFile(String relative) {
        assertFile(Path.of(relative));
    }

    private static void assertFile(Path relative) {
        assertTrue(Files.isRegularFile(IMAGE.resolve(relative)),
                () -> "Missing Airflow image artifact: " + relative);
    }

    private static String read(String relative) {
        return read(Path.of(relative));
    }

    private static String read(Path relative) {
        Path file = IMAGE.resolve(relative);
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
                () -> assertTrue(document.contains("WAIVER-P1-4.1-S1-20260817")),
                () -> assertTrue(document.contains("2026-09-16")),
                () -> assertTrue(document.contains("production")
                        && document.contains("prohibited")));
    }
}
