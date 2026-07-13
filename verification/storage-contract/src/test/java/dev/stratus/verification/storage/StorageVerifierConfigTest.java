package dev.stratus.verification.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class StorageVerifierConfigTest {
    @Test
    void loadsStrictHttpsCephConfiguration() {
        var config = StorageVerifierConfig.from(environment());

        assertEquals("https://object-store.stratus.local", config.endpoint().toString());
        assertEquals("verification-user", config.accessKey());
        assertTrue(config.pathStyleAccess());
        assertEquals(StorageVerifierConfig.REQUIRED_BUCKETS, config.requiredBuckets());
    }

    @Test
    void rejectsPlaintextEndpointByDefault() {
        var environment = environment();
        environment.put("CEPH_RGW_ENDPOINT", "http://object-store.stratus.local");

        var error = assertThrows(IllegalArgumentException.class,
                () -> StorageVerifierConfig.from(environment));

        assertTrue(error.getMessage().contains("HTTPS"));
    }

    @Test
    void permitsHttpOnlyWhenExplicitlyEnabledForDisposableDevelopment() {
        var environment = environment();
        environment.put("CEPH_RGW_ENDPOINT", "http://127.0.0.1:8000");
        environment.put("CEPH_RGW_ALLOW_HTTP", "true");

        assertEquals("http", StorageVerifierConfig.from(environment).endpoint().getScheme());
    }

    @Test
    void rejectsEndpointWithEmbeddedCredentialsOrPath() {
        var withCredentials = environment();
        withCredentials.put("CEPH_RGW_ENDPOINT", "https://user:secret@object-store.stratus.local");
        assertThrows(IllegalArgumentException.class, () -> StorageVerifierConfig.from(withCredentials));

        var withPath = environment();
        withPath.put("CEPH_RGW_ENDPOINT", "https://object-store.stratus.local/s3");
        assertThrows(IllegalArgumentException.class, () -> StorageVerifierConfig.from(withPath));
    }

    @Test
    void neverExposesSecretInDiagnosticText() {
        var config = StorageVerifierConfig.from(environment());

        assertFalse(config.toString().contains("verification-secret"));
    }

    @Test
    void rejectsMissingMalformedAndUnsupportedConfiguration() {
        for (var name : new String[]{"CEPH_RGW_ENDPOINT", "CEPH_RGW_ACCESS_KEY", "CEPH_RGW_SECRET_KEY"}) {
            var missing = environment();
            missing.remove(name);
            assertThrows(IllegalArgumentException.class, () -> StorageVerifierConfig.from(missing));
        }
        for (var endpoint : new String[]{"not a uri", "relative", "https:opaque", "ftp://object-store.stratus.local",
                "https://object-store.stratus.local?query=yes", "https://object-store.stratus.local#fragment"}) {
            var invalid = environment();
            invalid.put("CEPH_RGW_ENDPOINT", endpoint);
            invalid.put("CEPH_RGW_ALLOW_HTTP", "true");
            assertThrows(IllegalArgumentException.class, () -> StorageVerifierConfig.from(invalid));
        }
    }

    @Test
    void validatesDirectConstructionAndOptionalSettings() {
        assertThrows(NullPointerException.class, () -> new StorageVerifierConfig(null, "key", "secret", true,
                StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing"));
        assertThrows(IllegalArgumentException.class, () -> new StorageVerifierConfig(
                java.net.URI.create("https://host"), " ", "secret", true, StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing"));
        assertThrows(IllegalArgumentException.class, () -> new StorageVerifierConfig(
                java.net.URI.create("https://host"), "key", " ", true, StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing"));
        assertThrows(IllegalArgumentException.class, () -> new StorageVerifierConfig(
                java.net.URI.create("https://host"), "key", "secret", true, StorageVerifierConfig.REQUIRED_BUCKETS, "other"));
        var custom = environment();
        custom.put("S3_PATH_STYLE_ACCESS", "false");
        custom.put("CEPH_RGW_PROBE_BUCKET", "stratus-gold");
        assertFalse(StorageVerifierConfig.from(custom).pathStyleAccess());
        assertEquals("stratus-gold", StorageVerifierConfig.from(custom).probeBucket());
        var rootPath = environment();
        rootPath.put("CEPH_RGW_ENDPOINT", "https://object-store.stratus.local/");
        assertEquals("/", StorageVerifierConfig.from(rootPath).endpoint().getPath());
    }

    private static Map<String, String> environment() {
        var environment = new HashMap<String, String>();
        environment.put("CEPH_RGW_ENDPOINT", "https://object-store.stratus.local");
        environment.put("CEPH_RGW_ACCESS_KEY", "verification-user");
        environment.put("CEPH_RGW_SECRET_KEY", "verification-secret");
        environment.put("S3_PATH_STYLE_ACCESS", "true");
        return environment;
    }
}
