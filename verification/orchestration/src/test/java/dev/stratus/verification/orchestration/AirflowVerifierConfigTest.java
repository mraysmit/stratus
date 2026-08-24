// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Configuration boundary for the Airflow API verifier. These tests keep bad endpoints,
 * absent credentials, unusable polling intervals, and accidental credential rendering from
 * reaching the HTTP transport.
 */
@Tag("unit")
final class AirflowVerifierConfigTest {

    private static Map<String, String> completeEnvironment() {
        var environment = new HashMap<String, String>();
        environment.put("STRATUS_AIRFLOW_BASE_URL", "http://127.0.0.1:8088");
        environment.put("STRATUS_AIRFLOW_ALLOW_HTTP", "true");
        environment.put("STRATUS_AIRFLOW_USERNAME", "admin");
        environment.put("STRATUS_AIRFLOW_PASSWORD", "do-not-render");
        return environment;
    }

    @Test
    void appliesBoundedPollingDefaults() {
        var config = AirflowVerifierConfig.from(completeEnvironment());

        assertEquals(Duration.ofSeconds(2), config.pollInterval());
        assertEquals(Duration.ofMinutes(15), config.runTimeout());
        assertEquals("http://127.0.0.1:8088", config.baseUri().toString());
        assertFalse(config.anonymousAdmin());
    }

    @Test
    void acceptsExplicitDurations() {
        var environment = completeEnvironment();
        environment.put("STRATUS_AIRFLOW_POLL_INTERVAL_MS", "25");
        environment.put("STRATUS_AIRFLOW_RUN_TIMEOUT_MS", "9000");

        var config = AirflowVerifierConfig.from(environment);

        assertEquals(Duration.ofMillis(25), config.pollInterval());
        assertEquals(Duration.ofSeconds(9), config.runTimeout());
    }

    @Test
    void rejectsMissingValuesAndUnsafeEndpoints() {
        for (String required : new String[] {
                "STRATUS_AIRFLOW_BASE_URL",
                "STRATUS_AIRFLOW_USERNAME",
                "STRATUS_AIRFLOW_PASSWORD"}) {
            var environment = completeEnvironment();
            environment.remove(required);
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> AirflowVerifierConfig.from(environment));
            assertTrue(failure.getMessage().contains(required));
        }

        for (String invalid : new String[] {
                "ftp://127.0.0.1:8088",
                "http://user:secret@127.0.0.1:8088",
                "http://127.0.0.1:8088/api/v2",
                "not-a-url"}) {
            var environment = completeEnvironment();
            environment.put("STRATUS_AIRFLOW_BASE_URL", invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> AirflowVerifierConfig.from(environment), invalid);
        }

        var insecure = completeEnvironment();
        insecure.remove("STRATUS_AIRFLOW_ALLOW_HTTP");
        assertThrows(IllegalArgumentException.class,
                () -> AirflowVerifierConfig.from(insecure));
    }

    @Test
    void rejectsNonPositiveOrUnboundedDurations() {
        for (String value : new String[] {"0", "-1", "not-a-number", "3600001"}) {
            var environment = completeEnvironment();
            environment.put("STRATUS_AIRFLOW_RUN_TIMEOUT_MS", value);
            assertThrows(IllegalArgumentException.class,
                    () -> AirflowVerifierConfig.from(environment), value);
        }
    }

    @Test
    void redactsPasswordFromRenderedConfiguration() {
        String rendered = AirflowVerifierConfig.from(completeEnvironment()).toString();

        assertFalse(rendered.contains("do-not-render"));
        assertTrue(rendered.contains("<redacted>"));
    }
}
