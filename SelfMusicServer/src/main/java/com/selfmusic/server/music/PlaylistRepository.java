package com.selfmusic.server.music;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlaylistRepository extends JpaRepository<Playlist, String> {

    List<Playlist> findByUserIdOrderBySortOrderAscUpdatedAtDesc(String userId);

    Optional<Playlist> findByIdAndUserId(String id, String userId);

    @Query("select coalesce(max(p.sortOrder), -1) from Playlist p where p.userId = :userId")
    int findMaxSortOrder(@Param("userId") String userId);

    long countByUserId(String userId);
}
