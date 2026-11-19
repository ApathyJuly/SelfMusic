package com.selfmusic.server.music;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "songs")
public class Song {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "playlist_id", nullable = false, length = 36)
    private String playlistId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 200)
    private String artist;

    @Column(nullable = false, length = 200)
    private String album;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "audio_object_key", nullable = false, length = 512)
    private String audioObjectKey;

    @Column(name = "cover_object_key", length = 512)
    private String coverObjectKey;

    @Column(name = "lyric_object_key", length = 512)
    private String lyricObjectKey;

    @Column(name = "lyrics_text", columnDefinition = "TEXT")
    private String lyricsText;

    @Column(name = "lyric_speed", nullable = false)
    private double lyricSpeed = 1.0;

    @Column(name = "lyrics_offset_ms", nullable = false)
    private long lyricsOffsetMs;

    @Column(name = "object_version", nullable = false, length = 64)
    private String objectVersion;

    @Column(name = "is_favorite", nullable = false)
    private boolean favorite;

    @Column(name = "play_count", nullable = false)
    private int playCount;

    @Column(name = "last_position_ms", nullable = false)
    private long lastPositionMs;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
