// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Offline behavior checks for the revision-bound focused Spark test path. */
@Tag("unit")
final class SparkFocusedTestArtifactsBehaviorTest {

    private static final Path HARNESS = LiveSparkCluster.harnessDirectory();
    private static final Path ARTIFACT_LIBRARY = HARNESS.resolve(
            "scripts/lib/spark-compose-focused-test-artifacts.sh");
    private static final Path FOCUSED_RUNNER = HARNESS.resolve(
            "scripts/tests/spark-compose-run-focused-tests.sh");

    @TempDir
    Path temporaryDirectory;

    @Test
    void recordedArtifactsPassUntilAnInputOrInstalledArtifactChanges() {
        Fixture fixture = createArtifactFixture();

        CommandResult prepared = runArtifactLibrary(fixture, "focused_test_record_artifacts; "
                + "focused_test_validate_artifacts");
        assertEquals(0, prepared.exitCode(), prepared.output());

        write(fixture.repository().resolve("jobs/spark/src/main/Job.java"), "class Job { int changed; }\n");
        CommandResult staleSource = runArtifactLibrary(fixture, "focused_test_validate_artifacts");
        String staleSourceOutput = normalized(staleSource.output());
        assertTrue(staleSource.exitCode() != 0
                        && staleSourceOutput.contains("source inputs changed after preparation"),
                staleSource.output());

        write(fixture.repository().resolve("jobs/spark/src/main/Job.java"), "class Job {}\n");
        assertEquals(0, runArtifactLibrary(fixture, "focused_test_record_artifacts").exitCode());
        write(fixture.localRepository().resolve(
                "dev/stratus/stratus-spark-jobs/1.0-SNAPSHOT/stratus-spark-jobs-1.0-SNAPSHOT.jar"),
                "replaced artifact\n");
        CommandResult staleArtifact = runArtifactLibrary(fixture, "focused_test_validate_artifacts");
        String staleArtifactOutput = normalized(staleArtifact.output());
        assertTrue(staleArtifact.exitCode() != 0
                        && staleArtifactOutput.contains("installed Spark jobs artifact changed"),
                staleArtifact.output());
    }

    @Test
    void missingPreparationStateIsRejectedWithThePreparationCommand() {
        Fixture fixture = createArtifactFixture();

        CommandResult result = runArtifactLibrary(fixture, "focused_test_validate_artifacts");
        String output = normalized(result.output());

        assertTrue(result.exitCode() != 0
                        && output.contains("have not been prepared")
                        && output.contains("spark-compose-prepare-focused-tests.sh"),
                result.output());
    }

    @Test
    void focusedRunnerPinsTheLifecycleAndForwardsOnlyUserProperties() {
        Path harness = temporaryDirectory.resolve("runner-harness");
        Path tests = harness.resolve("scripts/tests");
        Path library = harness.resolve("scripts/lib");
        try {
            Files.createDirectories(tests);
            Files.createDirectories(library);
            Files.copy(FOCUSED_RUNNER, tests.resolve(FOCUSED_RUNNER.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
            write(library.resolve("spark-compose-maven-common.sh"), """
                    HARNESS_DIR=unused
                    REPO_DIR=unused
                    log() { printf 'LOG=%s\n' "$*"; }
                    fail() { printf 'ERROR=%s\n' "$*" >&2; exit 1; }
                    """);
            write(library.resolve("spark-compose-focused-test-artifacts.sh"), """
                    focused_test_validate_artifacts() { printf 'VALIDATED\n'; }
                    focused_test_maven_repository_argument() { printf '/prepared/repository\n'; }
                    """);
            Path liveRunner = tests.resolve("spark-compose-run-live-tests.sh");
            write(liveRunner, """
                    #!/usr/bin/env bash
                    for argument in "$@"; do printf 'ARG=<%s>\n' "$argument"; done
                    """);
            assertTrue(liveRunner.toFile().setExecutable(true) || Files.isExecutable(liveRunner));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        Path runner = tests.resolve(FOCUSED_RUNNER.getFileName());
        CommandResult forwarded = run(List.of(bashExecutable(), runner.toString(),
                "-Dtest=SparkClientConformanceTest#governedWrite", "-Dexample=value"), harness);
        assertEquals(0, forwarded.exitCode(), forwarded.output());
        assertTrue(forwarded.output().contains("VALIDATED")
                        && forwarded.output().contains("ARG=<test>")
                        && forwarded.output().contains("ARG=<-Pspark-integration-tests>")
                        && forwarded.output().contains("ARG=<-pl>")
                        && forwarded.output().contains("ARG=<:stratus-spark-tests>")
                        && forwarded.output().contains("ARG=<-Dmaven.repo.local=/prepared/repository>")
                        && forwarded.output().contains("ARG=<-Dtest=SparkClientConformanceTest#governedWrite>")
                        && forwarded.output().contains("ARG=<-Dexample=value>"),
                forwarded.output());

        CommandResult lifecycleOverride = run(List.of(bashExecutable(), runner.toString(),
                "verify", "-Dtest=SparkClientConformanceTest"), harness);
        assertTrue(lifecycleOverride.exitCode() != 0
                        && lifecycleOverride.output().contains("accepts Maven -D properties only"),
                lifecycleOverride.output());
        assertFalse(lifecycleOverride.output().contains("VALIDATED"), lifecycleOverride.output());

        CommandResult repositoryOverride = run(List.of(bashExecutable(), runner.toString(),
                "-Dtest=SparkClientConformanceTest", "-Dmaven.repo.local=/stale"), harness);
        assertTrue(repositoryOverride.exitCode() != 0
                        && repositoryOverride.output().contains("pins Maven to the repository"),
                repositoryOverride.output());
        CommandResult emptyRepositoryOverride = run(List.of(bashExecutable(), runner.toString(),
                "-Dtest=SparkClientConformanceTest", "-Dmaven.repo.local"), harness);
        assertTrue(emptyRepositoryOverride.exitCode() != 0
                        && emptyRepositoryOverride.output().contains("pins Maven to the repository"),
                emptyRepositoryOverride.output());

        for (String suppressor : List.of(
                "-DskipTests",
                "-Dmaven.test.skip=true",
                "-Dsurefire.skip=true",
                "-Dtest.groups=unit",
                "-Dtest.excludedGroups=spark-integration",
                "-Dsurefire.failIfNoSpecifiedTests=false")) {
            CommandResult suppressed = run(List.of(bashExecutable(), runner.toString(),
                    "-Dtest=SparkClientConformanceTest", suppressor), harness);
            assertTrue(suppressed.exitCode() != 0
                            && suppressed.output().contains(
                                    "must not override focused live-test execution"),
                    suppressor + System.lineSeparator() + suppressed.output());
            assertFalse(suppressed.output().contains("VALIDATED"), suppressed.output());
        }
    }

    private Fixture createArtifactFixture() {
        Path repository = temporaryDirectory.resolve("source-" + System.nanoTime());
        Path harness = repository.resolve("platform/spark/compose-cluster");
        Path localRepository = temporaryDirectory.resolve("maven-" + System.nanoTime());
        write(repository.resolve("pom.xml"), "root\n");
        write(repository.resolve("build-support/stratus-bom/pom.xml"), "bom\n");
        write(repository.resolve("build-support/stratus-build-parent/pom.xml"), "parent\n");
        write(repository.resolve("platform/spark/aws-runtime/pom.xml"), "aws\n");
        write(repository.resolve("jobs/spark/pom.xml"), "jobs\n");
        write(repository.resolve("jobs/spark/src/main/Job.java"), "class Job {}\n");
        write(repository.resolve("platform/spark/aws-runtime/target/"
                + "stratus-iceberg-aws-runtime-1.0-SNAPSHOT-runtime.jar"), "aws artifact\n");
        write(repository.resolve("jobs/spark/target/stratus-spark-jobs-1.0-SNAPSHOT.jar"),
                "jobs artifact\n");

        write(localRepository.resolve(
                "dev/stratus/stratus-reactor/1.0-SNAPSHOT/stratus-reactor-1.0-SNAPSHOT.pom"), "root\n");
        write(localRepository.resolve(
                "dev/stratus/stratus-bom/1.0-SNAPSHOT/stratus-bom-1.0-SNAPSHOT.pom"), "bom\n");
        write(localRepository.resolve("dev/stratus/stratus-build-parent/1.0-SNAPSHOT/"
                + "stratus-build-parent-1.0-SNAPSHOT.pom"), "parent\n");
        write(localRepository.resolve("dev/stratus/stratus-iceberg-aws-runtime/1.0-SNAPSHOT/"
                + "stratus-iceberg-aws-runtime-1.0-SNAPSHOT-runtime.jar"), "aws artifact\n");
        write(localRepository.resolve("dev/stratus/stratus-iceberg-aws-runtime/1.0-SNAPSHOT/"
                + "stratus-iceberg-aws-runtime-1.0-SNAPSHOT.pom"), "aws\n");
        write(localRepository.resolve("dev/stratus/stratus-spark-jobs/1.0-SNAPSHOT/"
                + "stratus-spark-jobs-1.0-SNAPSHOT.jar"), "jobs artifact\n");
        write(localRepository.resolve("dev/stratus/stratus-spark-jobs/1.0-SNAPSHOT/"
                + "stratus-spark-jobs-1.0-SNAPSHOT.pom"), "jobs\n");

        CommandResult init = run(List.of("git", "init", "-q"), repository);
        assertEquals(0, init.exitCode(), init.output());
        return new Fixture(repository, harness, localRepository);
    }

    private CommandResult runArtifactLibrary(Fixture fixture, String operation) {
        String command = "set -euo pipefail; "
                + "REPO_DIR=\"$TEST_REPO\"; HARNESS_DIR=\"$TEST_HARNESS\"; "
                + "STRATUS_MAVEN_LOCAL_REPOSITORY=\"$TEST_MAVEN_REPOSITORY\"; "
                + "log() { :; }; fail() { printf '%s\\n' \"$*\" >&2; exit 1; }; "
                + "repository_maven() { return 97; }; source \"$TEST_LIBRARY\"; " + operation;
        ProcessBuilder builder = new ProcessBuilder(bashExecutable(), "-c", command);
        builder.directory(fixture.repository().toFile());
        builder.environment().put("TEST_REPO", shellPath(fixture.repository()));
        builder.environment().put("TEST_HARNESS", shellPath(fixture.harness()));
        builder.environment().put("TEST_MAVEN_REPOSITORY", shellPath(fixture.localRepository()));
        builder.environment().put("TEST_LIBRARY", shellPath(ARTIFACT_LIBRARY));
        return run(builder);
    }

    private static CommandResult run(List<String> command, Path directory) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        return run(builder);
    }

    private static CommandResult run(ProcessBuilder builder) {
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IllegalStateException("Command timed out: " + builder.command());
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandResult(process.exitValue(), output);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not run " + builder.command(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + builder.command(), exception);
        }
    }

    private static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write " + path, exception);
        }
    }

    private static String bashExecutable() {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return "bash";
        }
        Path programFiles = Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"));
        Path gitBash = programFiles.resolve(Path.of("Git", "bin", "bash.exe"));
        if (Files.isExecutable(gitBash)) {
            return gitBash.toString();
        }
        for (String entry : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            Path candidate = Path.of(entry).resolve("bash.exe");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        throw new IllegalStateException("Git for Windows Bash is required");
    }

    private static String shellPath(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static String normalized(String output) {
        return output.replaceAll("\\s+", " ").trim();
    }

    private record Fixture(Path repository, Path harness, Path localRepository) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
