// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sanitized operational logging for the catalog conformance suite, mirroring
 * the Ceph REST logging conventions: INFO records lifecycle outcomes with
 * stable identifiers, byte counts, and SHA-256 fingerprints; DEBUG adds
 * diagnostic detail such as storage locations and client property keys.
 * Principal and storage secrets never cross this boundary — property keys
 * are logged, property values never are.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-04
 * @version 1.0.0
 */
final class CatalogVerificationLogging {

    static final String LOGGER_NAME = "dev.stratus.verification.catalog";

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);

    static {
        configure(System.getenv().getOrDefault("STRATUS_LOG_LEVEL", "INFO"));
    }

    private CatalogVerificationLogging() {
    }

    static void catalogConnected(CatalogVerifierConfig config) {
        LOGGER.info("Catalog connection established catalog={} polarisUri={} clientId={}",
                safeToken(config.catalogName()), safeToken(config.polarisUri().toString()),
                safeToken(config.clientId()));
        LOGGER.debug("Catalog client properties prepared propertyKeys={}",
                RestCatalogProperties.from(config).keySet().stream().map(CatalogVerificationLogging::safeToken)
                        .sorted().toList());
    }

    static void namespaceValidated(String zone, String location) {
        LOGGER.info("Namespace validated zone={} location={}", safeToken(zone), safeToken(location));
    }

    static void tableEvent(String action, String table, String location, Long snapshotId,
                           int rows, byte[] data) {
        byte[] payload = data == null ? new byte[0] : data;
        LOGGER.info("Table lifecycle action={} table={} snapshotId={} rows={} dataBytes={} dataSha256={}",
                safeToken(action), safeToken(table), snapshotId == null ? "none" : snapshotId,
                rows, payload.length, fingerprint(payload));
        LOGGER.debug("Table lifecycle detail action={} table={} location={} snapshotId={}",
                safeToken(action), safeToken(table), safeToken(location),
                snapshotId == null ? "none" : snapshotId);
    }

    /**
     * Records the deployed table's own attributes: INFO carries the stable
     * identifiers and counts; DEBUG carries the full column list, table
     * properties, and storage location. Table metadata is catalog content,
     * not secret material, so it may cross this boundary in full.
     */
    static void tableAttributesInspected(String table, String location, Long currentSnapshotId,
                                         List<String> columns, String partitionSpec,
                                         Map<String, String> properties) {
        LOGGER.info("Table attributes inspected table={} currentSnapshotId={} columnCount={} partitionSpec={}",
                safeToken(table), currentSnapshotId == null ? "none" : currentSnapshotId,
                columns.size(), safeToken(partitionSpec));
        LOGGER.debug("Table attributes detail table={} location={} columns={} properties={}",
                safeToken(table), safeToken(location),
                safeToken(columns.toString(), 1024),
                safeToken(new TreeMap<>(properties).toString(), 1024));
    }

    static void tableDefinitionValidated(String table, String aspect, String detail) {
        LOGGER.info("Table definition validated table={} aspect={} detail={}",
                safeToken(table), safeToken(aspect), safeToken(detail));
    }

    /**
     * Records an orphan-file scan. INFO carries the object inventory a
     * maintenance decision is taken on; DEBUG carries the orphan locations
     * themselves. Object keys are catalog content, not secret material.
     */
    static void orphanScanCompleted(String table, String location, int scannedFiles,
                                    int referencedFiles, List<String> orphanFiles,
                                    List<String> filesWithinMinimumAge) {
        LOGGER.info("Orphan scan completed table={} scannedFiles={} referencedFiles={} orphanFiles={} withinMinimumAge={}",
                safeToken(table), scannedFiles, referencedFiles,
                orphanFiles.size(), filesWithinMinimumAge.size());
        LOGGER.debug("Orphan scan detail table={} location={} orphans={} withinMinimumAge={}",
                safeToken(table), safeToken(location),
                safeToken(orphanFiles.toString(), 1024),
                safeToken(filesWithinMinimumAge.toString(), 1024));
    }

    /**
     * Records one metadata-driven maintenance decision. INFO carries the
     * verdict with the value and trigger it was taken from, because a verdict
     * without both cannot be reviewed; DEBUG adds which metadata table was
     * read and what the value counted.
     */
    static void maintenanceDecision(String table, String category, String metadataTable,
                                    long observed, long threshold, boolean actionRecommended,
                                    String detail) {
        LOGGER.info("Maintenance decision table={} category={} observed={} threshold={} actionRecommended={}",
                safeToken(table), safeToken(category), observed, threshold, actionRecommended);
        LOGGER.debug("Maintenance decision detail table={} category={} metadataTable={} detail={}",
                safeToken(table), safeToken(category), safeToken(metadataTable), safeToken(detail));
    }

    static void negativeConfirmed(String check, String detail) {
        LOGGER.info("Negative check confirmed check={} detail={}", safeToken(check), safeToken(detail));
    }

    static void configure(String configuredLevel) {
        Level level = switch (configuredLevel.toUpperCase(Locale.ROOT)) {
            case "DEBUG" -> Level.FINE;
            case "INFO" -> Level.INFO;
            default -> throw new IllegalArgumentException("STRATUS_LOG_LEVEL must be INFO or DEBUG");
        };
        Logger.getLogger(LOGGER_NAME).setLevel(level);
        for (var handler : Logger.getLogger("").getHandlers()) {
            handler.setLevel(level);
            handler.setFormatter(new OperationalLevelFormatter());
        }
    }

    /**
     * Renders console records in the operational level vocabulary: the JDK
     * backend names the diagnostic level FINE, but operators configure
     * DEBUG, so transcripts say DEBUG. One line per record with a UTC
     * timestamp; an attached exception renders in full so failure context
     * is never lost.
     */
    static final class OperationalLevelFormatter extends Formatter {

        @Override
        public String format(LogRecord logRecord) {
            String level = Level.FINE.equals(logRecord.getLevel())
                    ? "DEBUG" : logRecord.getLevel().getName();
            var line = new StringBuilder()
                    .append(DateTimeFormatter.ISO_INSTANT.format(
                            logRecord.getInstant().truncatedTo(ChronoUnit.MILLIS)))
                    .append(' ').append(level)
                    .append(' ').append(logRecord.getLoggerName())
                    .append(' ').append(formatMessage(logRecord))
                    .append(System.lineSeparator());
            if (logRecord.getThrown() != null) {
                var stackTrace = new StringWriter();
                logRecord.getThrown().printStackTrace(new PrintWriter(stackTrace));
                line.append(stackTrace);
            }
            return line.toString();
        }
    }

    private static String fingerprint(byte[] data) {
        if (data.length == 0) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for catalog data logging", e);
        }
    }

    private static String safeToken(String value) {
        return safeToken(value, 256);
    }

    private static String safeToken(String value, int maxLength) {
        String singleLine = value.replace('\r', '_').replace('\n', '_').replace('\t', '_');
        return singleLine.length() <= maxLength ? singleLine : singleLine.substring(0, maxLength);
    }
}
