package com.selfmusic.server.storage;

import java.io.InputStream;
import java.time.Duration;

public interface StorageService {

    void put(String key, InputStream input, long size, String contentType);

    InputStream open(String key, long start, long endInclusive);

    StorageMetadata head(String key);

    void delete(String key);

    boolean exists(String key);

    default boolean supportsPresignedUrls() {
        return false;
    }

    default String presignedGetUrl(String key, Duration ttl, String contentDisposition, String contentType) {
        throw new UnsupportedOperationException("当前存储不支持预签名下载");
    }

    default String presignedPutUrl(String key, Duration ttl, String contentType) {
        throw new UnsupportedOperationException("当前存储不支持预签名上传");
    }
}
