// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Contract between the Ceph developer harness's compose file, its .env template, its scripts, and its ignore rules. Guards against dead template variables, unguarded port publishing, missing restart/health policies, and trackable key material.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 1.0.0
 */
@Tag("unit")
final class HarnessContractTest {

    private static final Path HARNESS = Repo.root().resolve(Path.of("platform", "ceph", "local"));
    private static final Pattern COMPOSE_VARIABLE = Pattern.compile("\\$\\{([A-Z0-9_]+)(:-|:\\?)?");
    private static final Pattern TEMPLATE_KEY = Pattern.compile("^([A-Z0-9_]+)=", Pattern.MULTILINE);

    @Test
    void composeVariablesWithoutDefaultsAreInTheTemplate() {
        Set<String> templateKeys = templateKeys();
        String compose = Repo.read(HARNESS.resolve("compose.yaml"));
        List<String> violations = new ArrayList<>();
        Matcher matcher = COMPOSE_VARIABLE.matcher(compose);
        while (matcher.find()) {
            String variable = matcher.group(1);
            boolean hasDefault = ":-".equals(matcher.group(2));
            if (!hasDefault && !templateKeys.contains(variable)) {
                violations.add(variable);
            }
        }
        assertTrue(violations.isEmpty(), () ->
            "compose.yaml consumes variables with no default that .env.template does not declare: " + violations);
    }

    @Test
    void templateVariablesAreAllConsumed() {
        StringBuilder consumers = new StringBuilder(Repo.read(HARNESS.resolve("compose.yaml")));
        for (Path file : Repo.trackedFiles()) {
            if (file.startsWith(HARNESS.resolve("scripts"))) {
                consumers.append(Repo.read(file));
            }
        }
        String haystack = consumers.toString();
        List<String> dead = templateKeys().stream()
            .filter(key -> !haystack.contains(key))
            .toList();
        assertTrue(dead.isEmpty(), () ->
            ".env.template declares variables that neither compose.yaml nor any script consumes "
                + "(remove them or wire them through): " + dead);
    }

    @Test
    void rgwProxyBindsToLoopbackByDefault() {
        Map<String, Object> proxy = service("rgw-proxy");
        List<?> ports = (List<?>) proxy.get("ports");
        String publishing = String.valueOf(ports.get(0));
        assertTrue(publishing.startsWith("${CEPH_RGW_BIND_ADDRESS:-127.0.0.1}:"),
            "rgw-proxy must publish its port on a bind address that defaults to loopback, but publishes: "
                + publishing);
    }

    @Test
    void servicePoliciesHold() {
        Map<String, Object> services = services();
        Set<String> mustBeHealthy = dependedOnForHealth(services);
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Object> entry : services.entrySet()) {
            String name = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) entry.getValue();
            if (config.containsKey("build")) {
                violations.add(name + ": compose services must never build images");
            }
            if (isOneShot(config)) {
                if (!"no".equals(config.get("restart"))) {
                    violations.add(name + ": one-shot jobs must declare restart \"no\"");
                }
            } else if (isOnDemand(config)) {
                if (!Boolean.TRUE.equals(config.get("read_only"))) {
                    violations.add(name + ": verification services must be read_only");
                }
                List<?> securityOpt = (List<?>) config.getOrDefault("security_opt", List.of());
                if (!securityOpt.contains("no-new-privileges:true")) {
                    violations.add(name + ": verification services must set no-new-privileges");
                }
            } else if (!Set.of("unless-stopped", "always").contains(String.valueOf(config.get("restart")))) {
                violations.add(name + ": long-running services must declare a restart policy");
            }
            if (mustBeHealthy.contains(name) && !config.containsKey("healthcheck")) {
                violations.add(name + ": depended on with service_healthy but defines no healthcheck");
            }
        }
        assertTrue(violations.isEmpty(), () ->
            "compose.yaml service policy violations:\n" + String.join("\n", violations));
    }

    @Test
    void ignoreRulesCoverSecrets() {
        String gitignore = Repo.read(HARNESS.resolve(".gitignore"));
        List<String> missing = new ArrayList<>();
        for (String required : List.of(".env", "certs/", "private/", "*.key")) {
            if (gitignore.lines().map(String::trim).noneMatch(required::equals)) {
                missing.add(required);
            }
        }
        assertTrue(missing.isEmpty(), () ->
            "platform/ceph/local/.gitignore must ignore generated secret material; missing patterns: " + missing);
    }

    @Test
    void noKeyMaterialOrEnvFileIsTracked() {
        List<String> violations = Repo.trackedFiles().stream()
            .map(file -> file.getFileName().toString())
            .filter(name -> name.equals(".env")
                || name.endsWith(".key") || name.endsWith(".csr") || name.endsWith(".pem") || name.endsWith(".srl"))
            .toList();
        assertTrue(violations.isEmpty(), () ->
            "Key material and .env files must never be tracked: " + violations);
    }

    private static Set<String> templateKeys() {
        Set<String> keys = new HashSet<>();
        Matcher matcher = TEMPLATE_KEY.matcher(Repo.read(HARNESS.resolve(".env.template")));
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private static Map<String, Object> services() {
        Map<String, Object> compose = new Yaml().load(Repo.read(HARNESS.resolve("compose.yaml")));
        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        return services;
    }

    private static Map<String, Object> service(String name) {
        @SuppressWarnings("unchecked")
        Map<String, Object> service = (Map<String, Object>) services().get(name);
        return service;
    }

    private static Set<String> dependedOnForHealth(Map<String, Object> services) {
        Set<String> names = new HashSet<>();
        for (Object config : services.values()) {
            Object dependsOn = ((Map<?, ?>) config).get("depends_on");
            if (!(dependsOn instanceof Map<?, ?> dependencies)) {
                continue;
            }
            for (Map.Entry<?, ?> dependency : dependencies.entrySet()) {
                if (dependency.getValue() instanceof Map<?, ?> condition
                    && "service_healthy".equals(condition.get("condition"))) {
                    names.add(String.valueOf(dependency.getKey()));
                }
            }
        }
        return names;
    }

    private static boolean isOneShot(Map<String, Object> config) {
        String entrypoint = String.valueOf(config.get("entrypoint"));
        return entrypoint.contains("bootstrap.sh") || entrypoint.contains("configure.sh");
    }

    private static boolean isOnDemand(Map<String, Object> config) {
        List<?> profiles = (List<?>) config.getOrDefault("profiles", List.of());
        return profiles.contains("verification");
    }
}
