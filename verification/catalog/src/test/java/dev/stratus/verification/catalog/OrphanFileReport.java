// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import java.time.Instant;
import java.util.List;

/**
 * The outcome of one orphan-file scan: the object inventory the maintenance
 * decision is taken on, and the files that decision would apply to.
 *
 * <p>{@code orphanFiles} are unreferenced and older than the configured
 * minimum age. {@code filesWithinMinimumAge} are unreferenced but too young to
 * judge — a file being written right now is indistinguishable from an
 * abandoned one by reference alone, so the age threshold is what keeps a live
 * write out of the decision. The two lists are reported separately because a
 * maintenance rule that cannot see what it withheld cannot be reviewed.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.0.0
 */
record OrphanFileReport(String tableLocation, int scannedFiles, int referencedFiles,
                        List<String> orphanFiles, List<String> filesWithinMinimumAge,
                        Instant olderThanCutoff) {

    OrphanFileReport {
        orphanFiles = List.copyOf(orphanFiles);
        filesWithinMinimumAge = List.copyOf(filesWithinMinimumAge);
    }

    boolean hasOrphans() {
        return !orphanFiles.isEmpty();
    }
}
