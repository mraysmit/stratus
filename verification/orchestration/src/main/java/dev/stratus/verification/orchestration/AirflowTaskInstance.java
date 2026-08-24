// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.orchestration;

import java.time.Duration;
import java.time.Instant;

/** Observable task outcome, attempt count, and server-side execution timing. */
public record AirflowTaskInstance(
        String taskId, String state, int tryNumber, Instant startDate, Instant endDate) {

    public Duration duration() {
        return startDate == null || endDate == null
                ? Duration.ZERO : Duration.between(startDate, endDate);
    }
}
