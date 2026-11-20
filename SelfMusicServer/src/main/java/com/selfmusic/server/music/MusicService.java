package com.selfmusic.server.music;

import com.selfmusic.server.api.dto.DirectUploadCompleteRequest;
import com.selfmusic.server.api.dto.DirectUploadRequest;
import com.selfmusic.server.api.dto.DirectUploadResponse;
import com.selfmusic.server.api.dto.LyricsRequest;
import com.selfmusic.server.api.dto.MediaUrlResponse;
import com.selfmusic.server.api.dto.PlayUrlItem;
import com.selfmusic.server.api.dto.PlaylistDto;
import com.selfmusic.server.api.dto.SongDto;
import com.selfmusic.server.common.ApiException;
import com.selfmusic.server.media.MediaUrlService;
import com.selfmusic.server.storage.StorageMetadata;
import com.selfmusic.server.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MusicService {

    private static final long MAX_AUDIO_SIZE = 2L * 1024 * 1024 * 1024;
    private static final Duration DIRECT_UPLOAD_TTL = Duration.ofMinutes(30);
    private static final long MAX_COVER_SIZE = 10L * 1024 * 1024;
    private static final long MAX_LYRIC_SIZE = 2L * 1024 * 1024;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_PLAYLIST_NAME_LENGTH = 100;

    private static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");
    private static final Set<String> LYRIC_EXTENSIONS = Set.of("lrc", "txt");

    private final PlaylistRepository playlistRepository;
    private final SongRepository songRepository;
    private final StorageService storage;
    private final MediaUrlService mediaUrlService;

    @Transactional(readOnly = true)
    public List<PlaylistDto> listPlaylists(String userId) {
        List<Playlist> playlists = playlistRepository.findByUserIdOrderBySortOrderAscUpdatedAtDesc(userId);
        Map<String, Long> counts = songRepository.countByPlaylist(userId).stream()
                .collect(Collectors.toMap(SongRepository.PlaylistCount::getPlaylistId,
                        SongRepository.PlaylistCount::getCount, (left, right) -> left));
        return playlists.stream()
                .map(playlist -> toDto(playlist, counts.getOrDefault(playlist.getId(), 0L)))
                .toList();
    }

    @Transactional
    public PlaylistDto createPlaylist(String userId, String name, MultipartFile cover) {
        String cleanName = requiredName(name, MAX_PLAYLIST_NAME_LENGTH, "歌单名称");
        Playlist playlist = new Playlist();
        playlist.setUserId(userId);
        playlist.setName(cleanName);
        playlist.setSortOrder(playlistRepository.findMaxSortOrder(userId) + 1);
        playlistRepository.save(playlist);

        if (cover != null && !cover.isEmpty()) {
            String key = playlistCoverKey(userId, playlist.getId(), validateImage(cover, "jpg"));
            upload(cover, key, cover.getContentType());
            playlist.setCoverObjectKey(key);
            playlistRepository.save(playlist);
        }
        return toDto(playlist, 0);
    }

    @Transactional
    public PlaylistDto updatePlaylist(String userId, String playlistId, String name, MultipartFile cover) {
        Playlist playlist = requirePlaylist(playlistId, userId);
        String cleanName = requiredName(name, MAX_PLAYLIST_NAME_LENGTH, "歌单名称");
        playlist.setName(cleanName);
        if (cover != null && !cover.isEmpty()) {
            String key = playlistCoverKey(userId, playlistId, validateImage(cover, "jpg"));
            String oldKey = playlist.getCoverObjectKey();
            upload(cover, key, cover.getContentType());
            playlist.setCoverObjectKey(key);
            if (oldKey != null && !oldKey.equals(key)) {
                storage.delete(oldKey);
            }
        }
        playlistRepository.save(playlist);
        songRepository.syncAlbumName(playlistId, cleanName, Instant.now());
        long count = songRepository.countByPlaylistId(playlistId);
        return toDto(playlist, count);
    }

    @Transactional
    public List<PlaylistDto> reorderPlaylists(String userId, List<String> playlistIds) {
        if (playlistIds == null || playlistIds.isEmpty()) {
            throw ApiException.badRequest("invalid_order", "歌单顺序不能为空");
        }
        if (new HashSet<>(playlistIds).size() != playlistIds.size()) {
            throw ApiException.badRequest("invalid_order", "歌单顺序包含重复项");
        }
        List<Playlist> playlists = playlistRepository.findByUserIdOrderBySortOrderAscUpdatedAtDesc(userId);
        Map<String, Playlist> byId = playlists.stream()
                .collect(Collectors.toMap(Playlist::getId, playlist -> playlist, (left, right) -> left, LinkedHashMap::new));
        if (playlistIds.size() != playlists.size() || !byId.keySet().containsAll(playlistIds)) {
            throw ApiException.badRequest("invalid_order", "歌单顺序与当前账号不匹配");
        }
        for (int index = 0; index < playlistIds.size(); index++) {
            byId.get(playlistIds.get(index)).setSortOrder(index);
        }
        playlistRepository.saveAll(playlists);
        return listPlaylists(userId);
    }

    @Transactional
    public void deletePlaylist(String userId, String playlistId) {
        Playlist playlist = requirePlaylist(playlistId, userId);
        List<Song> songs = songRepository.findByPlaylistIdOrderBySortOrderAscCreatedAtAsc(playlistId);
        for (Song song : songs) {
            deleteSongObjects(song);
            songRepository.delete(song);
        }
        if (playlist.getCoverObjectKey() != null) {
            storage.delete(playlist.getCoverObjectKey());
        }
        playlistRepository.delete(playlist);
    }

    @Transactional(readOnly = true)
    public List<SongDto> listSongs(String userId, String playlistId) {
        List<Song> songs;
        if (playlistId == null || playlistId.isBlank()) {
            songs = songRepository.findByUserIdOrderByCreatedAtDesc(userId);
        } else {
            requirePlaylist(playlistId, userId);
            songs = songRepository.findByPlaylistIdOrderBySortOrderAscCreatedAtAsc(playlistId);
        }
        return songs.stream().map(this::toDto).toList();
    }

    @Transactional
    public SongDto createSong(
            String userId,
            String playlistId,
            MultipartFile audio,
            MultipartFile lyric,
            MultipartFile cover,
            String title,
            String artist,
            long durationMs
    ) {
        Playlist playlist = requirePlaylist(playlistId, userId);
        if (audio == null || audio.isEmpty()) {
            throw ApiException.badRequest("audio_required", "请选择音频文件");
        }
        String audioExtension = validateAudio(audio);
        String audioContentType = audioContentType(audio, audioExtension);

        String songId = UUID.randomUUID().toString();
        String root = songRoot(userId, songId);
        String audioKey = root + "/audio." + audioExtension;
        String coverKey = null;
        String lyricKey = null;
        String lyricsText = null;

        try {
            upload(audio, audioKey, audioContentType);
            if (cover != null && !cover.isEmpty()) {
                String coverExtension = validateImage(cover, "jpg");
                coverKey = root + "/cover." + coverExtension;
                upload(cover, coverKey, cover.getContentType());
            }
            if (lyric != null && !lyric.isEmpty()) {
                String lyricExtension = validateLyric(lyric);
                lyricKey = root + "/lyrics." + lyricExtension;
                lyricsText = readLyrics(lyric);
                upload(lyric, lyricKey, lyric.getContentType() == null ? "text/plain" : lyric.getContentType());
            }
        } catch (RuntimeException exception) {
            storage.delete(audioKey);
            if (coverKey != null) {
                storage.delete(coverKey);
            }
            if (lyricKey != null) {
                storage.delete(lyricKey);
            }
            throw exception;
        }

        String cleanTitle = title == null || title.isBlank()
                ? titleFromFilename(audio.getOriginalFilename())
                : title.trim();
        if (cleanTitle.length() > MAX_TITLE_LENGTH) {
            cleanTitle = cleanTitle.substring(0, MAX_TITLE_LENGTH);
        }
        String cleanArtist = artist == null || artist.isBlank() ? "未知艺术家" : artist.trim();
        if (cleanArtist.length() > 200) {
            cleanArtist = cleanArtist.substring(0, 200);
        }

        Song song = new Song();
        song.setId(songId);
        song.setUserId(userId);
        song.setPlaylistId(playlistId);
        song.setTitle(cleanTitle);
        song.setArtist(cleanArtist);
        song.setAlbum(playlist.getName());
        song.setDurationMs(Math.max(0, durationMs));
        song.setMimeType(audioContentType);
        song.setFileSize(audio.getSize());
        song.setAudioObjectKey(audioKey);
        song.setCoverObjectKey(coverKey);
        song.setLyricObjectKey(lyricKey);
        song.setLyricsText(lyricsText);
        Instant now = Instant.now();
        song.setObjectVersion(Long.toString(now.toEpochMilli()));
        song.setSortOrder(songRepository.findMaxSortOrder(playlistId) + 1);
        song.setCreatedAt(now);
        song.setUpdatedAt(now);
        songRepository.save(song);
        return toDto(song);
    }

    @Transactional(readOnly = true)
    public DirectUploadResponse initDirectUpload(String userId, String playlistId, DirectUploadRequest request) {
        requirePlaylist(playlistId, userId);
        if (!storage.supportsPresignedUrls()) {
            throw ApiException.badRequest("direct_upload_unavailable", "当前存储不支持直传，请使用普通上传");
        }
        String extension = validateAudio(request.filename(), request.contentType(), request.fileSize());
        String contentType = audioContentType(request.contentType(), extension);
        String songId = UUID.randomUUID().toString();
        String audioKey = songRoot(userId, songId) + "/audio." + extension;
        String uploadUrl = storage.presignedPutUrl(audioKey, DIRECT_UPLOAD_TTL, contentType);
        return new DirectUploadResponse(songId, audioKey, uploadUrl, contentType, DIRECT_UPLOAD_TTL.toSeconds());
    }

    @Transactional
    public SongDto completeDirectUpload(String userId, String songId, DirectUploadCompleteRequest request) {
        Playlist playlist = requirePlaylist(request.playlistId(), userId);
        String expectedPrefix = songRoot(userId, songId) + "/audio.";
        if (!request.audioKey().startsWith(expectedPrefix) || request.audioKey().contains("..")) {
            throw ApiException.badRequest("invalid_audio_key", "音频对象路径不合法");
        }
        if (songRepository.existsById(songId)) {
            throw ApiException.conflict("song_exists", "歌曲已存在");
        }
        StorageMetadata metadata;
        try {
            metadata = storage.head(request.audioKey());
        } catch (ApiException exception) {
            throw ApiException.badRequest("upload_incomplete", "音频尚未上传完成");
        }
        if (metadata.size() <= 0 || metadata.size() > MAX_AUDIO_SIZE) {
            throw ApiException.badRequest("invalid_audio", "音频文件为空或超过 2GB");
        }
        String extension = extensionOf(request.audioKey(), "mp3");
        if (!AUDIO_EXTENSIONS.contains(extension)) {
            throw ApiException.badRequest("invalid_audio", "不支持的音频格式: " + extension);
        }
        String contentType = request.mimeType();
        if (contentType == null || !contentType.startsWith("audio/")) {
            contentType = metadata.contentType();
        }
        if (contentType == null || contentType.isBlank()) {
            contentType = audioContentType((String) null, extension);
        }
        if (!contentType.startsWith("audio/") && !"application/octet-stream".equals(contentType)) {
            throw ApiException.badRequest("invalid_audio", "音频 Content-Type 不合法");
        }

        String title = request.title() == null || request.title().isBlank()
                ? "未命名歌曲"
                : request.title().trim();
        if (title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH);
        }
        String artist = request.artist() == null || request.artist().isBlank()
                ? "未知艺术家"
                : request.artist().trim();
        if (artist.length() > 200) {
            artist = artist.substring(0, 200);
        }

        Song song = new Song();
        song.setId(songId);
        song.setUserId(userId);
        song.setPlaylistId(playlist.getId());
        song.setTitle(title);
        song.setArtist(artist);
        song.setAlbum(playlist.getName());
        song.setDurationMs(Math.max(0, request.durationMs()));
        song.setMimeType(contentType);
        song.setFileSize(metadata.size());
        song.setAudioObjectKey(request.audioKey());
        Instant now = Instant.now();
        song.setObjectVersion(Long.toString(now.toEpochMilli()));
        song.setSortOrder(songRepository.findMaxSortOrder(playlist.getId()) + 1);
        song.setCreatedAt(now);
        song.setUpdatedAt(now);
        songRepository.save(song);
        return toDto(song);
    }

    @Transactional
    public SongDto updateSong(
            String userId,
            String songId,
            String title,
            MultipartFile lyric,
            MultipartFile cover
    ) {
        Song song = requireSong(songId, userId);
        if (title != null && !title.isBlank()) {
            String cleanTitle = title.trim();
            song.setTitle(cleanTitle.length() > MAX_TITLE_LENGTH ? cleanTitle.substring(0, MAX_TITLE_LENGTH) : cleanTitle);
        }
        if (cover != null && !cover.isEmpty()) {
            String oldKey = song.getCoverObjectKey();
            String key = songRoot(userId, songId) + "/cover." + validateImage(cover, "jpg");
            upload(cover, key, cover.getContentType());
            song.setCoverObjectKey(key);
            if (oldKey != null && !oldKey.equals(key)) {
                storage.delete(oldKey);
            }
        }
        if (lyric != null && !lyric.isEmpty()) {
            String oldKey = song.getLyricObjectKey();
            String key = songRoot(userId, songId) + "/lyrics." + validateLyric(lyric);
            song.setLyricsText(readLyrics(lyric));
            upload(lyric, key, lyric.getContentType() == null ? "text/plain" : lyric.getContentType());
            song.setLyricObjectKey(key);
            if (oldKey != null && !oldKey.equals(key)) {
                storage.delete(oldKey);
            }
        }
        Instant now = Instant.now();
        song.setObjectVersion(Long.toString(now.toEpochMilli()));
        song.setUpdatedAt(now);
        songRepository.save(song);
        return toDto(song);
    }

    @Transactional
    public SongDto moveSong(String userId, String songId, String targetPlaylistId) {
        Song song = requireSong(songId, userId);
        Playlist target = requirePlaylist(targetPlaylistId, userId);
        if (!target.getId().equals(song.getPlaylistId())) {
            song.setPlaylistId(target.getId());
            song.setAlbum(target.getName());
            song.setSortOrder(songRepository.findMaxSortOrder(target.getId()) + 1);
            Instant now = Instant.now();
            song.setObjectVersion(Long.toString(now.toEpochMilli()));
            song.setUpdatedAt(now);
            songRepository.save(song);
        }
        return toDto(song);
    }

    @Transactional
    public SongDto updateFavorite(String userId, String songId, boolean favorite) {
        Song song = requireSong(songId, userId);
        song.setFavorite(favorite);
        song.setUpdatedAt(Instant.now());
        songRepository.save(song);
        return toDto(song);
    }

    @Transactional
    public SongDto updateLyrics(String userId, String songId, LyricsRequest request) {
        Song song = requireSong(songId, userId);
        if (request.lyricsText() != null) {
            String text = request.lyricsText();
            if (text.length() > MAX_LYRIC_SIZE) {
                throw ApiException.badRequest("lyrics_too_large", "歌词文本过大");
            }
            song.setLyricsText(text);
        }
        if (request.lyricSpeed() != null) {
            double speed = request.lyricSpeed();
            if (speed < 0.25 || speed > 4.0) {
                throw ApiException.badRequest("invalid_speed", "歌词速度需在 0.25-4.0 之间");
            }
            song.setLyricSpeed(speed);
        }
        if (request.lyricsOffsetMs() != null) {
            song.setLyricsOffsetMs(request.lyricsOffsetMs());
        }
        Instant now = Instant.now();
        song.setObjectVersion(Long.toString(now.toEpochMilli()));
        song.setUpdatedAt(now);
        songRepository.save(song);
        return toDto(song);
    }

    @Transactional
    public void deleteSong(String userId, String songId) {
        Song song = requireSong(songId, userId);
        deleteSongObjects(song);
        songRepository.delete(song);
    }

    @Transactional(readOnly = true)
    public MediaUrlResponse playUrl(String userId, String songId, boolean download) {
        Song song = requireSong(songId, userId);
        String url = mediaUrlService.songStreamUrl(song, download);
        return new MediaUrlResponse(url, mediaUrlService.audioTtlSeconds());
    }

    @Transactional(readOnly = true)
    public List<PlayUrlItem> playUrls(String userId, List<String> songIds) {
        List<String> ids = songIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        Map<String, Song> byId = songRepository.findAllById(ids).stream()
                .filter(song -> userId.equals(song.getUserId()))
                .collect(Collectors.toMap(Song::getId, song -> song));
        if (byId.size() != ids.size()) {
            throw ApiException.notFound("歌曲不存在");
        }
        return ids.stream()
                .map(id -> new PlayUrlItem(id, mediaUrlService.songStreamUrl(byId.get(id), false)))
                .toList();
    }

    @Transactional(readOnly = true)
    public Song requireSong(String songId, String userId) {
        return songRepository.findByIdAndUserId(songId, userId)
                .orElseThrow(() -> ApiException.notFound("歌曲不存在"));
    }

    @Transactional(readOnly = true)
    public Playlist requirePlaylist(String playlistId, String userId) {
        return playlistRepository.findByIdAndUserId(playlistId, userId)
                .orElseThrow(() -> ApiException.notFound("歌单不存在"));
    }

    public SongDto toDto(Song song) {
        String coverUrl = song.getCoverObjectKey() == null
                ? null
                : mediaUrlService.songCoverUrl(song);
        return new SongDto(
                song.getId(),
                song.getPlaylistId(),
                song.getTitle(),
                song.getArtist(),
                song.getAlbum(),
                song.getDurationMs(),
                song.getMimeType(),
                song.getFileSize(),
                coverUrl,
                song.getLyricsText(),
                song.isFavorite(),
                song.getLyricSpeed(),
                song.getLyricsOffsetMs(),
                song.getObjectVersion(),
                song.getCreatedAt(),
                song.getUpdatedAt()
        );
    }

    private PlaylistDto toDto(Playlist playlist, long songCount) {
        String coverUrl = playlist.getCoverObjectKey() == null
                ? null
                : mediaUrlService.playlistCoverUrl(playlist);
        return new PlaylistDto(
                playlist.getId(),
                playlist.getName(),
                coverUrl,
                songCount,
                playlist.getSortOrder(),
                playlist.getUpdatedAt()
        );
    }

    private void upload(MultipartFile file, String key, String contentType) {
        try (InputStream input = file.getInputStream()) {
            storage.put(key, input, file.getSize(), contentType == null ? "application/octet-stream" : contentType);
        } catch (IOException exception) {
            throw new IllegalStateException("读取上传文件失败", exception);
        }
    }

    private void deleteSongObjects(Song song) {
        storage.delete(song.getAudioObjectKey());
        if (song.getCoverObjectKey() != null) {
            storage.delete(song.getCoverObjectKey());
        }
        if (song.getLyricObjectKey() != null) {
            storage.delete(song.getLyricObjectKey());
        }
    }

    private static String requiredName(String name, int maxLength, String field) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("invalid_name", field + "不能为空");
        }
        String clean = name.trim();
        if (clean.length() > maxLength) {
            throw ApiException.badRequest("invalid_name", field + "不能超过 " + maxLength + " 个字符");
        }
        return clean;
    }

    private static String songRoot(String userId, String songId) {
        return "users/" + userId + "/songs/" + songId;
    }

    private static String playlistCoverKey(String userId, String playlistId, String extension) {
        return "users/" + userId + "/playlists/" + playlistId + "/cover." + extension;
    }

    private static String validateAudio(MultipartFile file) {
        return validateAudio(file.getOriginalFilename(), file.getContentType(), file.getSize());
    }

    private static String validateAudio(String filename, String contentType, long size) {
        if (size <= 0 || size > MAX_AUDIO_SIZE) {
            throw ApiException.badRequest("invalid_audio", "音频文件为空或超过 2GB");
        }
        String extension = extensionOf(filename, "mp3");
        if (!AUDIO_EXTENSIONS.contains(extension)) {
            throw ApiException.badRequest("invalid_audio", "不支持的音频格式: " + extension);
        }
        if (contentType != null && !contentType.isBlank()
                && !contentType.startsWith("audio/")
                && !"application/octet-stream".equals(contentType)) {
            throw ApiException.badRequest("invalid_audio", "音频 Content-Type 不合法");
        }
        return extension;
    }

    private static String validateImage(MultipartFile file, String fallback) {
        if (file.getSize() <= 0 || file.getSize() > MAX_COVER_SIZE) {
            throw ApiException.badRequest("invalid_cover", "封面文件为空或超过 10MB");
        }
        String extension = extensionOf(file.getOriginalFilename(), fallback);
        if (!IMAGE_EXTENSIONS.contains(extension)) {
            throw ApiException.badRequest("invalid_cover", "不支持的图片格式: " + extension);
        }
        String type = file.getContentType();
        if (type != null && !type.isBlank() && !type.startsWith("image/") && !"application/octet-stream".equals(type)) {
            throw ApiException.badRequest("invalid_cover", "封面 Content-Type 不合法");
        }
        return extension;
    }

    private static String validateLyric(MultipartFile file) {
        if (file.getSize() <= 0 || file.getSize() > MAX_LYRIC_SIZE) {
            throw ApiException.badRequest("invalid_lyric", "歌词文件为空或超过 2MB");
        }
        String extension = extensionOf(file.getOriginalFilename(), "lrc");
        if (!LYRIC_EXTENSIONS.contains(extension)) {
            throw ApiException.badRequest("invalid_lyric", "歌词仅支持 .lrc 或 .txt");
        }
        return extension;
    }

    private static String readLyrics(MultipartFile file) {
        try {
            String text = new String(file.getBytes(), StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (IOException exception) {
            throw new IllegalStateException("读取歌词失败", exception);
        }
    }

    private static String audioContentType(MultipartFile file, String extension) {
        return audioContentType(file.getContentType(), extension);
    }

    private static String audioContentType(String type, String extension) {
        if (type != null && type.startsWith("audio/")) {
            return type;
        }
        return switch (extension) {
            case "mp3" -> "audio/mpeg";
            case "flac" -> "audio/flac";
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "ogg" -> "audio/ogg";
            case "opus" -> "audio/opus";
            case "wma" -> "audio/x-ms-wma";
            default -> "application/octet-stream";
        };
    }

    private static String titleFromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "未命名歌曲";
        }
        int dot = filename.lastIndexOf('.');
        String title = dot > 0 ? filename.substring(0, dot) : filename;
        title = title.trim();
        return title.isBlank() ? "未命名歌曲" : title;
    }

    private static String extensionOf(String filename, String fallback) {
        if (filename == null) {
            return fallback;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return fallback;
        }
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.matches("[a-z0-9]{1,8}") ? extension : fallback;
    }
}
