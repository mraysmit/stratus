// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import static dev.stratus.verification.catalog.ProbeTableWriter.appendRows;
import static dev.stratus.verification.catalog.ProbeTableWriter.probeRows;
import static dev.stratus.verification.catalog.ProbeTableWriter.readAll;
import static dev.stratus.verification.catalog.ProbeTableWriter.writeAndCommitEqualityDelete;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * Proves the metadata-driven maintenance decisions against the live catalog
 * and live object storage: that each verdict is read from the table's own
 * {@code files}, {@code manifests}, {@code delete_files}, and
 * {@code snapshots} metadata tables, and that each flips at its trigger.
 *
 * <p>Every decision is proven in both directions on the <em>same</em> table,
 * varying only the threshold. A test that saw a recommendation only when one
 * was expected could not tell a working rule from one that always recommends.
 *
 * <p>This is the evidence `P1-2.5-D1` requires before any compaction, rewrite,
 * or expiry action is wired to these decisions.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.0.0
 */
@Tag("catalog-integration")
final class MaintenanceDecisionConformanceTest {

    private static final Namespace PLATFORM = Namespace.of("platform");
    private static final long ONE_MEGABYTE = 1_000_000L;
    /** High enough that the category under test is the only one that can trigger. */
    private static final int NEVER = 1_000;

    private RESTCatalog catalog;
    private TableIdentifier probeTable;
    private final List<Table> createdProbes = new ArrayList<>();

    @BeforeEach
    void requireLiveCatalog() {
        catalog = LiveCatalog.connect();
        probeTable = TableIdentifier.of(PLATFORM, "maintenance_probe_"
                + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterEach
    void removeProbeTablesAndTheirObjectsThenReleaseTheCatalog() {
        if (catalog == null) {
            return;
        }
        try {
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
    void countsSmallDataFilesFromTheFilesMetadataTableAndFlipsAtTheTrigger() {
        Table table = probeWithThreeAppends();

        MaintenanceDecision below = decisionFor(table, thresholds(ONE_MEGABYTE, 4, NEVER, NEVER, NEVER),
                MaintenanceAdvisor.COMPACT_DATA_FILES);
        MaintenanceDecision atTrigger = decisionFor(table, thresholds(ONE_MEGABYTE, 3, NEVER, NEVER, NEVER),
                MaintenanceAdvisor.COMPACT_DATA_FILES);

        assertEquals(3, below.observed(), "three committed data files must be counted");
        assertFalse(below.actionRecommended(), "three small files must not trigger a four-file rule");
        assertEquals(3, atTrigger.observed(), "the count must not depend on the trigger");
        assertTrue(atTrigger.actionRecommended(), "three small files must trigger a three-file rule");

        // The size predicate must be real: with a one-byte target no file is
        // small, so a rule that merely counted rows would still report three.
        MaintenanceDecision noneAreSmall = decisionFor(table, thresholds(1L, 1, NEVER, NEVER, NEVER),
                MaintenanceAdvisor.COMPACT_DATA_FILES);
        assertEquals(0, noneAreSmall.observed(),
                "file_size_in_bytes must be read, not assumed");
        assertFalse(noneAreSmall.actionRecommended(), "no small files means no compaction");
    }

    @Test
    void countsManifestsFromTheManifestsMetadataTableAndFlipsAtTheTrigger() {
        Table table = probeWithThreeAppends();

        MaintenanceDecision below = decisionFor(table, thresholds(ONE_MEGABYTE, NEVER, 4, NEVER, NEVER),
                MaintenanceAdvisor.REWRITE_MANIFESTS);
        MaintenanceDecision atTrigger = decisionFor(table, thresholds(ONE_MEGABYTE, NEVER, 3, NEVER, NEVER),
                MaintenanceAdvisor.REWRITE_MANIFESTS);

        assertEquals(3, below.observed(), "each append adds a manifest to the current snapshot");
        assertFalse(below.actionRecommended());
        assertTrue(atTrigger.actionRecommended(), "three manifests must trigger a three-manifest rule");
        assertEquals("manifests", below.metadataTable());
    }

    @Test
    void countsDeleteFilesFromTheDeleteFilesMetadataTableAndFlipsAtTheTrigger() {
        Table table = probeWithACommittedEqualityDelete();

        MaintenanceDecision below = decisionFor(table, thresholds(ONE_MEGABYTE, NEVER, NEVER, 2, NEVER),
                MaintenanceAdvisor.COMPACT_DELETE_FILES);
        MaintenanceDecision atTrigger = decisionFor(table, thresholds(ONE_MEGABYTE, NEVER, NEVER, 1, NEVER),
                MaintenanceAdvisor.COMPACT_DELETE_FILES);

        assertEquals(1, below.observed(), "the committed equality-delete file must be counted");
        assertFalse(below.actionRecommended());
        assertTrue(atTrigger.actionRecommended(), "one delete file must trigger a one-file rule");
        assertEquals("delete_files", below.metadataTable());
    }

    @Test
    void countsSnapshotsBeyondRetentionFromTheSnapshotsMetadataTable() {
        Table table = probeWithThreeAppends();

        MaintenanceDecision withinRetention = decisionFor(table,
                thresholds(ONE_MEGABYTE, NEVER, NEVER, NEVER, 3), MaintenanceAdvisor.EXPIRE_SNAPSHOTS);
        MaintenanceDecision beyondRetention = decisionFor(table,
                thresholds(ONE_MEGABYTE, NEVER, NEVER, NEVER, 2), MaintenanceAdvisor.EXPIRE_SNAPSHOTS);

        assertEquals(0, withinRetention.observed(),
                "three snapshots against a retention of three leaves nothing to expire");
        assertFalse(withinRetention.actionRecommended());
        assertEquals(1, beyondRetention.observed(),
                "three snapshots against a retention of two leaves one beyond retention");
        assertTrue(beyondRetention.actionRecommended());
        assertEquals("snapshots", withinRetention.metadataTable());
    }

    @Test
    void reportsEveryCategoryAndChangesNothing() {
        Table table = probeWithACommittedEqualityDelete();
        long committedSnapshot = catalog.loadTable(probeTable).currentSnapshot().snapshotId();
        int rowsBefore = readAll(catalog.loadTable(probeTable)).size();

        MaintenanceReport report = new MaintenanceAdvisor(
                thresholds(ONE_MEGABYTE, NEVER, NEVER, NEVER, NEVER)).advise(table);

        assertEquals(MaintenanceAdvisor.categories(), report.categories(),
                "every maintenance category must be decided in one pass");
        assertEquals(List.of(), report.recommendedActions(),
                "thresholds no table can reach must recommend nothing");

        // Deciding is not maintenance.
        Table reloaded = catalog.loadTable(probeTable);
        assertEquals(committedSnapshot, reloaded.currentSnapshot().snapshotId(),
                "taking a decision must not advance the table snapshot");
        assertEquals(rowsBefore, readAll(reloaded).size(),
                "taking a decision must not change the table contents");
        CatalogVerificationLogging.tableDefinitionValidated(probeTable.toString(),
                "maintenance-decisions-non-destructive", "decided 4 categories and changed nothing");
    }

    private static MaintenanceThresholds thresholds(long targetFileSizeBytes, int smallFiles,
                                                    int manifests, int deleteFiles, int retainedSnapshots) {
        return new MaintenanceThresholds(targetFileSizeBytes, smallFiles, manifests,
                deleteFiles, retainedSnapshots);
    }

    private static MaintenanceDecision decisionFor(Table table, MaintenanceThresholds thresholds,
                                                   String category) {
        return new MaintenanceAdvisor(thresholds).advise(table).decision(category);
    }

    private Table probeWithThreeAppends() {
        Table table = createProbe(Map.of());
        appendRows(table, "maintenance-probe-0", probeRows(table, 1, 2));
        appendRows(table, "maintenance-probe-1", probeRows(table, 3, 4));
        appendRows(table, "maintenance-probe-2", probeRows(table, 5, 6));
        return table;
    }

    private Table probeWithACommittedEqualityDelete() {
        // Row-level deletes require format version 2; stated rather than
        // inherited so the test does not depend on the release default.
        Table table = createProbe(Map.of("format-version", "2"));
        appendRows(table, "maintenance-probe-0", probeRows(table, 1, 2));
        writeAndCommitEqualityDelete(table, 1L);
        return table;
    }

    private Table createProbe(Map<String, String> properties) {
        var schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.optional(2, "note", Types.StringType.get()));
        Table table = catalog.createTable(probeTable, schema, PartitionSpec.unpartitioned(), properties);
        createdProbes.add(table);
        return table;
    }
}
