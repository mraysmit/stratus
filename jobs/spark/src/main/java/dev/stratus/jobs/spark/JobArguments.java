// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(JobArguments.class);

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

    /**
     * Stops the job when an argument was supplied that this job does not read.
     *
     * <p>Without this, a renamed argument is not a break but a silence: the old
     * name is accepted, ignored, and the job runs with the default the caller
     * was trying to replace. That is how {@code --orderBy} would have survived
     * its rename to {@code --sequenceColumn} — every submission still working,
     * every one of them no longer ordering by anything.
     *
     * <p>The diagnostic record names the arguments and never their values: a
     * value can carry a secret, and a name cannot.
     */
    public JobArguments rejectUnknown(Set<String> known) {
        var unknown = new ArrayList<String>();
        for (String name : values.keySet()) {
            if (!known.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unrecognised argument(s) --"
                    + String.join(", --", unknown) + "; this job reads " + new TreeSet<>(known));
        }
        LOGGER.debug("JOB ARGUMENTS accepted={}", values.keySet());
        return this;
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
