// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongPredicate;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.MetadataTableType;
import org.apache.iceberg.MetadataTableUtils;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.Types;

/**
 * Takes the metadata-driven maintenance decisions for a table: whether its
 * data files want compacting, its manifests rewriting, its delete files
 * compacting, and whether it holds more snapshots than the retention policy
 * keeps.
 *
 * <p><strong>This advisor decides and never acts.</strong> It has no
 * compaction, rewrite, or expiry path, because `P1-2.5-D1` requires every
 * decision to be proven before a destructive action is wired to it. Each
 * decision is read from the table's own metadata tables — {@code files},
 * {@code manifests}, {@code delete_files}, {@code snapshots} — so the evidence
 * behind a verdict is the table's published metadata rather than a private
 * accounting this class keeps.
 *
 * <p>Snapshot expiry <em>behaviour</em> is proven by `P1-2.4-V1`; what is
 * decided here is only whether expiry is due.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.0.0
 */
final class MaintenanceAdvisor {

    static final String COMPACT_DATA_FILES = "compact-data-files";
    static final String REWRITE_MANIFESTS = "rewrite-manifests";
    static final String COMPACT_DELETE_FILES = "compact-delete-files";
    static final String EXPIRE_SNAPSHOTS = "expire-snapshots";

    private final MaintenanceThresholds thresholds;

    MaintenanceAdvisor(MaintenanceThresholds thresholds) {
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    MaintenanceReport advise(Table table) {
        Objects.requireNonNull(table, "table");
        var decisions = List.of(
                compactDataFiles(table),
                rewriteManifests(table),
                compactDeleteFiles(table),
                expireSnapshots(table));
        var report = new MaintenanceReport(table.name(), decisions);
        for (MaintenanceDecision decision : report.decisions()) {
            CatalogVerificationLogging.maintenanceDecision(table.name(), decision.category(),
                    decision.metadataTable(), decision.observed(), decision.threshold(),
                    decision.actionRecommended(), decision.detail());
        }
        return report;
    }

    /**
     * Small files are counted from the {@code files} metadata table rather
     * than from a scan, because that table is what an operator can query to
     * check the verdict.
     */
    private MaintenanceDecision compactDataFiles(Table table) {
        long smallFiles = count(table, MetadataTableType.FILES, "file_size_in_bytes",
                size -> size < thresholds.targetFileSizeBytes());
        return new MaintenanceDecision(COMPACT_DATA_FILES, "files", smallFiles,
                thresholds.smallFileCountTrigger(),
                smallFiles >= thresholds.smallFileCountTrigger(),
                "data files smaller than " + thresholds.targetFileSizeBytes() + " bytes");
    }

    private MaintenanceDecision rewriteManifests(Table table) {
        long manifests = count(table, MetadataTableType.MANIFESTS, null, size -> true);
        return new MaintenanceDecision(REWRITE_MANIFESTS, "manifests", manifests,
                thresholds.manifestCountTrigger(),
                manifests >= thresholds.manifestCountTrigger(),
                "manifests tracked by the current snapshot");
    }

    private MaintenanceDecision compactDeleteFiles(Table table) {
        long deleteFiles = count(table, MetadataTableType.DELETE_FILES, null, size -> true);
        return new MaintenanceDecision(COMPACT_DELETE_FILES, "delete_files", deleteFiles,
                thresholds.deleteFileCountTrigger(),
                deleteFiles >= thresholds.deleteFileCountTrigger(),
                "delete files applied on read");
    }

    /**
     * Reports how many snapshots sit beyond the retention count. Expiry is due
     * when at least one does; the retained snapshots themselves are never a
     * reason to act.
     */
    private MaintenanceDecision expireSnapshots(Table table) {
        long snapshots = count(table, MetadataTableType.SNAPSHOTS, null, size -> true);
        long beyondRetention = Math.max(0, snapshots - thresholds.retainedSnapshots());
        return new MaintenanceDecision(EXPIRE_SNAPSHOTS, "snapshots", beyondRetention, 1,
                beyondRetention >= 1,
                snapshots + " snapshots against a retention of " + thresholds.retainedSnapshots());
    }

    /**
     * Counts rows of a metadata table, optionally only those whose long column
     * satisfies a predicate.
     *
     * <p>Metadata tables are read through their scan tasks rather than the
     * generic record reader: their rows are computed from table metadata
     * rather than read from data files, so every task is a {@code DataTask}
     * and the generic reader — which requires a {@code file_path} column —
     * cannot read them.
     */
    private static long count(Table table, MetadataTableType type, String column,
                              LongPredicate matches) {
        Table metadataTable = MetadataTableUtils.createMetadataTableInstance(table, type);
        int index = column == null ? -1 : columnIndex(metadataTable.schema(), column, type);
        long matched = 0;
        try (CloseableIterable<FileScanTask> tasks = metadataTable.newScan().planFiles()) {
            for (FileScanTask task : tasks) {
                try (CloseableIterable<StructLike> rows = task.asDataTask().rows()) {
                    for (StructLike row : rows) {
                        if (index < 0 || matches.test(row.get(index, Long.class))) {
                            matched++;
                        }
                    }
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to read the " + type + " metadata table of " + table.name(), exception);
        }
        return matched;
    }

    private static int columnIndex(Schema schema, String column, MetadataTableType type) {
        List<Types.NestedField> columns = schema.columns();
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).name().equals(column)) {
                return index;
            }
        }
        throw new IllegalStateException("The " + type + " metadata table has no column '"
                + column + "'; it has: " + columns.stream().map(Types.NestedField::name).toList());
    }

    /** The categories this advisor decides, in report order. */
    static List<String> categories() {
        return new ArrayList<>(List.of(COMPACT_DATA_FILES, REWRITE_MANIFESTS,
                COMPACT_DELETE_FILES, EXPIRE_SNAPSHOTS));
    }
}
