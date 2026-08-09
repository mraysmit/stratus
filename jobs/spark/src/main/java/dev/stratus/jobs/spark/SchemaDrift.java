// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

/**
 * Compares the schema of an incoming batch against the schema of the table it
 * is about to be appended to.
 *
 * <p>A source system that adds a column has not broken anything, and refusing
 * the batch would stop a pipeline for a change nobody needs to review. A source
 * system that changes a column's type has broken something, and appending it
 * would leave the table holding two meanings for one name. So a new column is
 * allowed through to Iceberg's schema merge, and a changed type is refused.
 *
 * <p>Every conflict is reported, not the first. Iceberg stops at the first
 * incompatible column, which turns a source system that changed five columns
 * into five failed runs; an operator needs to see all five at once.
 *
 * <p>Only top-level fields are compared. Landing extracts are flat, and a
 * nested type that differs at any depth compares unequal here and is refused,
 * which is the safe reading of a structure nobody has reviewed.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
public final class SchemaDrift {

    private static final Logger LOGGER = Logger.getLogger(SchemaDrift.class.getName());

    private SchemaDrift() {
    }

    /**
     * Describes every column whose incoming type cannot be reconciled with the
     * type the table already holds. An empty list means the batch may be
     * appended, whether or not it adds columns.
     */
    public static List<String> conflicts(String table, StructType existing, StructType incoming) {
        // Case-insensitively, because that is how Spark resolves columns by
        // default and how Iceberg's own schema merge will match them. Comparing
        // case-sensitively here would call a header that changed from
        // CUSTOMER_ID to customer_id a new column, and quietly give the table
        // two columns holding the same thing.
        var held = new LinkedHashMap<String, StructField>();
        for (StructField field : existing.fields()) {
            held.put(field.name().toLowerCase(Locale.ROOT), field);
        }

        var conflicts = new ArrayList<String>();
        var added = new ArrayList<String>();
        for (StructField field : incoming.fields()) {
            StructField existingField = held.get(field.name().toLowerCase(Locale.ROOT));
            if (existingField == null) {
                added.add(field.name());
                continue;
            }
            if (!promotable(existingField.dataType(), field.dataType())) {
                conflicts.add(String.format("%s: table holds %s, batch carries %s",
                        existingField.name(), existingField.dataType().simpleString(),
                        field.dataType().simpleString()));
            }
        }

        LOGGER.log(Level.FINE, () -> String.format(
                "SCHEMA COMPARE table=%s tableColumns=%d batchColumns=%d added=%s conflicts=%d",
                table, existing.fields().length, incoming.fields().length,
                added.isEmpty() ? "none" : String.join(",", added), conflicts.size()));
        return List.copyOf(conflicts);
    }

    /**
     * Refuses the batch when any column's type has changed, naming every one of
     * them and the table they conflict with.
     */
    public static void refuseOnConflict(String table, StructType existing, StructType incoming) {
        List<String> conflicts = conflicts(table, existing, incoming);
        if (!conflicts.isEmpty()) {
            throw new Refusal("Incoming batch conflicts with the schema of " + table
                    + "; a column's type cannot change under an append: "
                    + String.join("; ", conflicts));
        }
    }

    /**
     * Whether Iceberg would widen the held type to the incoming one. The set is
     * Iceberg's own: integer to long, float to double, and a decimal to a wider
     * decimal of the same scale. Narrowing is never allowed, because the values
     * already committed would not survive it.
     */
    static boolean promotable(DataType held, DataType incoming) {
        if (held.equals(incoming)) {
            return true;
        }
        if (DataTypes.IntegerType.equals(held) && DataTypes.LongType.equals(incoming)) {
            return true;
        }
        if (DataTypes.FloatType.equals(held) && DataTypes.DoubleType.equals(incoming)) {
            return true;
        }
        return held instanceof DecimalType from && incoming instanceof DecimalType to
                && from.scale() == to.scale() && to.precision() >= from.precision();
    }

    /** Raised when a batch may not be appended because a column's type changed. */
    public static final class Refusal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Refusal(String message) {
            super(message);
        }
    }
}
