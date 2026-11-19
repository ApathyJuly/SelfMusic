package com.selfmusic.server.media;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class MediaUrlService {

    private final MediaTokenService tokenService;

    public String songStreamUrl(String songId, String userId, boolean download) {
        String token = tokenService.issue(songId, userId, "audio", tokenService.audioTtl(), download);
        return "/api/v1/media/songs/" + songId + "?token=" + encode(token);
    }

    public String songCoverUrl(String songId, String userId) {
        String token = tokenService.issue(songId, userId, "cover", tokenService.coverTtl(), false);
        return "/api/v1/media/songs/" + songId + "/cover?token=" + encode(token);
    }

    public String playlistCoverUrl(String playlistId, String userId) {
        String token = tokenService.issue(playlistId, userId, "playlist-cover", tokenService.coverTtl(), false);
        return "/api/v1/media/playlists/" + playlistId + "/cover?token=" + encode(token);
    }

    public long audioTtlSeconds() {
        return tokenService.audioTtl().toSeconds();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
