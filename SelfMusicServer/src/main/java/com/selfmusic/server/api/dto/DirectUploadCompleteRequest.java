package com.selfmusic.server.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DirectUploadCompleteRequest(
        @NotBlank
        String playlistId,

        @NotBlank
        @Size(max = 512)
        String audioKey,

        @Size(max = 200)
        String title,

        @Size(max = 200)
        String artist,

        @Size(max = 100)
        String mimeType,

        long durationMs
) {
}
