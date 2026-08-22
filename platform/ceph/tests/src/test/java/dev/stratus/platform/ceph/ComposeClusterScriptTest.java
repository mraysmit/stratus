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
 * Offline repository contract for the Ceph harness script interface defined by ADR-P1-002.
 *
 * <h2>Rationale</h2>
 *
 * <p>The harness intentionally has one Bash implementation across Linux, macOS, and Git Bash.
 * Renamed entry points, lost executable bits, missing fail-fast behavior, broken Windows path
 * conversion, unbounded evidence, or duplicated connection settings can make recovery and
 * verification fail only when operators need them. These assertions keep that interface explicit.
 *
 * <h2>Proof boundary and maintenance</h2>
 *
 * <p>Reusable files use {@code *_PATH}, required ordered steps and markers use
 * {@code REQUIRED_*}, and approved Git metadata uses {@code EXPECTED_*}. Update constants,
 * scripts, wrappers, README, and ADR-linked guidance in one atomic change; keep compatibility
 * wrappers until their documented removal point. This class proves source structure, not a live
 * Ceph lifecycle.
 *
 * <p>Developer acceptance still requires the live start/verify/stop sequence. UAT must run the same
 * immutable verifier and image digests with managed credentials and retained evidence. Production
 * requires the unchanged UAT-approved artifacts plus recovery, security, observability, capacity,
 * rollback, and change-control approval.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 2.1.0
 */
@Tag("unit")
final class ComposeClusterScriptTest {

    private static final Path HARNESS_ROOT = Repo.root().resolve(
            Path.of("platform", "ceph", "compose-cluster"));
    private static final Path SCRIPTS_ROOT = HARNESS_ROOT.resolve("scripts");
    private static final Path GIT_ATTRIBUTES_PATH = Repo.root().resolve(".gitattributes");
    private static final Path COMMON_SCRIPT_PATH = SCRIPTS_ROOT.resolve(
            Path.of("lib", "ceph-compose-common.sh"));
    private static final Path HOSTNAME_CONFIGURATION_PATH = SCRIPTS_ROOT.resolve(
            Path.of("lifecycle", "ceph-compose-configure-hostname.sh"));
    private static final Path LIVE_TEST_WRAPPER_PATH = SCRIPTS_ROOT.resolve(
            Path.of("verify", "ceph-compose-run-live-tests.sh"));
    private static final Path VALIDATION_ORCHESTRATOR_PATH = SCRIPTS_ROOT.resolve(
            Path.of("verify", "ceph-compose-validate-cluster.sh"));
    private static final Path HARNESS_SELF_TEST_PATH = SCRIPTS_ROOT.resolve(
            Path.of("verify", "ceph-compose-verify-harness.sh"));
    private static final Path CONNECTION_ENVIRONMENT_PATH = HARNESS_ROOT.resolve("connection.env");
    private static final Path COMPOSE_PATH = HARNESS_ROOT.resolve("compose.yaml");
    private static final Path README_PATH = HARNESS_ROOT.resolve("README.md");

    private static final String EXPECTED_EXECUTABLE_MODE = "100755";
    private static final List<String> REQUIRED_CONNECTION_KEYS = List.of(
            "CEPH_COMPOSE_PROJECT",
            "CEPH_HARNESS_NETWORK",
            "CEPH_RGW_ENDPOINT",
            "CEPH_DASHBOARD_ENDPOINT",
            "CEPH_HARNESS_CA_CERT");
    private static final List<String> REQUIRED_ENDPOINT_KEYS = List.of(
            "CEPH_RGW_ENDPOINT", "CEPH_DASHBOARD_ENDPOINT");
    private static final List<String> REQUIRED_LIVE_WRAPPER_MARKERS = List.of(
            "platform/ceph/tests/logs",
            "run_started_at=\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"",
            "run_completed_at=\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"",
            "run_id=\"ceph-live-tests-$run_timestamp\"",
            "test_log=\"$test_log_dir/$run_id.log\"",
            "rest-api-tests-$run_timestamp.log",
            "${PIPESTATUS[0]}",
            "sourceTranscript=",
            "RUN completedAtUtc=",
            "exit \"$maven_status\"");
    private static final List<String> REQUIRED_VALIDATION_DELEGATES = List.of(
            "verify/ceph-compose-bootstrap-buckets.sh",
            "verify/ceph-compose-provision-service-identities.sh",
            "verify/ceph-compose-verify-buckets.sh",
            "verify/ceph-compose-verify-storage.sh",
            "verify/ceph-compose-verify-security.sh",
            "verify/ceph-compose-verify-dashboard.sh",
            "verify/ceph-compose-verify-dataset.sh",
            "verify/ceph-compose-run-live-tests.sh");
    private static final List<String> REQUIRED_ORCHESTRATOR_MARKERS = List.of(
            "validate-cluster-$run_timestamp.txt",
            "run_id=\"ceph-validate-cluster-$run_timestamp\"",
            "RUN startedAtUtc=",
            "RESULT PASS runId=",
            "RESULT FAIL runId=",
            "${PIPESTATUS[0]}",
            "lifecycle/ceph-compose-startup.sh",
            "lifecycle/ceph-compose-shutdown.sh");
    private static final Map<String, String> REQUIRED_COMPATIBILITY_WRAPPERS = Map.ofEntries(
            Map.entry("lib/common.sh", "ceph-compose-common.sh"),
            Map.entry("lib/generate-compose-certificates.sh",
                    "ceph-compose-generate-certificates.sh"),
            Map.entry("lifecycle/install-prerequisites.sh",
                    "ceph-compose-install-prerequisites.sh"),
            Map.entry("lifecycle/reset.sh", "ceph-compose-reset.sh"),
            Map.entry("lifecycle/rotate-secrets.sh", "ceph-compose-rotate-secrets.sh"),
            Map.entry("lifecycle/shutdown.sh", "ceph-compose-shutdown.sh"),
            Map.entry("lifecycle/startup.sh", "ceph-compose-startup.sh"),
            Map.entry("verify/bootstrap-buckets.sh", "ceph-compose-bootstrap-buckets.sh"),
            Map.entry("verify/failure-drill.sh", "ceph-compose-failure-drill.sh"),
            Map.entry("verify/check.sh", "ceph-compose-verify-buckets.sh"),
            Map.entry("verify/verify-dashboard.sh", "ceph-compose-verify-dashboard.sh"),
            Map.entry("verify/verify-dataset.sh", "ceph-compose-verify-dataset.sh"),
            Map.entry("verify/selftest.sh", "ceph-compose-verify-harness.sh"),
            Map.entry("verify/verify-security.sh", "ceph-compose-verify-security.sh"),
            Map.entry("verify/verify-java.sh", "ceph-compose-verify-storage.sh"));

    @Test
    void harnessScriptsAreBashOnly() {
        List<String> violations = new ArrayList<>();
        List<Path> scripts = scriptFiles();
        assertTrue(!scripts.isEmpty(), "No harness scripts were discovered; check the configured harness path");
        for (Path script : Repo.trackedFiles()) {
            if (script.startsWith(SCRIPTS_ROOT)
                    && script.getFileName().toString().endsWith(".ps1")) {
                violations.add(script + " reintroduces a PowerShell twin; harness scripts are bash-only per ADR-P1-002");
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void bashScriptsFailFast() {
        List<String> violations = new ArrayList<>();
        String attributes = Repo.read(GIT_ATTRIBUTES_PATH);
        if (attributes.lines().map(String::trim).noneMatch("*.sh text eol=lf"::equals)) {
            violations.add(".gitattributes must enforce LF working-tree endings for container-mounted shell scripts");
        }
        for (Path script : Repo.trackedFiles()) {
            if (!script.startsWith(HARNESS_ROOT)
                    || !script.getFileName().toString().endsWith(".sh")) {
                continue;
            }
            List<String> lines = Repo.read(script).lines().toList();
            if (lines.size() < 2 || !lines.get(0).startsWith("#!") || !lines.get(1).equals("set -euo pipefail")) {
                violations.add(script + " must start with a shebang followed by 'set -euo pipefail'");
            }
        }
        String common = Repo.read(COMMON_SCRIPT_PATH);
        if (!common.contains("MSYS_NO_PATHCONV=1") || !common.contains("cygpath -w")) {
            violations.add("ceph-compose-common.sh must preserve container paths while passing host paths to Docker from Git Bash");
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void bashScriptsAreExecutableInGit() {
        List<String> violations = Repo.trackedFileModes().entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(SCRIPTS_ROOT))
            .filter(entry -> entry.getKey().getFileName().toString().endsWith(".sh"))
            .filter(entry -> !entry.getValue().equals(EXPECTED_EXECUTABLE_MODE))
            .map(entry -> entry.getKey() + " is tracked as " + entry.getValue()
                    + ", expected " + EXPECTED_EXECUTABLE_MODE)
            .toList();
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void renamedEntryPointsRetainCompatibilityWrappers() {
        List<String> violations = new ArrayList<>();
        List<Path> trackedFiles = Repo.trackedFiles();
        REQUIRED_COMPATIBILITY_WRAPPERS.forEach((wrapper, target) -> {
            Path wrapperPath = SCRIPTS_ROOT.resolve(wrapper);
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
        String configuration = Repo.read(HOSTNAME_CONFIGURATION_PATH);
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

    @Test
    void liveTestWrapperRetainsBoundedPerRunEvidence() {
        String wrapper = Repo.read(LIVE_TEST_WRAPPER_PATH);
        List<String> violations = new ArrayList<>();
        for (String required : REQUIRED_LIVE_WRAPPER_MARKERS) {
            if (!wrapper.contains(required)) {
                violations.add("live-test wrapper must contain " + required);
            }
        }
        if (wrapper.contains("trustStorePassword")) {
            violations.add("live-test wrapper must not put the truststore password on the JVM command line");
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void validationOrchestratorRunsTheDocumentedSequenceInOrderWithPerRunEvidence() {
        String orchestrator = Repo.read(VALIDATION_ORCHESTRATOR_PATH);
        List<String> violations = new ArrayList<>();
        int previousIndex = -1;
        for (String delegate : REQUIRED_VALIDATION_DELEGATES) {
            int index = orchestrator.indexOf(delegate);
            if (index < 0) {
                violations.add("validation orchestrator must delegate to " + delegate);
            } else if (index < previousIndex) {
                violations.add(delegate + " must run in the documented verification order");
            } else {
                previousIndex = index;
            }
        }
        for (String required : REQUIRED_ORCHESTRATOR_MARKERS) {
            if (!orchestrator.contains(required)) {
                violations.add("validation orchestrator must contain " + required);
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void connectionSettingsArePublishedOnceAndConsistently() {
        Map<String, String> settings = new LinkedHashMap<>();
        for (String line : Repo.read(CONNECTION_ENVIRONMENT_PATH).lines().toList()) {
            String trimmed = line.strip();
            int separator = trimmed.indexOf('=');
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && separator > 0) {
                settings.put(trimmed.substring(0, separator), trimmed.substring(separator + 1));
            }
        }
        List<String> violations = new ArrayList<>();
        for (String key : REQUIRED_CONNECTION_KEYS) {
            if (settings.getOrDefault(key, "").isBlank()) {
                violations.add("connection.env must define " + key);
            }
        }
        String project = settings.getOrDefault("CEPH_COMPOSE_PROJECT", "");
        String composeFile = Repo.read(COMPOSE_PATH);
        if (!composeFile.startsWith("name: " + project)) {
            violations.add("compose.yaml project name must match connection.env CEPH_COMPOSE_PROJECT");
        }
        if (!settings.getOrDefault("CEPH_HARNESS_NETWORK", "").equals(project + "_ceph")) {
            violations.add("CEPH_HARNESS_NETWORK must be the compose project name plus the 'ceph' network key");
        }
        for (String endpointKey : REQUIRED_ENDPOINT_KEYS) {
            String endpoint = settings.getOrDefault(endpointKey, "");
            if (!endpoint.startsWith("https://object-store.stratus.local:")) {
                violations.add(endpointKey + " must be the TLS proxy endpoint published as a network alias");
            }
        }
        String common = Repo.read(COMMON_SCRIPT_PATH);
        if (!common.contains("CEPH_COMPOSE_PROJECT=\"" + project + "\"")) {
            violations.add("ceph-compose-common.sh must define CEPH_COMPOSE_PROJECT once, matching connection.env");
        }
        // The definition above is the only permitted literal: every other use
        // must reference the variable so a rename is a one-line change.
        long commonLiterals = common.lines().filter(line -> line.contains(project)).count();
        if (commonLiterals > 1) {
            violations.add("ceph-compose-common.sh must contain the project name literal exactly once (the definition), found " + commonLiterals + " lines");
        }
        String harnessSelfTest = Repo.read(HARNESS_SELF_TEST_PATH);
        if (harnessSelfTest.contains(project)) {
            violations.add("ceph-compose-verify-harness.sh must reference $CEPH_COMPOSE_PROJECT, not the literal project name");
        }
        if (!Repo.read(README_PATH).contains(CONNECTION_ENVIRONMENT_PATH.getFileName().toString())) {
            violations.add("the harness README must reference connection.env");
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    private static List<Path> scriptFiles() {
        return Repo.trackedFiles().stream()
            .filter(file -> file.startsWith(SCRIPTS_ROOT))
            .filter(file -> file.getFileName().toString().endsWith(".sh"))
            .toList();
    }
}
