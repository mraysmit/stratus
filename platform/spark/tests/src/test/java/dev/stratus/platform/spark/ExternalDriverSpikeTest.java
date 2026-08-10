// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Answers one question before anything is built on the answer: can a Spark
 * driver running in this JVM, outside the container network, submit work to the
 * standalone master and get it executed?
 *
 * <p>Reaching the master is the easy half. The half that decides the design is
 * the reverse path: executors are launched by workers inside the container
 * bridge and must connect back to this process. On Docker Desktop the bridge
 * cannot route to a Windows host address without {@code host.docker.internal},
 * and nothing in this repository has ever configured it.
 *
 * <p>So the test runs a query that cannot be answered by the driver alone. A
 * {@code SELECT 1} is planned and folded locally and would pass against a
 * cluster no executor ever joined, which would prove exactly nothing.
 *
 * <p>This class is temporary. It exists to make the decision in
 * {@code docs/implementation/spark_client_submission.md} on evidence, and is
 * replaced by the real client once the answer is known.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
@Tag("spark-integration")
final class ExternalDriverSpikeTest {

    /**
     * Fixed so the ports a container must reach back to are knowable in
     * advance; the ephemeral defaults cannot be allowed through a firewall.
     */
    private static final String DRIVER_PORT = "17077";
    private static final String BLOCK_MANAGER_PORT = "17078";

    @Test
    void anExternalDriverCanHaveWorkExecutedByTheCluster() {
        LiveSparkCluster.require();

        SparkSession spark = SparkSession.builder()
                .appName("stratus-external-driver-spike")
                .master(System.getProperty("stratus.spark.master",
                        "spark://spark-master.stratus.local:7077"))
                // The address executors dial back on. host.docker.internal is
                // how Docker Desktop exposes the workstation to the bridge; the
                // bind address is every interface because the driver cannot
                // know which one that name resolves to.
                .config("spark.driver.host", "host.docker.internal")
                .config("spark.driver.bindAddress", "0.0.0.0")
                .config("spark.driver.port", DRIVER_PORT)
                .config("spark.blockManager.port", BLOCK_MANAGER_PORT)
                // Small and short: this is a reachability question, and a slow
                // failure is as useful as a fast one only if it still fails.
                .config("spark.cores.max", "1")
                .config("spark.executor.memory", "512m")
                .config("spark.sql.shuffle.partitions", "2")
                .getOrCreate();

        try {
            long rows = spark.range(0, 1000).filter("id % 7 = 0").count();

            assertEquals(143L, rows, "the cluster must have executed the query");

            // The discriminator. Both assertions above pass in local mode —
            // a count is a count, and getExecutorInfos counts the driver — so
            // without this the test would report success against a cluster it
            // never reached. The standalone master issues app-<timestamp>-<n>;
            // local mode issues local-<timestamp>.
            String applicationId = spark.sparkContext().applicationId();
            assertTrue(applicationId.startsWith("app-"),
                    "the work must have run on the standalone cluster, not in this JVM: "
                            + applicationId);
        } finally {
            spark.stop();
        }
    }
}
