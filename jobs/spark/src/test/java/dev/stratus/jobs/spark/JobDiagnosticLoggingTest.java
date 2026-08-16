// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.SLF4JServiceProvider;

/** Exercises job diagnostics through the real SLF4J 2.x provider. */
@Tag("unit")
final class JobDiagnosticLoggingTest {

    private TestLogCapture capture;

    @BeforeEach
    void listenToTheRealProvider() {
        capture = new TestLogCapture("dev.stratus.jobs.spark");
    }

    @AfterEach
    void stopListening() {
        capture.close();
    }

    @Test
    void theArgumentContractIsRecordedAtDebugWithoutAnyValues() {
        JobArguments.parse("--targetTable", "stratus.bronze.customers",
                        "--batchId", "2026-08-09", "--sourceSystem", "crm",
                        "--sourceFile", "s3a://stratus-landing/x.csv")
                .rejectUnknown(IngestionJob.ARGUMENTS);

        String message = onlyEventAt(Level.DEBUG).getMessage().getFormattedMessage();
        assertTrue(message.contains("batchId"), message);
        assertFalse(message.contains("2026-08-09"), message);
        assertFalse(message.contains("stratus-landing"), message);
    }

    @Test
    void theSchemaComparisonIsRecordedAtDebug() {
        StructType held = new StructType()
                .add("customer_id", DataTypes.IntegerType)
                .add("email", DataTypes.StringType);

        SchemaDrift.conflicts("stratus.bronze.customers", held,
                held.add("segment", DataTypes.StringType));

        String message = onlyEventAt(Level.DEBUG).getMessage().getFormattedMessage();
        assertTrue(message.contains("stratus.bronze.customers"), message);
        assertTrue(message.contains("added=segment"), message);
        assertTrue(message.contains("conflicts=0"), message);
    }

    @Test
    void theUpsertStatementIsRecordedAtDebug() {
        TransformJob.mergeStatement("stratus.silver.customers", "batch",
                new String[] {"customer_id"}, "updated_at");

        String message = onlyEventAt(Level.DEBUG).getMessage().getFormattedMessage();
        assertTrue(message.contains("MERGE INTO stratus.silver.customers"), message);
        assertTrue(message.contains("s.`updated_at` > t.`updated_at`"), message);
    }

    @Test
    void diagnosticRecordsAreNotEmittedAsOperationalOnes() {
        SchemaDrift.conflicts("stratus.bronze.customers",
                new StructType().add("a", DataTypes.StringType),
                new StructType().add("a", DataTypes.StringType));
        TransformJob.mergeStatement("stratus.silver.customers", "batch",
                new String[] {"a"}, "updated_at");

        assertEquals(2, capture.at(Level.DEBUG).size());
        assertTrue(capture.at(Level.INFO).isEmpty(), capture.at(Level.INFO).toString());
    }

    @Test
    void aJobStillReportsItsOutcomeAtInfo() {
        LineageEvent.emit("TRANSFORM", "stratus.bronze.customers", "stratus.silver.customers",
                "run-1", java.time.Clock.systemUTC());

        List<LogEvent> operational = capture.at(Level.INFO);
        assertEquals(1, operational.size());
        assertTrue(operational.get(0).getMessage().getFormattedMessage()
                .startsWith(LineageEvent.MARKER));
    }

    @Test
    void phaseFailuresAreTimedWithoutLeakingCredentials() {
        String secret = "must-not-reach-the-transcript";

        try {
            JobTelemetry.measure("INGESTION", "probe", "run-1", "stratus.bronze.probe",
                    () -> {
                        throw new IllegalStateException("authorization=Bearer " + secret);
                    });
        } catch (IllegalStateException expected) {
            // The emitted record below is the observable behavior under test.
        }

        String message = onlyEventAt(Level.ERROR).getMessage().getFormattedMessage();
        assertTrue(message.contains("status=FAILED"), message);
        assertTrue(message.contains("durationMs="), message);
        assertTrue(message.contains("<redacted>"), message);
        assertFalse(message.contains(secret), message);
    }

    @Test
    void exactlyOneSlf4jProviderIsActiveAndItIsLog4j2() {
        List<SLF4JServiceProvider> providers =
                ServiceLoader.load(SLF4JServiceProvider.class).stream()
                        .map(ServiceLoader.Provider::get).toList();

        assertEquals(1, providers.size(), "SLF4J providers: " + providers);
        assertTrue(providers.get(0).getClass().getName().startsWith("org.apache.logging.slf4j."),
                providers.get(0).getClass().getName());
        assertTrue(LoggerFactory.getILoggerFactory().getClass().getName().toLowerCase().contains("log4j"),
                LoggerFactory.getILoggerFactory().getClass().getName());
    }

    @Test
    void everyJobDeclaresTheArgumentsItReads() {
        for (Set<String> contract : List.of(IngestionJob.ARGUMENTS, TransformJob.ARGUMENTS,
                MaterialisationJob.ARGUMENTS, QualityCheckJob.ARGUMENTS,
                MaintenanceJob.ARGUMENTS, PromotionGate.ARGUMENTS)) {
            assertFalse(contract.isEmpty(), "a job must declare what it reads");
        }
    }

    private LogEvent onlyEventAt(Level level) {
        List<LogEvent> events = capture.at(level);
        assertEquals(1, events.size(), "expected one " + level + " event, got: " + events);
        return events.get(0);
    }
}
