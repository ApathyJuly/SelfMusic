package com.selfmusic.server.music;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SongRepository extends JpaRepository<Song, String> {

    List<Song> findByPlaylistIdOrderBySortOrderAscCreatedAtAsc(String playlistId);

    List<Song> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<Song> findByIdAndUserId(String id, String userId);

    long countByPlaylistId(String playlistId);

    @Query("select coalesce(max(s.sortOrder), -1) from Song s where s.playlistId = :playlistId")
    int findMaxSortOrder(@Param("playlistId") String playlistId);

    @Query("""
            select s.playlistId as playlistId, count(s) as count
            from Song s
            where s.userId = :userId
            group by s.playlistId
            """)
    List<PlaylistCount> countByPlaylist(@Param("userId") String userId);

    void deleteByPlaylistId(String playlistId);

    @Modifying
    @Query("update Song s set s.album = :name, s.updatedAt = :now where s.playlistId = :playlistId")
    int syncAlbumName(@Param("playlistId") String playlistId, @Param("name") String name, @Param("now") Instant now);

    interface PlaylistCount {
        String getPlaylistId();

        long getCount();
    }
}
