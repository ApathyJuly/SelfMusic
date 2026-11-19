package com.selfmusic.data

import com.selfmusic.data.local.SongDao
import com.selfmusic.data.local.SongEntity
import com.selfmusic.data.media.MediaStoreScanner
import kotlinx.coroutines.flow.Flow

class SongRepository(private val dao: SongDao, private val scanner: MediaStoreScanner) {
    fun songs(query: String): Flow<List<SongEntity>> = dao.observeSongs(query)
    fun favorites(): Flow<List<SongEntity>> = dao.favorites()
    suspend fun scan() = scanner.scan().also { dao.upsertAll(it) }
    suspend fun toggleFavorite(id: Long) = dao.toggleFavorite(id)
    suspend fun addSong(song: SongEntity) = dao.upsert(song)
    suspend fun updateLyrics(id: Long, title: String, posterUri: String?, lyrics: String, speed: Float, offsetMs: Long) = dao.updateLyrics(id, title, posterUri, lyrics, speed, offsetMs)
    suspend fun replaceCloudSongs(songs: List<SongEntity>) {
        val favorites = dao.favoriteCloudIds().toSet()
        dao.clearCloudSongs()
        dao.upsertAll(songs.map { it.copy(isFavorite = it.cloudId in favorites) })
    }
}
