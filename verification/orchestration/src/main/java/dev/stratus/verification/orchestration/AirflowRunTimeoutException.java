// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.orchestration;

/** A DAG run that did not reach an Airflow terminal state within its bounded observation window. */
public final class AirflowRunTimeoutException extends RuntimeException {

    private final String lastState;

    AirflowRunTimeoutException(String dagId, String runId, String lastState) {
        super("Airflow DAG run timed out: dagId=" + dagId + " runId=" + runId
                + " lastState=" + lastState);
        this.lastState = lastState;
    }

    public String lastState() {
        return lastState;
    }
}
