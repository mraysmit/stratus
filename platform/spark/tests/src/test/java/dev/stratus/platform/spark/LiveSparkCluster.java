// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
     * Skips the calling test unless the live opt-in switch is set; under the
     * spark-integration profile the switch is required instead, so the profile
     * can never silently pass by skipping.
     */
    static void require() {
        if (Boolean.getBoolean("spark.integration.required")) {
            assertTrue(Boolean.parseBoolean(System.getenv("STRATUS_SPARK_INTEGRATION")),
                    "STRATUS_SPARK_INTEGRATION=true is required by the selected Maven profile");
        }
        assumeTrue(Boolean.parseBoolean(System.getenv("STRATUS_SPARK_INTEGRATION")),
                "Set STRATUS_SPARK_INTEGRATION=true to run against a live Spark cluster");
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

    /** Runs a command in a cluster container and returns its combined output. */
    static CommandResult exec(String service, Duration timeout, String... command) {
        var argv = new ArrayList<>(List.of("docker", "compose", "--project-name", PROJECT,
                "exec", "-T", service));
        argv.addAll(List.of(command));
        return run(timeout, argv);
    }

    /**
     * Runs Spark SQL against the standalone master from inside the cluster.
     * This is a real submission: the driver starts in the master container and
     * the statement executes on the registered workers.
     */
    static CommandResult sparkSql(String sql, Duration timeout) {
        return exec(MASTER_SERVICE, timeout,
                "/opt/spark/bin/spark-sql",
                "--master", "spark://spark-master.stratus.local:7077",
                "-e", sql);
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

    /** Lists whatever remains under a prefix; blank output means nothing does. */
    static String listObjectPrefix(String bucketAndPrefix, Duration timeout) {
        CommandResult result = rclone(timeout, "ls", "cephrgw:" + bucketAndPrefix);
        // A missing prefix is not an error condition here — it is the state
        // cleanup is trying to reach — and rclone reports it on stderr.
        return result.succeeded() ? result.output().trim() : "";
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
        var builder = new ProcessBuilder(argv).redirectErrorStream(true);
        builder.directory(harnessDirectory().toFile());
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to start: " + String.join(" ", argv), exception);
        }
        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes());
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        "Timed out after " + timeout + ": " + String.join(" ", argv) + "\n" + output);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read output of " + String.join(" ", argv), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for " + String.join(" ", argv), exception);
        }
        return new CommandResult(process.exitValue(), output);
    }

    /** Exit status and combined output of one command run in the cluster. */
    record CommandResult(int exitCode, String output) {

        boolean succeeded() {
            return exitCode == 0;
        }

        String describe() {
            return "exit=" + exitCode + "\n" + output;
        }
    }
}
