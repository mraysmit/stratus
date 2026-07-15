// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every relative markdown link in a tracked document must resolve to an existing file, and every anchor must match a heading in its target. Guards against the dangling links left behind by file and section renames.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 1.0.0
 */
@Tag("unit")
final class DocumentationLinkTest {

    private static final Pattern LINK = Pattern.compile("\\[[^\\]]*\\]\\(([^)\\s]+)\\)");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");
    private static final Map<Path, Set<String>> SLUG_CACHE = new ConcurrentHashMap<>();

    @Test
    void relativeMarkdownLinksResolve() {
        List<String> violations = new ArrayList<>();
        for (Path document : markdownFiles()) {
            Matcher matcher = LINK.matcher(withoutFencedCode(Repo.read(document)));
            while (matcher.find()) {
                String violation = check(document, matcher.group(1));
                if (violation != null) {
                    violations.add(violation);
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "Broken documentation links:\n" + String.join("\n", violations));
    }

    private static List<Path> markdownFiles() {
        return Repo.trackedFiles().stream()
            .filter(file -> file.getFileName().toString().endsWith(".md"))
            .toList();
    }

    private static String check(Path document, String target) {
        if (target.contains("://") || target.startsWith("mailto:")) {
            return null;
        }
        String pathPart = target;
        String anchor = null;
        int hash = target.indexOf('#');
        if (hash >= 0) {
            anchor = target.substring(hash + 1);
            pathPart = target.substring(0, hash);
        }
        Path resolved = pathPart.isEmpty()
            ? document
            : document.getParent().resolve(pathPart.replace("%20", " ")).normalize();
        if (!Files.exists(resolved)) {
            return document + " -> " + target + " (missing target)";
        }
        if (anchor != null && !anchor.isEmpty() && resolved.toString().endsWith(".md")
            && !slugsOf(resolved).contains(anchor.toLowerCase(Locale.ROOT))) {
            return document + " -> " + target + " (missing anchor)";
        }
        return null;
    }

    /** GitHub-style anchor slugs for every heading in a markdown file. */
    private static Set<String> slugsOf(Path markdown) {
        return SLUG_CACHE.computeIfAbsent(markdown, file -> {
            Map<String, Integer> seen = new HashMap<>();
            Set<String> slugs = new HashSet<>();
            for (String line : withoutFencedCode(Repo.read(file)).split("\\r?\\n")) {
                Matcher heading = HEADING.matcher(line);
                if (!heading.matches()) {
                    continue;
                }
                String slug = slugify(heading.group(1));
                int count = seen.merge(slug, 1, Integer::sum);
                slugs.add(count == 1 ? slug : slug + "-" + (count - 1));
            }
            return slugs;
        });
    }

    private static String slugify(String heading) {
        String lower = heading.trim().toLowerCase(Locale.ROOT);
        StringBuilder slug = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                slug.append(c);
            } else if (c == ' ') {
                slug.append('-');
            }
        }
        return slug.toString();
    }

    private static String withoutFencedCode(String content) {
        StringBuilder result = new StringBuilder(content.length());
        boolean fenced = false;
        for (String line : content.split("\\r?\\n", -1)) {
            if (line.trim().startsWith("```")) {
                fenced = !fenced;
                result.append('\n');
            } else {
                result.append(fenced ? "" : line).append('\n');
            }
        }
        return result.toString();
    }
}
