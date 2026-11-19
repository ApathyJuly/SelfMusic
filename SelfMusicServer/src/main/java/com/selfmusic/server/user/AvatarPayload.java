package com.selfmusic.server.user;

public record AvatarPayload(byte[] bytes, String contentType) {
}
