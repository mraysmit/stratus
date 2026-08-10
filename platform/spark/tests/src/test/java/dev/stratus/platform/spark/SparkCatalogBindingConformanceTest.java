// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proves the Spark binding to Polaris and Ceph RGW (P1-3.2-D1): a client
 * resolves the governed namespaces through the catalog, writes and reads an
 * Iceberg table whose files land in the governed zone location, reaches raw
 * objects over S3A, and is refused when its catalog credential is wrong.
 *
 * <p>Every statement is submitted by a driver in this JVM to the live
 * standalone cluster, and executes against the deployed Polaris release and the
 * deployed Ceph RGW gateways. That is the path a consumer of the platform
 * takes; the earlier form of this suite ran inside the master container and so
 * never crossed the boundary it was meant to be testing
 * ({@code docs/implementation/spark_client_submission.md}).
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.1.0
 */
@Tag("spark-integration")
final class SparkCatalogBindingConformanceTest {

    private static final Duration CLEANUP = Duration.ofMinutes(8);

    private static StratusSparkClient client;

    private String probeTable;
    private String s3aPrefix;

    @BeforeAll
    static void connect() {
        LiveSparkCluster.require();
        client = StratusSparkClient.connect(
                SparkClientConfig.serviceIdentity("stratus-catalog-binding", 17081, 17082));
    }

    @AfterAll
    static void disconnect() {
        if (client != null) {
            client.close();
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void nameTheProbe() {
        probeTable = "stratus.bronze.spark_probe_" + UUID.randomUUID().toString().replace("-", "");
        s3aPrefix = null;
    }

    @AfterEach
    void removeProbeTableAndObjects() {
        // Unconditional: a failed assertion must not leave a table or a raw
        // object behind in a governed zone for the next run to trip over.
        if (probeTable != null) {
            client.sql("DROP TABLE IF EXISTS " + probeTable + " PURGE");
        }
        if (s3aPrefix != null) {
            LiveSparkCluster.removeObjectPrefix(s3aPrefix, CLEANUP);
            // The removal is asserted, not assumed. An unasserted cleanup that
            // silently fails leaves a green suite and a governed bucket filling
            // with probe objects — which is exactly what happened before the
            // client was given the harness CA.
            assertEquals("", LiveSparkCluster.listObjectPrefix(s3aPrefix, CLEANUP),
                    "the S3A probe objects must be gone after cleanup");
        }
    }

    @Test
    void resolvesEveryGovernedNamespaceThroughTheCatalog() {
        List<String> namespaces = client.sql("SHOW NAMESPACES IN stratus").stream()
                .map(row -> row.get(0).toString())
                .toList();

        for (String zone : new String[] {"bronze", "silver", "gold", "platform"}) {
            assertTrue(namespaces.contains(zone),
                    "the " + zone + " namespace must resolve through Polaris for "
                            + client.config().principalId() + ": " + namespaces);
        }
        SparkVerificationLogging.namespacesResolved(namespaces);
    }

    @Test
    void writesAndReadsAnIcebergTableThroughTheCatalogIntoTheGovernedLocation() {
        client.sql("CREATE TABLE " + probeTable + " (id BIGINT, note STRING) USING iceberg");
        client.sql("INSERT INTO " + probeTable + " VALUES (1, 'probe-1'), (2, 'probe-2')");

        List<String> notes = client.sql("SELECT note FROM " + probeTable + " ORDER BY id").stream()
                .map(row -> row.get(0).toString())
                .toList();
        assertEquals(List.of("probe-1", "probe-2"), notes, "every written row must read back");

        // The rows returning is not enough: the files must be in the governed
        // zone bucket, which is what makes this a platform table rather than one
        // that merely happens to work.
        String file = client.scalar("SELECT file_path FROM " + probeTable + ".files LIMIT 1");
        assertTrue(file.startsWith("s3://stratus-bronze/bronze/"),
                "data files must land inside the governed bronze location: " + file);

        SparkVerificationLogging.scenarioPassed("catalog write and read",
                "rows read back and files landed under s3://stratus-bronze/bronze/");
    }

    @Test
    void readsAndWritesRawObjectsOverS3a() {
        // Hadoop's UserGroupInformation asks for the Subject of the current
        // access control context, and a JDK that has removed the security
        // manager refuses: "getSubject is not supported". Java 17 and 21 answer
        // it; 24 onwards do not, and Java 25 rejects the
        // -Djava.security.manager=allow workaround at VM startup. The cluster
        // runs Java 17, so this is a constraint on the client's JVM alone, and
        // it reaches only the S3A connector — every catalog path above uses
        // Iceberg's own S3 client and is unaffected.
        //
        // Stated as an assumption rather than a silent pass: the run says which
        // JVM it was on and why the check did not happen, and the same suite on
        // a supported JDK proves the path for real.
        assumeTrue(Runtime.version().feature() <= 21,
                "The S3A connector needs a JVM that still answers Subject.getSubject; this is Java "
                        + Runtime.version().feature() + ". Run the client suite on Java 17 or 21 "
                        + "to prove the raw-object path.");

        // S3A is the path Spark uses for landing files and event logs, and it is
        // configured separately from Iceberg's S3FileIO — a working catalog says
        // nothing about whether this path works. Writing to a directory rather
        // than a table keeps the catalog out of it entirely.
        String prefix = "spark-conformance/" + UUID.randomUUID();
        s3aPrefix = "stratus-landing/" + prefix;
        String location = "s3a://stratus-landing/" + prefix;

        client.sql("INSERT OVERWRITE DIRECTORY '" + location + "' USING csv SELECT 7 AS id");

        // A headerless CSV names its single column _c0; selecting `id` here
        // would fail on the column name rather than on the storage path.
        assertEquals("7", client.scalar("SELECT _c0 FROM csv.`" + location + "`"),
                "the S3A round trip must return the row that was written");
    }

    @Test
    void refusesToResolveTheCatalogWithAForgedPrincipalSecret() {
        // Positive control first: this client's real credential works right
        // now, so a refusal below is about the credential rather than an
        // unreachable catalog or a broken cluster.
        List<String> real = client.sql("SHOW NAMESPACES IN stratus").stream()
                .map(row -> row.get(0).toString())
                .toList();
        assertTrue(real.contains("bronze"),
                "the real credential must resolve the catalog: " + real);

        // A second catalog on the same session, configured with a secret that
        // was never issued. The endpoint comes from the provider's published
        // settings, not a literal here (ADR-P1-003).
        String forged = "forged";
        client.session().conf().set("spark.sql.catalog." + forged,
                "org.apache.iceberg.spark.SparkCatalog");
        client.session().conf().set("spark.sql.catalog." + forged + ".type", "rest");
        client.session().conf().set("spark.sql.catalog." + forged + ".uri",
                HarnessConnection.polarisCatalogUri());
        client.session().conf().set("spark.sql.catalog." + forged + ".warehouse", "stratus");
        client.session().conf().set("spark.sql.catalog." + forged + ".credential",
                "svc-spark:forged-secret-0000000000000000");

        var refused = assertThrows(Exception.class,
                () -> client.session().sql("SHOW NAMESPACES IN " + forged).collectAsList(),
                "a forged principal secret must be refused");

        // Asserted on its own rather than combined with another condition: an
        // `A && B` here passes whenever either half is false, so it would stop
        // testing the namespaces the moment the other half changed.
        assertFalse(String.valueOf(refused.getMessage()).contains("silver"),
                "a refused catalog must not list governed namespaces: " + refused.getMessage());

        SparkVerificationLogging.negativeConfirmed("forged principal secret", 1,
                "the catalog refused and listed no governed namespace");
    }
}
