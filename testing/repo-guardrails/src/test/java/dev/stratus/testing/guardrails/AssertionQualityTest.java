// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Fails the build on assertions that cannot distinguish a passing test from
 * one that never tested anything.
 *
 * <p>Every rule here was written after that exact defect reached the
 * repository. A test that passes whatever the system does is worse than no
 * test: it occupies the place where the real check should be and reports
 * green while doing it. The offline gate is the only place these are caught
 * cheaply, because none of them fail at runtime — they pass, which is the
 * problem.
 *
 * <p>Rules are deliberately narrow. A guardrail that fires on honest code
 * gets suppressed, and a suppressed guardrail protects nothing.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
@Tag("unit")
final class AssertionQualityTest {

    /**
     * Every test source in the repository except this one.
     *
     * <p>A rule has to quote the thing it bans — in its pattern, and in the
     * comment saying why it exists — so scanning this file only ever finds the
     * ban itself. The exclusion is safe because none of the rules below can
     * apply here: this class runs offline against file text and never sees a
     * command's output, a teardown, or a live endpoint.
     *
     * <p>It is also the reason this file was believed clean when it was
     * written: the rules were verified before it was committed, and
     * {@code trackedFiles} could not see it.
     */
    private static List<Path> testSources() {
        return Repo.trackedFiles().stream()
                .filter(path -> path.toString().endsWith("Test.java"))
                .filter(path -> path.toString().replace('\\', '/').contains("/src/test/java/"))
                .filter(path -> !path.getFileName().toString().equals("AssertionQualityTest.java"))
                .toList();
    }

    private static String name(Path path) {
        return Repo.root().relativize(path).toString().replace('\\', '/');
    }

    @Test
    void noAssertionMatchesAShortNumberAgainstCommandOutput() {
        // Command output carries a banner, an application id, and timestamps,
        // so it contains every digit whatever the command returned:
        // output().contains("4") holds for a result of 7. Assert on a labelled
        // value instead — see LiveSparkCluster.scalar.
        Pattern weak = Pattern.compile("output\\(\\)\\.contains\\(\"\\d{1,3}\"\\)");
        var violations = new ArrayList<String>();
        for (Path source : testSources()) {
            Matcher matcher = weak.matcher(Repo.read(source));
            while (matcher.find()) {
                violations.add(name(source) + ": " + matcher.group());
            }
        }
        assertTrue(violations.isEmpty(), () ->
                "A short number matched against command output passes whatever the command "
                        + "returned. Assert a labelled value instead:\n  " + String.join("\n  ", violations));
    }

    @Test
    void cleanupThatReportsFailureByReturnValueIsAsserted() {
        // These helpers return a status rather than throwing, so a discarded
        // result is a cleanup that silently did nothing while the suite stayed
        // green — which is how probe objects accumulated in a governed bucket
        // before anyone listed it.
        List<String> nonThrowingCleanups = List.of("removeObjectPrefix(", "sparkSql(");
        var violations = new ArrayList<String>();
        for (Path source : testSources()) {
            String text = Repo.read(source);
            for (String block : teardownBlocks(text)) {
                boolean callsCleanup = nonThrowingCleanups.stream().anyMatch(block::contains);
                if (callsCleanup && !block.contains("assert")) {
                    violations.add(name(source));
                }
            }
        }
        assertTrue(violations.isEmpty(), () ->
                "A teardown calling a helper that returns its failure must assert the result:\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    void noAssertFalseCombinesConditionsWithAnd() {
        // assertFalse(a && b) passes as soon as either side is false, so it
        // silently stops testing the other. It is almost never what was meant,
        // and the half that keeps passing is invisible.
        Pattern conjunction = Pattern.compile("assertFalse\\([^;]*?&&", Pattern.DOTALL);
        var violations = new ArrayList<String>();
        for (Path source : testSources()) {
            if (conjunction.matcher(Repo.read(source)).find()) {
                violations.add(name(source));
            }
        }
        assertTrue(violations.isEmpty(), () ->
                "assertFalse with && passes when either half is false; split it into separate "
                        + "assertions:\n  " + String.join("\n  ", violations));
    }

    @Test
    void aConsumerTestNeverHardcodesAnotherProductsEndpoint() {
        // ADR-P1-003: a consumer takes endpoints from the provider's published
        // connection.env. A literal in a test is a second place to change, and
        // the test is the one nobody remembers.
        //
        // Scoped to live-tagged classes only. An offline test cannot dial an
        // endpoint, so a hostname there is a fixture or — as in the Spark
        // harness guardrail — an assertion that the name is absent from a
        // template. Flagging those would fire on honest code, and a guardrail
        // that fires on honest code gets suppressed.
        record Boundary(String moduleFragment, String foreignHost) {
        }
        List<Boundary> boundaries = List.of(
                new Boundary("platform/spark/", "polaris.stratus.local"),
                new Boundary("platform/spark/", "object-store.stratus.local"),
                new Boundary("verification/catalog/", "polaris.stratus.local"),
                new Boundary("verification/catalog/", "object-store.stratus.local"));

        var violations = new ArrayList<String>();
        for (Path source : testSources()) {
            String path = name(source);
            String text = Repo.read(source);
            if (!text.contains("-integration\")")) {
                continue;
            }
            for (Boundary boundary : boundaries) {
                // The bare hostname, not a quote followed by it: a real
                // violation embeds the host inside a URL
                // ("https://polaris.stratus.local:8181/..."), so anchoring on
                // the opening quote misses every case this rule exists for.
                if (path.startsWith(boundary.moduleFragment())
                        && text.contains(boundary.foreignHost())) {
                    violations.add(path + " hardcodes " + boundary.foreignHost());
                }
            }
        }
        assertTrue(violations.isEmpty(), () ->
                "Read the endpoint from the provider's connection.env instead:\n  "
                        + String.join("\n  ", violations));
    }

    /** The bodies of every {@code @AfterEach} and {@code @AfterAll} method. */
    private static List<String> teardownBlocks(String source) {
        var blocks = new ArrayList<String>();
        Matcher matcher = Pattern.compile("@After(Each|All)").matcher(source);
        while (matcher.find()) {
            int brace = source.indexOf('{', matcher.end());
            if (brace < 0) {
                continue;
            }
            int depth = 1;
            int index = brace + 1;
            while (index < source.length() && depth > 0) {
                if (source.charAt(index) == '{') {
                    depth++;
                } else if (source.charAt(index) == '}') {
                    depth--;
                }
                index++;
            }
            blocks.add(source.substring(brace, index));
        }
        return blocks;
    }
}
