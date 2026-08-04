// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pure validation behavior of the secret-store verifier configuration:
 * required values enforced by name, insecure transport gated by the explicit
 * disposable-development override, defaults applied, and the token redacted
 * from toString.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-04
 * @version 1.0.0
 */
@Tag("unit")
final class SecretStoreVerifierConfigTest {

    private static Map<String, String> completeEnvironment() {
        var environment = new HashMap<String, String>();
        environment.put("OPENBAO_ENDPOINT", "http://127.0.0.1:8200");
        environment.put("OPENBAO_ALLOW_HTTP", "true");
        environment.put("OPENBAO_TOKEN", "dev-token-value");
        return environment;
    }

    @Test
    void buildsFromACompleteEnvironmentWithDefaults() {
        var config = SecretStoreVerifierConfig.from(completeEnvironment());

        assertEquals("http://127.0.0.1:8200", config.endpoint().toString());
        assertEquals("secret", config.kvMount(), "the KV mount must default to the harness value");
        assertEquals("stratus/service-identities", config.serviceIdentityPath(),
                "the identity path must default to the harness value");
    }

    @Test
    void rejectsEveryMissingRequiredValueByName() {
        for (String required : new String[] {"OPENBAO_ENDPOINT", "OPENBAO_TOKEN"}) {
            var environment = completeEnvironment();
            environment.remove(required);
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> SecretStoreVerifierConfig.from(environment),
                    required + " must be rejected when absent");
            assertTrue(failure.getMessage().contains(required));
        }
    }

    @Test
    void rejectsPlainHttpWithoutTheDevelopmentOverride() {
        var environment = completeEnvironment();
        environment.remove("OPENBAO_ALLOW_HTTP");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> SecretStoreVerifierConfig.from(environment));
        assertTrue(failure.getMessage().contains("OPENBAO_ALLOW_HTTP"));
    }

    @Test
    void rejectsAnEndpointThatIsNotAnOriginUrl() {
        for (String invalid : new String[] {
                "http://user:secret@127.0.0.1:8200",
                "http://127.0.0.1:8200/some/path",
                "ftp://127.0.0.1:8200"}) {
            var environment = completeEnvironment();
            environment.put("OPENBAO_ENDPOINT", invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> SecretStoreVerifierConfig.from(environment),
                    invalid + " must be rejected");
        }
    }

    @Test
    void redactsTheTokenFromToString() {
        var rendered = SecretStoreVerifierConfig.from(completeEnvironment()).toString();

        assertFalse(rendered.contains("dev-token-value"), "the token must never render");
        assertTrue(rendered.contains("<redacted>"));
    }
}
