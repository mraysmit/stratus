package dev.stratus.verification.storage;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class S3ObjectStorageClient implements ObjectStorageClient {
    private final S3Client client;

    S3ObjectStorageClient(S3Client client) {
        this.client = client;
    }

    public static S3ObjectStorageClient create(StorageVerifierConfig config) {
        var client = S3Client.builder()
                .endpointOverride(config.endpoint())
                .region(Region.of("default"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.accessKey(), config.secretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(config.connectionTimeout())
                        .socketTimeout(config.socketTimeout()))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(config.apiCallAttemptTimeout())
                        .apiCallTimeout(config.apiCallTimeout())
                        .addExecutionInterceptor(new AttemptLoggingInterceptor())
                        .build())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(config.pathStyleAccess())
                        .build())
                .build();
        return new S3ObjectStorageClient(client);
    }

    @Override
    public Set<String> listBuckets() {
        return execute("LIST_BUCKETS", null, null, () -> client.listBuckets().buckets().stream()
                .map(bucket -> bucket.name()).collect(Collectors.toUnmodifiableSet()));
    }

    @Override
    public void put(String bucket, String key, byte[] content) {
        execute("PUT", bucket, key, content.length, () ->
                client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(content)));
    }

    @Override
    public byte[] get(String bucket, String key) {
        return execute("GET", bucket, key, () ->
                client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray());
    }

    @Override
    public long size(String bucket, String key) {
        return execute("HEAD", bucket, key, () ->
                client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength());
    }

    @Override
    public boolean exists(String bucket, String key) {
        var context = context("EXISTS", bucket, key, -1);
        var started = System.nanoTime();
        StorageVerifier.LOGGER.fine(() -> context + " started");
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            logSuccess(context, started, " exists=true");
            return true;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                logSuccess(context, started, " exists=false");
                return false;
            }
            logFailure(context, started, exception);
            throw exception;
        } catch (RuntimeException exception) {
            logFailure(context, started, exception);
            throw exception;
        }
    }

    @Override
    public Set<String> list(String bucket, String prefix) {
        return execute("LIST", bucket, prefix, () -> client.listObjectsV2Paginator(
                        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).maxKeys(2).build())
                .contents().stream().map(object -> object.key()).collect(Collectors.toUnmodifiableSet()));
    }

    @Override
    public void verifyCredentialsRejected() {
        expectServiceRejection("INVALID_CREDENTIALS", null, Set.of(401, 403), client::listBuckets);
    }

    @Override
    public void verifyListDenied(String bucket) {
        expectServiceRejection("LIST_DENIED", bucket, Set.of(403), () -> client.listObjectsV2(
                ListObjectsV2Request.builder().bucket(bucket).maxKeys(1).build()));
    }

    @Override
    public void multipartPut(String bucket, String key, byte[] content, int partSize) {
        var created = execute("MULTIPART_CREATE", bucket, key, () -> client.createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build()));
        var parts = new ArrayList<CompletedPart>();
        try {
            var partNumber = 1;
            for (var offset = 0; offset < content.length; offset += partSize) {
                var length = Math.min(partSize, content.length - offset);
                var body = RequestBody.fromByteBuffer(java.nio.ByteBuffer.wrap(content, offset, length));
                var currentPart = partNumber;
                var response = execute("MULTIPART_PART_" + currentPart, bucket, key, length, () ->
                        client.uploadPart(UploadPartRequest.builder().bucket(bucket).key(key)
                                .uploadId(created.uploadId()).partNumber(currentPart).contentLength((long) length).build(), body));
                parts.add(CompletedPart.builder().partNumber(partNumber).eTag(response.eTag()).build());
                partNumber++;
            }
            execute("MULTIPART_COMPLETE", bucket, key, content.length, () -> client.completeMultipartUpload(request ->
                    request.bucket(bucket).key(key).uploadId(created.uploadId())
                            .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())));
        } catch (RuntimeException exception) {
            try {
                execute("MULTIPART_ABORT", bucket, key, () -> client.abortMultipartUpload(request ->
                        request.bucket(bucket).key(key).uploadId(created.uploadId())));
            } catch (RuntimeException abortException) {
                exception.addSuppressed(abortException);
            }
            throw exception;
        }
    }

    @Override
    public void delete(String bucket, String key) {
        execute("DELETE", bucket, key, () ->
                client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()));
    }

    @Override
    public void close() {
        StorageVerifier.LOGGER.fine("Closing S3 client");
        client.close();
    }

    private static <T> T execute(String operation, String bucket, String key, Supplier<T> action) {
        return execute(operation, bucket, key, -1, action);
    }

    private static <T> T execute(String operation, String bucket, String key, int bytes, Supplier<T> action) {
        var context = context(operation, bucket, key, bytes);
        var started = System.nanoTime();
        StorageVerifier.LOGGER.fine(() -> context + " started");
        try {
            var result = action.get();
            logSuccess(context, started, "");
            return result;
        } catch (RuntimeException exception) {
            logFailure(context, started, exception);
            throw exception;
        }
    }

    private static void expectServiceRejection(String operation, String bucket, Set<Integer> acceptedStatuses,
                                               Supplier<?> action) {
        var context = context(operation, bucket, null, -1);
        var started = System.nanoTime();
        StorageVerifier.LOGGER.fine(() -> context + " started");
        try {
            action.get();
        } catch (SdkServiceException exception) {
            if (acceptedStatuses.contains(exception.statusCode())) {
                logSuccess(context, started, " rejectedStatus=" + exception.statusCode()
                        + " requestId=" + safe(exception.requestId()));
                return;
            }
            logFailure(context, started, exception);
            throw exception;
        } catch (RuntimeException exception) {
            logFailure(context, started, exception);
            throw exception;
        }
        var failure = new IllegalStateException(context + " unexpectedly succeeded");
        logFailure(context, started, failure);
        throw failure;
    }

    private static void logSuccess(String context, long started, String suffix) {
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        StorageVerifier.LOGGER.fine(() -> context + " succeeded elapsedMs=" + elapsedMillis + suffix);
    }

    private static void logFailure(String context, long started, RuntimeException exception) {
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        var service = exception instanceof SdkServiceException value
                ? " status=" + value.statusCode() + " requestId=" + safe(value.requestId()) : "";
        StorageVerifier.LOGGER.log(Level.WARNING,
                context + " failed elapsedMs=" + elapsedMillis + service
                        + " exception=" + exception.getClass().getSimpleName(), exception);
    }

    private static String context(String operation, String bucket, String key, int bytes) {
        return "S3 operation=" + operation
                + (bucket == null ? "" : " bucket=" + bucket)
                + (key == null ? "" : " key=" + key)
                + (bytes < 0 ? "" : " bytes=" + bytes);
    }

    private static String safe(String value) {
        return value == null ? "unavailable" : value;
    }

    private static final class AttemptLoggingInterceptor implements ExecutionInterceptor {
        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes executionAttributes) {
            StorageVerifier.LOGGER.fine(() -> "S3 HTTP attempt method="
                    + context.httpRequest().method() + " uri=" + context.httpRequest().getUri());
        }

        @Override
        public void afterTransmission(Context.AfterTransmission context, ExecutionAttributes executionAttributes) {
            StorageVerifier.LOGGER.fine(() -> "S3 HTTP response status="
                    + context.httpResponse().statusCode() + " requestId="
                    + context.httpResponse().firstMatchingHeader("x-amz-request-id").orElse("unavailable"));
        }
    }
}
