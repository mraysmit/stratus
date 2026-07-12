package dev.stratus.verification.storage;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
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

import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

public final class S3ObjectStorageClient implements ObjectStorageClient {
    private final S3Client client;

    private S3ObjectStorageClient(S3Client client) {
        this.client = client;
    }

    public static S3ObjectStorageClient create(StorageVerifierConfig config) {
        var client = S3Client.builder()
                .endpointOverride(config.endpoint())
                .region(Region.of("default"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.accessKey(), config.secretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(config.pathStyleAccess())
                        .build())
                .build();
        return new S3ObjectStorageClient(client);
    }

    @Override
    public Set<String> listBuckets() {
        return client.listBuckets().buckets().stream().map(bucket -> bucket.name()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void put(String bucket, String key, byte[] content) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(content));
    }

    @Override
    public byte[] get(String bucket, String key) {
        return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    @Override
    public long size(String bucket, String key) {
        return client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength();
    }

    @Override
    public Set<String> list(String bucket, String prefix) {
        return client.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
                .contents().stream().map(object -> object.key()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void multipartPut(String bucket, String key, byte[] content, int partSize) {
        var created = client.createMultipartUpload(CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build());
        var parts = new ArrayList<CompletedPart>();
        try {
            var partNumber = 1;
            for (var offset = 0; offset < content.length; offset += partSize) {
                var length = Math.min(partSize, content.length - offset);
                var body = RequestBody.fromByteBuffer(java.nio.ByteBuffer.wrap(content, offset, length));
                var response = client.uploadPart(UploadPartRequest.builder().bucket(bucket).key(key)
                        .uploadId(created.uploadId()).partNumber(partNumber).contentLength((long) length).build(), body);
                parts.add(CompletedPart.builder().partNumber(partNumber).eTag(response.eTag()).build());
                partNumber++;
            }
            client.completeMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(created.uploadId())
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()));
        } catch (RuntimeException exception) {
            client.abortMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(created.uploadId()));
            throw exception;
        }
    }

    @Override
    public void delete(String bucket, String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void close() {
        client.close();
    }
}
