package dev.stratus.verification.storage;

import java.time.Clock;
import java.io.PrintStream;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.nio.file.Path;

public final class StorageVerifierMain {
    static Supplier<Map<String, String>> environment = System::getenv;
    static Function<StorageVerifierConfig, ObjectStorageClient> clientFactory = S3ObjectStorageClient::create;
    static PrintStream output = System.out;
    static PrintStream error = System.err;
    static IntConsumer exit = System::exit;

    private StorageVerifierMain() { }

    public static void main(String[] args) {
        exit.accept(run(environment.get(), clientFactory, output, error));
    }

    static int run(Map<String, String> environment, Function<StorageVerifierConfig, ObjectStorageClient> clientFactory,
                   PrintStream output, PrintStream error) {
        try {
            StorageContractVerifier.configureLogging(environment.getOrDefault("STRATUS_LOG_LEVEL", "INFO"));
            StorageContractVerifier.configurePersistentLogging(
                    Path.of(environment.getOrDefault("STRATUS_LOG_FILE", "logs/storage-contract-verifier.%g.log")),
                    Integer.parseInt(environment.getOrDefault("STRATUS_LOG_MAX_BYTES", "10485760")),
                    Integer.parseInt(environment.getOrDefault("STRATUS_LOG_FILE_COUNT", "5")));
            var config = StorageVerifierConfig.from(environment);
            try (var client = clientFactory.apply(config)) {
                var report = new StorageContractVerifier(client, Clock.systemUTC())
                        .verify(config.requiredBuckets(), config.probeBucket());
                output.println(report.toJson());
                return report.success() ? 0 : 2;
            }
        } catch (IllegalArgumentException exception) {
            error.println("Configuration error: " + exception.getMessage());
            return 64;
        } catch (RuntimeException exception) {
            error.println("Verification error: " + exception.getMessage());
            return 2;
        } finally {
            StorageContractVerifier.closePersistentLogging();
        }
    }
}
