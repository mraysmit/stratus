// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The wiring that lets a live test profile fail instead of skipping.
 *
 * <p>Each live layer has an opt-in switch in the environment and a
 * {@code <layer>.integration.required} property that says the switch is
 * mandatory because a live profile was selected. The test reads that property
 * as a system property, so the value has to travel: root POM property, through
 * Surefire's {@code systemPropertyVariables} in the build parent, into the
 * forked JVM. Every link is in a different file and none of them fails loudly.
 *
 * <p>{@code spark.integration.required} was declared, set by its profile, and
 * read by {@code LiveSparkCluster} while the build parent never forwarded it —
 * so under {@code -Pspark-integration-tests} with no cluster the suite skipped
 * and the build went green, which is the exact outcome the property exists to
 * prevent. Nothing failed, because a missing forward looks like a switch that
 * is simply off.
 *
 * <p>These rules are structural: they compare the POMs to each other and never
 * run Maven. They cannot tell whether a test consults its property, only that
 * the property could reach it.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-11
 * @version 1.0.0
 */
@Tag("unit")
final class LiveProfileWiringTest {

    /** {@code ceph.integration.required} names the layer {@code ceph}. */
    private static final Pattern REQUIRED_PROPERTY =
            Pattern.compile("^([a-z]+)\\.integration\\.required$");

    /** {@code ceph-integration} names the layer {@code ceph}. */
    private static final Pattern LIVE_TAG = Pattern.compile("^([a-z]+)-integration$");

    private static final String SUREFIRE = "maven-surefire-plugin";
    private static final String ALL_TESTS = "all-tests";

    @Test
    void everyLiveTagHasAnOptInPropertyAndItsOwnProfile() {
        Document root = parse(rootPom());
        List<String> violations = new ArrayList<>();
        for (String layer : liveLayers(root)) {
            String property = layer + ".integration.required";
            if (!projectProperties(root).containsKey(property)) {
                violations.add("tag " + layer + "-integration is excluded by default but the root POM "
                        + "declares no " + property + ", so no profile can demand the live environment");
            }
            String profile = layer + "-integration-tests";
            if (profileProperties(root, profile) == null) {
                violations.add("tag " + layer + "-integration is excluded by default but there is no "
                        + profile + " profile to select it");
            }
        }
        assertTrue(violations.isEmpty(), () -> report(violations));
    }

    @Test
    void everyOptInPropertyIsForwardedToTheTestJvm() {
        Map<String, String> forwarded = surefireSystemProperties(parse(buildParentPom()));
        List<String> violations = new ArrayList<>();
        for (String property : requiredProperties(parse(rootPom()))) {
            String expected = "${" + property + "}";
            String actual = forwarded.get(property);
            if (actual == null) {
                violations.add(property + " is declared in the root POM but the build parent does not "
                        + "forward it, so the test JVM never sees it and the guard that reads it can "
                        + "never fire. Add <" + property + ">" + expected + "</" + property + "> to the "
                        + SUREFIRE + " systemPropertyVariables");
            } else if (!expected.equals(actual)) {
                violations.add(property + " is forwarded as " + actual + " rather than " + expected
                        + ", so the profile's value does not reach the test JVM");
            }
        }
        assertTrue(violations.isEmpty(), () -> report(violations));
    }

    @Test
    void everyTargetedProfileDemandsItsOwnLayer() {
        Document root = parse(rootPom());
        List<String> violations = new ArrayList<>();
        for (String layer : liveLayers(root)) {
            String profile = layer + "-integration-tests";
            Map<String, String> properties = profileProperties(root, profile);
            if (properties == null) {
                continue;
            }
            String property = layer + ".integration.required";
            if (!"true".equals(properties.get(property))) {
                violations.add(profile + " does not set " + property + " to true, so selecting it "
                        + "against a stopped harness reports success after skipping every test");
            }
        }
        assertTrue(violations.isEmpty(), () -> report(violations));
    }

    @Test
    void theAllTestsProfileDemandsEveryLayer() {
        Document root = parse(rootPom());
        Map<String, String> properties = profileProperties(root, ALL_TESTS);
        assertTrue(properties != null, () -> "The root POM declares no " + ALL_TESTS + " profile");
        List<String> violations = new ArrayList<>();
        for (String property : requiredProperties(root)) {
            if (!"true".equals(properties.get(property))) {
                violations.add(ALL_TESTS + " does not set " + property + " to true, so the profile "
                        + "that claims to run everything lets that layer skip in silence");
            }
        }
        assertTrue(violations.isEmpty(), () -> report(violations));
    }

    /** The layers named by the tags excluded from the default build. */
    private static List<String> liveLayers(Document root) {
        String excluded = projectProperties(root).get("test.excludedGroups");
        assertTrue(excluded != null && !excluded.isBlank(),
                () -> "The root POM declares no default test.excludedGroups, so live suites would "
                        + "run in the offline build");
        List<String> layers = new ArrayList<>();
        for (String tag : excluded.split("\\|")) {
            Matcher matcher = LIVE_TAG.matcher(tag.trim());
            if (matcher.matches()) {
                layers.add(matcher.group(1));
            }
        }
        assertTrue(!layers.isEmpty(),
                () -> "No live tag is excluded by default: " + excluded);
        return layers;
    }

    /** The {@code <layer>.integration.required} properties the root POM declares. */
    private static List<String> requiredProperties(Document root) {
        List<String> properties = projectProperties(root).keySet().stream()
                .filter(name -> REQUIRED_PROPERTY.matcher(name).matches())
                .toList();
        assertTrue(!properties.isEmpty(),
                () -> "The root POM declares no <layer>.integration.required property");
        return properties;
    }

    private static Map<String, String> projectProperties(Document document) {
        Element properties = child(document.getDocumentElement(), "properties");
        assertTrue(properties != null, () -> "The POM has no top-level <properties>");
        return textOfChildren(properties);
    }

    /** The named profile's properties, or null when the profile is absent. */
    private static Map<String, String> profileProperties(Document document, String id) {
        Element profiles = child(document.getDocumentElement(), "profiles");
        if (profiles == null) {
            return null;
        }
        for (Element profile : children(profiles, "profile")) {
            Element identifier = child(profile, "id");
            if (identifier != null && id.equals(identifier.getTextContent().trim())) {
                Element properties = child(profile, "properties");
                return properties == null ? Map.of() : textOfChildren(properties);
            }
        }
        return null;
    }

    /** The system properties Surefire hands to the forked test JVM. */
    private static Map<String, String> surefireSystemProperties(Document document) {
        Element build = child(document.getDocumentElement(), "build");
        Element management = build == null ? null : child(build, "pluginManagement");
        Element plugins = management == null ? null : child(management, "plugins");
        assertTrue(plugins != null,
                () -> "The build parent declares no pluginManagement plugins, so no module inherits "
                        + "a Surefire configuration");
        for (Element plugin : children(plugins, "plugin")) {
            Element artifactId = child(plugin, "artifactId");
            if (artifactId == null || !SUREFIRE.equals(artifactId.getTextContent().trim())) {
                continue;
            }
            Element configuration = child(plugin, "configuration");
            Element variables = configuration == null
                    ? null : child(configuration, "systemPropertyVariables");
            return variables == null ? Map.of() : textOfChildren(variables);
        }
        throw new IllegalStateException("The build parent manages no " + SUREFIRE);
    }

    private static Map<String, String> textOfChildren(Element parent) {
        Map<String, String> values = new LinkedHashMap<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                values.put(element.getTagName(), element.getTextContent().trim());
            }
        }
        return values;
    }

    private static Element child(Element parent, String name) {
        List<Element> matches = children(parent, name);
        return matches.isEmpty() ? null : matches.get(0);
    }

    /** Direct children only: getElementsByTagName would reach into nested profiles. */
    private static List<Element> children(Element parent, String name) {
        List<Element> matches = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && element.getTagName().equals(name)) {
                matches.add(element);
            }
        }
        return matches;
    }

    private static Path rootPom() {
        return Repo.root().resolve("pom.xml");
    }

    private static Path buildParentPom() {
        return Repo.root().resolve(Path.of("build-support", "stratus-build-parent", "pom.xml"));
    }

    private static Document parse(Path pom) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(Repo.read(pom).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse " + pom, e);
        }
    }

    private static String report(List<String> violations) {
        return "A live test profile cannot demand its environment:\n  " + String.join("\n  ", violations);
    }
}
