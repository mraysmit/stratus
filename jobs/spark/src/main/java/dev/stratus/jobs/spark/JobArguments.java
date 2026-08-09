// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code --name value} arguments a platform job is submitted with.
 *
 * <p>Parsing is strict. A job that silently ignores a misspelled argument
 * writes to the wrong table or skips a check while reporting success, so an
 * unpaired or unrecognised argument stops the job before it touches any data.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
public final class JobArguments {

    private final Map<String, String> values;

    private JobArguments(Map<String, String> values) {
        this.values = values;
    }

    public static JobArguments parse(String... argv) {
        var values = new LinkedHashMap<String, String>();
        for (int index = 0; index < argv.length; index++) {
            String token = argv[index];
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException(
                        "Expected an argument name beginning with --, got: " + token);
            }
            if (index + 1 >= argv.length) {
                throw new IllegalArgumentException("Argument " + token + " has no value");
            }
            String name = token.substring(2);
            if (values.put(name, argv[++index]) != null) {
                throw new IllegalArgumentException("Argument --" + name + " was given twice");
            }
        }
        return new JobArguments(values);
    }

    public String require(String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required argument --" + name + "; given: " + values.keySet());
        }
        return value;
    }

    public Optional<String> optional(String name) {
        String value = values.get(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /** Splits a comma-separated argument, trimming and rejecting empty entries. */
    public String[] requireList(String name) {
        String[] parts = require(name).split(",");
        for (int index = 0; index < parts.length; index++) {
            parts[index] = parts[index].trim();
            if (parts[index].isEmpty()) {
                throw new IllegalArgumentException("Argument --" + name + " has an empty entry");
            }
        }
        return parts;
    }
}
