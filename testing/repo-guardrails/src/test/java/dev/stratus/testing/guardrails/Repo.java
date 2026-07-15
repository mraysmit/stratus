// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only access to the repository tree for guardrail tests.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
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

    /** All git-tracked files, so generated and ignored material is never scanned. */
    static List<Path> trackedFiles() {
        Path root = root();
        try {
            Process process = new ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z").start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git ls-files failed; the guardrail tests require git");
            }
            List<Path> files = new ArrayList<>();
            for (String entry : output.split("\0")) {
                if (!entry.isBlank()) {
                    files.add(root.resolve(entry));
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

    static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** File content, or null when the file is binary (contains a NUL byte). */
    static String readIfText(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            for (byte b : bytes) {
                if (b == 0) {
                    return null;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}
