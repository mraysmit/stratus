// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure part of table maintenance: working out where a table's files
 * live.
 *
 * <p>Orphan removal has to be handed a location it can list, because Iceberg's
 * S3FileIO records paths as {@code s3://} and the cluster registers only
 * {@code s3a}. The location is not among the properties {@code SHOW
 * TBLPROPERTIES} returns, so it is derived from the newest metadata file — and
 * getting that derivation wrong points a destructive operation at the wrong
 * prefix.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
@Tag("unit")
final class MaintenanceJobTest {

    @Test
    void aTableLocationIsTheParentOfItsMetadataDirectory() {
        assertEquals("s3://stratus-bronze/bronze/customers",
                MaintenanceJob.metadataParent(
                        "s3://stratus-bronze/bronze/customers/metadata/00003-abc.metadata.json"));
    }

    @Test
    void aTableWhoseNameContainsTheWordMetadataIsStillResolvedCorrectly() {
        // The last occurrence, not the first: a table called "metadata" would
        // otherwise resolve to the bucket root, and orphan removal would be
        // pointed at every table in the zone.
        assertEquals("s3://stratus-bronze/bronze/metadata",
                MaintenanceJob.metadataParent(
                        "s3://stratus-bronze/bronze/metadata/metadata/00001-abc.metadata.json"));
    }

    @Test
    void aPathThatIsNotAMetadataFileIsRefusedRatherThanGuessedAt() {
        // Returning the path unchanged would hand orphan removal a location
        // that is not the table's, and every file under it would look like an
        // orphan.
        var refused = assertThrows(IllegalStateException.class,
                () -> MaintenanceJob.metadataParent(
                        "s3://stratus-bronze/bronze/customers/data/00000-x.parquet"));

        assertTrue(refused.getMessage().contains("00000-x.parquet"), refused.getMessage());
    }

    @Test
    void theDestructiveOperationIsTheOneThatNeedsAnExplicitRetention() {
        // Stated here so the set cannot quietly grow: expiry and compaction
        // rewrite what the table already refers to, while orphan removal
        // deletes files the table does not know about, and only that one can
        // remove a file a concurrent write has staged.
        assertEquals("delete_orphan_files", MaintenanceJob.DELETE_ORPHAN_FILES);
        assertTrue(MaintenanceJob.ARGUMENTS.contains("olderThan"));
        assertTrue(MaintenanceJob.ARGUMENTS.contains("retainLast"));
    }
}
