// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Naming rules the repository has already paid to establish: capability-named implementation documents, no resurrection of retired names, and documented Maven module selectors that actually exist in the reactor.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 1.0.0
 */
@Tag("unit")
final class NamingConventionTest {

    private static final Pattern LEGACY_INCREMENT_NAME = Pattern.compile("increment\\d+_.*\\.md");
    private static final Pattern MODULE_SELECTOR = Pattern.compile("-(?:pl|rf)\\s+:([A-Za-z0-9._-]+)");

    @Test
    void implementationDocumentsUseCapabilityNames() {
        List<String> offenders = Repo.trackedFiles().stream()
            .filter(file -> file.getParent() != null && file.getParent().endsWith(Path.of("docs", "implementation")))
            .map(file -> file.getFileName().toString())
            .filter(name -> LEGACY_INCREMENT_NAME.matcher(name).matches())
            .toList();
        assertTrue(offenders.isEmpty(), () ->
            "Implementation documents must be named for the capability they describe, not the increment number "
                + "(see docs/README.md Naming Conventions): " + offenders);
    }

    @Test
    void retiredNamesDoNotReappear() {
        List<String> retired = retiredNames();
        List<String> violations = new ArrayList<>();
        for (Path file : Repo.trackedFiles()) {
            if (file.getFileName().toString().equals("retired-names.txt")) {
                continue;
            }
            String content = Repo.readIfText(file);
            if (content == null) {
                continue;
            }
            String lower = content.toLowerCase(Locale.ROOT);
            for (String name : retired) {
                if (lower.contains(name)) {
                    violations.add(file + " contains retired name '" + name + "'");
                }
            }
        }
        assertTrue(violations.isEmpty(), () ->
            "Retired names must not reappear (add the replacement name instead; "
                + "the deny list is testing/repo-guardrails/src/test/resources/retired-names.txt):\n"
                + String.join("\n", violations));
    }

    @Test
    void documentedModuleSelectorsExistInReactor() {
        Set<String> moduleArtifactIds = reactorArtifactIds();
        List<String> violations = new ArrayList<>();
        for (Path file : Repo.trackedFiles()) {
            if (!file.getFileName().toString().endsWith(".md")) {
                continue;
            }
            Matcher matcher = MODULE_SELECTOR.matcher(Repo.read(file));
            while (matcher.find()) {
                String artifactId = matcher.group(1);
                if (!moduleArtifactIds.contains(artifactId)) {
                    violations.add(file + " references module selector :" + artifactId
                        + " but no pom.xml declares that artifactId");
                }
            }
        }
        assertTrue(violations.isEmpty(), () ->
            "Documented Maven commands reference modules that do not exist:\n" + String.join("\n", violations));
    }

    private static List<String> retiredNames() {
        String resource;
        try (var stream = NamingConventionTest.class.getResourceAsStream("/retired-names.txt")) {
            resource = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("retired-names.txt must be on the test classpath", e);
        }
        return resource.lines()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .map(line -> line.toLowerCase(Locale.ROOT))
            .toList();
    }

    private static Set<String> reactorArtifactIds() {
        Set<String> artifactIds = new HashSet<>();
        for (Path file : Repo.trackedFiles()) {
            if (!file.getFileName().toString().equals("pom.xml")) {
                continue;
            }
            artifactIds.add(projectArtifactId(file));
        }
        return artifactIds;
    }

    private static String projectArtifactId(Path pom) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(Repo.read(pom).getBytes(StandardCharsets.UTF_8)));
            NodeList children = document.getDocumentElement().getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element element && element.getTagName().equals("artifactId")) {
                    return element.getTextContent().trim();
                }
            }
            throw new IllegalStateException("No artifactId in " + pom);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse " + pom, e);
        }
    }
}
