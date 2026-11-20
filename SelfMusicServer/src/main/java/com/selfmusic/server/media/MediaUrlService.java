package com.selfmusic.server.media;

import com.selfmusic.server.music.Playlist;
import com.selfmusic.server.music.Song;
import com.selfmusic.server.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class MediaUrlService {

    private final MediaTokenService tokenService;
    private final MediaStreamService streamService;
    private final StorageService storage;

    public String songStreamUrl(Song song, boolean download) {
        if (storage.supportsPresignedUrls()) {
            return storage.presignedGetUrl(
                    song.getAudioObjectKey(),
                    tokenService.audioTtl(),
                    download ? streamService.contentDisposition(song.getTitle()) : null,
                    song.getMimeType()
            );
        }
        return relativeSongStreamUrl(song.getId(), song.getUserId(), download);
    }

    public String songCoverUrl(Song song) {
        if (storage.supportsPresignedUrls()) {
            return storage.presignedGetUrl(
                    song.getCoverObjectKey(),
                    tokenService.coverTtl(),
                    null,
                    null
            );
        }
        return relativeSongCoverUrl(song.getId(), song.getUserId());
    }

    public String playlistCoverUrl(Playlist playlist) {
        if (storage.supportsPresignedUrls()) {
            return storage.presignedGetUrl(
                    playlist.getCoverObjectKey(),
                    tokenService.coverTtl(),
                    null,
                    null
            );
        }
        return relativePlaylistCoverUrl(playlist.getId(), playlist.getUserId());
    }

    public long audioTtlSeconds() {
        return tokenService.audioTtl().toSeconds();
    }

    private String relativeSongStreamUrl(String songId, String userId, boolean download) {
        String token = tokenService.issue(songId, userId, "audio", tokenService.audioTtl(), download);
        return "/api/v1/media/songs/" + songId + "?token=" + encode(token);
    }

    private String relativeSongCoverUrl(String songId, String userId) {
        String token = tokenService.issue(songId, userId, "cover", tokenService.coverTtl(), false);
        return "/api/v1/media/songs/" + songId + "/cover?token=" + encode(token);
    }

    private String relativePlaylistCoverUrl(String playlistId, String userId) {
        String token = tokenService.issue(playlistId, userId, "playlist-cover", tokenService.coverTtl(), false);
        return "/api/v1/media/playlists/" + playlistId + "/cover?token=" + encode(token);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
