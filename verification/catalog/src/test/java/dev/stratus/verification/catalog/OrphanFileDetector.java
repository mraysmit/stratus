// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.iceberg.ContentFile;
import org.apache.iceberg.ManifestContent;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ReachableFileUtil;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FileInfo;
import org.apache.iceberg.io.SupportsPrefixOperations;

/**
 * Finds objects under a table's location that nothing in the table's metadata
 * references — the residue an interrupted or failed write leaves behind.
 *
 * <p><strong>This class detects and never deletes.</strong> It exposes no
 * removal path at all, because `P1-2.5-D1` requires the decision to be proven
 * before any destructive action is wired: a detector that is wrong about which
 * files are reachable would, with a delete path attached, destroy live table
 * data. Reachability is computed across <em>every</em> snapshot, not the
 * current one, since an unexpired snapshot still makes its files live.
 *
 * <p>Age is the second safeguard. A file being written at this moment is
 * unreferenced — the commit that will reference it has not happened yet — and
 * is indistinguishable by reference alone from one abandoned an hour ago. Any
 * file younger than the configured minimum age is therefore reported
 * separately and never counted as an orphan. Time comes from an injected
 * {@link Clock} so that decision is testable rather than wall-clock dependent.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.0.0
 */
final class OrphanFileDetector {

    private final Clock clock;
    private final Duration minimumAge;

    OrphanFileDetector(Clock clock, Duration minimumAge) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.minimumAge = Objects.requireNonNull(minimumAge, "minimumAge");
        if (minimumAge.isNegative()) {
            throw new IllegalArgumentException(
                    "minimumAge must not be negative, got: " + minimumAge);
        }
    }

    /**
     * Scans everything under the table's location and classifies each object
     * against the table's reachable set.
     */
    OrphanFileReport detect(Table table) {
        Objects.requireNonNull(table, "table");
        if (!(table.io() instanceof SupportsPrefixOperations prefixIo)) {
            throw new IllegalStateException(
                    "Orphan detection requires a FileIO supporting prefix listing, got: "
                            + table.io().getClass().getName());
        }

        Set<String> referenced = referencedLocations(table);
        Instant cutoff = clock.instant().minus(minimumAge);
        List<String> orphans = new ArrayList<>();
        List<String> withinMinimumAge = new ArrayList<>();
        int scanned = 0;

        for (FileInfo info : prefixIo.listPrefix(table.location())) {
            scanned++;
            if (referenced.contains(normalize(info.location()))) {
                continue;
            }
            if (info.createdAtMillis() > cutoff.toEpochMilli()) {
                withinMinimumAge.add(info.location());
            } else {
                orphans.add(info.location());
            }
        }

        orphans.sort(String::compareTo);
        withinMinimumAge.sort(String::compareTo);
        var report = new OrphanFileReport(table.location(), scanned, referenced.size(),
                orphans, withinMinimumAge, cutoff);
        CatalogVerificationLogging.orphanScanCompleted(table.name(), table.location(),
                report.scannedFiles(), report.referencedFiles(),
                report.orphanFiles(), report.filesWithinMinimumAge());
        return report;
    }

    /**
     * Every location the table can reach: current and previous metadata files,
     * statistics files, and — for each snapshot — its manifest list, its
     * manifests, and the data and delete files those manifests name.
     */
    private static Set<String> referencedLocations(Table table) {
        Set<String> referenced = new HashSet<>();
        // `true` includes previous metadata files still named in the metadata
        // log; those objects are present in the bucket and are not orphans.
        for (String metadata : ReachableFileUtil.metadataFileLocations(table, true)) {
            referenced.add(normalize(metadata));
        }
        for (String statistics : ReachableFileUtil.statisticsFilesLocations(table)) {
            referenced.add(normalize(statistics));
        }
        for (Snapshot snapshot : table.snapshots()) {
            if (snapshot.manifestListLocation() != null) {
                referenced.add(normalize(snapshot.manifestListLocation()));
            }
            for (ManifestFile manifest : snapshot.allManifests(table.io())) {
                referenced.add(normalize(manifest.path()));
                addContentFiles(table, manifest, referenced);
            }
        }
        return referenced;
    }

    private static void addContentFiles(Table table, ManifestFile manifest, Set<String> referenced) {
        CloseableIterable<? extends ContentFile<?>> files =
                manifest.content() == ManifestContent.DATA
                        ? ManifestFiles.read(manifest, table.io(), table.specs())
                        : ManifestFiles.readDeleteManifest(manifest, table.io(), table.specs());
        try (files) {
            for (ContentFile<?> file : files) {
                referenced.add(normalize(file.location()));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to read manifest " + manifest.path() + " while resolving reachable files",
                    exception);
        }
    }

    /**
     * Compares on bucket and key alone. The listing and the metadata do not
     * have to spell a location the same way — one may carry a scheme the other
     * omits — and a spelling mismatch would classify a live file as an orphan.
     */
    private static String normalize(String location) {
        String value = location.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }
}
