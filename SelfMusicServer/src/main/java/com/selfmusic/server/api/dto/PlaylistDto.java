package com.selfmusic.server.api.dto;

import java.time.Instant;

public record PlaylistDto(
        String id,
        String name,
        String coverUrl,
        long songCount,
        int sortOrder,
        Instant updatedAt
) {
}
