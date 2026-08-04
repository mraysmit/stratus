// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The top-level directory set is a closed taxonomy: the ownership table in
 * docs/reference/repository-layout.md is the allowlist, and any tracked
 * top-level directory absent from it fails the build. This keeps the layout
 * document and the real tree from drifting apart in either direction.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-31
 * @version 1.0.0
 */
@Tag("unit")
final class RepositoryLayoutTest {

    private static final Path LAYOUT_DOCUMENT = Path.of("docs", "reference", "repository-layout.md");

    /** Table rows look like: | `applications/` | ownership | current contents | */
    private static final Pattern DIRECTORY_ROW = Pattern.compile("^\\|\\s*`([a-z0-9-]+)/`\\s*\\|(.+)$");

    @Test
    void trackedTopLevelDirectoriesAreDocumented() {
        Map<String, String> documented = documentedDirectories();
        TreeSet<String> undocumented = new TreeSet<>();
        Path root = Repo.root();
        for (Path file : Repo.trackedFiles()) {
            Path relative = root.relativize(file);
            if (relative.getNameCount() < 2) {
                continue;
            }
            String topLevel = relative.getName(0).toString();
            if (topLevel.startsWith(".") || documented.containsKey(topLevel)) {
                continue;
            }
            undocumented.add(topLevel);
        }
        assertTrue(undocumented.isEmpty(), () ->
            "Tracked top-level directories missing from the layout table in " + LAYOUT_DOCUMENT
                + ": " + undocumented + ". Either the content belongs inside an existing directory"
                + " (product integration under platform/<product>/, conformance suites under"
                + " verification/<capability>/), or the new directory needs a row in the table.");
    }

    @Test
    void documentedDirectoriesExistUnlessDeclaredGitIgnored() {
        Path root = Repo.root();
        List<String> missing = new ArrayList<>();
        documentedDirectories().forEach((directory, description) -> {
            if (!description.toLowerCase(java.util.Locale.ROOT).contains("git-ignored")
                    && !Files.isDirectory(root.resolve(directory))) {
                missing.add(directory);
            }
        });
        assertTrue(missing.isEmpty(), () ->
            "Directories documented in " + LAYOUT_DOCUMENT + " do not exist: " + missing
                + ". Remove the stale rows or restore the directories.");
    }

    private static Map<String, String> documentedDirectories() {
        Map<String, String> directories = new LinkedHashMap<>();
        for (String line : Repo.read(Repo.root().resolve(LAYOUT_DOCUMENT)).lines().toList()) {
            Matcher row = DIRECTORY_ROW.matcher(line.strip());
            if (row.matches()) {
                directories.put(row.group(1), row.group(2));
            }
        }
        assertFalse(directories.size() < 10, () ->
            "Expected the layout table in " + LAYOUT_DOCUMENT + " to list the top-level directories"
                + " but parsed only " + directories.keySet() + "; has the table format changed?");
        return directories;
    }
}
