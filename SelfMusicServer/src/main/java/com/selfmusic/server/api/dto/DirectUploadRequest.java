package com.selfmusic.server.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DirectUploadRequest(
        @NotBlank
        @Size(max = 255)
        String filename,

        @NotBlank
        @Size(max = 100)
        String contentType,

        @Min(1)
        long fileSize,

        @Size(max = 200)
        String title,

        @Size(max = 200)
        String artist,

        @Min(0)
        long durationMs
) {
}
