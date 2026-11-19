package com.selfmusic.server.api.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReorderRequest(@NotEmpty List<String> playlistIds) {
}
