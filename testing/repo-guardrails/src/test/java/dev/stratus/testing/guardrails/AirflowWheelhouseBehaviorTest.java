// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Behavioral checks for the Airflow image wheelhouse integrity boundary. */
@Tag("unit")
final class AirflowWheelhouseBehaviorTest {

    private static final Path INTEGRITY_LIBRARY = Repo.root().resolve(Path.of(
            "platform", "airflow", "image", "scripts", "lib",
            "airflow-wheelhouse-integrity.sh"));
    private static final String CLIENT_ARCHIVE = "pyspark_client-4.1.3.tar.gz";
    private static final String FULL_PYSPARK_ARCHIVE = "pyspark-4.1.3.tar.gz";

    @TempDir
    Path temporaryDirectory;

    @Test
    void exactFileSetRejectsAnUnexpectedSupersededArtifact() throws IOException {
        Path wheelhouse = temporaryDirectory.resolve("wheelhouse-extra");
        Files.createDirectories(wheelhouse);
        Files.writeString(wheelhouse.resolve(CLIENT_ARCHIVE), "client", StandardCharsets.UTF_8);
        Files.writeString(wheelhouse.resolve(FULL_PYSPARK_ARCHIVE), "full", StandardCharsets.UTF_8);
        Files.writeString(wheelhouse.resolve("resolved-artifacts.sha256"), "manifest",
                StandardCharsets.UTF_8);
        Path expected = expectedArtifacts(CLIENT_ARCHIVE);

        CommandResult result = runHarness("""
                source "$1"
                verify_wheelhouse_exact_file_set "$2" "$3"
                """, INTEGRITY_LIBRARY, expected, wheelhouse);

        assertAll(
                () -> assertTrue(result.exitCode() != 0,
                        "An unreferenced artifact must fail the build preflight"),
                () -> assertTrue(result.output().contains(
                        "unexpected=" + FULL_PYSPARK_ARCHIVE), result::output));
    }

    @Test
    void exactFileSetAcceptsOnlyTheSelectedArtifactsAndManifest() throws IOException {
        Path wheelhouse = temporaryDirectory.resolve("wheelhouse-exact");
        Files.createDirectories(wheelhouse);
        Files.writeString(wheelhouse.resolve(CLIENT_ARCHIVE), "client", StandardCharsets.UTF_8);
        Files.writeString(wheelhouse.resolve("resolved-artifacts.sha256"), "manifest",
                StandardCharsets.UTF_8);
        Path expected = expectedArtifacts(CLIENT_ARCHIVE);

        CommandResult result = runHarness("""
                source "$1"
                verify_wheelhouse_exact_file_set "$2" "$3"
                """, INTEGRITY_LIBRARY, expected, wheelhouse);

        assertEquals(0, result.exitCode(), result::output);
    }

    @Test
    void failedCandidateMoveImmediatelyRestoresThePreviousWheelhouse() throws IOException {
        Path active = temporaryDirectory.resolve("active-failure");
        Path candidate = temporaryDirectory.resolve("candidate-failure");
        Path previous = temporaryDirectory.resolve("previous-failure");
        Files.createDirectories(active);
        Files.createDirectories(candidate);
        Files.writeString(active.resolve("known-good.whl"), "old", StandardCharsets.UTF_8);
        Files.writeString(candidate.resolve("candidate.whl"), "new", StandardCharsets.UTF_8);

        CommandResult result = runHarness("""
                move_count=0
                mv() {
                  move_count=$((move_count + 1))
                  if [[ $move_count -eq 2 ]]; then
                    return 73
                  fi
                  command mv "$@"
                }
                source "$1"
                promote_wheelhouse_candidate "$2" "$3" "$4"
                """, INTEGRITY_LIBRARY, active, candidate, previous);

        assertAll(
                () -> assertTrue(result.exitCode() != 0,
                        "The injected candidate move failure must be observable"),
                () -> assertTrue(result.output().contains("event=wheelhouse_promotion_rolled_back"),
                        result::output),
                () -> assertTrue(Files.isRegularFile(active.resolve("known-good.whl"))),
                () -> assertTrue(Files.isRegularFile(candidate.resolve("candidate.whl"))),
                () -> assertFalse(Files.exists(previous),
                        "A completed rollback must not retain ambiguous promotion state"));
    }

    @Test
    void nextInvocationRecoversAnInterruptedPromotionBeforeReplacement() throws IOException {
        Path active = temporaryDirectory.resolve("active-recovery");
        Path previous = temporaryDirectory.resolve("previous-recovery");
        Files.createDirectories(previous);
        Files.writeString(previous.resolve("known-good.whl"), "old", StandardCharsets.UTF_8);

        CommandResult result = runHarness("""
                source "$1"
                recover_wheelhouse_promotion "$2" "$3"
                """, INTEGRITY_LIBRARY, active, previous);

        assertAll(
                () -> assertEquals(0, result.exitCode(), result::output),
                () -> assertTrue(result.output().contains("event=wheelhouse_promotion_recovered"),
                        result::output),
                () -> assertTrue(Files.isRegularFile(active.resolve("known-good.whl"))),
                () -> assertFalse(Files.exists(previous)));
    }

    private Path expectedArtifacts(String... artifacts) throws IOException {
        Path expected = temporaryDirectory.resolve("expected-" + System.nanoTime() + ".txt");
        Files.write(expected, List.of(artifacts), StandardCharsets.UTF_8);
        return expected;
    }

    private CommandResult runHarness(String body, Path... arguments) throws IOException {
        Path harness = temporaryDirectory.resolve("harness-" + System.nanoTime() + ".sh");
        Files.writeString(harness, "#!/usr/bin/env bash\nset -euo pipefail\n" + body,
                StandardCharsets.UTF_8);
        List<String> command = new ArrayList<>();
        command.add(Repo.bashExecutable().toString());
        command.add(harness.toString());
        for (Path argument : arguments) {
            command.add(argument.toAbsolutePath().toString().replace('\\', '/'));
        }
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().remove("MSYSTEM");
        try {
            Process process = builder.start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IllegalStateException("Wheelhouse harness timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return new CommandResult(process.exitValue(), output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing wheelhouse harness", e);
        }
    }

    private record CommandResult(int exitCode, String output) {
    }
}
