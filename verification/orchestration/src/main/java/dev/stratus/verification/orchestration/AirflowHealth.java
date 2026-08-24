// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.orchestration;

/** Airflow components needed for scheduling and durable run state. */
public record AirflowHealth(String metadatabaseStatus, String schedulerStatus) {
}
