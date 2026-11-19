package com.selfmusic.data.local

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.musicDataStore by preferencesDataStore("music_settings")

class MusicSettings(private val context: Context) {
    private val scanPathKey = stringPreferencesKey("scan_path")
    private val loudnessBoostKey = booleanPreferencesKey("loudness_boost")
    private val fadeEnabledKey = booleanPreferencesKey("fade_enabled")
    private val fadeDurationKey = intPreferencesKey("fade_duration_ms")
    private val playbackModeKey = stringPreferencesKey("playback_mode")
    val scanPath: Flow<String?> = context.musicDataStore.data.map { it[scanPathKey] }
    val loudnessBoost: Flow<Boolean> = context.musicDataStore.data.map { it[loudnessBoostKey] ?: true }
    val fadeEnabled: Flow<Boolean> = context.musicDataStore.data.map { it[fadeEnabledKey] ?: true }
    val fadeDurationMs: Flow<Int> = context.musicDataStore.data.map { it[fadeDurationKey] ?: 800 }
    val playbackMode: Flow<String> = context.musicDataStore.data.map { it[playbackModeKey] ?: "SEQUENTIAL" }
    suspend fun setScanPath(uri: String) { context.musicDataStore.edit { it[scanPathKey] = uri } }
    suspend fun setLoudnessBoost(enabled: Boolean) { context.musicDataStore.edit { it[loudnessBoostKey] = enabled } }
    suspend fun setFadeEnabled(enabled: Boolean) { context.musicDataStore.edit { it[fadeEnabledKey] = enabled } }
    suspend fun setFadeDurationMs(duration: Int) { context.musicDataStore.edit { it[fadeDurationKey] = duration.coerceIn(200, 3000) } }
    suspend fun setPlaybackMode(mode: String) { context.musicDataStore.edit { it[playbackModeKey] = mode } }
}
