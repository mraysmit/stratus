// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared entry point for the live Spark tests: enforces the profile's opt-in
 * switch and runs commands inside the running cluster.
 *
 * <p>Commands execute in the real containers of the real Spark cluster
 * through the harness's own Compose project. Nothing here stands in for
 * Spark, the catalog, or object storage; a test that cannot reach the cluster
 * fails or is skipped, never substituted.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.0.0
 */
final class LiveSparkCluster {

    static final String MASTER_SERVICE = "spark-master";
    private static final String PROJECT = "stratus-spark-local";

    private LiveSparkCluster() {
    }

    /**
     * Whether the live opt-in switch is set. A cleanup callback must consult
     * this before touching the cluster: JUnit runs {@code @AfterAll} even when
     * {@code @BeforeAll} aborted, so a suite that skipped for want of a cluster
     * would otherwise fail on the cleanup rather than skip.
     */
    static boolean enabled() {
        return Boolean.parseBoolean(System.getenv("STRATUS_SPARK_INTEGRATION"))
                || Boolean.getBoolean("stratus.spark.integration");
    }

    /**
     * Skips the calling test unless the live opt-in switch is set; under the
     * spark-integration profile the switch is required instead, so the profile
     * can never silently pass by skipping.
     */
    static void require() {
        if (Boolean.getBoolean("spark.integration.required")) {
            assertTrue(enabled(),
                    "the live-test runner must set STRATUS_SPARK_INTEGRATION=true or "
                            + "-Dstratus.spark.integration=true");
        }
        assumeTrue(enabled(),
                "Set STRATUS_SPARK_INTEGRATION=true or -Dstratus.spark.integration=true "
                        + "to run against a live Spark cluster");
    }

    /** The Polaris catalog endpoint, from the provider's published settings. */
    static String polarisEndpoint() {
        Path connection = harnessDirectory().getParent().getParent()
                .resolve("polaris/compose-service/connection.env");
        try {
            for (String line : Files.readAllLines(connection)) {
                if (line.startsWith("POLARIS_ENDPOINT=")) {
                    return line.substring("POLARIS_ENDPOINT=".length()).trim();
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read " + connection, exception);
        }
        throw new IllegalStateException("POLARIS_ENDPOINT is not published in " + connection);
    }

    /** The Spark harness directory, located from the repository root. */
    static Path harnessDirectory() {
        Path here = Path.of("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path harness = candidate.resolve("platform/spark/compose-cluster");
            if (Files.isDirectory(harness)) {
                return harness;
            }
        }
        throw new IllegalStateException("Could not locate platform/spark/compose-cluster from " + here);
    }

    /** The diagnostic level the suite and the jobs it submits both run at. */
    static String logLevel() {
        return System.getenv().getOrDefault("STRATUS_LOG_LEVEL", "INFO");
    }

    /** Runs a command in a cluster container and returns its combined output. */
    static CommandResult exec(String service, Duration timeout, String... command) {
        // The level is passed into the container, not inherited: compose exec
        // starts a fresh process in the container's own environment, so a job
        // asked to run at DEBUG on the workstation would otherwise log at INFO
        // and every diagnostic record would be discarded before it could reach
        // the transcript.
        var argv = new ArrayList<>(List.of("docker", "compose", "--project-name", PROJECT,
                "exec", "-T", "-e", "STRATUS_LOG_LEVEL=" + logLevel(),
                "-e", "STRATUS_RUN_ID=" + SparkTelemetry.runId(), service));
        argv.addAll(List.of(command));
        return run(timeout, argv);
    }

    /**
     * Removes objects under a prefix through the Ceph harness's own rclone
     * client, so a probe object written over S3A does not stay behind in a
     * governed zone (code_style_rules 7.1). Spark SQL has no delete verb, and
     * the storage owner's client is the honest way to remove what a test put
     * there.
     */
    static CommandResult removeObjectPrefix(String bucketAndPrefix, Duration timeout) {
        return rclone(timeout, "purge", "cephrgw:" + bucketAndPrefix);
    }

    /**
     * Places a landing fixture from the test's own resources into the landing
     * zone, through the storage owner's own client, so the pipeline starts from
     * a real object written the way a source system would deliver one — not
     * from a table Spark made earlier.
     */
    static CommandResult uploadLandingResource(String resourceName, String bucketRelativePath,
                                               Duration timeout) {
        byte[] content;
        try (var stream = LiveSparkCluster.class.getResourceAsStream("/landing/" + resourceName)) {
            if (stream == null) {
                throw new IllegalStateException("Landing fixture not on the classpath: " + resourceName);
            }
            content = stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read landing fixture " + resourceName, exception);
        }
        return writeObject("stratus-landing/" + bucketRelativePath, content, timeout);
    }

    /** Places a landing file whose content the test states inline. */
    static CommandResult writeLandingContent(String bucketRelativePath, String content,
                                             Duration timeout) {
        return writeObject("stratus-landing/" + bucketRelativePath,
                content.getBytes(StandardCharsets.UTF_8), timeout);
    }

    /** Writes a small object to a bucket path the test names. */
    static CommandResult writeObject(String bucketAndKey, String content, Duration timeout) {
        return writeObject(bucketAndKey, content.getBytes(StandardCharsets.UTF_8), timeout);
    }

    /**
     * Writes bytes to a bucket path through the storage owner's client.
     *
     * <p>The content travels base64-encoded and is decoded inside the
     * container. Text pushed through a shell is at the mercy of every layer
     * between here and there — commas, quotes, and newlines all mean something
     * to one of them — and a fixture that arrives subtly different from the file
     * under version control is a test measuring something nobody wrote down.
     */
    private static CommandResult writeObject(String bucketAndKey, byte[] content, Duration timeout) {
        String encoded = Base64.getEncoder().encodeToString(content);
        String container = "/tmp/stratus-" + Integer.toHexString(bucketAndKey.hashCode());
        return run(timeout, List.of("docker", "compose", "--project-name", "stratus-ceph-local",
                "exec", "-T", "s3client",
                "sh", "-c",
                "printf '%s' '" + encoded + "' | base64 -d > " + container
                        + " && rclone --ca-cert /certs/stratus-ca.crt copyto " + container
                        + " cephrgw:" + bucketAndKey));
    }

    /** Lists whatever remains under a prefix; blank output means nothing does. */
    static String listObjectPrefix(String bucketAndPrefix, Duration timeout) {
        CommandResult result = rclone(timeout, "ls", "cephrgw:" + bucketAndPrefix);
        // A missing prefix is not an error condition here — it is the state
        // cleanup is trying to reach — and rclone reports it on stderr.
        return result.succeeded() ? result.output().trim() : "";
    }

    /**
     * Submits a platform job class from the mounted job jar to the standalone
     * master. This is a real submission: the driver runs in the master
     * container and the work executes on the registered workers.
     */
    static CommandResult submitJob(String mainClass, Duration timeout, String... jobArguments) {
        var argv = new ArrayList<>(List.of(
                "/opt/spark/bin/spark-submit",
                "--master", "spark://spark-master.stratus.local:7077",
                "--conf", "spark.executorEnv.STRATUS_LOG_LEVEL=" + logLevel(),
                "--conf", "spark.executorEnv.STRATUS_RUN_ID=" + SparkTelemetry.runId(),
                "--class", mainClass,
                "/opt/stratus/jobs/stratus-spark-jobs.jar"));
        argv.addAll(List.of(jobArguments));
        return exec(MASTER_SERVICE, timeout, argv.toArray(new String[0]));
    }

    private static CommandResult rclone(Duration timeout, String... arguments) {
        // --ca-cert is required: the client validates the Ceph proxy against
        // the harness's disposable CA, which is not in any system trust store.
        // Without it every call fails with "certificate signed by unknown
        // authority" and, if the caller ignores the result, silently does
        // nothing.
        var argv = new ArrayList<>(List.of("docker", "compose", "--project-name", "stratus-ceph-local",
                "exec", "-T", "s3client",
                "rclone", "--ca-cert", "/certs/stratus-ca.crt"));
        argv.addAll(List.of(arguments));
        return run(timeout, argv);
    }

    private static CommandResult run(Duration timeout, List<String> argv) {
        return run(timeout, argv, describe(argv));
    }

    /**
     * A short name for what a command was doing, taken from the argv itself so
     * a transcript reads as a sequence of actions rather than of process
     * invocations.
     */
    private static String describe(List<String> argv) {
        for (String argument : argv) {
            if (argument.endsWith("spark-submit")) {
                int mainClass = argv.indexOf("--class");
                return mainClass < 0 || mainClass + 1 >= argv.size()
                        ? "spark-submit" : "submit " + argv.get(mainClass + 1);
            }
            if (argument.equals("rclone")) {
                return "rclone " + argv.get(argv.indexOf("rclone") + 3);
            }
        }
        return "exec";
    }

    private static CommandResult run(Duration timeout, List<String> argv, String description) {
        SparkVerificationLogging.commandStarted(description, argv, timeout.toMillis());
        String metric = "spark.command." + description.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9.]+", "_");
        try (var observed = SparkTelemetry.start("cluster_command", metric,
                "action=" + SparkLogSanitizer.token(description)
                        + " timeoutMs=" + timeout.toMillis())) {
            var builder = new ProcessBuilder(argv).redirectErrorStream(true);
            builder.directory(harnessDirectory().toFile());
            Process process;
            try {
                process = builder.start();
            } catch (IOException exception) {
                observed.failed(exception, "phase=process_start");
                throw new UncheckedIOException("Failed to start: "
                        + String.join(" ", SparkVerificationLogging.redact(argv)), exception);
            }
            long processStartMillis = observed.elapsedMillis();
            var reader = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "spark-command-output");
                thread.setDaemon(true);
                return thread;
            });
            Future<String> outputFuture = reader.submit(() -> {
                try (var stream = process.getInputStream()) {
                    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
            });
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                    String output = completedOutput(outputFuture);
                    var failure = new IllegalStateException("Timed out after " + timeout + ": "
                            + String.join(" ", SparkVerificationLogging.redact(argv)) + "\n"
                            + SparkLogSanitizer.token(output, 4096));
                    observed.failed(failure, "phase=process_wait processStartMs=" + processStartMillis
                            + " outputBytes=" + output.getBytes(StandardCharsets.UTF_8).length);
                    throw failure;
                }
                String output = outputFuture.get(10, TimeUnit.SECONDS);
                long totalMillis = observed.elapsedMillis();
                SparkVerificationLogging.commandCompleted(
                        description, argv, process.exitValue(), totalMillis, output);
                observed.succeeded("exitCode=" + process.exitValue()
                        + " processStartMs=" + processStartMillis
                        + " outputBytes=" + output.getBytes(StandardCharsets.UTF_8).length);
                return new CommandResult(process.exitValue(), output);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                observed.failed(exception, "phase=process_wait");
                throw new IllegalStateException("Interrupted waiting for "
                        + String.join(" ", SparkVerificationLogging.redact(argv)), exception);
            } catch (ExecutionException | TimeoutException exception) {
                observed.failed(exception, "phase=output_read");
                throw new IllegalStateException("Failed to read output of "
                        + String.join(" ", SparkVerificationLogging.redact(argv)), exception);
            } finally {
                reader.shutdownNow();
            }
        }
    }

    private static String completedOutput(Future<String> output) {
        try {
            return output.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "<interrupted while reading command output>";
        } catch (ExecutionException | TimeoutException exception) {
            return "<command output unavailable: " + exception.getClass().getSimpleName() + ">";
        }
    }

    /** Exit status and combined output of one command run in the cluster. */
    record CommandResult(int exitCode, String output) {

        boolean succeeded() {
            return exitCode == 0;
        }

        String describe() {
            return "exit=" + exitCode + " output=" + SparkLogSanitizer.token(output, 4096);
        }
    }
}
