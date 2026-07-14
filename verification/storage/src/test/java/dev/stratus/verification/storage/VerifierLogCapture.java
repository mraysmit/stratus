package dev.stratus.verification.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
