// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import java.util.List;
import java.util.Map;

/**
 * The documented definition of the permanent quality result store,
 * {@code platform.quality_check_results} (architecture §5.3): every check
 * run appends one record here, and the table is a permanent append-only
 * audit trail partitioned by zone and by checked-at day. Type names are
 * Iceberg's canonical type strings, kept as literals so this main tree
 * stays free of Iceberg dependencies; the harness bootstrap provisions the
 * table from the same documented shape, and the conformance suite compares
 * the deployed table against this definition.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-06
 * @version 1.0.0
 */
public final class QualityCheckResultsTableDefinition {

    public static final String NAMESPACE = "platform";
    public static final String TABLE_NAME = "quality_check_results";

    /**
     * Marker property recording the append-only contract in the table
     * metadata itself. Iceberg does not enforce append-only writes; the
     * marker makes the contract discoverable by engines and operators.
     */
    public static final String APPEND_ONLY_PROPERTY = "stratus.append-only";

    /** One schema column: Iceberg canonical type name plus nullability. */
    public record Column(String name, String typeName, boolean required) {
    }

    /** One partition field: the source column and the Iceberg transform. */
    public record PartitionField(String sourceColumn, String transform) {
    }

    private static final List<Column> COLUMNS = List.of(
            new Column("run_id", "string", true),
            new Column("dataset_namespace", "string", true),
            new Column("dataset_name", "string", true),
            new Column("zone", "string", true),
            new Column("check_type", "string", true),
            new Column("check_name", "string", true),
            new Column("severity", "string", true),
            new Column("status", "string", true),
            new Column("metric_value", "double", false),
            new Column("threshold", "double", false),
            new Column("failure_detail", "string", false),
            new Column("pipeline_run_id", "string", false),
            new Column("checked_at", "timestamp", true),
            new Column("iceberg_snapshot_id", "long", false));

    private static final List<PartitionField> PARTITION_FIELDS = List.of(
            new PartitionField("zone", "identity"),
            new PartitionField("checked_at", "day"));

    private static final Map<String, String> PROPERTIES = Map.of(
            APPEND_ONLY_PROPERTY, "true",
            "write.format.default", "parquet");

    private QualityCheckResultsTableDefinition() {
    }

    public static List<Column> columns() {
        return COLUMNS;
    }

    public static List<PartitionField> partitionFields() {
        return PARTITION_FIELDS;
    }

    public static Map<String, String> properties() {
        return PROPERTIES;
    }
}
