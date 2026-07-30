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
 * Guards the ownership and deployment-neutrality of the shared Ceph contract.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-29
 * @version 1.0.0
 */
@Tag("unit")
final class CephTestArchitectureTest {

    /**
     * Every deployment-neutral live contract in this module. Each proves a
     * different Ceph API surface: the S3 data API through the SDK and again over
     * raw signed REST, the Admin Operations API, and the Dashboard REST API.
     */
    private static final List<String> LIVE_CONTRACTS = List.of(
        "CephRgwContractTest",
        "CephS3RestContractTest",
        "CephAdminOpsRestContractTest",
        "CephDashboardRestContractTest");

    private static final String CONTRACT_DIRECTORY =
        "platform/ceph/tests/src/test/java/dev/stratus/platform/ceph/";

    @Test
    void cephTestsLiveUnderTheCephPlatformCapability() {
        Path root = Repo.root();
        for (String contract : LIVE_CONTRACTS) {
            assertTrue(Files.isRegularFile(root.resolve(CONTRACT_DIRECTORY + contract + ".java")),
                () -> contract + " must live under the Ceph platform capability");
        }
        assertFalse(Files.exists(root.resolve(
            "verification/storage/src/test/java/dev/stratus/verification/storage/CephRgwIntegrationTest.java")));
        assertFalse(Files.exists(root.resolve(
            "testing/repo-guardrails/src/test/java/dev/stratus/testing/guardrails/HarnessContractTest.java")));
        assertFalse(Files.exists(root.resolve(
            "testing/repo-guardrails/src/test/java/dev/stratus/testing/guardrails/ScriptParityTest.java")));
    }

    @Test
    void liveContractsDoNotInvokeADeploymentImplementation() {
        List<String> forbidden = List.of(
            "ProcessBuilder",
            "compose.yaml",
            "docker compose",
            "podman compose",
            "scripts/lifecycle",
            "scripts/verify");
        for (String name : LIVE_CONTRACTS) {
            String contract = Repo.read(Repo.root().resolve(CONTRACT_DIRECTORY + name + ".java"));
            List<String> violations = forbidden.stream().filter(contract::contains).toList();
            assertTrue(violations.isEmpty(),
                () -> name + " invokes deployment-specific machinery: " + violations);
            assertTrue(contract.contains("System.getenv()"),
                () -> name + " must consume the implementation-supplied environment");
        }
    }

    @Test
    void liveContractsFailRatherThanSkipWhenTheSelectedProfileRequiresACluster() {
        for (String name : LIVE_CONTRACTS) {
            String contract = Repo.read(Repo.root().resolve(CONTRACT_DIRECTORY + name + ".java"));
            assertTrue(contract.contains("Boolean.getBoolean(\"ceph.integration.required\")"),
                () -> name + " must fail, not skip, when a live Maven profile is selected");
            assertTrue(contract.contains("assumeTrue"),
                () -> name + " must skip when no live profile is selected and no cluster is configured");
        }
    }
}
