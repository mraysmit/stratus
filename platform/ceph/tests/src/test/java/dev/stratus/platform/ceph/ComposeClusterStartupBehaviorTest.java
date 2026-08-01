// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.ceph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Executes the real Compose startup entry point against an isolated harness.
 * Purpose-built protocol fakes record external commands, so these tests cover
 * shell behavior without contacting Docker, OpenSSL, or a live Ceph cluster.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-01
 * @version 1.0.0
 */
@Tag("unit")
final class ComposeClusterStartupBehaviorTest {

    private static final Path SOURCE = Repo.root().resolve(
        Path.of("platform", "ceph", "compose-cluster"));

    @TempDir
    Path temporaryDirectory;

    @Test
    void legacyEnvironmentIsMigratedIdempotentlyThroughCompatibilityEntryPoint() {
        Path harness = isolatedHarness(legacyEnvironment());

        CommandResult first = runStartup(harness);
        assertEquals(0, first.exitCode(), first.output());

        String migrated = read(harness.resolve(".env"));
        assertEquals("existing-access", valueOf(migrated, "CEPH_RGW_ACCESS_KEY"));
        assertEquals("existing-secret", valueOf(migrated, "CEPH_RGW_SECRET_KEY"));
        assertEquals("stratus-admin-ops-reader", valueOf(migrated, "CEPH_ADMIN_OPS_UID"));
        assertTrue(valueOf(migrated, "CEPH_ADMIN_OPS_ACCESS_KEY").startsWith("stratus-adminops-"));
        assertFalse(valueOf(migrated, "CEPH_ADMIN_OPS_SECRET_KEY").isBlank());
        assertEquals("https://object-store.stratus.local:9444",
            valueOf(migrated, "CEPH_DASHBOARD_ENDPOINT"));

        CommandResult second = runStartup(harness);
        assertEquals(0, second.exitCode(), second.output());
        String migratedAgain = read(harness.resolve(".env"));
        assertEquals(migrated, migratedAgain, "a second startup must not rewrite or duplicate credentials");
        for (String key : List.of("CEPH_ADMIN_OPS_UID", "CEPH_ADMIN_OPS_ACCESS_KEY",
                "CEPH_ADMIN_OPS_SECRET_KEY", "CEPH_DASHBOARD_ENDPOINT")) {
            assertEquals(1, migratedAgain.lines().filter(line -> line.startsWith(key + "=")).count(),
                key + " must occur exactly once");
        }

        List<String> dockerCalls = read(harness.resolve("fake-docker.log")).lines().toList();
        int config = indexContaining(dockerCalls, "config --quiet");
        int up = indexContaining(dockerCalls, "up --detach --remove-orphans --wait");
        assertTrue(config >= 0 && up > config,
            () -> "Compose interpolation must be validated before startup; calls were " + dockerCalls);
    }

    @Test
    void partialCredentialBundleIsRejectedBeforeExternalCommands() {
        Path harness = isolatedHarness(legacyEnvironment()
            + "CEPH_ADMIN_OPS_UID=stratus-admin-ops-reader\n");

        CommandResult result = runStartup(harness);

        assertTrue(result.exitCode() != 0, result.output());
        assertTrue(result.output().contains("must all be populated or all be absent"), result.output());
        assertFalse(Files.exists(harness.resolve("fake-docker.log")),
            "Docker must not be contacted for an unsafe partial credential bundle");
    }

    private Path isolatedHarness(String environment) {
        Path harness = temporaryDirectory.resolve("harness-" + System.nanoTime());
        try {
            copyTree(SOURCE.resolve("scripts"), harness.resolve("scripts"));
            Files.copy(SOURCE.resolve(".env.template"), harness.resolve(".env.template"));
            Files.copy(SOURCE.resolve("compose.yaml"), harness.resolve("compose.yaml"));
            Files.writeString(harness.resolve(".env"), environment, StandardCharsets.UTF_8);
            Path fakeBin = Files.createDirectories(harness.resolve("fake-bin"));
            executable(fakeBin.resolve("docker"), fakeDocker());
            executable(fakeBin.resolve("openssl"), fakeOpenSsl());
            return harness;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void executable(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        if (!path.toFile().setExecutable(true, false)) {
            throw new IOException("Could not make test command executable: " + path);
        }
    }

    private static CommandResult runStartup(Path harness) {
        ProcessBuilder builder = new ProcessBuilder(Repo.bashExecutable(), "-c",
            "export PATH=\"$PWD/fake-bin:$PATH\"; exec \"$PWD/scripts/lifecycle/startup.sh\"");
        builder.directory(harness.toFile());
        builder.redirectErrorStream(true);
        builder.environment().remove("MSYSTEM");
        try {
            Process process = builder.start();
            AtomicReference<String> captured = new AtomicReference<>("");
            Thread outputReader = Thread.ofVirtual().start(() -> {
                try {
                    captured.set(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    captured.set("Could not read process output: " + e.getMessage());
                }
            });
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                outputReader.join(TimeUnit.SECONDS.toMillis(2));
                throw new IllegalStateException("Compose startup behavior test timed out. Output:\n"
                    + captured.get());
            }
            outputReader.join(TimeUnit.SECONDS.toMillis(2));
            return new CommandResult(process.exitValue(), captured.get());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not execute the Compose startup entry point", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing the Compose startup entry point", e);
        }
    }

    private static String legacyEnvironment() {
        return """
            CEPH_RGW_ENDPOINT=https://object-store.stratus.local:8443
            CEPH_RGW_ACCESS_KEY=existing-access
            CEPH_RGW_SECRET_KEY=existing-secret
            CEPH_DENIED_UID=stratus-denied-owner
            CEPH_DENIED_ACCESS_KEY=denied-access
            CEPH_DENIED_SECRET_KEY=denied-secret
            CEPH_DASHBOARD_PORT=9444
            CEPH_DASHBOARD_USER=stratus-dashboard
            CEPH_DASHBOARD_PASSWORD=existing-dashboard-password
            COMPOSE_IMPLEMENTATION=docker
            """;
    }

    private static String valueOf(String environment, String key) {
        return environment.lines()
            .filter(line -> line.startsWith(key + "="))
            .reduce((first, second) -> second)
            .map(line -> line.substring(key.length() + 1))
            .orElseThrow(() -> new AssertionError(key + " is missing from migrated .env"));
    }

    private static int indexContaining(List<String> lines, String fragment) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains(fragment)) {
                return index;
            }
        }
        return -1;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String fakeDocker() {
        return """
            #!/usr/bin/env bash
            set -euo pipefail
            if [[ "${1:-}" == network && "${2:-}" == ls ]]; then
              exit 0
            fi
            printf '%s\n' "$*" >>fake-docker.log
            """;
    }

    private static String fakeOpenSsl() {
        return """
            #!/usr/bin/env bash
            set -euo pipefail
            command="${1:-}"
            shift || true
            if [[ "$command" == x509 && " $* " == *" -checkend "* ]]; then exit 0; fi
            if [[ "$command" == x509 && " $* " == *" -pubkey "* ]]; then printf 'fake-public-key\n'; exit 0; fi
            if [[ "$command" == pkey ]]; then printf 'fake-public-key\n'; exit 0; fi
            if [[ "$command" == verify ]]; then exit 0; fi
            while (( $# > 0 )); do
              if [[ "$1" == -keyout || "$1" == -out ]]; then
                shift
                mkdir -p "$(dirname "$1")"
                : >"$1"
              fi
              shift || true
            done
            """;
    }

    private record CommandResult(int exitCode, String output) {
    }
}
