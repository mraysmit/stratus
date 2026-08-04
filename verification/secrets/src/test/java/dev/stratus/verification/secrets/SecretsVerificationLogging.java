// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.secrets;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sanitized operational logging for the secrets conformance suite. The API
 * is shaped so secret values cannot pass through it: it accepts paths, field
 * names, versions, and HTTP statuses only. INFO records lifecycle outcomes;
 * DEBUG adds diagnostic detail.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-04
 * @version 1.0.0
 */
final class SecretsVerificationLogging {

    static final String LOGGER_NAME = "dev.stratus.verification.secrets";

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);

    static {
        configure(System.getenv().getOrDefault("STRATUS_LOG_LEVEL", "INFO"));
    }

    private SecretsVerificationLogging() {
    }

    static void storeConnected(SecretStoreVerifierConfig config) {
        LOGGER.info("Secret store connection prepared endpoint={} kvMount={}",
                safeToken(config.endpoint().toString()), safeToken(config.kvMount()));
        LOGGER.debug("Secret store layout serviceIdentityPath={}",
                safeToken(config.serviceIdentityPath()));
    }

    static void kvEvent(String action, String path, int version) {
        LOGGER.info("Secret store event action={} path={} version={}",
                safeToken(action), safeToken(path), version);
        LOGGER.debug("Secret store event detail action={} path={}",
                safeToken(action), safeToken(path));
    }

    static void identityValidated(String path, List<String> fieldNames) {
        LOGGER.info("Service identity validated path={} fields={}",
                safeToken(path), fieldNames.stream().map(SecretsVerificationLogging::safeToken).sorted().toList());
    }

    static void negativeConfirmed(String check, int httpStatus) {
        LOGGER.info("Negative check confirmed check={} httpStatus={}", safeToken(check), httpStatus);
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

    private static String safeToken(String value) {
        String singleLine = value.replace('\r', '_').replace('\n', '_').replace('\t', '_');
        return singleLine.length() <= 256 ? singleLine : singleLine.substring(0, 256);
    }
}
