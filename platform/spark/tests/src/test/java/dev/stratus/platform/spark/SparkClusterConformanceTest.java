// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proves the reduced developer Spark cluster is serving: the master is up and
 * both workers have registered and report themselves alive with usable
 * capacity (P1-3.1-D1).
 *
 * <p>Registration is the property that matters. A worker container that is
 * running but has not joined the master contributes nothing to a submission,
 * and only the master's own view distinguishes the two.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.0.0
 */
@Tag("spark-integration")
final class SparkClusterConformanceTest {

    private static final Duration SHORT = Duration.ofMinutes(2);
    private static final int EXPECTED_WORKERS = 2;

    @BeforeEach
    void requireLiveCluster() {
        LiveSparkCluster.require();
    }

    @Test
    void masterReportsBothWorkersAliveWithCapacity() {
        var result = LiveSparkCluster.exec(LiveSparkCluster.MASTER_SERVICE, SHORT,
                "curl", "--silent", "--max-time", "20", "http://localhost:8080/json/");
        assertTrue(result.succeeded(), "the master web UI must answer: " + result.describe());

        long alive = Pattern.compile("\"state\"\\s*:\\s*\"ALIVE\"").matcher(result.output()).results().count();
        assertEquals(EXPECTED_WORKERS, alive,
                "both workers must have registered and be ALIVE, master view: " + result.output());

        // A registered worker with no cores would satisfy the count above and
        // still be unable to run anything.
        var cores = Pattern.compile("\"cores\"\\s*:\\s*(\\d+)").matcher(result.output());
        assertTrue(cores.find(), "the master must report core capacity: " + result.output());
        assertTrue(Integer.parseInt(cores.group(1)) > 0,
                "the cluster must offer at least one core, got: " + cores.group(1));

        SparkVerificationLogging.clusterInspected((int) alive, Integer.parseInt(cores.group(1)));
    }

    @Test
    void masterStatusIsAliveRatherThanStandby() {
        var result = LiveSparkCluster.exec(LiveSparkCluster.MASTER_SERVICE, SHORT,
                "curl", "--silent", "--max-time", "20", "http://localhost:8080/json/");

        assertTrue(result.output().contains("\"status\" : \"ALIVE\"")
                        || result.output().contains("\"status\":\"ALIVE\""),
                "the master must be ALIVE rather than in standby or recovery: " + result.output());
    }

    @Test
    void theRuntimeImageCarriesTheLockedIcebergAndS3aArtifacts() {
        // The binding under test in P1-3.2-D1 cannot work without these, and a
        // base image without them fails much later with a confusing
        // ClassNotFoundException inside a job.
        var result = LiveSparkCluster.exec(LiveSparkCluster.MASTER_SERVICE, SHORT,
                "sh", "-c", "ls /opt/spark/jars | grep -E 'iceberg-spark-runtime|iceberg-aws-bundle|hadoop-aws'");
        assertTrue(result.succeeded(), "the runtime jars must be present: " + result.describe());

        for (String artifact : new String[] {
                "iceberg-spark-runtime-4.1_2.13", "iceberg-aws-bundle", "hadoop-aws"}) {
            assertTrue(result.output().contains(artifact),
                    artifact + " must be in the image, found: " + result.output());
        }
    }

    @Test
    void theRuntimeImageCarriesItsArtifactLock() {
        // The lock is what makes the image auditable without rebuilding it.
        var result = LiveSparkCluster.exec(LiveSparkCluster.MASTER_SERVICE, SHORT,
                "cat", "/opt/stratus/artifact-lock.txt");

        assertTrue(result.succeeded(), "the artifact lock must ship in the image: " + result.describe());
        assertTrue(result.output().contains("iceberg-spark-runtime-4.1_2.13-1.11.0.jar"),
                "the lock must name the pinned Iceberg runtime: " + result.output());
        assertTrue(result.output().contains("hadoop-aws-3.4.1.jar"),
                "the lock must name the pinned S3A connector: " + result.output());
    }
}
