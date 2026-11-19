package com.selfmusic.playback

import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.selfmusic.data.local.MusicSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessBoostEnabled = true
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(PlaybackCache.factory(this))).setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true
        ).setHandleAudioBecomingNoisy(true).build().apply { repeatMode = Player.REPEAT_MODE_OFF }
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = updateLoudnessEnhancer()
        })
        serviceScope.launch { MusicSettings(this@PlaybackService).loudnessBoost.collectLatest { enabled -> loudnessBoostEnabled = enabled; updateLoudnessEnhancer() } }
        session = MediaSession.Builder(this, player).build()
    }
    private fun updateLoudnessEnhancer() {
        loudnessEnhancer?.release(); loudnessEnhancer = null
        if (!loudnessBoostEnabled || player.audioSessionId <= 0) return
        loudnessEnhancer = runCatching { LoudnessEnhancer(player.audioSessionId).apply { setTargetGain(600); enabled = true } }.getOrNull()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() { serviceScope.cancel(); loudnessEnhancer?.release(); loudnessEnhancer = null; session?.run { player.release(); release() }; session = null; PlaybackCache.release(); super.onDestroy() }
}
