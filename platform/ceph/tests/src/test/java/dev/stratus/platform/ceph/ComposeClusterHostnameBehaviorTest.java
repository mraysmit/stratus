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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises hosts-file configuration without touching the workstation file. */
@Tag("unit")
final class ComposeClusterHostnameBehaviorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void addsTheMappingOnceAndCheckModeAcceptsIt() {
        Path hosts = hostsFile("127.0.0.1 localhost\n");

        CommandResult first = run(hosts);
        CommandResult second = run(hosts);
        CommandResult check = run(hosts, "--check");

        assertEquals(0, first.exitCode(), first.output());
        assertEquals(0, second.exitCode(), second.output());
        assertEquals(0, check.exitCode(), check.output());
        String content = read(hosts);
        assertEquals(1, content.lines().filter(line -> line.contains("object-store.stratus.local")).count());
        assertTrue(content.contains("127.0.0.1\tobject-store.stratus.local\t# Stratus Ceph Compose"));
    }

    @Test
    void rejectsAConflictingMappingWithoutChangingTheFile() {
        String original = "192.0.2.10 object-store.stratus.local # managed elsewhere\n";
        Path hosts = hostsFile(original);

        CommandResult result = run(hosts);

        assertTrue(result.exitCode() != 0, result.output());
        assertTrue(result.output().contains("already maps to 192.0.2.10"), result.output());
        assertEquals(original, read(hosts));
    }

    @Test
    void checkModeReportsAMissingMappingWithoutChangingTheFile() {
        String original = "127.0.0.1 localhost\n";
        Path hosts = hostsFile(original);

        CommandResult result = run(hosts, "--check");

        assertTrue(result.exitCode() != 0, result.output());
        assertTrue(result.output().contains("is not configured"), result.output());
        assertEquals(original, read(hosts));
        assertFalse(read(hosts).contains("object-store.stratus.local"));
    }

    @Test
    void rejectsAnInvalidAddressWithoutChangingTheFile() {
        String original = "127.0.0.1 localhost\n";
        Path hosts = hostsFile(original);

        CommandResult result = run(hosts, "--address", "999.0.0.1");

        assertTrue(result.exitCode() != 0, result.output());
        assertTrue(result.output().contains("must be an IPv4 or IPv6 address"), result.output());
        assertEquals(original, read(hosts));
    }

    private Path hostsFile(String content) {
        Path hosts = temporaryDirectory.resolve("hosts-" + System.nanoTime());
        try {
            return Files.writeString(hosts, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static CommandResult run(Path hosts, String... arguments) {
        List<String> command = new ArrayList<>(List.of(Repo.bashExecutable(),
            "platform/ceph/compose-cluster/scripts/lifecycle/ceph-compose-configure-hostname.sh"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(Repo.root().toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("STRATUS_HOSTS_FILE", bashPath(hosts));
        try {
            Process process = builder.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Hosts-file behavior test timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandResult(process.exitValue(), output);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not execute hosts-file configuration script", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while testing hosts-file configuration", e);
        }
    }

    private static String bashPath(Path path) {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return path.toString();
        }
        Path cygpath = Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"),
            "Git", "usr", "bin", "cygpath.exe");
        try {
            Process process = new ProcessBuilder(cygpath.toString(), "-u", path.toString()).start();
            String converted = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.waitFor() != 0 || converted.isEmpty()) {
                throw new IllegalStateException("Could not convert test hosts path for Git Bash");
            }
            return converted;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not run Git for Windows cygpath", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while converting test hosts path", e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record CommandResult(int exitCode, String output) {
    }
}
