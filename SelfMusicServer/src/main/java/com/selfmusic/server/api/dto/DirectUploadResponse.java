package com.selfmusic.server.api.dto;

public record DirectUploadResponse(
        String songId,
        String audioKey,
        String uploadUrl,
        String contentType,
        long expiresIn
) {
}
