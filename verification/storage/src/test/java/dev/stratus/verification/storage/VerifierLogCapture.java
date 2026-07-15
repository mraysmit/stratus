// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Implementation of VerifierLogCapture functionality.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 1.0.0
 */
final class VerifierLogCapture implements AutoCloseable {
    private final ArrayList<LogRecord> records = new ArrayList<>();
    private final Map<Handler, Level> previousRootHandlerLevels = new HashMap<>();
    private final Handler captureHandler;
    private final Level previousLoggerLevel;
    private final boolean previousUseParentHandlers;

    VerifierLogCapture() {
        previousLoggerLevel = StorageVerifier.LOGGER.getLevel();
        previousUseParentHandlers = StorageVerifier.LOGGER.getUseParentHandlers();
        for (var handler : Logger.getLogger("").getHandlers()) {
            previousRootHandlerLevels.put(handler, handler.getLevel());
        }
        captureHandler = new Handler() {
            public void publish(LogRecord record) { records.add(record); }
            public void flush() { }
            public void close() { }
        };
        captureHandler.setLevel(Level.ALL);
        StorageVerifier.LOGGER.addHandler(captureHandler);
        StorageVerifier.LOGGER.setUseParentHandlers(false);
    }

    List<LogRecord> records() {
        return List.copyOf(records);
    }

    @Override
    public void close() {
        StorageVerifier.closePersistentLogging();
        StorageVerifier.LOGGER.removeHandler(captureHandler);
        captureHandler.close();
        StorageVerifier.LOGGER.setUseParentHandlers(previousUseParentHandlers);
        StorageVerifier.LOGGER.setLevel(previousLoggerLevel);
        previousRootHandlerLevels.forEach(Handler::setLevel);
    }
}
