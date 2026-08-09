// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Covers the comparison that decides whether a landing batch may be appended
 * to the bronze table it is aimed at.
 *
 * <p>These are Spark types compared by Stratus code, with no session, no
 * cluster, and nothing standing in for either — the decision is arithmetic on
 * two schemas, and it is the decision, not the write, that is under test here.
 * Whether Iceberg then evolves the table as this predicts is proven on the live
 * cluster by {@code SparkIncrementalLoadVerificationTest}.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
@Tag("unit")
final class SchemaDriftTest {

    private static final String TABLE = "stratus.bronze.customers";

    private static StructType schema() {
        return new StructType()
                .add("customer_id", DataTypes.IntegerType)
                .add("email", DataTypes.StringType)
                .add("updated_at", DataTypes.TimestampType);
    }

    @Test
    void anIdenticalSchemaHasNoConflicts() {
        assertEquals(List.of(), SchemaDrift.conflicts(TABLE, schema(), schema()));
    }

    @Test
    void anAddedColumnIsNotAConflict() {
        // A source system that starts sending a new field has not changed what
        // any existing column means, and refusing it would stop a pipeline for
        // a change that needs no review.
        StructType incoming = schema().add("segment", DataTypes.StringType);

        assertEquals(List.of(), SchemaDrift.conflicts(TABLE, schema(), incoming));
    }

    @Test
    void aMissingColumnIsNotAConflict() {
        StructType incoming = new StructType()
                .add("customer_id", DataTypes.IntegerType)
                .add("email", DataTypes.StringType);

        assertEquals(List.of(), SchemaDrift.conflicts(TABLE, schema(), incoming));
    }

    @Test
    void aChangedTypeIsAConflictThatNamesTheColumnAndBothTypes() {
        StructType incoming = new StructType()
                .add("customer_id", DataTypes.StringType)
                .add("email", DataTypes.StringType)
                .add("updated_at", DataTypes.TimestampType);

        List<String> conflicts = SchemaDrift.conflicts(TABLE, schema(), incoming);

        assertEquals(1, conflicts.size(), "one column changed: " + conflicts);
        assertTrue(conflicts.get(0).contains("customer_id"), conflicts.get(0));
        assertTrue(conflicts.get(0).contains("int"), "the held type must be named: " + conflicts.get(0));
        assertTrue(conflicts.get(0).contains("string"),
                "the incoming type must be named: " + conflicts.get(0));
    }

    @Test
    void everyConflictIsReportedRatherThanTheFirst() {
        // Iceberg stops at the first incompatible column, which turns a source
        // system that changed three of them into three failed runs. An operator
        // needs all three at once.
        StructType incoming = new StructType()
                .add("customer_id", DataTypes.StringType)
                .add("email", DataTypes.BooleanType)
                .add("updated_at", DataTypes.DateType);

        List<String> conflicts = SchemaDrift.conflicts(TABLE, schema(), incoming);

        assertEquals(3, conflicts.size(), "all three changes must be reported: " + conflicts);
    }

    @Test
    void wideningAColumnIsAllowedAndNarrowingItIsNot() {
        // Iceberg's own promotion set. Widening keeps every value already
        // committed; narrowing cannot, which is why it is refused rather than
        // attempted and rolled back.
        assertTrue(SchemaDrift.promotable(DataTypes.IntegerType, DataTypes.LongType));
        assertTrue(SchemaDrift.promotable(DataTypes.FloatType, DataTypes.DoubleType));
        assertTrue(SchemaDrift.promotable(DataTypes.createDecimalType(5, 2),
                DataTypes.createDecimalType(9, 2)));

        assertFalse(SchemaDrift.promotable(DataTypes.LongType, DataTypes.IntegerType));
        assertFalse(SchemaDrift.promotable(DataTypes.DoubleType, DataTypes.FloatType));
        assertFalse(SchemaDrift.promotable(DataTypes.createDecimalType(9, 2),
                DataTypes.createDecimalType(5, 2)));
        assertFalse(SchemaDrift.promotable(DataTypes.createDecimalType(9, 2),
                DataTypes.createDecimalType(9, 4)),
                "a decimal may widen but may not change scale");
    }

    @Test
    void aColumnRenamedOnlyByCaseIsTheSameColumn() {
        // Spark resolves columns case-insensitively by default. Treating
        // CUSTOMER_ID as new would give the table two columns holding the same
        // thing, and neither of them all the rows.
        StructType incoming = new StructType()
                .add("CUSTOMER_ID", DataTypes.StringType)
                .add("email", DataTypes.StringType);

        List<String> conflicts = SchemaDrift.conflicts(TABLE, schema(), incoming);

        assertEquals(1, conflicts.size(), "the case-changed column must still be compared: " + conflicts);
        assertTrue(conflicts.get(0).startsWith("customer_id"),
                "the conflict names the column as the table holds it: " + conflicts.get(0));
    }

    @Test
    void refusalNamesTheTableAndEveryConflict() {
        StructType incoming = new StructType()
                .add("customer_id", DataTypes.StringType)
                .add("email", DataTypes.BooleanType)
                .add("updated_at", DataTypes.TimestampType);

        SchemaDrift.Refusal refusal = assertThrows(SchemaDrift.Refusal.class,
                () -> SchemaDrift.refuseOnConflict(TABLE, schema(), incoming));

        assertTrue(refusal.getMessage().contains(TABLE), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("customer_id"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("email"), refusal.getMessage());
    }

    @Test
    void aBatchThatOnlyAddsColumnsIsNotRefused() {
        // The negative control. Without it a rule that refused everything would
        // pass every test above and stop the pipeline on every batch.
        SchemaDrift.refuseOnConflict(TABLE, schema(), schema().add("segment", DataTypes.StringType));
    }
}
