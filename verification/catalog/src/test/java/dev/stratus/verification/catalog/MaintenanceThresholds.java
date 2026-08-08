// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

/**
 * The trigger points a maintenance decision is taken against.
 *
 * <p>Every value is a count or a size read from the table's own metadata
 * tables, never a wall-clock or environment-dependent quantity, so a decision
 * is reproducible from the table alone. A trigger of one would recommend
 * action on any table that has ever been written, which is why each count
 * trigger must be at least one and is compared with {@code >=}.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.0.0
 */
record MaintenanceThresholds(long targetFileSizeBytes, int smallFileCountTrigger,
                             int manifestCountTrigger, int deleteFileCountTrigger,
                             int retainedSnapshots) {

    MaintenanceThresholds {
        if (targetFileSizeBytes <= 0) {
            throw new IllegalArgumentException(
                    "targetFileSizeBytes must be positive, got: " + targetFileSizeBytes);
        }
        requireAtLeastOne("smallFileCountTrigger", smallFileCountTrigger);
        requireAtLeastOne("manifestCountTrigger", manifestCountTrigger);
        requireAtLeastOne("deleteFileCountTrigger", deleteFileCountTrigger);
        requireAtLeastOne("retainedSnapshots", retainedSnapshots);
    }

    private static void requireAtLeastOne(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1, got: " + value);
        }
    }
}
