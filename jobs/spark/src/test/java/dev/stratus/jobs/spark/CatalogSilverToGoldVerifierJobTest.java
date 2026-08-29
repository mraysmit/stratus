// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit contract for the non-Spark silver-to-gold catalog verifier. */
final class CatalogSilverToGoldVerifierJobTest {

    @Test
    void expectedCountriesAreCanonicalizedAndMustNotBeEmpty() {
        assertEquals(Set.of("GB", "NL", "US"),
                CatalogSilverToGoldVerifierJob.expectedCountries("US,GB,NL"));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogSilverToGoldVerifierJob.expectedCountries(" , "));
    }

    @Test
    void expectedCountsMustBePositive() {
        assertEquals(3L, CatalogSilverToGoldVerifierJob.positive("3", "expectedRows"));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogSilverToGoldVerifierJob.positive("0", "expectedRows"));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogSilverToGoldVerifierJob.positive("three", "expectedRows"));
    }

    @Test
    void tableIdentifiersRemainClosedToGeneratedSilverAndGoldProbes() {
        String silver = "stratus.silver.airflow_pipeline_probe_run_accepted";
        String gold = "stratus.gold.airflow_pipeline_probe_run_accepted";
        assertEquals(silver, CatalogSilverToGoldVerifierJob.requireSilver(silver));
        assertEquals(gold, CatalogSilverToGoldVerifierJob.requireGold(gold));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogSilverToGoldVerifierJob.requireSilver("stratus.silver.customers"));
    }
}
