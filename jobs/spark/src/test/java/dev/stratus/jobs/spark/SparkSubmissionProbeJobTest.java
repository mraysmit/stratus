// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Unit contract for the packaged Airflow-to-Spark submission probe. */
@Tag("unit")
final class SparkSubmissionProbeJobTest {

    @Test
    void acceptsARealPositiveWorkloadSize() {
        assertEquals(1_000L, SparkSubmissionProbeJob.parseExpectedCount("1000"));
    }

    @Test
    void refusesZeroNegativeAndNonNumericWorkloadSizes() {
        assertThrows(IllegalArgumentException.class,
                () -> SparkSubmissionProbeJob.parseExpectedCount("0"));
        assertThrows(IllegalArgumentException.class,
                () -> SparkSubmissionProbeJob.parseExpectedCount("-1"));
        assertThrows(IllegalArgumentException.class,
                () -> SparkSubmissionProbeJob.parseExpectedCount("many"));
    }
}
