// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.spark.sql.DataFrameWriterV2;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

/**
 * The write configuration each medallion zone's tables carry, as data.
 *
 * <p>The architecture (§6.4.6) requires these to be stated per table rather
 * than left to engine defaults, because an engine default is a decision nobody
 * made that changes silently on upgrade — and the ones that matter here decide
 * whether a delete rewrites a file or leaves a delete file behind, and whether
 * two concurrent writers can both commit.
 *
 * <p>Shaped after {@code QualityCheckResultsTableDefinition}, which states the
 * platform results table the same way: the definition is the source of truth,
 * and the conformance test reads the deployed table back against it.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
public final class ZoneWriteProperties {

    /** Marks a table whose contract is append-only. Iceberg does not enforce it. */
    public static final String APPEND_ONLY = "stratus.append-only";

    /**
     * Properties Iceberg accepts only when a table is created. Re-asserting
     * these on an existing table is at best a no-op and at worst refused by the
     * catalog, so they are excluded from the per-run alignment below.
     */
    private static final Set<String> CREATE_ONLY = Set.of("format-version");

    private static final Logger LOGGER = Logger.getLogger(ZoneWriteProperties.class.getName());

    private ZoneWriteProperties() {
    }

    /**
     * Bronze: the record of what arrived. Copy-on-write across the board even
     * though bronze does no row-level work — it means an accidental DELETE
     * rewrites files visibly rather than quietly minting delete files on a
     * table whose contract says it is never mutated.
     */
    public static Map<String, String> bronze() {
        var properties = shared();
        // Required for schema evolution on append: without it Spark's analyzer
        // rejects a batch carrying a new column before Iceberg's schema merge
        // is ever reached, and merge-schema alone is silently a no-op.
        properties.put("write.spark.accept-any-schema", "true");
        properties.put(APPEND_ONLY, "true");
        return Collections.unmodifiableMap(properties);
    }

    /** Silver: the conformed zone, upserted copy-on-write by the transform job. */
    public static Map<String, String> silver() {
        var properties = shared();
        // Clusters merge output by partition so each copy-on-write merge
        // rewrites fewer files than it otherwise would.
        properties.put("write.distribution-mode", "hash");
        return Collections.unmodifiableMap(properties);
    }

    /** Gold: rebuilt in full by the materialisation job. */
    public static Map<String, String> gold() {
        return Collections.unmodifiableMap(shared());
    }

    private static LinkedHashMap<String, String> shared() {
        var properties = new LinkedHashMap<String, String>();
        properties.put("format-version", "2");
        properties.put("write.format.default", "parquet");
        properties.put("write.parquet.compression-codec", "zstd");
        properties.put("write.target-file-size-bytes", "536870912");
        properties.put("write.delete.mode", "copy-on-write");
        properties.put("write.update.mode", "copy-on-write");
        properties.put("write.merge.mode", "copy-on-write");
        properties.put("write.delete.isolation-level", "serializable");
        properties.put("write.update.isolation-level", "serializable");
        properties.put("write.merge.isolation-level", "serializable");
        return properties;
    }

    /** Carries the properties onto a create builder. */
    public static DataFrameWriterV2<Row> onCreate(DataFrameWriterV2<Row> writer,
                                                  Map<String, String> properties) {
        DataFrameWriterV2<Row> configured = writer;
        for (Map.Entry<String, String> property : properties.entrySet()) {
            configured = configured.tableProperty(property.getKey(), property.getValue());
        }
        return configured;
    }

    /**
     * Re-states the properties on an existing table.
     *
     * <p>Called on every run, not only at create, because {@code tableProperty}
     * is honoured by CREATE and REPLACE and silently discarded by an append. A
     * job that set them only at create would leave every table made before
     * these properties existed non-conformant for good, and nothing would say
     * so.
     */
    public static void align(SparkSession spark, String table, Map<String, String> properties) {
        var assignments = new StringBuilder();
        properties.forEach((key, value) -> {
            if (CREATE_ONLY.contains(key)) {
                return;
            }
            if (!assignments.isEmpty()) {
                assignments.append(", ");
            }
            // Every key and value here is a compile-time constant of this
            // class, so the statement carries nothing a caller supplied.
            assignments.append('\'').append(key).append("' = '").append(value).append('\'');
        });
        String statement = "ALTER TABLE " + table + " SET TBLPROPERTIES (" + assignments + ")";
        LOGGER.log(Level.FINE, () -> "ZONE PROPERTIES " + statement);
        spark.sql(statement);
    }
}
