// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pins the documented definition of {@code platform.quality_check_results}
 * (architecture §5.3): the fourteen columns in order, their types and
 * nullability, the zone and checked-at-day partitioning, and the append-only
 * marker. The live conformance suite compares the deployed table against
 * this same definition, so a drift in either direction fails a build.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-06
 * @version 1.0.0
 */
@Tag("unit")
final class QualityCheckResultsTableDefinitionTest {

    @Test
    void namesTheGovernedPlatformTable() {
        assertEquals("platform", QualityCheckResultsTableDefinition.NAMESPACE);
        assertEquals("quality_check_results", QualityCheckResultsTableDefinition.TABLE_NAME);
    }

    @Test
    void declaresTheFourteenDocumentedColumnsInOrder() {
        var columns = QualityCheckResultsTableDefinition.columns();

        var documented = List.of(
                new QualityCheckResultsTableDefinition.Column("run_id", "string", true),
                new QualityCheckResultsTableDefinition.Column("dataset_namespace", "string", true),
                new QualityCheckResultsTableDefinition.Column("dataset_name", "string", true),
                new QualityCheckResultsTableDefinition.Column("zone", "string", true),
                new QualityCheckResultsTableDefinition.Column("check_type", "string", true),
                new QualityCheckResultsTableDefinition.Column("check_name", "string", true),
                new QualityCheckResultsTableDefinition.Column("severity", "string", true),
                new QualityCheckResultsTableDefinition.Column("status", "string", true),
                new QualityCheckResultsTableDefinition.Column("metric_value", "double", false),
                new QualityCheckResultsTableDefinition.Column("threshold", "double", false),
                new QualityCheckResultsTableDefinition.Column("failure_detail", "string", false),
                new QualityCheckResultsTableDefinition.Column("pipeline_run_id", "string", false),
                new QualityCheckResultsTableDefinition.Column("checked_at", "timestamp", true),
                new QualityCheckResultsTableDefinition.Column("iceberg_snapshot_id", "long", false));

        assertEquals(documented, columns,
                "the definition must match the architecture §5.3 result record schema exactly");
    }

    @Test
    void columnNamesAreDistinct() {
        var names = QualityCheckResultsTableDefinition.columns().stream()
                .map(QualityCheckResultsTableDefinition.Column::name)
                .collect(Collectors.toSet());

        assertEquals(QualityCheckResultsTableDefinition.columns().size(), names.size(),
                "duplicate column names would make the schema ambiguous");
    }

    @Test
    void partitionsByZoneIdentityAndCheckedAtDay() {
        assertEquals(List.of(
                        new QualityCheckResultsTableDefinition.PartitionField("zone", "identity"),
                        new QualityCheckResultsTableDefinition.PartitionField("checked_at", "day")),
                QualityCheckResultsTableDefinition.partitionFields(),
                "the table must be partitioned by zone and by checked_at day for query performance");
    }

    @Test
    void partitionSourceColumnsExistInTheSchema() {
        Set<String> columnNames = QualityCheckResultsTableDefinition.columns().stream()
                .map(QualityCheckResultsTableDefinition.Column::name)
                .collect(Collectors.toSet());

        for (var partitionField : QualityCheckResultsTableDefinition.partitionFields()) {
            assertTrue(columnNames.contains(partitionField.sourceColumn()),
                    "partition source column " + partitionField.sourceColumn()
                            + " must be a declared schema column");
        }
    }

    @Test
    void marksTheTableAppendOnly() {
        assertEquals("true",
                QualityCheckResultsTableDefinition.properties()
                        .get(QualityCheckResultsTableDefinition.APPEND_ONLY_PROPERTY),
                "results are append-only: a permanent audit trail (architecture §5.3)");
    }
}
