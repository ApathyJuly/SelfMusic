package com.selfmusic.server.api;

import com.selfmusic.server.common.ApiException;
import com.selfmusic.server.media.InvalidRangeException;
import com.selfmusic.server.media.MediaStreamService;
import com.selfmusic.server.media.MediaTokenService;
import com.selfmusic.server.music.MusicService;
import com.selfmusic.server.music.Playlist;
import com.selfmusic.server.music.Song;
import com.selfmusic.server.storage.StorageMetadata;
import com.selfmusic.server.storage.StorageService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaTokenService tokenService;
    private final MediaStreamService streamService;
    private final StorageService storage;
    private final MusicService musicService;

    @GetMapping("/songs/{songId}")
    public void streamSong(
            @PathVariable String songId,
            @RequestParam("token") String token,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            HttpServletResponse response
    ) throws IOException {
        MediaTokenService.Payload payload = verifyToken(token, songId, "audio");
        Song song = musicService.requireSong(songId, payload.userId());
        serve(response, song.getAudioObjectKey(), song.getMimeType(),
                filename(song.getTitle(), song.getAudioObjectKey()), rangeHeader, payload.download());
    }

    @RequestMapping(value = "/songs/{songId}", method = RequestMethod.HEAD)
    public void headSong(
            @PathVariable String songId,
            @RequestParam("token") String token,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            HttpServletResponse response
    ) {
        MediaTokenService.Payload payload = verifyToken(token, songId, "audio");
        Song song = musicService.requireSong(songId, payload.userId());
        serveHead(response, song.getAudioObjectKey(), song.getMimeType(),
                filename(song.getTitle(), song.getAudioObjectKey()), rangeHeader, payload.download());
    }

    @GetMapping("/songs/{songId}/cover")
    public void streamSongCover(
            @PathVariable String songId,
            @RequestParam("token") String token,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            HttpServletResponse response
    ) throws IOException {
        MediaTokenService.Payload payload = verifyToken(token, songId, "cover");
        Song song = musicService.requireSong(songId, payload.userId());
        if (song.getCoverObjectKey() == null) {
            throw ApiException.notFound("歌曲封面不存在");
        }
        serve(response, song.getCoverObjectKey(), null, "cover", rangeHeader, false);
    }

    @GetMapping("/playlists/{playlistId}/cover")
    public void streamPlaylistCover(
            @PathVariable String playlistId,
            @RequestParam("token") String token,
            @RequestHeader(value = "Range", required = false) String rangeHeader,
            HttpServletResponse response
    ) throws IOException {
        MediaTokenService.Payload payload = verifyToken(token, playlistId, "playlist-cover");
        Playlist playlist = musicService.requirePlaylist(playlistId, payload.userId());
        if (playlist.getCoverObjectKey() == null) {
            throw ApiException.notFound("歌单封面不存在");
        }
        serve(response, playlist.getCoverObjectKey(), null, "cover", rangeHeader, false);
    }

    private void serve(
            HttpServletResponse response,
            String key,
            String contentType,
            String filename,
            String rangeHeader,
            boolean download
    ) throws IOException {
        StorageMetadata metadata = storage.head(key);
        MediaStreamService.Range range;
        try {
            range = streamService.parseRange(rangeHeader, metadata.size());
        } catch (InvalidRangeException exception) {
            response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + metadata.size());
            return;
        }
        long start = range == null ? 0 : range.start();
        long end = range == null ? metadata.size() - 1 : range.end();
        long length = end - start + 1;

        applyHeaders(response, metadata, contentType, filename, start, end, length, range != null, download);
        try (InputStream input = storage.open(key, start, end)) {
            streamService.copy(input, response.getOutputStream(), length);
        }
    }

    private void serveHead(
            HttpServletResponse response,
            String key,
            String contentType,
            String filename,
            String rangeHeader,
            boolean download
    ) {
        StorageMetadata metadata = storage.head(key);
        MediaStreamService.Range range;
        try {
            range = streamService.parseRange(rangeHeader, metadata.size());
        } catch (InvalidRangeException exception) {
            response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + metadata.size());
            return;
        }
        long start = range == null ? 0 : range.start();
        long end = range == null ? metadata.size() - 1 : range.end();
        long length = end - start + 1;
        applyHeaders(response, metadata, contentType, filename, start, end, length, range != null, download);
    }

    private void applyHeaders(
            HttpServletResponse response,
            StorageMetadata metadata,
            String contentType,
            String filename,
            long start,
            long end,
            long length,
            boolean partial,
            boolean download
    ) {
        response.setStatus(partial ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
        response.setContentType(contentType == null || contentType.isBlank() ? metadata.contentType() : contentType);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setContentLengthLong(length);
        if (partial) {
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + metadata.size());
        }
        if (metadata.etag() != null) {
            response.setHeader(HttpHeaders.ETAG, metadata.etag());
        }
        if (download) {
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, streamService.contentDisposition(filename));
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=3600");
    }

    private MediaTokenService.Payload verifyToken(String token, String subjectId, String kind) {
        MediaTokenService.Payload payload = tokenService.verify(token);
        if (!payload.subjectId().equals(subjectId) || !payload.kind().equals(kind)) {
            throw ApiException.unauthorized("媒体链接与资源不匹配");
        }
        return payload;
    }

    private static String filename(String title, String key) {
        int dot = key == null ? -1 : key.lastIndexOf('.');
        String extension = dot >= 0 ? key.substring(dot) : "";
        return (title == null || title.isBlank() ? "audio" : title) + extension;
    }
}
