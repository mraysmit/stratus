// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Proves a client outside the cluster can use the platform: reach the master,
 * have work executed, resolve the catalog as its own principal, and read and
 * write governed data.
 *
 * <p>Each of these was previously unprovable. The suite ran inside the master
 * container, so it exercised the cluster's own Spark installation and its
 * ambient identity — every question about the path a client takes to the
 * platform went unasked
 * ({@code docs/implementation/spark_client_submission.md}).
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
@Tag("spark-integration")
final class SparkClientConformanceTest {

    private static final String SUFFIX =
            UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String PROBE_TABLE = "stratus.bronze.client_probe_" + SUFFIX;

    @RegisterExtension
    private static final SparkSuiteContext SPARK = new SparkSuiteContext();

    private static StratusSparkClient client;

    @BeforeAll
    static void connect() {
        LiveSparkCluster.require();
        client = SPARK.client(
                SparkClientConfig.serviceIdentity("stratus-client-conformance", 17077, 17078)
                        .withApplicationCores(2));
    }

    @AfterAll
    static void disconnect() {
        if (client != null) {
            try {
                // Asserted, not assumed: a cleanup whose result is discarded
                // leaves probe tables in a governed zone while the suite still
                // reports green.
                client.sql("DROP TABLE IF EXISTS " + PROBE_TABLE + " PURGE");
                assertTrue(client.sql("SHOW TABLES IN stratus.bronze LIKE 'client_probe_"
                                + SUFFIX + "'").isEmpty(),
                        "the probe table must be gone from the governed zone");
            } finally {
                client.close();
            }
        }
    }

    @Test
    void theClientRunsItsWorkOnTheClusterRatherThanInItsOwnJvm() {
        // The discriminator every other assertion here depends on. A session
        // that fell back to local mode answers every query correctly and proves
        // nothing about the platform.
        String applicationId = client.applicationId();

        assertTrue(applicationId.startsWith("app-"),
                "work must run on the standalone cluster, not in this JVM: " + applicationId);
        assertEquals("143", client.scalar(
                        "SELECT count(*) FROM range(0, 1000) WHERE id % 7 = 0"),
                "the cluster must execute a query that needs executors");
    }

    @Test
    void aTwoCoreApplicationPlacesExecutorsOnBothWorkers() {
        client.scalar("SELECT count(*) FROM range(0, 10000, 1, 8)");
        String driverHost = client.session().sparkContext().getConf().get("spark.driver.host");
        List<String> executorHosts = Arrays.stream(
                        client.session().sparkContext().statusTracker().getExecutorInfos())
                .filter(info -> !info.host().equals(driverHost))
                .map(info -> info.host())
                .distinct()
                .toList();

        assertEquals(2, executorHosts.size(),
                "one single-core executor must run on each worker: " + executorHosts);
    }

    @Test
    void warmTinyQueriesStayWithinTheDeveloperLatencyBudget() {
        client.scalar("SELECT sum(id) FROM range(0, 100)");
        List<Long> elapsedMillis = new ArrayList<>();
        for (int attempt = 0; attempt < 5; attempt++) {
            long started = System.nanoTime();
            assertEquals("4950", client.scalar("SELECT sum(id) FROM range(0, 100)"));
            elapsedMillis.add(Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
        Collections.sort(elapsedMillis);
        long median = elapsedMillis.get(elapsedMillis.size() / 2);
        long slowest = elapsedMillis.get(elapsedMillis.size() - 1);
        SparkVerificationLogging.performanceMeasured(
                "warm-tiny-query", elapsedMillis.size(), median, slowest, slowest);

        assertTrue(slowest < Duration.ofSeconds(5).toMillis(),
                "a warm trivial query must not pay application startup; slowestMs=" + slowest);
    }

    @Test
    void theClientResolvesEveryGovernedNamespaceAsItsOwnPrincipal() {
        var namespaces = client.sql("SHOW NAMESPACES IN stratus").stream()
                .map(row -> row.get(0).toString())
                .toList();

        for (String zone : new String[] {"bronze", "silver", "gold", "platform"}) {
            assertTrue(namespaces.contains(zone),
                    zone + " must resolve for " + client.config().principalId() + ": " + namespaces);
        }
    }

    @Test
    void theClientWritesAndReadsGovernedDataThroughTheCatalog() {
        client.sql("CREATE TABLE " + PROBE_TABLE + " (id BIGINT, note STRING) USING iceberg");
        client.sql("INSERT INTO " + PROBE_TABLE + " VALUES (1, 'probe-1'), (2, 'probe-2')");

        assertEquals("2", client.scalar("SELECT count(*) FROM " + PROBE_TABLE),
                "both rows must commit through the catalog");
        assertEquals("probe-2", client.scalar(
                        "SELECT note FROM " + PROBE_TABLE + " WHERE id = 2"),
                "and read back with their values");

        // Rows returning is not enough: the files must be in the governed zone,
        // which is what makes this a platform table rather than one that merely
        // happens to work.
        String location = client.scalar("SELECT file_path FROM " + PROBE_TABLE + ".files LIMIT 1");
        assertTrue(location.startsWith("s3://stratus-bronze/bronze/"),
                "data must land in the governed bronze location: " + location);
    }
}
