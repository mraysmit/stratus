// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.orchestration;

import java.time.Duration;
import java.time.Instant;

/** One observed Airflow DAG-run state with server-side execution timing when available. */
public record AirflowDagRun(
        String runId, String state, Instant startDate, Instant endDate) {

    public Duration duration() {
        return startDate == null || endDate == null
                ? Duration.ZERO : Duration.between(startDate, endDate);
    }

    public boolean terminal() {
        return "success".equals(state) || "failed".equals(state);
    }
}
