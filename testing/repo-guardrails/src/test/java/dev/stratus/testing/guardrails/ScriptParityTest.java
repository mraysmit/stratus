// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
final class ScriptParityTest {

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
        String common = Repo.read(SCRIPTS.resolve(Path.of("lib", "common.sh")));
        if (!common.contains("MSYS_NO_PATHCONV=1") || !common.contains("cygpath -w")) {
            violations.add("common.sh must preserve container paths while passing host paths to Docker from Git Bash");
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
