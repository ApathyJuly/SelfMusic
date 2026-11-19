package com.selfmusic.server.storage;

import java.io.InputStream;

public interface StorageService {

    void put(String key, InputStream input, long size, String contentType);

    InputStream open(String key, long start, long endInclusive);

    StorageMetadata head(String key);

    void delete(String key);

    boolean exists(String key);
}
