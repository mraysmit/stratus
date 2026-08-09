// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proves the Spark binding to Polaris and Ceph RGW (P1-3.2-D1): the engine
 * resolves the governed namespaces through the catalog, writes and reads an
 * Iceberg table whose files land in the governed zone location, reaches raw
 * objects over S3A, and is refused when its catalog credential is wrong.
 *
 * <p>Every statement is submitted to the live standalone cluster and executes
 * against the deployed Polaris release and the deployed Ceph RGW gateways.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.0.0
 */
@Tag("spark-integration")
final class SparkCatalogBindingConformanceTest {

    private static final Duration JOB = Duration.ofMinutes(8);

    private String probeTable;
    private String s3aPrefix;

    @BeforeEach
    void requireLiveCluster() {
        LiveSparkCluster.require();
        probeTable = "stratus.bronze.spark_probe_" + UUID.randomUUID().toString().replace("-", "");
        s3aPrefix = null;
    }

    @AfterEach
    void removeProbeTableAndObjects() {
        // Unconditional: a failed assertion must not leave a table or a raw
        // object behind in a governed zone for the next run to trip over.
        if (probeTable != null) {
            LiveSparkCluster.sparkSql("DROP TABLE IF EXISTS " + probeTable + " PURGE", JOB);
        }
        if (s3aPrefix != null) {
            LiveSparkCluster.removeObjectPrefix(s3aPrefix, JOB);
            // The removal is asserted, not assumed. An unasserted cleanup that
            // silently fails leaves a green suite and a governed bucket
            // filling with probe objects — which is exactly what happened
            // before the client was given the harness CA.
            assertEquals("", LiveSparkCluster.listObjectPrefix(s3aPrefix, JOB),
                    "the S3A probe objects must be gone after cleanup");
        }
    }

    @Test
    void resolvesEveryGovernedNamespaceThroughTheCatalog() {
        var result = LiveSparkCluster.sparkSql("SHOW NAMESPACES IN stratus", JOB);

        assertTrue(result.succeeded(), "the catalog must resolve: " + result.describe());
        for (String zone : new String[] {"bronze", "silver", "gold", "platform"}) {
            assertTrue(result.output().contains(zone),
                    "the " + zone + " namespace must resolve through Polaris: " + result.output());
        }
    }

    @Test
    void writesAndReadsAnIcebergTableThroughTheCatalogIntoTheGovernedLocation() {
        var created = LiveSparkCluster.sparkSql(
                "CREATE TABLE " + probeTable + " (id BIGINT, note STRING) USING iceberg", JOB);
        assertTrue(created.succeeded(), "table creation must succeed: " + created.describe());

        var inserted = LiveSparkCluster.sparkSql(
                "INSERT INTO " + probeTable + " VALUES (1, 'probe-1'), (2, 'probe-2')", JOB);
        assertTrue(inserted.succeeded(), "the write must commit: " + inserted.describe());

        var read = LiveSparkCluster.sparkSql(
                "SELECT note FROM " + probeTable + " ORDER BY id", JOB);
        assertTrue(read.succeeded(), "the read-back must succeed: " + read.describe());
        assertTrue(read.output().contains("probe-1") && read.output().contains("probe-2"),
                "every written row must read back: " + read.output());

        // The rows returning is not enough: the files must be in the governed
        // zone bucket, which is what makes this a platform table rather than
        // one that merely happens to work.
        var files = LiveSparkCluster.sparkSql(
                "SELECT file_path FROM " + probeTable + ".files", JOB);
        assertTrue(files.succeeded(), "the files metadata table must be queryable: " + files.describe());
        assertTrue(files.output().contains("s3://stratus-bronze/bronze/"),
                "data files must land inside the governed bronze location: " + files.output());
    }

    @Test
    void readsAndWritesRawObjectsOverS3a() {
        // S3A is the path Spark uses for landing files and event logs, and it
        // is configured separately from Iceberg's S3FileIO — a working catalog
        // says nothing about whether this path works. Writing to a directory
        // rather than a table keeps the catalog out of it entirely.
        String prefix = "spark-conformance/" + UUID.randomUUID();
        s3aPrefix = "stratus-landing/" + prefix;
        String location = "s3a://stratus-landing/" + prefix;

        var written = LiveSparkCluster.sparkSql(
                "INSERT OVERWRITE DIRECTORY '" + location + "' USING csv SELECT 7 AS id", JOB);
        assertTrue(written.succeeded(), "an S3A write must succeed: " + written.describe());

        // A headerless CSV names its single column _c0; selecting `id` here
        // would fail on the column name rather than on the storage path. The
        // value is read through a labelled marker because every digit appears
        // somewhere in spark-sql's banner and application id.
        assertEquals("7", LiveSparkCluster.scalar("_c0", "csv.`" + location + "`", JOB),
                "the S3A round trip must return the row that was written");
    }

    @Test
    void refusesToResolveTheCatalogWithAForgedPrincipalSecret() {
        // Positive control first: the configured credential works right now,
        // so a refusal below is about the credential rather than an
        // unreachable catalog or a broken cluster.
        var control = LiveSparkCluster.sparkSql("SHOW NAMESPACES IN stratus", JOB);
        assertTrue(control.succeeded() && control.output().contains("bronze"),
                "the real credential must resolve the catalog: " + control.describe());

        // The catalog endpoint comes from the provider's published connection
        // settings, not a literal here: ADR-P1-003 keeps one place to change.
        var forged = LiveSparkCluster.exec(LiveSparkCluster.MASTER_SERVICE, JOB,
                "/opt/spark/bin/spark-sql",
                "--master", "spark://spark-master.stratus.local:7077",
                "--conf", "spark.sql.catalog.forged=org.apache.iceberg.spark.SparkCatalog",
                "--conf", "spark.sql.catalog.forged.type=rest",
                "--conf", "spark.sql.catalog.forged.uri="
                        + LiveSparkCluster.polarisEndpoint() + "/api/catalog",
                "--conf", "spark.sql.catalog.forged.warehouse=stratus",
                "--conf", "spark.sql.catalog.forged.credential=svc-spark:forged-secret-0000000000000000",
                "-e", "SHOW NAMESPACES IN forged");

        assertFalse(forged.succeeded(),
                "a forged principal secret must be refused, got: " + forged.describe());
        // Asserted on its own rather than combined with another condition: an
        // `A && B` here passes whenever either half is false, so it would stop
        // testing the namespaces the moment the other half changed.
        assertFalse(forged.output().contains("silver"),
                "a refused catalog must not list governed namespaces: " + forged.output());
    }
}
