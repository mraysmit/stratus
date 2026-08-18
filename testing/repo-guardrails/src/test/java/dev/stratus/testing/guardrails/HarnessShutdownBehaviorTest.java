// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every developer harness must be able to stop even when its {@code .env} is
 * missing or unusable: an operator whose environment file was never generated,
 * was half-written by an interrupted startup, or was deleted by hand still has
 * to be able to release the containers. Each shutdown script therefore tears
 * down by Compose project name alone rather than interpolating the Compose
 * file, and the harness libraries document that promise in a comment. This
 * guardrail is what holds them to it.
 *
 * <p>The real shutdown scripts run against an isolated repository-shaped tree,
 * so the operator's own environment files are never touched. They run with
 * {@code COMPOSE_IMPLEMENTATION} set to a runtime that does not exist, which
 * makes every script stop at its container-runtime selection. That is a
 * deliberate safety property, not a convenience: {@code compose_teardown}
 * carries the production project name, so a test that let a real runtime reach
 * it would tear down the developer's running cluster. The assertion is
 * therefore about <em>where</em> each script stops — past the environment file,
 * at the runtime lookup. A regression that reinstates an environment-file
 * requirement on the teardown path stops earlier and fails here.
 *
 * <p>Whether teardown then succeeds against a live runtime is proven by the
 * harness verification runs, not by this offline guardrail.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-05
 * @version 1.0.0
 */
@Tag("unit")
final class HarnessShutdownBehaviorTest {

    /** Proof the script reached its container-runtime selection. */
    private static final String RUNTIME_REJECTION =
        "COMPOSE_IMPLEMENTATION must be auto, docker, or podman";

    /** The failure every harness library raises when it demands an environment file. */
    private static final String ENVIRONMENT_DEMAND = ".env from .env.template";

    private static final String ABSENT_RUNTIME = "stratus-no-container-runtime";

    private static final List<Harness> HARNESSES = List.of(
        new Harness("ceph", "platform/ceph/compose-cluster",
            "scripts/lifecycle/ceph-compose-shutdown.sh"),
        new Harness("airflow", "platform/airflow/developer",
            "scripts/lifecycle/airflow-compose-shutdown.sh"),
        new Harness("openbao", "platform/openbao/compose-service",
            "scripts/lifecycle/openbao-compose-shutdown.sh"),
        new Harness("polaris", "platform/polaris/compose-service",
            "scripts/lifecycle/polaris-compose-shutdown.sh"),
        new Harness("spark", "platform/spark/compose-cluster",
            "scripts/lifecycle/spark-compose-shutdown.sh"));

    @TempDir
    Path temporaryDirectory;

    @Test
    void everyHarnessTearsDownWithNoEnvironmentFileAtAll() {
        assertReachesTeardown(null);
    }

    /**
     * The libraries promise teardown survives an environment file that is
     * present but unusable, which is the state an interrupted startup leaves
     * behind. A file holding no settings exercises exactly that: sourcing
     * succeeds and defines nothing.
     */
    @Test
    void everyHarnessTearsDownWithAnUnusableEnvironmentFile() {
        assertReachesTeardown("# interrupted startup left no settings behind\n");
    }

    private void assertReachesTeardown(String environmentFile) {
        Path isolatedRepository = isolatedRepository(environmentFile);
        List<String> violations = new ArrayList<>();
        for (Harness harness : HARNESSES) {
            CommandResult result = runShutdown(isolatedRepository, harness);
            if (result.output().contains(ENVIRONMENT_DEMAND)) {
                violations.add(harness.name()
                    + " demands an environment file on the teardown path: " + result.output());
            } else if (!result.output().contains(RUNTIME_REJECTION)) {
                violations.add(harness.name()
                    + " did not reach its container-runtime selection (exit " + result.exitCode()
                    + "): " + result.output());
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    /**
     * A repository-shaped tree holding every harness, because a consumer
     * harness resolves its providers through the repository layout and sources
     * their published connection settings.
     */
    private Path isolatedRepository(String environmentFile) {
        Path root = temporaryDirectory.resolve("repository-" + System.nanoTime());
        try {
            for (Harness harness : HARNESSES) {
                Path source = Repo.root().resolve(harness.directory());
                Path target = root.resolve(harness.directory());
                copyTree(source.resolve("scripts"), target.resolve("scripts"));
                copyIfPresent(source.resolve("compose.yaml"), target.resolve("compose.yaml"));
                copyIfPresent(source.resolve("connection.env"), target.resolve("connection.env"));
                if (environmentFile != null) {
                    Files.writeString(target.resolve(".env"), environmentFile, StandardCharsets.UTF_8);
                }
            }
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the isolated harness tree", e);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void copyIfPresent(Path source, Path target) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static CommandResult runShutdown(Path isolatedRepository, Harness harness) {
        Path script = isolatedRepository.resolve(harness.directory()).resolve(harness.shutdownScript());
        ProcessBuilder builder = new ProcessBuilder(Repo.bashExecutable(), script.toString());
        builder.directory(isolatedRepository.toFile());
        builder.redirectErrorStream(true);
        builder.environment().remove("MSYSTEM");
        builder.environment().put("COMPOSE_IMPLEMENTATION", ABSENT_RUNTIME);
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
                throw new IllegalStateException(harness.name()
                    + " shutdown timed out. Output:\n" + captured.get());
            }
            outputReader.join(TimeUnit.SECONDS.toMillis(2));
            return new CommandResult(process.exitValue(), captured.get());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not execute " + script, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing " + script, e);
        }
    }

    private record Harness(String name, String directory, String shutdownScript) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
