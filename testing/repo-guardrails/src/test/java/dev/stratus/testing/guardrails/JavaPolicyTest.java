// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Offline contract for the repository-wide Java 21 policy. */
@Tag("unit")
final class JavaPolicyTest {

    private static final Path BUILD_PARENT = Path.of(
            "build-support", "stratus-build-parent", "pom.xml");

    @Test
    void buildAndSparkModulesTargetJava21() {
        assertRelease(BUILD_PARENT, "21");
        assertRelease(Path.of("jobs", "spark", "pom.xml"), "21");
        assertRelease(Path.of("platform", "spark", "tests", "pom.xml"), "21");
    }

    @Test
    void sparkAndAirflowRuntimeImagesUseJava21() {
        String sparkDockerfile = read(Path.of("platform", "spark", "image", "Dockerfile"));
        String sparkCommon = read(Path.of("platform", "spark", "compose-cluster",
                "scripts", "lib", "spark-compose-common.sh"));
        String airflowLock = read(Path.of("platform", "airflow", "image",
                "artifact-lock.properties"));
        assertTrue(sparkDockerfile.contains("scala2.13-java21-python3-ubuntu"));
        assertTrue(sparkCommon.contains("scala2.13-java21-python3-ubuntu"));
        assertTrue(airflowLock.contains("java.version=21"));
        assertTrue(airflowLock.contains("java.image=eclipse-temurin:21-jre"));
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
            for (String stale : List.of("Java 25", "JDK 25", "Java 26", "JDK 26")) {
                boolean trinoException = (relative.endsWith(Path.of(
                                "docs", "implementation", "trino_query.md"))
                        || relative.endsWith(Path.of("docs", "implementation",
                                "stratus_implementation_plan_phase2.md"))
                        || relative.endsWith(Path.of("docs", "architecture",
                                "stratus_on_prem_data_fabric_architecture.md")))
                        && (stale.equals("Java 25") || stale.equals("JDK 25"));
                if (trinoException) {
                    continue;
                }
                if (content.contains(stale)) {
                    violations.add(relative + " contains " + stale);
                }
            }
        }
        assertTrue(violations.isEmpty(), () ->
                "Authoritative documentation must use the Java 21 policy:\n  "
                        + String.join("\n  ", violations));
    }

    private static void assertRelease(Path pom, String expected) {
        String content = read(pom);
        String marker = "<maven.compiler.release>" + expected + "</maven.compiler.release>";
        assertEquals(1, content.lines().filter(line -> line.contains(marker)).count(),
                () -> pom + " must declare exactly one " + marker);
    }

    private static String read(Path relative) {
        return Repo.read(Repo.root().resolve(relative));
    }
}
