// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.orchestration;

/** Registered DAG identity and scheduling pause state. */
public record AirflowDag(String dagId, boolean paused) {
}
