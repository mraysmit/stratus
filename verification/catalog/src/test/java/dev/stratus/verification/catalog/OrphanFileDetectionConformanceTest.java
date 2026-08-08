// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import static dev.stratus.verification.catalog.ProbeTableWriter.appendRows;
import static dev.stratus.verification.catalog.ProbeTableWriter.probeRows;
import static dev.stratus.verification.catalog.ProbeTableWriter.readAll;
import static dev.stratus.verification.catalog.ProbeTableWriter.writeAndCommitEqualityDelete;
import static dev.stratus.verification.catalog.ProbeTableWriter.writeDataFileWithoutCommitting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.SupportsPrefixOperations;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proves orphan-file detection against the live catalog and live object
 * storage: that a healthy table yields no orphans, that a real aborted write
 * is found, that the age threshold withholds a file too young to judge, and
 * that detecting changes nothing.
 *
 * <p>The orphan is produced the way a real one occurs — a Parquet file written
 * by the production writer into the table's own data location, whose commit
 * never happens. Nothing here is a stand-in for the storage layer or the
 * catalog; both are the deployed products.
 *
 * <p>This is the evidence `P1-2.5-D1` requires before any destructive
 * maintenance action is wired.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.0.0
 */
@Tag("catalog-integration")
final class OrphanFileDetectionConformanceTest {

    private static final Namespace PLATFORM = Namespace.of("platform");
    private static final Duration ONE_HOUR = Duration.ofHours(1);

    private RESTCatalog catalog;
    private TableIdentifier probeTable;
    private final List<Table> createdProbes = new ArrayList<>();

    @BeforeEach
    void requireLiveCatalog() {
        catalog = LiveCatalog.connect();
        probeTable = TableIdentifier.of(PLATFORM, "orphan_probe_"
                + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterEach
    void removeProbeTablesAndTheirObjectsThenReleaseTheCatalog() {
        if (catalog == null) {
            return;
        }
        try {
            // Unconditional, and it must also remove the deliberately
            // uncommitted file: an orphan this suite created is still residue
            // in a governed zone (code_style_rules 7.1).
            for (Table probe : createdProbes) {
                TableIdentifier identifier = TableIdentifier.of(
                        Namespace.of(probe.name().split("\\.")[1]),
                        probe.name().split("\\.")[2]);
                if (catalog.tableExists(identifier)) {
                    catalog.dropTable(identifier, true);
                }
                if (probe.io() instanceof SupportsPrefixOperations prefixIo) {
                    prefixIo.deletePrefix(probe.location());
                }
            }
        } finally {
            createdProbes.clear();
            try {
                catalog.close();
            } catch (IOException exception) {
                throw new UncheckedIOException("Failed to release the REST catalog client", exception);
            }
        }
    }

    @Test
    void reportsNoOrphansWhenEveryObjectUnderTheTableIsReferenced() {
        Table table = probeWithTwoCommittedAppends();

        // The clock is ahead of the writes and the minimum age is zero, so
        // every object is old enough to judge and nothing is withheld.
        var detector = new OrphanFileDetector(
                Clock.fixed(Instant.now().plus(ONE_HOUR), ZoneOffset.UTC), Duration.ZERO);
        OrphanFileReport report = detector.detect(table);

        // Without these two the assertion below would hold over an empty
        // listing, which is the failure mode that makes a clean result
        // meaningless.
        assertTrue(report.scannedFiles() > 0,
                "the scan must have listed objects under " + table.location());
        assertTrue(report.referencedFiles() > 0,
                "the table must reach metadata, manifest, and data files");
        assertEquals(List.of(), report.filesWithinMinimumAge(),
                "a zero minimum age must leave nothing withheld");
        assertEquals(List.of(), report.orphanFiles(),
                "a table whose objects are all referenced must yield no orphans");
        assertFalse(report.hasOrphans(), "hasOrphans must agree with the empty orphan list");
    }

    @Test
    void detectsAnAbortedWriteAndLeavesItUntouched() {
        Table table = probeWithTwoCommittedAppends();
        long committedSnapshot = catalog.loadTable(probeTable).currentSnapshot().snapshotId();
        String abandoned = writeDataFileWithoutCommitting(table, "aborted-write");

        var detector = new OrphanFileDetector(
                Clock.fixed(Instant.now().plus(Duration.ofHours(2)), ZoneOffset.UTC), ONE_HOUR);
        OrphanFileReport report = detector.detect(table);

        assertEquals(List.of(normalize(abandoned)),
                report.orphanFiles().stream().map(OrphanFileDetectionConformanceTest::normalize).toList(),
                "the uncommitted file, and only it, must be reported as an orphan");
        assertTrue(report.hasOrphans(), "hasOrphans must agree with the populated orphan list");

        // Detection is not maintenance: nothing may be removed, and the table
        // must be exactly as it was.
        assertTrue(table.io().newInputFile(abandoned).exists(),
                "detection must not remove the file it reports");
        Table reloaded = catalog.loadTable(probeTable);
        assertEquals(committedSnapshot, reloaded.currentSnapshot().snapshotId(),
                "detection must not advance the table snapshot");
        assertEquals(4, readAll(reloaded).size(),
                "detection must not disturb the committed rows");
        CatalogVerificationLogging.tableDefinitionValidated(probeTable.toString(),
                "orphan-detection-non-destructive", "reported 1 orphan and changed nothing");
    }

    @Test
    void treatsACommittedDeleteFileAsReferenced() {
        // Row-level deletes put the reachable set behind a delete manifest,
        // which is read by a different code path from data manifests. Without
        // this the delete file is unreferenced, and a delete path wired to
        // that verdict would remove the record of what was deleted.
        Table table = probeWithACommittedEqualityDelete();

        var detector = new OrphanFileDetector(
                Clock.fixed(Instant.now().plus(ONE_HOUR), ZoneOffset.UTC), Duration.ZERO);
        OrphanFileReport report = detector.detect(table);

        assertTrue(report.scannedFiles() > 0,
                "the scan must have listed objects under " + table.location());
        assertEquals(List.of(), report.orphanFiles(),
                "a committed delete file and its manifest must be reachable, not orphans");
        assertEquals(List.of(), report.filesWithinMinimumAge(),
                "a zero minimum age must leave nothing withheld");
        CatalogVerificationLogging.tableDefinitionValidated(probeTable.toString(),
                "orphan-detection-delete-files", "delete file and delete manifest resolved as referenced");
    }

    @Test
    void withholdsAnUnreferencedFileThatIsYoungerThanTheMinimumAge() {
        Table table = probeWithTwoCommittedAppends();
        String inFlight = writeDataFileWithoutCommitting(table, "still-being-written");

        // Same unreferenced file, judged at the moment it was written: a live
        // write looks exactly like an abandoned one until it is old enough.
        var detector = new OrphanFileDetector(Clock.fixed(Instant.now(), ZoneOffset.UTC), ONE_HOUR);
        OrphanFileReport report = detector.detect(table);

        assertEquals(List.of(), report.orphanFiles(),
                "a file younger than the minimum age must never be called an orphan");
        assertEquals(List.of(normalize(inFlight)),
                report.filesWithinMinimumAge().stream()
                        .map(OrphanFileDetectionConformanceTest::normalize).toList(),
                "the withheld file must still be reported, so the decision can be reviewed");
    }

    private Table probeWithTwoCommittedAppends() {
        var schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.optional(2, "note", Types.StringType.get()));
        Table table = catalog.createTable(probeTable, schema);
        createdProbes.add(table);
        // Two appends, because the first snapshot's manifest list stays in the
        // bucket while only the second snapshot is current, and it is
        // reachable solely by iterating every snapshot. Measured: computing
        // reachability from the current snapshot alone drops referencedFiles
        // from 9 to 8 and reports that manifest list as an orphan — which,
        // with a delete path attached, would destroy the ability to read the
        // snapshot it belongs to. Its manifest survives that mistake only
        // because an append carries the previous manifest forward.
        appendRows(table, "orphan-probe-0", probeRows(table, 1, 2));
        appendRows(table, "orphan-probe-1", probeRows(table, 3, 4));
        return table;
    }

    /**
     * A table carrying committed data and a committed equality-delete file, so
     * the reachable set spans a delete manifest as well as data manifests.
     */
    private Table probeWithACommittedEqualityDelete() {
        var schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.optional(2, "note", Types.StringType.get()));
        // Row-level deletes require format version 2; stated rather than
        // inherited so the test does not depend on the release default.
        Table table = catalog.createTable(probeTable, schema, PartitionSpec.unpartitioned(),
                Map.of("format-version", "2"));
        createdProbes.add(table);
        appendRows(table, "orphan-probe-0", probeRows(table, 1, 2));

        writeAndCommitEqualityDelete(table, 1L);
        return table;
    }

    /** Locations are compared on bucket and key, never on scheme spelling. */
    private static String normalize(String location) {
        String value = location;
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        return value;
    }
}
