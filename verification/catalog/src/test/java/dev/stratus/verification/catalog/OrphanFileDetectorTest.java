// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Offline checks on the orphan detector's construction guards and on the
 * report's own contract. The detection behaviour itself is proven against the
 * live catalog and live object storage by
 * {@link OrphanFileDetectionConformanceTest}; nothing here stands in for
 * either.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.0.0
 */
@Tag("unit")
final class OrphanFileDetectorTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void refusesANegativeMinimumAge() {
        // A negative age would push the cutoff into the future and classify a
        // file being written right now as an orphan.
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new OrphanFileDetector(FIXED, Duration.ofSeconds(-1)));

        assertTrue(failure.getMessage().contains("minimumAge"),
                "the message must name the offending argument, got: " + failure.getMessage());
    }

    @Test
    void requiresAClockAndAMinimumAge() {
        assertThrows(NullPointerException.class, () -> new OrphanFileDetector(null, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> new OrphanFileDetector(FIXED, null));
    }

    @Test
    void acceptsAZeroMinimumAge() {
        // Zero is the deliberate "judge everything" setting the conformance
        // suite uses to prove the reachable set is complete; only a negative
        // age is a mistake.
        assertDoesNotThrow(() -> new OrphanFileDetector(FIXED, Duration.ZERO));
    }

    @Test
    void reportCopiesItsListsAndAgreesWithHasOrphans() {
        var orphans = new ArrayList<>(List.of("s3://bucket/table/data/abandoned.parquet"));
        var young = new ArrayList<String>();
        var report = new OrphanFileReport("s3://bucket/table", 7, 6, orphans, young, FIXED.instant());

        orphans.add("s3://bucket/table/data/added-after-the-fact.parquet");
        young.add("s3://bucket/table/data/also-added.parquet");

        assertEquals(1, report.orphanFiles().size(),
                "the report must not change when the caller mutates the list it passed");
        assertEquals(List.of(), report.filesWithinMinimumAge());
        assertTrue(report.hasOrphans());
        assertFalse(new OrphanFileReport("s3://bucket/table", 7, 7, List.of(), List.of(),
                FIXED.instant()).hasOrphans());
    }
}
