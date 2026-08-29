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
import org.apache.iceberg.Schema;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.expressions.Evaluator;
import org.apache.iceberg.types.Types;
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
                evidence("bronze", "table", "row_count", "blocking", "PASSED"),
                evidence("bronze", "table", "freshness", "warning", "WARNING")));
        var failed = PromotionEvidenceEvaluator.evaluate("run", "stratus.bronze.table", List.of(
                evidence("bronze", "table", "row_count", "blocking", "FAILED")));
        var overridden = PromotionEvidenceEvaluator.evaluate("run", "stratus.bronze.table",
                List.of(evidence("bronze", "table", "row_count", "blocking", "FAILED"),
                        evidence("bronze", "table", "promotion_override", "blocking",
                                "overridden")));

        assertTrue(noEvidence.blocked());
        assertFalse(passed.blocked());
        assertEquals(List.of("freshness"), passed.warningChecks());
        assertTrue(failed.blocked());
        assertEquals(List.of("row_count"), failed.failingChecks());
        assertFalse(overridden.blocked());
    }

    @Test
    void evaluatorIgnoresSameRunEvidenceRecordedForAnotherDataset() {
        var decision = PromotionEvidenceEvaluator.evaluate("shared-run", "stratus.silver.customers",
                List.of(
                        evidence("silver", "customers", "customer_id_unique", "blocking",
                                "PASSED"),
                        evidence("silver", "orders", "order_id_unique", "blocking", "FAILED")));

        assertFalse(decision.blocked(),
                "A failure for another dataset must not block the requested target");
        assertEquals(1, decision.checksExamined(),
                "Only evidence belonging to the requested dataset may be evaluated");
    }

    @Test
    void evaluatorFailsClosedWhenTheRunContainsOnlyAnotherDatasetsEvidence() {
        var decision = PromotionEvidenceEvaluator.evaluate("shared-run", "stratus.silver.customers",
                List.of(evidence("silver", "orders", "order_id_unique", "blocking", "PASSED")));

        assertTrue(decision.blocked(),
                "Unrelated passing evidence must not authorize the requested dataset");
        assertEquals(0, decision.checksExamined());
    }

    @Test
    void directCatalogPredicateSelectsTheRunAndExactDataset() {
        var schema = new Schema(
                Types.NestedField.required(1, "run_id", Types.StringType.get()),
                Types.NestedField.required(2, "dataset_namespace", Types.StringType.get()),
                Types.NestedField.required(3, "dataset_name", Types.StringType.get()));
        var evaluator = new Evaluator(schema.asStruct(),
                CatalogPromotionGateJob.evidenceFilter(
                        "shared-run", "stratus.silver.customers"), true);

        assertTrue(evaluator.eval(record(schema, "shared-run", "silver", "customers")));
        assertFalse(evaluator.eval(record(schema, "shared-run", "silver", "orders")));
        assertFalse(evaluator.eval(record(schema, "other-run", "silver", "customers")));
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
            String namespace, String dataset, String name, String severity, String status) {
        return new PromotionEvidenceEvaluator.Evidence(
                namespace, dataset, name, severity, status);
    }

    private static GenericRecord record(
            Schema schema, String runId, String namespace, String dataset) {
        GenericRecord record = GenericRecord.create(schema);
        record.setField("run_id", runId);
        record.setField("dataset_namespace", namespace);
        record.setField("dataset_name", dataset);
        return record;
    }
}
