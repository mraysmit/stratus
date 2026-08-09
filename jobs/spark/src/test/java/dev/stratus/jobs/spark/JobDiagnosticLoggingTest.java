// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Exercises the diagnostic records the jobs emit, through the real logging
 * backend they emit them on.
 *
 * <p>DEBUG records are the ones an operator reads after a run went wrong, which
 * means they are also the ones nobody notices are missing until that moment.
 * The completion gate (code_style_rules §6, §12) requires both levels to be
 * exercised by tests, and there is no way to exercise a level except by
 * listening to the backend that publishes it.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
@Tag("unit")
final class JobDiagnosticLoggingTest {

    private final List<Logger> listening = new ArrayList<>();
    private final CapturingHandler capture = new CapturingHandler();

    @BeforeEach
    void listenToTheRealBackend() {
        for (Class<?> source : List.of(JobArguments.class, SchemaDrift.class, TransformJob.class)) {
            Logger backend = Logger.getLogger(source.getName());
            backend.setLevel(Level.ALL);
            backend.addHandler(capture);
            listening.add(backend);
        }
    }

    @AfterEach
    void stopListening() {
        for (Logger backend : listening) {
            backend.removeHandler(capture);
            backend.setLevel(null);
        }
        listening.clear();
    }

    @Test
    void theArgumentContractIsRecordedAtDebugWithoutAnyValues() {
        // A value can carry a secret and a name cannot, so the record names the
        // arguments and stops there. This is the rule that keeps a diagnostic
        // record from becoming a credential leak.
        JobArguments.parse("--targetTable", "stratus.bronze.customers",
                        "--batchId", "2026-08-09", "--sourceSystem", "crm",
                        "--sourceFile", "s3a://stratus-landing/x.csv")
                .rejectUnknown(IngestionJob.ARGUMENTS);

        LogRecord record = onlyRecordAt(Level.FINE);
        assertTrue(record.getMessage().contains("batchId"),
                "the argument names must be recorded: " + record.getMessage());
        assertFalse(record.getMessage().contains("2026-08-09"),
                "no argument value may reach a diagnostic record: " + record.getMessage());
        assertFalse(record.getMessage().contains("stratus-landing"),
                "no argument value may reach a diagnostic record: " + record.getMessage());
    }

    @Test
    void anUnknownArgumentStopsTheJobAndIsNotJustLogged() {
        var refused = assertThrows(IllegalArgumentException.class,
                () -> JobArguments.parse("--sourceTable", "stratus.bronze.customers",
                                "--orderBy", "updated_at")
                        .rejectUnknown(TransformJob.ARGUMENTS));

        assertTrue(refused.getMessage().contains("orderBy"),
                "the refusal must name the argument nobody reads: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("sequenceColumn"),
                "and the ones it does: " + refused.getMessage());
        assertTrue(capture.at(Level.FINE).isEmpty(),
                "a refused argument set must not be recorded as accepted");
    }

    @Test
    void theSchemaComparisonIsRecordedAtDebug() {
        StructType held = new StructType()
                .add("customer_id", DataTypes.IntegerType)
                .add("email", DataTypes.StringType);

        SchemaDrift.conflicts("stratus.bronze.customers", held,
                held.add("segment", DataTypes.StringType));

        LogRecord record = onlyRecordAt(Level.FINE);
        assertTrue(record.getMessage().contains("stratus.bronze.customers"), record.getMessage());
        assertTrue(record.getMessage().contains("added=segment"),
                "an added column is what the operator is looking for: " + record.getMessage());
        assertTrue(record.getMessage().contains("conflicts=0"), record.getMessage());
    }

    @Test
    void theUpsertStatementIsRecordedAtDebug() {
        // An operator asking why a row did not update needs the statement that
        // did not update it, not a description of one.
        TransformJob.mergeStatement("stratus.silver.customers", "batch",
                new String[] {"customer_id"}, "updated_at");

        LogRecord record = onlyRecordAt(Level.FINE);
        assertTrue(record.getMessage().contains("MERGE INTO stratus.silver.customers"),
                record.getMessage());
        assertTrue(record.getMessage().contains("s.`updated_at` > t.`updated_at`"),
                record.getMessage());
    }

    @Test
    void diagnosticRecordsAreNotEmittedAsOperationalOnes() {
        // If these were INFO they would be in every transcript, and the
        // lifecycle lines an operator actually reads would be buried in them.
        SchemaDrift.conflicts("stratus.bronze.customers",
                new StructType().add("a", DataTypes.StringType),
                new StructType().add("a", DataTypes.StringType));
        TransformJob.mergeStatement("stratus.silver.customers", "batch",
                new String[] {"a"}, "updated_at");

        assertEquals(2, capture.at(Level.FINE).size(), "both records must be diagnostic");
        assertTrue(capture.at(Level.INFO).isEmpty(),
                "neither may be operational: " + capture.at(Level.INFO));
    }

    @Test
    void aJobStillReportsItsOutcomeAtInfo() {
        // The other half of the gate: an operator reading INFO alone must still
        // see what the run did. Lineage is that record for every job.
        Logger backend = Logger.getLogger(LineageEvent.class.getName());
        backend.setLevel(Level.ALL);
        backend.addHandler(capture);
        listening.add(backend);

        LineageEvent.emit("TRANSFORM", "stratus.bronze.customers", "stratus.silver.customers",
                "run-1", java.time.Clock.systemUTC());

        List<LogRecord> operational = capture.at(Level.INFO);
        assertEquals(1, operational.size(), "the outcome must be recorded once");
        assertTrue(operational.get(0).getMessage().startsWith(LineageEvent.MARKER),
                operational.get(0).getMessage());
    }

    @Test
    void everyJobDeclaresTheArgumentsItReads() {
        // A job with no declared set would accept anything, and rejectUnknown
        // would be a call that could never fail.
        for (Set<String> contract : List.of(IngestionJob.ARGUMENTS, TransformJob.ARGUMENTS,
                MaterialisationJob.ARGUMENTS, QualityCheckJob.ARGUMENTS,
                MaintenanceJob.ARGUMENTS, PromotionGate.ARGUMENTS)) {
            assertFalse(contract.isEmpty(), "a job must declare what it reads");
        }
    }

    private LogRecord onlyRecordAt(Level level) {
        List<LogRecord> records = capture.at(level);
        assertEquals(1, records.size(), "expected one " + level + " record, got: " + records);
        return records.get(0);
    }

    /** Collects records from the real JDK logging backend. */
    private static final class CapturingHandler extends Handler {

        private final List<LogRecord> records = new ArrayList<>();

        List<LogRecord> at(Level level) {
            return records.stream().filter(record -> record.getLevel().equals(level)).toList();
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
