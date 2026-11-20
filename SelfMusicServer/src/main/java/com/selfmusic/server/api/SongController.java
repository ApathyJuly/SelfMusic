package com.selfmusic.server.api;

import com.selfmusic.server.api.dto.DirectUploadCompleteRequest;
import com.selfmusic.server.api.dto.FavoriteRequest;
import com.selfmusic.server.api.dto.LyricsRequest;
import com.selfmusic.server.api.dto.MediaUrlResponse;
import com.selfmusic.server.api.dto.MoveSongRequest;
import com.selfmusic.server.api.dto.PlayUrlItem;
import com.selfmusic.server.api.dto.PlayUrlsRequest;
import com.selfmusic.server.api.dto.SongDto;
import com.selfmusic.server.music.MusicService;
import com.selfmusic.server.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/songs")
@RequiredArgsConstructor
public class SongController {

    private final MusicService musicService;

    @GetMapping
    public List<SongDto> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "playlistId", required = false) String playlistId
    ) {
        return musicService.listSongs(principal.id(), playlistId);
    }

    @PutMapping(value = "/{songId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SongDto update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String songId,
            @RequestParam(value = "title", required = false) String title,
            @RequestPart(value = "lyric", required = false) MultipartFile lyric,
            @RequestPart(value = "cover", required = false) MultipartFile cover
    ) {
        return musicService.updateSong(principal.id(), songId, title, lyric, cover);
    }

    @PatchMapping("/{songId}/move")
    public SongDto move(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String songId,
            @Valid @RequestBody MoveSongRequest request
    ) {
        return musicService.moveSong(principal.id(), songId, request.playlistId());
    }

    @PutMapping("/{songId}/favorite")
    public SongDto favorite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String songId,
            @RequestBody FavoriteRequest request
    ) {
        return musicService.updateFavorite(principal.id(), songId, request.favorite());
    }

    @PutMapping("/{songId}/lyrics")
    public SongDto lyrics(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String songId,
            @RequestBody LyricsRequest request
    ) {
        return musicService.updateLyrics(principal.id(), songId, request);
    }

    @DeleteMapping("/{songId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String songId
    ) {
        musicService.deleteSong(principal.id(), songId);
    }

    @PostMapping(value = "/{songId}/direct-upload/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SongDto completeDirectUpload(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String songId,
            @Valid @RequestBody DirectUploadCompleteRequest request
    ) {
        return musicService.completeDirectUpload(principal.id(), songId, request);
    }

    @GetMapping("/{songId}/play-url")
    public MediaUrlResponse playUrl(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String songId
    ) {
        return musicService.playUrl(principal.id(), songId, false);
    }

    @PostMapping("/play-urls")
    public List<PlayUrlItem> playUrls(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody PlayUrlsRequest request
    ) {
        return musicService.playUrls(principal.id(), request.songIds());
    }

    @GetMapping("/{songId}/download-url")
    public MediaUrlResponse downloadUrl(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String songId
    ) {
        return musicService.playUrl(principal.id(), songId, true);
    }
}
