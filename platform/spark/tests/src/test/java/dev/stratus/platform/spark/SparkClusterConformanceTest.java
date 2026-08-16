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
                "both workers must have registered and be ALIVE, master view: " + result.describe());

        // A registered worker with no cores would satisfy the count above and
        // still be unable to run anything.
        var cores = Pattern.compile("\"cores\"\\s*:\\s*(\\d+)").matcher(result.output());
        assertTrue(cores.find(), "the master must report core capacity: " + result.describe());
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
                "the master must be ALIVE rather than in standby or recovery: " + result.describe());
    }

    @Test
    void theRuntimeImageCarriesTheLockedIcebergAndS3aArtifacts() {
        // The binding under test in P1-3.2-D1 cannot work without these, and a
        // base image without them fails much later with a confusing
        // ClassNotFoundException inside a job.
        var result = LiveSparkCluster.exec(LiveSparkCluster.MASTER_SERVICE, SHORT,
                "sh", "-c", "ls /opt/spark/jars | grep -E 'stratus-iceberg-aws-runtime|hadoop-aws'");
        assertTrue(result.succeeded(), "the runtime jars must be present: " + result.describe());

        for (String artifact : new String[] {
                "stratus-iceberg-aws-runtime", "hadoop-aws"}) {
            assertTrue(result.output().contains(artifact),
                    artifact + " must be in the image, found: " + result.describe());
        }
    }

    @Test
    void hadoopAndBothAwsClientsHaveOneUnambiguousClasspath() {
        var hadoop = LiveSparkCluster.exec(LiveSparkCluster.MASTER_SERVICE, SHORT,
                "sh", "-c", "ls -1 /opt/spark/jars/hadoop-client-api-*.jar "
                        + "/opt/spark/jars/hadoop-client-runtime-*.jar /opt/spark/jars/hadoop-aws-*.jar");
        assertTrue(hadoop.succeeded(), "the Hadoop client set must be inspectable: " + hadoop.describe());
        assertTrue(hadoop.output().contains("hadoop-client-api-3.4.3.jar")
                        && hadoop.output().contains("hadoop-client-runtime-3.4.3.jar")
                        && hadoop.output().contains("hadoop-aws-3.4.3.jar"),
                "every Hadoop client artifact must be 3.4.3: " + hadoop.output());
        assertTrue(!hadoop.output().contains("3.4.2") && !hadoop.output().contains("3.4.1"),
                "no older Hadoop client may remain on the runtime classpath: " + hadoop.output());

        // Opening every archive through `jar tf` starts one JVM per JAR. The
        // runtime image currently has close to 300 archives, which made this
        // metadata assertion take more than three minutes on Docker Desktop.
        // The image already includes Python; one process can inspect the same
        // ZIP central directories without changing what is proved.
        String ownerScan = "import glob, os, zipfile\n"
                + "name = 'software/amazon/awssdk/services/s3/S3Client.class'\n"
                + "def owns(path):\n"
                + "    with zipfile.ZipFile(path) as archive:\n"
                + "        return name in archive.namelist()\n"
                + "print('\\n'.join(os.path.basename(path) for path in "
                + "glob.glob('/opt/spark/jars/*.jar') if owns(path)))";
        var sdkOwners = LiveSparkCluster.exec(LiveSparkCluster.MASTER_SERVICE, SHORT,
                "python3", "-c", ownerScan);
        assertTrue(sdkOwners.succeeded(), "AWS SDK ownership must be inspectable: " + sdkOwners.describe());
        assertEquals(1, sdkOwners.output().lines().filter(line -> !line.isBlank()).count(),
                "only Hadoop's SDK may own the unrelocated AWS class: " + sdkOwners.output());
        assertTrue(sdkOwners.output().contains("bundle-2.35.4.jar"),
                "Hadoop 3.4.3's supported SDK must own the public AWS package: " + sdkOwners.output());

        var iceberg = LiveSparkCluster.exec(LiveSparkCluster.MASTER_SERVICE, SHORT,
                "sh", "-c", "entries=$(mktemp); "
                        + "jar tf /opt/spark/jars/stratus-iceberg-aws-runtime-*.jar >\"$entries\"; "
                        + "grep -E 'org/apache/iceberg/aws/s3/S3FileIO.class|"
                        + "dev/stratus/thirdparty/iceberg/amazon/awssdk/services/s3/S3Client.class' \"$entries\"; "
                        + "if grep -q '^software/amazon/' \"$entries\"; then "
                        + "echo 'unrelocated Amazon class leaked from Iceberg'; rm -f \"$entries\"; exit 1; fi; "
                        + "rm -f \"$entries\"");
        assertTrue(iceberg.succeeded()
                        && iceberg.output().contains("org/apache/iceberg/aws/s3/S3FileIO.class")
                        && iceberg.output().contains(
                                "dev/stratus/thirdparty/iceberg/amazon/awssdk/services/s3/S3Client.class"),
                "Iceberg must carry only relocated Amazon libraries: " + iceberg.output());
    }

    @Test
    void theRuntimeImageCarriesItsArtifactLock() {
        // The lock is what makes the image auditable without rebuilding it.
        var result = LiveSparkCluster.exec(LiveSparkCluster.MASTER_SERVICE, SHORT,
                "cat", "/opt/stratus/artifact-lock.txt");

        assertTrue(result.succeeded(), "the artifact lock must ship in the image: " + result.describe());
        assertTrue(result.output().contains("stratus-iceberg-aws-runtime-1.0-SNAPSHOT-runtime.jar"),
                "the lock must name the isolated Iceberg runtime: " + result.describe());
        assertTrue(result.output().contains("hadoop-aws-3.4.3.jar"),
                "the lock must name the pinned S3A connector: " + result.describe());
    }
}
