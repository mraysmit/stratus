// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Prevents the canonical Airflow guide from drifting back to retired examples. */
@Tag("unit")
final class AirflowDocumentationConformanceTest {

    private static final Path GUIDE = Repo.root().resolve(
            Path.of("docs", "implementation", "airflow_orchestration.md"));
    private static final List<String> RETIRED_CONTENT = List.of(
            "PromotionGateJob",
            "LandingToBronzeJob",
            "BronzeToSilverJob",
            "SilverToGoldJob",
            "podman generate systemd --new",
            "/opt/stratus/airflow/dags",
            "schedule=\"@hourly\"",
            "schedule=\"@daily\"");

    @Test
    void canonicalGuideNamesTheImplementedBoundaryAndSources() {
        String guide = Repo.read(GUIDE);

        assertAll(
                () -> assertTrue(guide.contains("platform/airflow/developer/dags/")),
                () -> assertTrue(guide.contains("SparkSubmitOperator")),
                () -> assertTrue(guide.contains("dev.stratus.jobs.spark.PromotionGate")),
                () -> assertTrue(guide.contains("evaluate_bronze_promotion=failed")),
                () -> assertTrue(guide.contains("manual/API (`None`)")),
                () -> assertTrue(guide.contains("ADR-P1-007")),
                () -> assertTrue(guide.contains("point-in-time evidence")));
    }

    @Test
    void canonicalGuideExcludesRetiredBlueprintContent() {
        String guide = Repo.read(GUIDE);
        for (String retired : RETIRED_CONTENT) {
            assertFalse(guide.contains(retired), () -> "Retired Airflow guidance returned: " + retired);
        }
    }
}
