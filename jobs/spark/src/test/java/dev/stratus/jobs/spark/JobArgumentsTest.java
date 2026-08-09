// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Offline checks on job argument parsing. These run without Spark because the
 * failure they guard against — a job accepting a malformed invocation and
 * writing to the wrong place — happens before any cluster is involved.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
@Tag("unit")
final class JobArgumentsTest {

    @Test
    void readsNamedValues() {
        JobArguments arguments = JobArguments.parse(
                "--targetTable", "stratus.bronze.customers", "--sourceSystem", "crm");

        assertEquals("stratus.bronze.customers", arguments.require("targetTable"));
        assertEquals(Optional.of("crm"), arguments.optional("sourceSystem"));
        assertEquals(Optional.empty(), arguments.optional("runId"));
    }

    @Test
    void refusesAnArgumentWithNoValue() {
        // A trailing flag usually means a shell expansion produced nothing.
        // Silently ignoring it would run the job against a default nobody
        // chose.
        var failure = assertThrows(IllegalArgumentException.class,
                () -> JobArguments.parse("--targetTable"));

        assertTrue(failure.getMessage().contains("--targetTable"), failure.getMessage());
    }

    @Test
    void refusesAPositionalArgument() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> JobArguments.parse("stratus.bronze.customers"));

        assertTrue(failure.getMessage().contains("--"), failure.getMessage());
    }

    @Test
    void refusesARepeatedArgument() {
        // Two values for one name means the caller believes something that is
        // not true about which one applies.
        var failure = assertThrows(IllegalArgumentException.class,
                () -> JobArguments.parse("--targetTable", "a", "--targetTable", "b"));

        assertTrue(failure.getMessage().contains("twice"), failure.getMessage());
    }

    @Test
    void requireNamesTheMissingArgumentAndWhatWasGiven() {
        JobArguments arguments = JobArguments.parse("--sourceFile", "s3a://bucket/f.csv");

        var failure = assertThrows(IllegalArgumentException.class, () -> arguments.require("targetTable"));

        assertTrue(failure.getMessage().contains("--targetTable"), failure.getMessage());
        assertTrue(failure.getMessage().contains("sourceFile"),
                "the message must show what was supplied: " + failure.getMessage());
    }

    @Test
    void treatsABlankValueAsMissing() {
        JobArguments arguments = JobArguments.parse("--targetTable", "   ");

        assertThrows(IllegalArgumentException.class, () -> arguments.require("targetTable"));
        assertEquals(Optional.empty(), arguments.optional("targetTable"));
    }

    @Test
    void splitsAndTrimsListArguments() {
        JobArguments arguments = JobArguments.parse("--businessKey", " customer_id , region ");

        assertArrayEquals(new String[] {"customer_id", "region"}, arguments.requireList("businessKey"));
    }

    @Test
    void refusesAListWithAnEmptyEntry() {
        // "a,,b" is a template that did not render, not a two-column key.
        JobArguments arguments = JobArguments.parse("--businessKey", "customer_id,,region");

        assertThrows(IllegalArgumentException.class, () -> arguments.requireList("businessKey"));
    }
}
