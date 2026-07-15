// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The harness ships every script as a PowerShell/bash pair. These checks catch the drift that actually happens: a missing twin, a missing fail-fast preamble, or twins referencing different runtime artifacts.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 1.0.0
 */
@Tag("unit")
final class ScriptParityTest {

    private static final Path LOCAL = Repo.root().resolve(Path.of("platform", "ceph", "local"));
    private static final Path SCRIPTS = LOCAL.resolve("scripts");
    private static final Pattern JAR_PATH = Pattern.compile("/opt/stratus/[A-Za-z0-9._-]+\\.jar");
    private static final Pattern EVIDENCE_PREFIX = Pattern.compile("storage-[a-z][a-z-]*-(?=[$\"'{])");
    private static final List<String> SERVICES = List.of("s3client", "verifier", "verifier-untrusted");

    @Test
    void everyScriptShipsAsAPair() {
        Set<String> baseNames = new TreeSet<>();
        List<String> violations = new ArrayList<>();
        for (Path script : scriptFiles()) {
            String name = script.getFileName().toString();
            baseNames.add(name.substring(0, name.lastIndexOf('.')));
        }
        for (String base : baseNames) {
            for (String extension : List.of(".ps1", ".sh")) {
                if (scriptFiles().stream().noneMatch(f -> f.getFileName().toString().equals(base + extension))) {
                    violations.add(base + extension + " is missing; every script ships as a .ps1/.sh pair");
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void bashScriptsFailFast() {
        List<String> violations = new ArrayList<>();
        for (Path script : Repo.trackedFiles()) {
            if (!script.startsWith(LOCAL) || !script.getFileName().toString().endsWith(".sh")) {
                continue;
            }
            List<String> lines = Repo.read(script).lines().toList();
            if (lines.size() < 2 || !lines.get(0).startsWith("#!") || !lines.get(1).equals("set -euo pipefail")) {
                violations.add(script + " must start with a shebang followed by 'set -euo pipefail'");
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void powershellScriptsFailFast() {
        List<String> violations = new ArrayList<>();
        for (Path script : scriptFiles()) {
            if (!script.getFileName().toString().endsWith(".ps1")) {
                continue;
            }
            List<String> statements = Repo.read(script).lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
            int first = statements.isEmpty() || !statements.get(0).startsWith("param(") ? 0 : 1;
            if (statements.size() <= first
                || !statements.get(first).equals("$ErrorActionPreference = 'Stop'")) {
                violations.add(script + " must set $ErrorActionPreference = 'Stop' before any other statement");
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void twinsReferenceTheSameRuntimeArtifacts() {
        List<String> violations = new ArrayList<>();
        Set<String> baseNames = new TreeSet<>();
        for (Path script : scriptFiles()) {
            String name = script.getFileName().toString();
            baseNames.add(name.substring(0, name.lastIndexOf('.')));
        }
        for (String base : baseNames) {
            Path shell = SCRIPTS.resolve(base + ".sh");
            Path powershell = SCRIPTS.resolve(base + ".ps1");
            if (!shell.toFile().isFile() || !powershell.toFile().isFile()) {
                continue; // pairing is asserted separately
            }
            compareTokens(base, "jar path", tokens(shell, JAR_PATH), tokens(powershell, JAR_PATH), violations);
            compareTokens(base, "evidence prefix",
                tokens(shell, EVIDENCE_PREFIX), tokens(powershell, EVIDENCE_PREFIX), violations);
            compareTokens(base, "compose service",
                serviceReferences(shell), serviceReferences(powershell), violations);
        }
        assertTrue(violations.isEmpty(), () ->
            "Script twins reference different runtime artifacts:\n" + String.join("\n", violations));
    }

    private static List<Path> scriptFiles() {
        return Repo.trackedFiles().stream()
            .filter(file -> file.startsWith(SCRIPTS))
            .filter(file -> {
                String name = file.getFileName().toString();
                return name.endsWith(".sh") || name.endsWith(".ps1");
            })
            .toList();
    }

    private static Set<String> tokens(Path script, Pattern pattern) {
        Set<String> tokens = new TreeSet<>();
        Matcher matcher = pattern.matcher(Repo.read(script));
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static Set<String> serviceReferences(Path script) {
        String content = Repo.read(script);
        Set<String> referenced = new HashSet<>();
        for (String service : SERVICES) {
            if (Pattern.compile("(?<![A-Za-z0-9_-])" + Pattern.quote(service) + "(?![A-Za-z0-9_-])")
                .matcher(content).find()) {
                referenced.add(service);
            }
        }
        return referenced;
    }

    private static void compareTokens(
        String base, String kind, Set<String> shell, Set<String> powershell, List<String> violations) {
        if (!shell.equals(powershell)) {
            violations.add(base + ": " + kind + " differs between twins — .sh uses " + shell
                + " but .ps1 uses " + powershell);
        }
    }
}
