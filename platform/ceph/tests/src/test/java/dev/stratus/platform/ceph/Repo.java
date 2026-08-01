// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.ceph;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only repository access for Ceph implementation guardrails.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-29
 * @version 1.0.0
 */
final class Repo {

    private Repo() {
    }

    static Path root() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve(".git")) && Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Repository root not found above " + current);
    }

    static List<Path> trackedFiles() {
        Path root = root();
        try {
            Process process = new ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z").start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git ls-files failed; the Ceph tests require git");
            }
            List<Path> files = new ArrayList<>();
            for (String entry : output.split("\0")) {
                Path file = root.resolve(entry);
                if (!entry.isBlank() && Files.exists(file)) {
                    files.add(file);
                }
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("git is required to enumerate tracked files", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while listing tracked files", e);
        }
    }

    static Map<Path, String> trackedFileModes() {
        Path root = root();
        try {
            Process process = new ProcessBuilder("git", "-C", root.toString(),
                "ls-files", "--stage", "-z").start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git ls-files --stage failed; the Ceph tests require git");
            }
            Map<Path, String> modes = new LinkedHashMap<>();
            for (String entry : output.split("\0")) {
                if (entry.isBlank()) {
                    continue;
                }
                int space = entry.indexOf(' ');
                int tab = entry.indexOf('\t');
                if (space < 0 || tab < 0) {
                    throw new IllegalStateException("Unexpected git ls-files --stage record: " + entry);
                }
                modes.put(root.resolve(entry.substring(tab + 1)), entry.substring(0, space));
            }
            return modes;
        } catch (IOException e) {
            throw new UncheckedIOException("git is required to inspect tracked file modes", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while inspecting tracked file modes", e);
        }
    }

    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    static String bashExecutable() {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return "bash";
        }
        Path programFiles = Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"));
        Path gitBash = programFiles.resolve(Path.of("Git", "bin", "bash.exe"));
        if (Files.isExecutable(gitBash)) {
            return gitBash.toString();
        }
        for (String entry : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            Path directory = Path.of(entry);
            for (Path candidate : List.of(directory.resolve("bash.exe"),
                    directory.resolveSibling("bin").resolve("bash.exe"))) {
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        throw new IllegalStateException("Git for Windows Bash is required to test the Compose harness");
    }
}
