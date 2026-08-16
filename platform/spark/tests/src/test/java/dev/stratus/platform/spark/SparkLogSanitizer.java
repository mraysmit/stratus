// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Central one-line formatting and credential redaction for Spark transcripts. */
final class SparkLogSanitizer {

    private static final String REDACTED = "<redacted>";
    private static final Set<String> SECRET_OPTIONS = Set.of(
            "--password", "--secret", "--token", "--credential", "--authorization",
            "--api-key", "--apikey", "--access-key", "--private-key");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)((?:secret|password|token|credential|authorization|api[-_.]?key|"
                    + "access[-_.]?key|private[-_.]?key)\\s*[:=]\\s*)([^,;\\s}\\]]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^,;\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern URI_USER_INFO = Pattern.compile("(://)[^/@\\s]+@");

    private SparkLogSanitizer() {
    }

    static List<String> arguments(List<String> argv) {
        var sanitized = new ArrayList<String>(argv.size());
        boolean redactNext = false;
        for (String argument : argv) {
            if (redactNext) {
                sanitized.add(REDACTED);
                redactNext = false;
                continue;
            }
            String lower = argument.toLowerCase(Locale.ROOT);
            if (SECRET_OPTIONS.contains(lower)) {
                sanitized.add(argument);
                redactNext = true;
            } else {
                sanitized.add(token(argument, 512));
            }
        }
        return List.copyOf(sanitized);
    }

    static String token(String value) {
        return token(value, 256);
    }

    static String token(String value, int maximumLength) {
        if (value == null) {
            return "null";
        }
        String redacted = BEARER.matcher(value).replaceAll("Bearer " + REDACTED);
        redacted = KEY_VALUE_SECRET.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = URI_USER_INFO.matcher(redacted).replaceAll("$1" + REDACTED + "@");
        String singleLine = redacted.replace('\r', '_').replace('\n', '_').replace('\t', '_');
        return singleLine.length() <= maximumLength
                ? singleLine : singleLine.substring(0, maximumLength);
    }

    static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
