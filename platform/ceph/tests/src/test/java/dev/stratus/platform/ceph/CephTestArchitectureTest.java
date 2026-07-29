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

    @Test
    void cephTestsLiveUnderTheCephPlatformCapability() {
        Path root = Repo.root();
        assertTrue(Files.isRegularFile(root.resolve(
            "platform/ceph/tests/src/test/java/dev/stratus/platform/ceph/CephRgwContractTest.java")));
        assertFalse(Files.exists(root.resolve(
            "verification/storage/src/test/java/dev/stratus/verification/storage/CephRgwIntegrationTest.java")));
        assertFalse(Files.exists(root.resolve(
            "testing/repo-guardrails/src/test/java/dev/stratus/testing/guardrails/HarnessContractTest.java")));
        assertFalse(Files.exists(root.resolve(
            "testing/repo-guardrails/src/test/java/dev/stratus/testing/guardrails/ScriptParityTest.java")));
    }

    @Test
    void liveContractDoesNotInvokeADeploymentImplementation() {
        String contract = Repo.read(Repo.root().resolve(
            "platform/ceph/tests/src/test/java/dev/stratus/platform/ceph/CephRgwContractTest.java"));
        List<String> forbidden = List.of(
            "ProcessBuilder",
            "compose.yaml",
            "docker compose",
            "podman compose",
            "scripts/lifecycle",
            "scripts/verify");
        List<String> violations = forbidden.stream().filter(contract::contains).toList();
        assertTrue(violations.isEmpty(),
            () -> "The shared Ceph contract invokes deployment-specific machinery: " + violations);
        assertTrue(contract.contains("System.getenv()"),
            "The shared Ceph contract must consume the implementation-supplied environment");
    }
}
