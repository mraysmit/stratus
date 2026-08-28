// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CatalogPromotionGateJobTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void evaluatorFailsClosedAndHonoursRecordedEvidence() {
        var noEvidence = PromotionEvidenceEvaluator.evaluate("run", "stratus.bronze.table",
                List.of());
        var passed = PromotionEvidenceEvaluator.evaluate("run", "stratus.bronze.table", List.of(
                evidence("row_count", "blocking", "PASSED"),
                evidence("freshness", "warning", "WARNING")));
        var failed = PromotionEvidenceEvaluator.evaluate("run", "stratus.bronze.table", List.of(
                evidence("row_count", "blocking", "FAILED")));
        var overridden = PromotionEvidenceEvaluator.evaluate("run", "stratus.bronze.table",
                List.of(evidence("row_count", "blocking", "FAILED"),
                        evidence("promotion_override", "blocking", "overridden")));

        assertTrue(noEvidence.blocked());
        assertFalse(passed.blocked());
        assertEquals(List.of("freshness"), passed.warningChecks());
        assertTrue(failed.blocked());
        assertEquals(List.of("row_count"), failed.failingChecks());
        assertFalse(overridden.blocked());
    }

    @Test
    void sparkDefaultsAreReducedToOneDirectCatalogBinding() throws IOException {
        Path properties = temporaryDirectory.resolve("spark-defaults.conf");
        Files.writeString(properties, """
                spark.sql.defaultCatalog stratus
                spark.sql.catalog.stratus org.apache.iceberg.spark.SparkCatalog
                spark.sql.catalog.stratus.type rest
                spark.sql.catalog.stratus.uri https://catalog.example/api/catalog
                spark.sql.catalog.stratus.warehouse stratus
                spark.sql.catalog.stratus.rest.auth.type oauth2
                spark.sql.catalog.stratus.credential svc-spark:secret-value
                spark.sql.catalog.stratus.io-impl org.apache.iceberg.aws.s3.S3FileIO
                spark.sql.catalog.stratus.s3.endpoint https://objects.example
                spark.sql.catalog.stratus.s3.path-style-access true
                spark.sql.shuffle.partitions 8
                """);

        var settings = CatalogPromotionGateJob.CatalogSettings.load(properties);

        assertEquals("stratus", settings.catalogName());
        assertEquals("https://catalog.example/api/catalog", settings.properties().get("uri"));
        assertEquals("svc-spark:secret-value", settings.properties().get("credential"));
        assertFalse(settings.properties().containsKey("type"));
        assertFalse(settings.properties().containsKey("sql.shuffle.partitions"));
    }

    @Test
    void qualityTableMustBelongToTheConfiguredCatalog() {
        assertEquals("platform.quality_check_results",
                CatalogPromotionGateJob.tableIdentifier("stratus",
                        "stratus.platform.quality_check_results").toString());
        assertThrows(IllegalArgumentException.class,
                () -> CatalogPromotionGateJob.tableIdentifier("stratus",
                        "other.platform.quality_check_results"));
    }

    @Test
    void catalogStateVerifierAcceptsOnlyExplicitStates() {
        assertTrue(CatalogTableStateJob.ARGUMENTS.contains("expectedState"));
        assertTrue(CatalogTableStateJob.ARGUMENTS.contains("catalogProperties"));
    }

    private static PromotionEvidenceEvaluator.Evidence evidence(
            String name, String severity, String status) {
        return new PromotionEvidenceEvaluator.Evidence(name, severity, status);
    }
}
