package dev.stratus.verification.storage;

import java.time.Clock;

public final class StorageVerifierMain {
    private StorageVerifierMain() { }

    public static void main(String[] args) {
        try {
            var config = StorageVerifierConfig.from(System.getenv());
            try (var client = S3ObjectStorageClient.create(config)) {
                var report = new StorageContractVerifier(client, Clock.systemUTC())
                        .verify(config.requiredBuckets(), config.probeBucket());
                System.out.println(report.toJson());
                if (!report.success()) System.exit(2);
            }
        } catch (IllegalArgumentException exception) {
            System.err.println("Configuration error: " + exception.getMessage());
            System.exit(64);
        } catch (RuntimeException exception) {
            System.err.println("Verification error: " + exception.getMessage());
            System.exit(2);
        }
    }
}
