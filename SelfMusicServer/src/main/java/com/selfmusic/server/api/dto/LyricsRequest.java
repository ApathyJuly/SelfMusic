package com.selfmusic.server.api.dto;

public record LyricsRequest(
        String lyricsText,
        Double lyricSpeed,
        Long lyricsOffsetMs
) {
}
