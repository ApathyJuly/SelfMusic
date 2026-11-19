package com.selfmusic.server.api.dto;

import java.time.Instant;

public record SongDto(
        String id,
        String playlistId,
        String title,
        String artist,
        String album,
        long durationMs,
        String mimeType,
        long fileSize,
        String coverUrl,
        String lyricsText,
        boolean isFavorite,
        double lyricSpeed,
        long lyricsOffsetMs,
        String objectVersion,
        Instant createdAt,
        Instant updatedAt
) {
}
