// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real SLF4J-to-JDK logging backend of the catalog conformance
 * suite without replacing it, and proves that principal and storage secrets
 * cannot enter log records at either level.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-04
 * @version 1.0.0
 */
@Tag("unit")
final class CatalogVerificationLoggingTest {

    private final Logger backend = Logger.getLogger(CatalogVerificationLogging.LOGGER_NAME);
    private final Logger root = Logger.getLogger("");
    private final CapturingHandler capture = new CapturingHandler();
    private final Map<Handler, Level> rootHandlerLevels = new IdentityHashMap<>();
    private Level originalLevel;
    private boolean originalUseParentHandlers;

    @BeforeEach
    void captureTheRealBackend() {
        originalLevel = backend.getLevel();
        originalUseParentHandlers = backend.getUseParentHandlers();
        for (Handler handler : root.getHandlers()) {
            rootHandlerLevels.put(handler, handler.getLevel());
        }
        capture.setLevel(Level.ALL);
        backend.setUseParentHandlers(false);
        backend.addHandler(capture);
    }

    @AfterEach
    void restoreTheBackend() {
        backend.removeHandler(capture);
        backend.setUseParentHandlers(originalUseParentHandlers);
        backend.setLevel(originalLevel);
        rootHandlerLevels.forEach(Handler::setLevel);
    }

    private static CatalogVerifierConfig config() {
        return CatalogVerifierConfig.from(Map.of(
                "STRATUS_POLARIS_URI", "https://polaris.stratus.local:8181/api/catalog",
                "STRATUS_POLARIS_CLIENT_ID", "stratus-root",
                "STRATUS_POLARIS_CLIENT_SECRET", "polaris-secret-value",
                "STRATUS_POLARIS_CATALOG", "stratus",
                "CEPH_RGW_ENDPOINT", "https://object-store.stratus.local:8443",
                "CEPH_RGW_ACCESS_KEY", "svc-polaris-abc123",
                "CEPH_RGW_SECRET_KEY", "storage-secret-value"));
    }

    @Test
    void connectionRecordsIdentifyTheCatalogWithoutEitherSecret() {
        CatalogVerificationLogging.configure("DEBUG");

        CatalogVerificationLogging.catalogConnected(config());

        String text = capturedText();
        assertTrue(text.contains("Catalog connection established catalog=stratus"
                + " polarisUri=https://polaris.stratus.local:8181/api/catalog clientId=stratus-root"));
        assertTrue(text.contains("Catalog client properties prepared propertyKeys="),
                "DEBUG must record the property keys");
        assertTrue(text.contains("s3.secret-access-key"),
                "property KEYS are not secrets and must be listed");
        assertFalse(text.contains("polaris-secret-value"), "the principal secret must never be logged");
        assertFalse(text.contains("storage-secret-value"), "the storage secret must never be logged");
    }

    @Test
    void tableLifecycleRecordsCarryCountsAndFingerprintAtInfo() {
        CatalogVerificationLogging.configure("INFO");
        byte[] data = "probe-parquet-bytes".getBytes(StandardCharsets.UTF_8);

        CatalogVerificationLogging.tableEvent("append-confirmed", "platform.conformance_probe_x",
                "s3://stratus-platform/platform/conformance_probe_x", 42L, 3, data);

        assertEquals(1, capture.records.size(), "INFO configuration must suppress DEBUG records");
        String text = capturedText();
        assertTrue(text.contains("Table lifecycle action=append-confirmed"
                + " table=platform.conformance_probe_x"));
        assertTrue(text.contains("snapshotId=42 rows=3 dataBytes=" + data.length + " dataSha256="));
    }

    @Test
    void debugConfigurationAddsTheDiagnosticLocationRecord() {
        CatalogVerificationLogging.configure("DEBUG");

        CatalogVerificationLogging.tableEvent("create-confirmed", "platform.t",
                "s3://stratus-platform/platform/t", null, 0, null);

        assertEquals(2, capture.records.size(), "DEBUG must add the diagnostic record");
        assertEquals(Level.INFO, capture.records.getFirst().getLevel());
        assertEquals(Level.FINE, capture.records.getLast().getLevel());
        assertTrue(capturedText().contains("location=s3://stratus-platform/platform/t"));
        assertTrue(capturedText().contains("snapshotId=none"));
    }

    @Test
    void orphanScanRecordsTheInventoryAtInfoAndTheLocationsAtDebug() {
        CatalogVerificationLogging.configure("INFO");

        CatalogVerificationLogging.orphanScanCompleted("platform.orphan_probe_x",
                "s3://stratus-platform/platform/orphan_probe_x", 9, 8,
                List.of("s3://stratus-platform/platform/orphan_probe_x/data/abandoned.parquet"),
                List.of());

        assertEquals(1, capture.records.size(), "INFO configuration must suppress DEBUG records");
        String infoText = capturedText();
        assertTrue(infoText.contains("Orphan scan completed table=platform.orphan_probe_x"
                + " scannedFiles=9 referencedFiles=8 orphanFiles=1 withinMinimumAge=0"));
        assertFalse(infoText.contains("abandoned.parquet"),
                "the inventory is the INFO record; the locations belong at DEBUG");

        capture.records.clear();
        CatalogVerificationLogging.configure("DEBUG");

        CatalogVerificationLogging.orphanScanCompleted("platform.orphan_probe_x",
                "s3://stratus-platform/platform/orphan_probe_x", 9, 8,
                List.of("s3://stratus-platform/platform/orphan_probe_x/data/abandoned.parquet"),
                List.of("s3://stratus-platform/platform/orphan_probe_x/data/in-flight.parquet"));

        assertEquals(2, capture.records.size(), "DEBUG must add the location record");
        assertEquals(Level.FINE, capture.records.getLast().getLevel());
        String debugText = capturedText();
        assertTrue(debugText.contains("abandoned.parquet"), "DEBUG must name the orphans");
        assertTrue(debugText.contains("in-flight.parquet"),
                "DEBUG must also name what the age threshold withheld");
    }

    @Test
    void maintenanceDecisionRecordsCarryTheValueAndTriggerAtInfo() {
        CatalogVerificationLogging.configure("INFO");

        CatalogVerificationLogging.maintenanceDecision("platform.maintenance_probe_x",
                "compact-data-files", "files", 5, 3, true, "data files smaller than 1000000 bytes");

        assertEquals(1, capture.records.size(), "INFO configuration must suppress DEBUG records");
        String text = capturedText();
        assertTrue(text.contains("Maintenance decision table=platform.maintenance_probe_x"
                + " category=compact-data-files observed=5 threshold=3 actionRecommended=true"),
                "a verdict without its value and trigger cannot be reviewed, got: " + text);

        capture.records.clear();
        CatalogVerificationLogging.configure("DEBUG");

        CatalogVerificationLogging.maintenanceDecision("platform.maintenance_probe_x",
                "compact-data-files", "files", 5, 3, true, "data files smaller than 1000000 bytes");

        assertEquals(2, capture.records.size(), "DEBUG must add the metadata-table record");
        assertEquals(Level.FINE, capture.records.getLast().getLevel());
        assertTrue(capturedText().contains("metadataTable=files"),
                "DEBUG must name the metadata table the decision was read from");
    }

    @Test
    void tableDefinitionRecordsIdentifyTheValidatedAspectAtInfo() {
        CatalogVerificationLogging.configure("INFO");

        CatalogVerificationLogging.tableDefinitionValidated("platform.quality_check_results",
                "partitioning", "[zone, checked_at_day]");

        assertEquals(1, capture.records.size(), "the definition record is a single INFO line");
        assertTrue(capturedText().contains("Table definition validated"
                + " table=platform.quality_check_results aspect=partitioning detail=[zone, checked_at_day]"));
    }

    @Test
    void tableAttributeRecordsCarryTheFullSchemaAndPropertiesAtDebug() {
        CatalogVerificationLogging.configure("DEBUG");

        CatalogVerificationLogging.tableAttributesInspected(
                "platform.quality_check_results",
                "s3://stratus-platform/platform/quality_check_results",
                42L,
                List.of("run_id string required", "checked_at timestamp required"),
                "[zone identity, checked_at_day day]",
                Map.of("stratus.append-only", "true"));

        String text = capturedText();
        assertTrue(text.contains("Table attributes inspected table=platform.quality_check_results"
                        + " currentSnapshotId=42 columnCount=2"),
                "INFO must carry the stable identifiers and counts");
        assertTrue(text.contains("partitionSpec=[zone identity, checked_at_day day]"));
        assertTrue(text.contains("columns=[run_id string required, checked_at timestamp required]"),
                "DEBUG must carry the full column list");
        assertTrue(text.contains("properties={stratus.append-only=true}"),
                "DEBUG must carry the table properties");
        assertTrue(text.contains("location=s3://stratus-platform/platform/quality_check_results"));
    }

    @Test
    void tableAttributeDebugDetailIsSuppressedAtInfo() {
        CatalogVerificationLogging.configure("INFO");

        CatalogVerificationLogging.tableAttributesInspected("platform.t", "s3://x", null,
                List.of("id long required"), "[]", Map.of());

        assertEquals(1, capture.records.size(), "INFO configuration must suppress the DEBUG detail");
        assertTrue(capturedText().contains("currentSnapshotId=none"));
    }

    @Test
    void rendersDiagnosticRecordsWithTheDebugLevelNameNotTheJulName() {
        var formatter = new CatalogVerificationLogging.OperationalLevelFormatter();

        var diagnostic = new LogRecord(Level.FINE, "Table lifecycle detail action=x");
        diagnostic.setLoggerName(CatalogVerificationLogging.LOGGER_NAME);
        var operational = new LogRecord(Level.INFO, "Table lifecycle action=x");
        operational.setLoggerName(CatalogVerificationLogging.LOGGER_NAME);

        assertTrue(formatter.format(diagnostic).contains(" DEBUG "),
                "operators configure DEBUG, so transcripts must say DEBUG, not JUL's FINE");
        assertFalse(formatter.format(diagnostic).contains("FINE"),
                "the JUL level name must not leak into the rendered line");
        assertTrue(formatter.format(operational).contains(" INFO "));
    }

    @Test
    void rendersARecordThrowableSoFailureContextIsNotLost() {
        var formatter = new CatalogVerificationLogging.OperationalLevelFormatter();
        var failing = new LogRecord(Level.INFO, "Cleanup reported a failure");
        failing.setLoggerName(CatalogVerificationLogging.LOGGER_NAME);
        failing.setThrown(new IllegalStateException("probe-cleanup-detail"));

        String rendered = formatter.format(failing);
        assertTrue(rendered.contains("IllegalStateException"),
                "a record's attached exception must render");
        assertTrue(rendered.contains("probe-cleanup-detail"),
                "the exception message must render");
    }

    @Test
    void rejectsAnUnknownConfiguredLevel() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CatalogVerificationLogging.configure("TRACE"));
        assertTrue(failure.getMessage().contains("STRATUS_LOG_LEVEL"));
    }

    /** slf4j-jdk14 substitutes parameters before handing records to JUL. */
    private String capturedText() {
        var rendered = new StringBuilder();
        for (LogRecord logRecord : capture.records) {
            rendered.append(logRecord.getLevel()).append(' ')
                    .append(logRecord.getMessage()).append('\n');
        }
        return rendered.toString();
    }

    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord logRecord) {
            records.add(logRecord);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
