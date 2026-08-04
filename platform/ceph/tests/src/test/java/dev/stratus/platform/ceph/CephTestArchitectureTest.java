// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.ceph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guards the ownership and deployment-neutrality of the shared Ceph conformance suite.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-29
 * @version 1.0.0
 */
@Tag("unit")
final class CephTestArchitectureTest {

    /**
     * Every deployment-neutral live conformance test in this module. Each proves a
     * different Ceph API surface: the S3 data API through the SDK and again over
     * raw signed REST, the Admin Operations API, and the Dashboard REST API.
     */
    private static final List<String> LIVE_CONFORMANCE_TESTS = List.of(
        "CephRgwConformanceTest",
        "CephS3RestConformanceTest",
        "CephAdminOpsRestConformanceTest",
        "CephDashboardRestConformanceTest");

    private static final String CONFORMANCE_DIRECTORY =
        "platform/ceph/tests/src/test/java/dev/stratus/platform/ceph/";

    @Test
    void cephTestsLiveUnderTheCephPlatformCapability() {
        Path root = Repo.root();
        for (String conformanceTest : LIVE_CONFORMANCE_TESTS) {
            assertTrue(Files.isRegularFile(root.resolve(CONFORMANCE_DIRECTORY + conformanceTest + ".java")),
                () -> conformanceTest + " must live under the Ceph platform capability");
        }
        // Resurrections of retired test-class names are caught by the
        // retired-names guardrail in stratus-repo-guardrails.
        assertFalse(Files.exists(root.resolve(
            "verification/storage/src/test/java/dev/stratus/verification/storage/CephRgwIntegrationTest.java")));
        assertFalse(Files.exists(root.resolve(
            "testing/repo-guardrails/src/test/java/dev/stratus/testing/guardrails/ScriptParityTest.java")));
    }

    @Test
    void liveConformanceTestsDoNotInvokeADeploymentImplementation() {
        List<String> forbidden = List.of(
            "ProcessBuilder",
            "compose.yaml",
            "docker compose",
            "podman compose",
            "scripts/lifecycle",
            "scripts/verify");
        for (String name : LIVE_CONFORMANCE_TESTS) {
            String source = Repo.read(Repo.root().resolve(CONFORMANCE_DIRECTORY + name + ".java"));
            List<String> violations = forbidden.stream().filter(source::contains).toList();
            assertTrue(violations.isEmpty(),
                () -> name + " invokes deployment-specific machinery: " + violations);
            assertTrue(source.contains("System.getenv()"),
                () -> name + " must consume the implementation-supplied environment");
        }
    }

    @Test
    void liveConformanceTestsFailRatherThanSkipWhenTheSelectedProfileRequiresACluster() {
        for (String name : LIVE_CONFORMANCE_TESTS) {
            String source = Repo.read(Repo.root().resolve(CONFORMANCE_DIRECTORY + name + ".java"));
            assertTrue(source.contains("Boolean.getBoolean(\"ceph.integration.required\")"),
                () -> name + " must fail, not skip, when a live Maven profile is selected");
            assertTrue(source.contains("assumeTrue"),
                () -> name + " must skip when no live profile is selected and no cluster is configured");
        }
    }
}
