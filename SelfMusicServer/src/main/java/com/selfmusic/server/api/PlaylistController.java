package com.selfmusic.server.api;

import com.selfmusic.server.api.dto.PlaylistDto;
import com.selfmusic.server.api.dto.ReorderRequest;
import com.selfmusic.server.api.dto.SongDto;
import com.selfmusic.server.music.MusicService;
import com.selfmusic.server.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/playlists")
@RequiredArgsConstructor
public class PlaylistController {

    private final MusicService musicService;

    @GetMapping
    public List<PlaylistDto> list(@AuthenticationPrincipal UserPrincipal principal) {
        return musicService.listPlaylists(principal.id());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PlaylistDto> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("name") String name,
            @RequestPart(value = "cover", required = false) MultipartFile cover
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(musicService.createPlaylist(principal.id(), name, cover));
    }

    @PutMapping(value = "/{playlistId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PlaylistDto update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String playlistId,
            @RequestParam("name") String name,
            @RequestPart(value = "cover", required = false) MultipartFile cover
    ) {
        return musicService.updatePlaylist(principal.id(), playlistId, name, cover);
    }

    @PatchMapping("/order")
    public List<PlaylistDto> reorder(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ReorderRequest request
    ) {
        return musicService.reorderPlaylists(principal.id(), request.playlistIds());
    }

    @DeleteMapping("/{playlistId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String playlistId
    ) {
        musicService.deletePlaylist(principal.id(), playlistId);
    }

    @GetMapping("/{playlistId}/songs")
    public List<SongDto> songs(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String playlistId
    ) {
        return musicService.listSongs(principal.id(), playlistId);
    }

    @PostMapping(value = "/{playlistId}/songs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SongDto> uploadSong(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String playlistId,
            @RequestPart("audio") MultipartFile audio,
            @RequestPart(value = "lyric", required = false) MultipartFile lyric,
            @RequestPart(value = "cover", required = false) MultipartFile cover,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "artist", required = false) String artist,
            @RequestParam(value = "durationMs", defaultValue = "0") long durationMs
    ) {
        SongDto song = musicService.createSong(
                principal.id(), playlistId, audio, lyric, cover, title, artist, durationMs
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(song);
    }
}
