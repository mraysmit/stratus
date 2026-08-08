// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Offline checks on the maintenance threshold guards and the report's own
 * contract. The decisions themselves are proven against the live catalog by
 * {@link MaintenanceDecisionConformanceTest}; nothing here stands in for a
 * metadata table.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.0.0
 */
@Tag("unit")
final class MaintenanceThresholdsTest {

    @Test
    void refusesANonPositiveTargetFileSize() {
        // A zero or negative target makes every file "large" and silently
        // disables compaction rather than failing.
        for (long size : new long[] {0L, -1L}) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> new MaintenanceThresholds(size, 1, 1, 1, 1));
            assertTrue(failure.getMessage().contains("targetFileSizeBytes"),
                    "the message must name the offending argument, got: " + failure.getMessage());
        }
    }

    @Test
    void refusesACountTriggerBelowOne() {
        // A trigger of zero recommends action on every table forever.
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceThresholds(1L, 0, 1, 1, 1))
                .getMessage().contains("smallFileCountTrigger"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceThresholds(1L, 1, 0, 1, 1))
                .getMessage().contains("manifestCountTrigger"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceThresholds(1L, 1, 1, 0, 1))
                .getMessage().contains("deleteFileCountTrigger"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceThresholds(1L, 1, 1, 1, 0))
                .getMessage().contains("retainedSnapshots"));
    }

    @Test
    void reportCopiesItsDecisionsAndNamesTheRecommendedCategories() {
        var decisions = new ArrayList<>(List.of(
                new MaintenanceDecision("compact-data-files", "files", 5, 3, true, "five small files"),
                new MaintenanceDecision("rewrite-manifests", "manifests", 1, 4, false, "one manifest")));
        var report = new MaintenanceReport("platform.probe", decisions);

        decisions.add(new MaintenanceDecision("added-after-the-fact", "files", 0, 1, true, ""));

        assertEquals(2, report.decisions().size(),
                "the report must not change when the caller mutates the list it passed");
        assertEquals(List.of("compact-data-files"), report.recommendedActions(),
                "only categories whose trigger was reached are recommended");
        assertEquals(5, report.decision("compact-data-files").observed());
    }

    @Test
    void reportNamesTheCategoriesItHasWhenAskedForOneItDoesNot() {
        var report = new MaintenanceReport("platform.probe", List.of(
                new MaintenanceDecision("rewrite-manifests", "manifests", 1, 4, false, "one")));

        var failure = assertThrows(IllegalArgumentException.class,
                () -> report.decision("compact-data-files"));

        assertTrue(failure.getMessage().contains("rewrite-manifests"),
                "the message must list what is available, got: " + failure.getMessage());
    }
}
