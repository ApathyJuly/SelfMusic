package com.selfmusic.server.storage;

public record StorageMetadata(long size, String contentType, String etag) {
}
