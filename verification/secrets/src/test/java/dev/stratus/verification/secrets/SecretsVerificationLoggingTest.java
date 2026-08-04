// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Exercises the real SLF4J-to-JDK logging backend of the secrets conformance
 * suite and proves the logging API cannot carry secret values: it accepts
 * paths, field names, versions, and statuses only.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-04
 * @version 1.0.0
 */
@Tag("unit")
final class SecretsVerificationLoggingTest {

    private final Logger backend = Logger.getLogger(SecretsVerificationLogging.LOGGER_NAME);
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

    @Test
    void kvEventsRecordPathAndVersionAtInfoAndDetailAtDebug() {
        SecretsVerificationLogging.configure("DEBUG");

        SecretsVerificationLogging.kvEvent("write-confirmed",
                "secret/data/stratus/verify/probe", 2);

        assertEquals(2, capture.records.size());
        assertEquals(Level.INFO, capture.records.getFirst().getLevel());
        assertEquals(Level.FINE, capture.records.getLast().getLevel());
        String text = capturedText();
        assertTrue(text.contains("Secret store event action=write-confirmed"
                + " path=secret/data/stratus/verify/probe version=2"));
    }

    @Test
    void infoConfigurationSuppressesTheDebugDetail() {
        SecretsVerificationLogging.configure("INFO");

        SecretsVerificationLogging.identityValidated("stratus/service-identities/svc-polaris",
                List.of("access_key", "secret_key"));

        assertEquals(1, capture.records.size());
        assertTrue(capturedText().contains("Service identity validated"
                + " path=stratus/service-identities/svc-polaris fields=[access_key, secret_key]"));
    }

    @Test
    void negativeConfirmationsRecordTheStatusOnly() {
        SecretsVerificationLogging.configure("INFO");

        SecretsVerificationLogging.negativeConfirmed("forged-token", 403);

        assertTrue(capturedText().contains("Negative check confirmed check=forged-token httpStatus=403"));
        assertFalse(capturedText().toLowerCase().contains("token="), "no token material may render");
    }

    @Test
    void rejectsAnUnknownConfiguredLevel() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SecretsVerificationLogging.configure("TRACE"));
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
