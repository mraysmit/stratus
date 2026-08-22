// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Offline repository contract for the approved Java release across builds, images, and documents.
 *
 * <h2>Rationale</h2>
 *
 * <p>A Java change can drift independently through compiler settings, Spark images, Airflow's
 * Spark client, and architecture documents. That produces artifacts which compile on one machine
 * but fail in an image or advertise an unsupported runtime. This test makes the approved release,
 * runtime markers, and documented exceptions one explicit policy.
 *
 * <h2>Maintenance and promotion</h2>
 *
 * <p>Change {@link #JAVA_RELEASE} only after every affected product's support matrix has been
 * reviewed. Update compiler parents, runtime images, compatibility tests, and authoritative
 * documents together; keep exceptions named and narrow. Developer builds must pass the offline and
 * component compatibility suites. UAT must exercise the same immutable images with representative
 * workloads, and production must promote the unchanged UAT-approved digests with provenance,
 * rollback, and owner approval. A documentation-only edit cannot approve a Java migration.
 *
 * <p>This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-18
 * @version 1.1.0
 */
@Tag("unit")
final class JavaPolicyTest {

    private static final String JAVA_RELEASE = "21";
    private static final String REQUIRED_SPARK_IMAGE_MARKER =
            "scala2.13-java" + JAVA_RELEASE + "-python3-ubuntu";
    private static final String REQUIRED_AIRFLOW_JAVA_IMAGE =
            "eclipse-temurin:" + JAVA_RELEASE + "-jre";
    private static final List<String> SUPERSEDED_JAVA_POLICIES = List.of(
            "Java 25", "JDK 25", "Java 26", "JDK 26");
    private static final Set<String> ALLOWED_TRINO_JAVA_POLICIES = Set.of("Java 25", "JDK 25");

    private static final Path BUILD_PARENT_PATH = Path.of(
            "build-support", "stratus-build-parent", "pom.xml");
    private static final Path SPARK_JOBS_POM_PATH = Path.of("jobs", "spark", "pom.xml");
    private static final Path SPARK_TESTS_POM_PATH = Path.of(
            "platform", "spark", "tests", "pom.xml");
    private static final Path SPARK_DOCKERFILE_PATH = Path.of(
            "platform", "spark", "image", "Dockerfile");
    private static final Path SPARK_COMMON_SCRIPT_PATH = Path.of(
            "platform", "spark", "compose-cluster", "scripts", "lib",
            "spark-compose-common.sh");
    private static final Path AIRFLOW_ARTIFACT_LOCK_PATH = Path.of(
            "platform", "airflow", "image", "artifact-lock.properties");
    private static final Set<Path> TRINO_POLICY_DOCUMENT_PATHS = Set.of(
            Path.of("docs", "implementation", "trino_query.md"),
            Path.of("docs", "implementation", "stratus_implementation_plan_phase2.md"),
            Path.of("docs", "architecture", "stratus_on_prem_data_fabric_architecture.md"));

    @Test
    void buildAndSparkModulesTargetTheApprovedJavaRelease() {
        assertRelease(BUILD_PARENT_PATH, JAVA_RELEASE);
        assertRelease(SPARK_JOBS_POM_PATH, JAVA_RELEASE);
        assertRelease(SPARK_TESTS_POM_PATH, JAVA_RELEASE);
    }

    @Test
    void sparkAndAirflowRuntimeImagesUseTheApprovedJavaRelease() {
        String sparkDockerfile = read(SPARK_DOCKERFILE_PATH);
        String sparkCommon = read(SPARK_COMMON_SCRIPT_PATH);
        String airflowLock = read(AIRFLOW_ARTIFACT_LOCK_PATH);

        assertTrue(sparkDockerfile.contains(REQUIRED_SPARK_IMAGE_MARKER));
        assertTrue(sparkCommon.contains(REQUIRED_SPARK_IMAGE_MARKER));
        assertTrue(airflowLock.contains("java.version=" + JAVA_RELEASE));
        assertTrue(airflowLock.contains("java.image=" + REQUIRED_AIRFLOW_JAVA_IMAGE));
    }

    @Test
    void authoritativeDocumentsDoNotAdvertiseSupersededJavaPolicies() {
        List<String> violations = new ArrayList<>();
        for (Path file : Repo.trackedFiles()) {
            Path relative = Repo.root().relativize(file);
            if (!relative.startsWith("docs") || !file.getFileName().toString().endsWith(".md")
                    || relative.toString().contains("handover")) {
                continue;
            }
            String content = Repo.read(file);
            for (String stale : SUPERSEDED_JAVA_POLICIES) {
                if (TRINO_POLICY_DOCUMENT_PATHS.contains(relative)
                        && ALLOWED_TRINO_JAVA_POLICIES.contains(stale)) {
                    continue;
                }
                if (content.contains(stale)) {
                    violations.add(relative + " contains " + stale);
                }
            }
        }
        assertTrue(violations.isEmpty(), () ->
                "Authoritative documentation must use the Java " + JAVA_RELEASE
                        + " policy:\n  " + String.join("\n  ", violations));
    }

    private static void assertRelease(Path pomPath, String expectedRelease) {
        String content = read(pomPath);
        String marker = "<maven.compiler.release>" + expectedRelease
                + "</maven.compiler.release>";
        assertEquals(1, content.lines().filter(line -> line.contains(marker)).count(),
                () -> pomPath + " must declare exactly one " + marker);
    }

    private static String read(Path relative) {
        return Repo.read(Repo.root().resolve(relative));
    }
}
