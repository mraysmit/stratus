package dev.stratus.verification.storage;

import java.util.Set;

public interface ObjectStorageClient extends AutoCloseable {
    Set<String> listBuckets();
    void put(String bucket, String key, byte[] content);
    byte[] get(String bucket, String key);
    long size(String bucket, String key);
    Set<String> list(String bucket, String prefix);
    void multipartPut(String bucket, String key, byte[] content, int partSize);
    void delete(String bucket, String key);
    @Override void close();
}
