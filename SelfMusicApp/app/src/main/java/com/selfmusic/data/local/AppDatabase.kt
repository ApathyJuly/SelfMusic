package com.selfmusic.data.local

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: Long, val title: String, val artist: String, val album: String,
    val albumId: Long, val durationMs: Long, val uri: String, val mimeType: String?,
    val fileSize: Long, val dateAdded: Long, val isFavorite: Boolean = false,
    val playCount: Int = 0, val lastPositionMs: Long = 0,
    val lyricUri: String? = null, val posterUri: String? = null, val isImported: Boolean = false,
    val lyricsText: String? = null, val lyricSpeed: Float = 1f, val lyricsOffsetMs: Long = 0,
    val sourceType: String = "LOCAL", val cloudId: String? = null,
    val audioObjectKey: String? = null, val coverObjectKey: String? = null,
    val lyricObjectKey: String? = null, val objectVersion: String? = null
)

@Entity(tableName = "playlists")
data class PlaylistEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
    val coverUri: String? = null, val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis())

@Entity(tableName = "playlist_songs", primaryKeys = ["playlistId", "songId"])
data class PlaylistSongEntity(val playlistId: Long, val songId: Long, val sortOrder: Int, val addedAt: Long = System.currentTimeMillis())

@Dao interface SongDao {
    @Query("SELECT * FROM songs WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE") fun observeSongs(query: String): Flow<List<SongEntity>>
    @Query("SELECT * FROM songs WHERE isFavorite = 1 ORDER BY title COLLATE NOCASE") fun favorites(): Flow<List<SongEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun upsertAll(songs: List<SongEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(song: SongEntity)
    @Query("UPDATE songs SET isFavorite = NOT isFavorite WHERE id = :id") suspend fun toggleFavorite(id: Long)
    @Query("UPDATE songs SET lastPositionMs = :position, playCount = playCount + 1 WHERE id = :id") suspend fun saveProgress(id: Long, position: Long)
    @Query("UPDATE songs SET title = :title, posterUri = :posterUri, lyricsText = :lyrics, lyricSpeed = :speed, lyricsOffsetMs = :offsetMs WHERE id = :id") suspend fun updateLyrics(id: Long, title: String, posterUri: String?, lyrics: String, speed: Float, offsetMs: Long)
    @Query("DELETE FROM songs WHERE sourceType = 'CLOUD'") suspend fun clearCloudSongs()
    @Query("SELECT cloudId FROM songs WHERE sourceType = 'CLOUD' AND isFavorite = 1") suspend fun favoriteCloudIds(): List<String>
}
@Dao interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC") fun observe(): Flow<List<PlaylistEntity>>
    @Insert suspend fun create(playlist: PlaylistEntity): Long
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun delete(id: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addSong(item: PlaylistSongEntity)
}
@Database(entities = [SongEntity::class, PlaylistEntity::class, PlaylistSongEntity::class], version = 4)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao; abstract fun playlistDao(): PlaylistDao
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN lyricsText TEXT")
                db.execSQL("ALTER TABLE songs ADD COLUMN lyricSpeed REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE songs ADD COLUMN lyricsOffsetMs INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'LOCAL'")
                db.execSQL("ALTER TABLE songs ADD COLUMN cloudId TEXT")
                db.execSQL("ALTER TABLE songs ADD COLUMN audioObjectKey TEXT")
                db.execSQL("ALTER TABLE songs ADD COLUMN coverObjectKey TEXT")
                db.execSQL("ALTER TABLE songs ADD COLUMN lyricObjectKey TEXT")
                db.execSQL("ALTER TABLE songs ADD COLUMN objectVersion TEXT")
            }
        }
    }
}
