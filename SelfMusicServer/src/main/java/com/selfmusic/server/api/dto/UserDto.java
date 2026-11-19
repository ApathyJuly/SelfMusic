package com.selfmusic.server.api.dto;

import java.time.Instant;

public record UserDto(
        String id,
        String username,
        String displayName,
        Instant createdAt,
        String avatarObjectKey
) {
}
