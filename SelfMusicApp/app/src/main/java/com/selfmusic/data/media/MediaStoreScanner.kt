package com.selfmusic.data.media

import android.content.ContentResolver
import android.provider.MediaStore
import com.selfmusic.data.local.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStoreScanner(private val resolver: ContentResolver) {
    suspend fun scan(): List<SongEntity> = withContext(Dispatchers.IO) {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED)
        resolver.query(uri, columns, "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0", null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE); val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM); val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION); val mime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val size = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE); val added = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            buildList { while (c.moveToNext()) add(SongEntity(c.getLong(id), c.getString(title).orEmpty(), c.getString(artist) ?: "未知歌手", c.getString(album) ?: "未知专辑", c.getLong(albumId), c.getLong(duration), android.content.ContentUris.withAppendedId(uri, c.getLong(id)).toString(), c.getString(mime), c.getLong(size), c.getLong(added))) }
        } ?: emptyList()
    }
}
