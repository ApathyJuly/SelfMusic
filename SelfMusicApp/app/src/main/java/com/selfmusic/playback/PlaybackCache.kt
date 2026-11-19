package com.selfmusic.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object PlaybackCache {
    private var cache: SimpleCache? = null
    @Synchronized fun factory(context: Context): CacheDataSource.Factory {
        val app = context.applicationContext
        val value = cache ?: SimpleCache(File(app.cacheDir, "music_stream_cache"), LeastRecentlyUsedCacheEvictor(5L * 1024 * 1024 * 1024), StandaloneDatabaseProvider(app)).also { cache = it }
        return CacheDataSource.Factory().setCache(value).setUpstreamDataSourceFactory(DefaultDataSource.Factory(app)).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
    @Synchronized fun release() { cache?.release(); cache = null }
}
