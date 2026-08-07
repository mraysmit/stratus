// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.ceph;

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
 * Implementation-specific contract between the Ceph Compose cluster's compose
 * file, environment template, scripts, and ignore rules.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 1.0.0
 */
@Tag("unit")
final class ComposeClusterConformanceTest {

    private static final Path HARNESS = Repo.root().resolve(Path.of("platform", "ceph", "compose-cluster"));
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
                    violations.add(name + ": on-demand services must be read_only");
                }
                List<?> securityOpt = (List<?>) config.getOrDefault("security_opt", List.of());
                if (!securityOpt.contains("no-new-privileges:true")) {
                    violations.add(name + ": on-demand services must set no-new-privileges");
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
        for (String required : List.of(".env", "certs/", "private/", ".rotation/", "*.key")) {
            if (gitignore.lines().map(String::trim).noneMatch(required::equals)) {
                missing.add(required);
            }
        }
        assertTrue(missing.isEmpty(), () ->
            "platform/ceph/compose-cluster/.gitignore must ignore generated secret material; missing patterns: " + missing);
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

    @Test
    void certificateGeneratorPreservesPublicAndPrivateFileBoundaries() {
        List<String> violations = new ArrayList<>();
        String generator = Repo.read(HARNESS.resolve("scripts/lib/ceph-compose-generate-certificates.sh"));
        if (!generator.contains("chmod 0644 \"$ca_cert\" \"$rgw_cert\"")) {
            violations.add("public certificates must be readable by non-root client containers");
        }
        if (!generator.contains("chmod 0600 \"$ca_key\" \"$rgw_key\"")) {
            violations.add("private keys must remain owner-only");
        }
        if (!generator.contains("key_matches_certificate")) {
            violations.add("renewal must repair a certificate that does not match its key");
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void secretRotationUsesOverlapCutoverAndRevocationWithoutDestroyingData() {
        String rotation = Repo.read(HARNESS.resolve("scripts/lifecycle/ceph-compose-rotate-secrets.sh"));
        List<String> violations = new ArrayList<>();
        // Anchored on the rotation-path call: the repair path also creates a
        // key, and an unanchored search would silently start matching it.
        int create = rotation.indexOf("set_rgw_key create \"$CEPH_DEMO_UID\" \"$new_primary_access\"");
        int cutover = rotation.indexOf("log \"CUTOVER PASS:");
        int revoke = rotation.indexOf("set_rgw_key remove", cutover);
        if (create < 0 || cutover < 0 || revoke < 0 || !(create < cutover && cutover < revoke)) {
            violations.add("new RGW keys must be created and verified before old keys are revoked");
        }
        if (!rotation.contains("ceph dashboard ac-user-set-password \"$1\" -i \"$password_file\"")) {
            violations.add("the Dashboard password must be supplied through a protected input file");
        }
        if (!rotation.contains("STRATUS_FORCE_CA_ROTATION=true")) {
            violations.add("rotation must stage a replacement CA and endpoint certificate");
        }
        if (!rotation.contains("old_primary_access=\"$CEPH_RGW_ACCESS_KEY\"")
            || !rotation.contains("export CEPH_RGW_ACCESS_KEY=\"$new_primary_access\"")) {
            violations.add("cutover must retain old values for revocation and export new values for Compose");
        }
        if (!rotation.contains("--access-key \"$access_key\" >/dev/null")) {
            violations.add("RGW key removal output must be suppressed because it includes remaining secret keys");
        }
        if (!rotation.contains("assert_removed_key_rejected")
            || !rotation.contains("assert_old_dashboard_password_rejected")
            || !rotation.contains("assert_old_ca_rejected")) {
            violations.add("rotation must prove every old authentication path is rejected");
        }
        if (!rotation.contains("Rotation failed before revocation; attempting rollback")
            || !rotation.contains("Rotation failed after revocation began")) {
            violations.add("rotation must distinguish safe rollback from the post-revocation boundary");
        }
        if (rotation.contains("down --volumes") || rotation.contains("ceph-compose-reset.sh")) {
            violations.add("secret rotation must never destroy Ceph volumes or reset the cluster");
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void secretRotationRecoversFromInterruptionWithoutManualRepair() {
        String rotation = Repo.read(HARNESS.resolve("scripts/lifecycle/ceph-compose-rotate-secrets.sh"));
        List<String> violations = new ArrayList<>();
        if (!rotation.contains("printf '%s\\n' \"$$\" >\"$lock_dir/owner.pid\"")) {
            violations.add("the lock must record its owning process, or a killed run cannot be told from a live one");
        }
        int acquired = rotation.indexOf("lock_acquired=true");
        int recordOwner = rotation.indexOf("owner.pid\"", acquired);
        if (acquired < 0 || recordOwner < 0) {
            violations.add("the owning process must be recorded once the lock is held");
        }
        if (!rotation.contains("kill -0 \"$owner_pid\"")) {
            violations.add("staleness must be decided by testing the owning process, not by age or by prompting");
        }
        int reclaim = rotation.indexOf("reclaim_stale_lock");
        int stillFails = rotation.indexOf("Another secret rotation appears to be active");
        if (reclaim < 0 || stillFails < 0 || reclaim > stillFails) {
            violations.add("a live lock must still fail closed after the stale-lock check declines to reclaim");
        }
        if (!rotation.contains("Removing orphaned rotation stage directory")) {
            violations.add("reclaiming a stale lock must also clear the stage directories the dead run left behind");
        }
        if (!rotation.contains("[[ -d \"$orphan\" && \"$orphan\" != \"$stage\" ]]")) {
            violations.add("orphan cleanup must never remove the current run's own stage directory");
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void keyRepairReconcilesRgwWithEnvAndRemovesUnrevokedKeys() {
        String rotation = Repo.read(HARNESS.resolve("scripts/lifecycle/ceph-compose-rotate-secrets.sh"));
        List<String> violations = new ArrayList<>();
        if (!rotation.contains("--repair-keys) mode=repair-keys ;;")) {
            violations.add("the drift left by a rolled-back rotation must have a repair command, not a manual procedure");
        }
        if (!rotation.contains("run --repair-keys to reconcile RGW with .env")) {
            violations.add("preflight must name the repair command when it refuses on key drift");
        }
        int repairDispatch = rotation.indexOf("if [[ \"$mode\" == repair-keys ]]");
        int rotationStart = rotation.indexOf("old_primary_access=\"$CEPH_RGW_ACCESS_KEY\"");
        if (repairDispatch < 0 || rotationStart < 0 || repairDispatch > rotationStart) {
            violations.add("repair must exit before any rotation state is generated; it rotates nothing");
        }
        if (!rotation.contains("REPAIR: removing key '$existing' from $uid")) {
            violations.add("repair must remove keys absent from .env — a key left by a failed rotation is un-revoked");
        }
        if (!rotation.contains("|| fail \"Failed to attach the .env key to $uid\"")) {
            violations.add("repair must verify each reattached key against RGW rather than assume success");
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
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

    // Any profile-gated service runs only on demand (verification runs, the
    // s3admin policy tool) and must carry the same runtime hardening.
    private static boolean isOnDemand(Map<String, Object> config) {
        List<?> profiles = (List<?>) config.getOrDefault("profiles", List.of());
        return !profiles.isEmpty();
    }
}
