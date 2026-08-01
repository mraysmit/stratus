// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.ceph;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Harness scripts ship as a single bash implementation (ADR-P1-002). These checks catch the regressions that matter: a PowerShell twin reappearing, or a bash script losing its fail-fast preamble or Git Bash path handling.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 2.0.0
 */
@Tag("unit")
final class ComposeClusterScriptTest {

    private static final Path HARNESS = Repo.root().resolve(Path.of("platform", "ceph", "compose-cluster"));
    private static final Path SCRIPTS = HARNESS.resolve("scripts");

    @Test
    void harnessScriptsAreBashOnly() {
        List<String> violations = new ArrayList<>();
        List<Path> scripts = scriptFiles();
        assertTrue(!scripts.isEmpty(), "No harness scripts were discovered; check the configured harness path");
        for (Path script : Repo.trackedFiles()) {
            if (script.startsWith(SCRIPTS) && script.getFileName().toString().endsWith(".ps1")) {
                violations.add(script + " reintroduces a PowerShell twin; harness scripts are bash-only per ADR-P1-002");
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void bashScriptsFailFast() {
        List<String> violations = new ArrayList<>();
        String attributes = Repo.read(Repo.root().resolve(".gitattributes"));
        if (attributes.lines().map(String::trim).noneMatch("*.sh text eol=lf"::equals)) {
            violations.add(".gitattributes must enforce LF working-tree endings for container-mounted shell scripts");
        }
        for (Path script : Repo.trackedFiles()) {
            if (!script.startsWith(HARNESS) || !script.getFileName().toString().endsWith(".sh")) {
                continue;
            }
            List<String> lines = Repo.read(script).lines().toList();
            if (lines.size() < 2 || !lines.get(0).startsWith("#!") || !lines.get(1).equals("set -euo pipefail")) {
                violations.add(script + " must start with a shebang followed by 'set -euo pipefail'");
            }
        }
        String common = Repo.read(SCRIPTS.resolve(Path.of("lib", "ceph-compose-common.sh")));
        if (!common.contains("MSYS_NO_PATHCONV=1") || !common.contains("cygpath -w")) {
            violations.add("ceph-compose-common.sh must preserve container paths while passing host paths to Docker from Git Bash");
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void bashScriptsAreExecutableInGit() {
        List<String> violations = Repo.trackedFileModes().entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(SCRIPTS))
            .filter(entry -> entry.getKey().getFileName().toString().endsWith(".sh"))
            .filter(entry -> !entry.getValue().equals("100755"))
            .map(entry -> entry.getKey() + " is tracked as " + entry.getValue() + ", expected 100755")
            .toList();
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void renamedEntryPointsRetainCompatibilityWrappers() {
        Map<String, String> wrappers = new LinkedHashMap<>();
        wrappers.put("lib/common.sh", "ceph-compose-common.sh");
        wrappers.put("lib/generate-compose-certificates.sh", "ceph-compose-generate-certificates.sh");
        wrappers.put("lifecycle/install-prerequisites.sh", "ceph-compose-install-prerequisites.sh");
        wrappers.put("lifecycle/reset.sh", "ceph-compose-reset.sh");
        wrappers.put("lifecycle/rotate-secrets.sh", "ceph-compose-rotate-secrets.sh");
        wrappers.put("lifecycle/shutdown.sh", "ceph-compose-shutdown.sh");
        wrappers.put("lifecycle/startup.sh", "ceph-compose-startup.sh");
        wrappers.put("verify/bootstrap-buckets.sh", "ceph-compose-bootstrap-buckets.sh");
        wrappers.put("verify/failure-drill.sh", "ceph-compose-failure-drill.sh");
        wrappers.put("verify/check.sh", "ceph-compose-verify-buckets.sh");
        wrappers.put("verify/verify-dashboard.sh", "ceph-compose-verify-dashboard.sh");
        wrappers.put("verify/verify-dataset.sh", "ceph-compose-verify-dataset.sh");
        wrappers.put("verify/selftest.sh", "ceph-compose-verify-harness.sh");
        wrappers.put("verify/verify-security.sh", "ceph-compose-verify-security.sh");
        wrappers.put("verify/verify-java.sh", "ceph-compose-verify-storage.sh");

        List<String> violations = new ArrayList<>();
        List<Path> trackedFiles = Repo.trackedFiles();
        wrappers.forEach((wrapper, target) -> {
            Path wrapperPath = SCRIPTS.resolve(wrapper);
            if (!trackedFiles.contains(wrapperPath)) {
                violations.add(wrapper + " is not tracked");
            } else if (!Repo.read(wrapperPath).contains(target)) {
                violations.add(wrapper + " does not delegate to " + target);
            }
        });
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void hostnameConfigurationAutomatesEachSupportedPrivilegeBoundary() {
        String configuration = Repo.read(SCRIPTS.resolve(
            Path.of("lifecycle", "ceph-compose-configure-hostname.sh")));
        List<String> violations = new ArrayList<>();
        if (!configuration.contains("-Verb RunAs")) {
            violations.add("Windows hosts-file configuration must request UAC elevation");
        }
        if (!configuration.contains("sudo tee -a")) {
            violations.add("Linux and macOS hosts-file configuration must support sudo");
        }
        if (!configuration.contains("conflict:")) {
            violations.add("hosts-file configuration must reject conflicting mappings");
        }
        if (!configuration.contains("--check")) {
            violations.add("hosts-file configuration must expose read-only validation");
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    private static List<Path> scriptFiles() {
        return Repo.trackedFiles().stream()
            .filter(file -> file.startsWith(SCRIPTS))
            .filter(file -> file.getFileName().toString().endsWith(".sh"))
            .toList();
    }
}
