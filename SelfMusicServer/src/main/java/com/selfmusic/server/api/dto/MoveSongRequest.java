package com.selfmusic.server.api.dto;

import jakarta.validation.constraints.NotBlank;

public record MoveSongRequest(@NotBlank String playlistId) {
}
