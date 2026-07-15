// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.storage;

import java.util.Set;

/**
 * Interface defining contracts for ObjectStorageClient functionality.
 *
 * This interface is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 1.0.0
 */
public interface ObjectStorageClient extends AutoCloseable {
    Set<String> listBuckets();
    void put(String bucket, String key, byte[] content);
    byte[] get(String bucket, String key);
    long size(String bucket, String key);
    boolean exists(String bucket, String key);
    Set<String> list(String bucket, String prefix);
    void verifyCredentialsRejected();
    void verifyListDenied(String bucket);
    void multipartPut(String bucket, String key, byte[] content, int partSize);
    void delete(String bucket, String key);
    @Override void close();
}
