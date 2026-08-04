// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.logging.Level;
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
    private static final String LOG_FORMAT = "%1$tFT%1$tT.%1$tL%1$tz %4$s %2$s %5$s%6$s%n";

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", LOG_FORMAT);
    }

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
        String singleLine = value.replace('\r', '_').replace('\n', '_').replace('\t', '_');
        return singleLine.length() <= 256 ? singleLine : singleLine.substring(0, 256);
    }
}
