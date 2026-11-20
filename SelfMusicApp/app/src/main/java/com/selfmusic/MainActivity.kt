package com.selfmusic

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.selfmusic.data.SongRepository
import com.selfmusic.data.cloud.CloudPlaylist
import com.selfmusic.data.local.AppDatabase
import com.selfmusic.data.local.MusicSettings
import com.selfmusic.data.local.SongEntity
import com.selfmusic.data.media.MediaStoreScanner
import com.selfmusic.data.server.ServerApi
import com.selfmusic.data.server.ServerSession
import com.selfmusic.data.server.ServerSessionStore
import com.selfmusic.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PlaybackMode(val label: String) { SEQUENTIAL("顺序播放"), SHUFFLE("随机播放"), REPEAT_ONE("单曲循环") }

class MainActivity : ComponentActivity() {
    private lateinit var vm: MusicViewModel
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.any { ok -> ok }) vm.scan() }
    override fun onCreate(state: Bundle?) { super.onCreate(state); enableEdgeToEdge()
        val db = androidx.room.Room.databaseBuilder(applicationContext, AppDatabase::class.java, "selfmusic.db").addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4).fallbackToDestructiveMigration().build()
        val serverStore = ServerSessionStore(applicationContext)
        // 服务器地址已固定在 gradle.properties；旧地址的会话自动失效
        val savedSession = serverStore.session.value
        if (savedSession != null &&
            !savedSession.baseUrl.trimEnd('/').equals(AppConfig.defaultServerBaseUrl.trimEnd('/'), ignoreCase = true)
        ) {
            serverStore.clear()
        }
        val serverApi = ServerApi(applicationContext, serverStore)
        vm = ViewModelProvider(this, MusicViewModel.Factory(SongRepository(db.songDao(), MediaStoreScanner(contentResolver)), MusicSettings(applicationContext), serverStore, serverApi, applicationContext))[MusicViewModel::class.java]
        setContent {
            val themeKey by vm.themeColor.collectAsStateWithLifecycle()
            GoldTheme(themeKey) { MusicApp(vm, ::ensureLibraryAccess) }
        }
    }

    private fun ensureLibraryAccess() {
        val needs = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_AUDIO) else add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needs.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissions.launch(needs.toTypedArray())
        } else {
            vm.scan()
        }
    }
}

class MusicViewModel(
    private val repo: SongRepository,
    private val settings: MusicSettings,
    private val serverStore: ServerSessionStore,
    private val serverApi: ServerApi,
    private val context: Context
) : ViewModel() {
    private val query = MutableStateFlow("")
    val songs = query.flatMapLatest(repo::songs).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val search = query.asStateFlow()
    val scanPath = settings.scanPath.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val loudnessBoost = settings.loudnessBoost.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val fadeEnabled = settings.fadeEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val fadeDurationMs = settings.fadeDurationMs.stateIn(viewModelScope, SharingStarted.Eagerly, 800)
    val playbackMode = settings.playbackMode.map { runCatching { PlaybackMode.valueOf(it) }.getOrDefault(PlaybackMode.SEQUENTIAL) }.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackMode.SEQUENTIAL)
    val serverSession: StateFlow<ServerSession?> = serverStore.session
    val serverLoggedIn = serverStore.session.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, serverStore.session.value != null)

    private val _authMessage = MutableStateFlow<String?>(null); val authMessage = _authMessage.asStateFlow()
    private val _authBusy = MutableStateFlow(false); val authBusy = _authBusy.asStateFlow()
    private val _cloudMessage = MutableStateFlow<String?>(null); val cloudMessage = _cloudMessage.asStateFlow()
    private val _cloudBusy = MutableStateFlow(false); val cloudBusy = _cloudBusy.asStateFlow()
    private val _cloudDirectories = MutableStateFlow<List<String>>(emptyList()); val cloudDirectories = _cloudDirectories.asStateFlow()
    private val _cloudPlaylists = MutableStateFlow<List<CloudPlaylist>>(emptyList()); val cloudPlaylists = _cloudPlaylists.asStateFlow()
    private val _selectedCloudDirectory = MutableStateFlow<String?>(null); val selectedCloudDirectory = _selectedCloudDirectory.asStateFlow()

    private var controller: MediaController? = null
    private val _current = MutableStateFlow<MediaItem?>(null); val current = _current.asStateFlow()
    private val _playing = MutableStateFlow(false); val playing = _playing.asStateFlow()
    private val _position = MutableStateFlow(0L); val position = _position.asStateFlow()
    private val _duration = MutableStateFlow(0L); val duration = _duration.asStateFlow()
    private val _activeQueue = MutableStateFlow<List<SongEntity>>(emptyList()); val activeQueue = _activeQueue.asStateFlow()
    private var positionTicker: Job? = null
    private var fadeJob: Job? = null

    init {
        MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync().also { future ->
            future.addListener({ controller = runCatching { future.get().also(::watch) }.getOrNull() }, ContextCompat.getMainExecutor(context))
        }
    }

    private fun watch(p: Player) {
        applyPlaybackMode(p, playbackMode.value)
        _current.value = p.currentMediaItem
        _playing.value = p.isPlaying
        positionTicker?.cancel()
        positionTicker = viewModelScope.launch {
            while (true) {
                _position.value = p.currentPosition.coerceAtLeast(0)
                _duration.value = p.duration.takeIf { it > 0 } ?: 0
                delay(500)
            }
        }
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { _playing.value = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _current.value = mediaItem
                _position.value = 0
                if (p.isPlaying && fadeEnabled.value) fadeIn(p)
            }
            override fun onPlaybackStateChanged(state: Int) { _duration.value = p.duration.takeIf { it > 0 } ?: 0 }
        })
    }

    fun search(value: String) { query.value = value }
    fun scan() = viewModelScope.launch { repo.scan() }

    fun favorite(song: SongEntity) = viewModelScope.launch {
        if (song.sourceType == "CLOUD") {
            if (song.cloudId == null) return@launch
            runCatching { serverApi.setFavorite(song, !song.isFavorite) }
                .onSuccess { updated ->
                    repo.addSong(updated)
                    _activeQueue.value = _activeQueue.value.map { if (it.id == updated.id) updated else it }
                }
                .onFailure { _cloudMessage.value = it.message ?: "收藏失败" }
        } else {
            repo.toggleFavorite(song.id)
            _activeQueue.value = _activeQueue.value.map { if (it.id == song.id) it.copy(isFavorite = !it.isFavorite) else it }
        }
    }

    fun savePath(uri: Uri) = viewModelScope.launch { settings.setScanPath(uri.toString()) }
    fun setLoudnessBoost(enabled: Boolean) = viewModelScope.launch { settings.setLoudnessBoost(enabled) }
    fun setFadeEnabled(enabled: Boolean) = viewModelScope.launch { settings.setFadeEnabled(enabled) }
    fun setFadeDurationMs(duration: Int) = viewModelScope.launch { settings.setFadeDurationMs(duration) }

    val themeColor: StateFlow<String> = settings.themeColor.stateIn(viewModelScope, SharingStarted.Eagerly, "gold")
    fun setThemeColor(key: String) = viewModelScope.launch { settings.setThemeColor(key) }

    fun login(username: String, password: String) = viewModelScope.launch {
        _authBusy.value = true
        _authMessage.value = null
        runCatching { serverApi.login(AppConfig.defaultServerBaseUrl, username, password) }
            .onSuccess {
                _authMessage.value = "登录成功"
                _justLoggedIn.value = true
                refreshAvatar()
                refreshCloud()
            }
            .onFailure { _authMessage.value = it.message ?: "登录失败" }
        _authBusy.value = false
    }

    fun register(username: String, password: String, displayName: String) = viewModelScope.launch {
        _authBusy.value = true
        _authMessage.value = null
        runCatching { serverApi.register(AppConfig.defaultServerBaseUrl, username, password, displayName) }
            .onSuccess {
                _authMessage.value = "注册并登录成功"
                _justLoggedIn.value = true
                refreshAvatar()
                refreshCloud()
            }
            .onFailure { _authMessage.value = it.message ?: "注册失败" }
        _authBusy.value = false
    }

    fun clearAuthMessage() {
        _authMessage.value = null
    }

    fun logout() = viewModelScope.launch {
        _authBusy.value = true
        runCatching { serverApi.logout() }
        repo.replaceCloudSongs(emptyList())
        _cloudPlaylists.value = emptyList()
        _cloudDirectories.value = emptyList()
        _selectedCloudDirectory.value = null
        _authMessage.value = "已退出服务器"
        _cloudMessage.value = null
        _avatar.value = null
        _profileMessage.value = null
        _authBusy.value = false
    }

    private val _avatar = MutableStateFlow<Bitmap?>(null); val avatar = _avatar.asStateFlow()
    private val _profileBusy = MutableStateFlow(false); val profileBusy = _profileBusy.asStateFlow()
    private val _profileMessage = MutableStateFlow<String?>(null); val profileMessage = _profileMessage.asStateFlow()
    private val _justLoggedIn = MutableStateFlow(false); val justLoggedIn = _justLoggedIn.asStateFlow()

    fun consumeJustLoggedIn() { _justLoggedIn.value = false }

    fun clearProfileMessage() { _profileMessage.value = null }

    /** 拉取服务端头像并解码；未设置头像时清空。 */
    fun refreshAvatar() = viewModelScope.launch {
        val bytes = runCatching { serverApi.fetchAvatar() }.getOrNull()
        _avatar.value = bytes?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    }

    fun uploadAvatar(uri: Uri) = viewModelScope.launch {
        _profileBusy.value = true
        _profileMessage.value = null
        runCatching {
            val bytes = withContext(Dispatchers.IO) { compressAvatar(context, uri) }
            serverApi.uploadAvatar(bytes)
            bytes
        }.onSuccess { bytes ->
            _avatar.value = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            _profileMessage.value = "头像已更新"
        }.onFailure { _profileMessage.value = it.message ?: "头像上传失败" }
        _profileBusy.value = false
    }

    fun removeAvatar() = viewModelScope.launch {
        _profileBusy.value = true
        _profileMessage.value = null
        runCatching { serverApi.deleteAvatar() }
            .onSuccess {
                _avatar.value = null
                _profileMessage.value = "头像已移除"
            }
            .onFailure { _profileMessage.value = it.message ?: "移除头像失败" }
        _profileBusy.value = false
    }

    fun createCloudDirectory(name: String, cover: Uri? = null) = viewModelScope.launch {
        if (!requireServerLogin()) return@launch
        _cloudBusy.value = true
        runCatching {
            val created = serverApi.createPlaylist(name, cover)
            val playlists = serverApi.playlists()
            val entries = serverApi.songs(created.directory)
            Triple(created, playlists, entries)
        }
            .onSuccess { (created, playlists, entries) ->
                applyPlaylists(playlists)
                _selectedCloudDirectory.value = created.directory
                repo.replaceCloudSongs(entries)
                _cloudMessage.value = "已创建歌单：$name"
            }
            .onFailure { _cloudMessage.value = it.message ?: "创建歌单失败" }
        _cloudBusy.value = false
    }

    fun updateCloudDirectory(directory: String, name: String, cover: Uri?) = viewModelScope.launch {
        if (!requireServerLogin()) return@launch
        _cloudBusy.value = true
        runCatching { serverApi.updatePlaylist(directory, name, cover); serverApi.playlists() }
            .onSuccess {
                applyPlaylists(it)
                _cloudMessage.value = "歌单资料已更新"
            }
            .onFailure { _cloudMessage.value = it.message ?: "更新歌单失败" }
        _cloudBusy.value = false
    }

    fun moveCloudPlaylist(directory: String, offset: Int) = viewModelScope.launch {
        if (!requireServerLogin()) return@launch
        val current = _cloudPlaylists.value.toMutableList()
        val from = current.indexOfFirst { it.directory == directory }
        val to = from + offset
        if (from < 0 || to !in current.indices) return@launch
        java.util.Collections.swap(current, from, to)
        _cloudBusy.value = true
        runCatching { serverApi.reorderPlaylists(current.map(CloudPlaylist::directory)) }
            .onSuccess {
                applyPlaylists(it)
                _cloudMessage.value = "歌单排序已保存"
            }
            .onFailure { _cloudMessage.value = it.message ?: "保存歌单排序失败" }
        _cloudBusy.value = false
    }

    fun loadCloudDirectories() = viewModelScope.launch {
        if (!requireServerLogin()) return@launch
        _cloudBusy.value = true
        runCatching { serverApi.playlists() }
            .onSuccess {
                applyPlaylists(it)
                if (it.isEmpty()) _cloudMessage.value = "服务器暂无歌单，请先创建歌单"
            }
            .onFailure { _cloudMessage.value = it.message ?: "读取歌单失败" }
        _cloudBusy.value = false
    }

    fun deleteCloudSong(song: SongEntity) = viewModelScope.launch {
        if (!requireServerLogin()) return@launch
        _cloudBusy.value = true
        runCatching {
            serverApi.deleteSong(song)
            _selectedCloudDirectory.value?.let { serverApi.songs(it) }.orEmpty()
        }
            .onSuccess { remaining ->
                if (remaining.isEmpty() && _selectedCloudDirectory.value == null) refreshCloud()
                else repo.replaceCloudSongs(remaining)
                _cloudMessage.value = "已删除：${song.title}"
            }
            .onFailure { _cloudMessage.value = it.message ?: "删除歌曲失败" }
        _cloudBusy.value = false
    }

    fun moveCloudSong(song: SongEntity, targetDirectory: String) = viewModelScope.launch {
        if (!requireServerLogin()) return@launch
        _cloudBusy.value = true
        runCatching {
            val moved = serverApi.moveSong(song, targetDirectory)
            val playlists = serverApi.playlists()
            val current = _selectedCloudDirectory.value?.let { serverApi.songs(it) }.orEmpty()
            Triple(moved, playlists, current)
        }
            .onSuccess { (moved, playlists, currentSongs) ->
                applyPlaylists(playlists)
                repo.replaceCloudSongs(currentSongs)
                _cloudMessage.value = "已将《${moved.title}》移动到 ${playlists.firstOrNull { it.directory == targetDirectory }?.name ?: targetDirectory}"
            }
            .onFailure { _cloudMessage.value = it.message ?: "移动歌曲失败" }
        _cloudBusy.value = false
    }

    fun updateCloudSong(song: SongEntity, title: String, lyric: Uri?, cover: Uri?) = viewModelScope.launch {
        if (!requireServerLogin()) return@launch
        val directory = _selectedCloudDirectory.value
        _cloudBusy.value = true
        runCatching {
            serverApi.updateSong(song, title, lyric, cover)
            if (directory != null) serverApi.songs(directory) else emptyList()
        }
            .onSuccess { remaining ->
                if (directory == null) refreshCloud() else repo.replaceCloudSongs(remaining)
                _cloudMessage.value = "歌曲资料已更新：${title.ifBlank { song.title }}"
            }
            .onFailure { _cloudMessage.value = it.message ?: "编辑歌曲失败" }
        _cloudBusy.value = false
    }

    fun downloadCloudSong(song: SongEntity, destination: Uri) = viewModelScope.launch {
        if (!requireServerLogin()) return@launch
        _cloudBusy.value = true
        _cloudMessage.value = "正在下载：${song.title}"
        runCatching { serverApi.downloadSong(song, destination) }
            .onSuccess { _cloudMessage.value = "歌曲已保存：${song.title}" }
            .onFailure { _cloudMessage.value = it.message ?: "下载歌曲失败" }
        _cloudBusy.value = false
    }

    fun refreshCloud() = viewModelScope.launch {
        if (!requireServerLogin()) return@launch
        _cloudBusy.value = true
        runCatching {
            val playlists = serverApi.playlists()
            val selected = playlists.firstOrNull()?.directory
            val entries = selected?.let { serverApi.songs(it) }.orEmpty()
            Triple(playlists, selected, entries)
        }
            .onSuccess { (playlists, selected, entries) ->
                applyPlaylists(playlists)
                _selectedCloudDirectory.value = selected
                repo.replaceCloudSongs(entries)
                _cloudMessage.value = if (playlists.isEmpty()) "服务器暂无歌单，请先创建" else "已加载 ${playlists.size} 个歌单，${entries.size} 首歌曲"
            }
            .onFailure { _cloudMessage.value = it.message ?: "云端同步失败" }
        _cloudBusy.value = false
    }

    fun selectCloudDirectory(directory: String) = viewModelScope.launch {
        if (!requireServerLogin()) return@launch
        _cloudBusy.value = true
        _selectedCloudDirectory.value = directory
        runCatching { serverApi.songs(directory) }
            .onSuccess {
                repo.replaceCloudSongs(it)
                _cloudMessage.value = "已加载 ${it.size} 首歌曲"
            }
            .onFailure { _cloudMessage.value = it.message ?: "加载歌单失败" }
        _cloudBusy.value = false
    }

    fun updateLyrics(song: SongEntity, title: String, posterUri: String?, lyrics: String, speed: Float, offsetMs: Long) = viewModelScope.launch {
        if (song.sourceType == "CLOUD") {
            val directory = _selectedCloudDirectory.value
            _cloudBusy.value = true
            runCatching {
                serverApi.updateLyrics(song, lyrics, speed, offsetMs)
                if (title != song.title) {
                    serverApi.updateSong(song, title, null, posterUri?.takeIf { it.startsWith("content://") }?.let(Uri::parse))
                }
                if (directory != null) serverApi.songs(directory) else emptyList()
            }
                .onSuccess {
                    if (it.isNotEmpty()) repo.replaceCloudSongs(it)
                    else repo.updateLyrics(song.id, title, song.posterUri, lyrics, speed, offsetMs)
                    _cloudMessage.value = "歌词已保存到服务器"
                }
                .onFailure { _cloudMessage.value = it.message ?: "歌词保存失败" }
            _cloudBusy.value = false
        } else {
            repo.updateLyrics(song.id, title, posterUri, lyrics, speed, offsetMs)
        }
    }

    fun importSong(audio: Uri, lyric: Uri?, poster: Uri?, title: String, directory: String = "") = viewModelScope.launch {
        val loggedIn = serverStore.session.value != null
        if (loggedIn && directory.isNotBlank()) {
            _cloudBusy.value = true
            _cloudMessage.value = "正在上传到服务器…"
            runCatching { serverApi.uploadSong(directory, audio, lyric, poster, title, durationOf(audio)) }
                .onSuccess { song ->
                    _selectedCloudDirectory.value = directory
                    repo.replaceCloudSongs(serverApi.songs(directory))
                    _cloudMessage.value = "已上传：${song.title}"
                }
                .onFailure { _cloudMessage.value = it.message ?: "上传失败" }
            _cloudBusy.value = false
        } else {
            repo.addSong(
                SongEntity(
                    id = -System.currentTimeMillis(),
                    title = title.ifBlank { context.fileName(audio).substringBeforeLast('.') },
                    artist = "本地导入",
                    album = "我的导入",
                    albumId = 0,
                    durationMs = durationOf(audio),
                    uri = audio.toString(),
                    mimeType = context.contentResolver.getType(audio),
                    fileSize = 0,
                    dateAdded = System.currentTimeMillis(),
                    lyricUri = lyric?.toString(),
                    posterUri = poster?.toString(),
                    isImported = true
                )
            )
        }
    }

    fun play(song: SongEntity, queue: List<SongEntity>) = viewModelScope.launch {
        val actualQueue = queue.ifEmpty { listOf(song) }
        val cloudSongs = actualQueue.filter { it.sourceType == "CLOUD" }
        val remoteUrls = if (cloudSongs.isNotEmpty()) {
            runCatching { serverApi.playUrls(cloudSongs.mapNotNull { it.cloudId }) }
                .getOrElse {
                    _cloudMessage.value = it.message ?: "无法获取播放地址"
                    return@launch
                }
        } else {
            emptyMap()
        }
        val urls = actualQueue.associate { entry ->
            entry.id to if (entry.sourceType == "CLOUD") {
                remoteUrls[requireNotNull(entry.cloudId)]
            } else {
                entry.uri
            }
        }
        controller?.run {
            val list = actualQueue.map { entry ->
                MediaItem.Builder()
                    .setUri(requireNotNull(urls[entry.id]))
                    .setMediaId(entry.id.toString())
                    .setCustomCacheKey(if (entry.sourceType == "CLOUD") "server:${entry.cloudId}:${entry.objectVersion}" else null)
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(entry.title)
                            .setArtist(entry.artist)
                            .setArtworkUri(entry.posterUri?.let(Uri::parse))
                            .build()
                    )
                    .build()
            }
            _activeQueue.value = actualQueue
            setMediaItems(list, actualQueue.indexOfFirst { it.id == song.id }.coerceAtLeast(0), 0)
            prepare()
            if (fadeEnabled.value) volume = 0f
            play()
            if (fadeEnabled.value) fadeIn(this)
        }
    }

    fun cyclePlaybackMode() = viewModelScope.launch {
        val next = when (playbackMode.value) {
            PlaybackMode.SEQUENTIAL -> PlaybackMode.SHUFFLE
            PlaybackMode.SHUFFLE -> PlaybackMode.REPEAT_ONE
            PlaybackMode.REPEAT_ONE -> PlaybackMode.SEQUENTIAL
        }
        settings.setPlaybackMode(next.name)
        controller?.let { applyPlaybackMode(it, next) }
    }

    private fun applyPlaybackMode(player: Player, mode: PlaybackMode) {
        player.shuffleModeEnabled = mode == PlaybackMode.SHUFFLE
        player.repeatMode = when (mode) {
            PlaybackMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
            PlaybackMode.SHUFFLE -> Player.REPEAT_MODE_ALL
            PlaybackMode.SEQUENTIAL -> Player.REPEAT_MODE_OFF
        }
    }

    private fun fadeIn(player: Player) {
        fadeJob?.cancel()
        if (!fadeEnabled.value) { player.volume = 1f; return }
        fadeJob = viewModelScope.launch {
            player.volume = 0f
            val steps = 20
            repeat(steps) {
                delay((fadeDurationMs.value / steps).toLong())
                player.volume = (it + 1) / steps.toFloat()
            }
        }
    }

    private fun fadeOut(player: Player, after: () -> Unit) {
        fadeJob?.cancel()
        if (!fadeEnabled.value) { after(); return }
        fadeJob = viewModelScope.launch {
            val start = player.volume
            val steps = 20
            repeat(steps) {
                delay((fadeDurationMs.value / steps).toLong())
                player.volume = start * (steps - it - 1) / steps
            }
            after()
            player.volume = 1f
        }
    }

    fun toggle() {
        controller?.let {
            if (it.isPlaying) fadeOut(it) { it.pause() } else {
                if (fadeEnabled.value) it.volume = 0f
                it.play()
                fadeIn(it)
            }
        }
    }

    fun next() { controller?.let { fadeOut(it) { it.seekToNextMediaItem(); if (it.isPlaying) fadeIn(it) } } }
    fun previous() { controller?.let { fadeOut(it) { it.seekToPreviousMediaItem(); if (it.isPlaying) fadeIn(it) } } }
    fun seekTo(position: Long) { controller?.seekTo(position) }
    fun stop() { controller?.stop() }

    override fun onCleared() {
        positionTicker?.cancel()
        fadeJob?.cancel()
        controller?.release()
    }

    private fun applyPlaylists(playlists: List<CloudPlaylist>) {
        _cloudPlaylists.value = playlists
        _cloudDirectories.value = playlists.map(CloudPlaylist::directory)
    }

    private fun requireServerLogin(): Boolean {
        if (serverStore.session.value == null) {
            _cloudMessage.value = "请先在设置中登录服务器"
            return false
        }
        return true
    }

    private fun durationOf(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    class Factory(
        private val r: SongRepository,
        private val s: MusicSettings,
        private val store: ServerSessionStore,
        private val api: ServerApi,
        private val c: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(m: Class<T>) = MusicViewModel(r, s, store, api, c) as T
    }
}

@Composable private fun MusicApp(vm: MusicViewModel, requestLibraryAccess: () -> Unit) {
    val songs by vm.songs.collectAsStateWithLifecycle(); val search by vm.search.collectAsStateWithLifecycle(); val item by vm.current.collectAsStateWithLifecycle(); val playing by vm.playing.collectAsStateWithLifecycle(); val activeQueue by vm.activeQueue.collectAsStateWithLifecycle(); val path by vm.scanPath.collectAsStateWithLifecycle(); val boost by vm.loudnessBoost.collectAsStateWithLifecycle(); val fadeEnabled by vm.fadeEnabled.collectAsStateWithLifecycle(); val fadeDuration by vm.fadeDurationMs.collectAsStateWithLifecycle(); val position by vm.position.collectAsStateWithLifecycle(); val duration by vm.duration.collectAsStateWithLifecycle(); val serverSession by vm.serverSession.collectAsStateWithLifecycle(); val authMessage by vm.authMessage.collectAsStateWithLifecycle(); val authBusy by vm.authBusy.collectAsStateWithLifecycle(); val cloudMessage by vm.cloudMessage.collectAsStateWithLifecycle(); val cloudBusy by vm.cloudBusy.collectAsStateWithLifecycle(); val avatarBitmap by vm.avatar.collectAsStateWithLifecycle(); val profileBusy by vm.profileBusy.collectAsStateWithLifecycle(); val profileMessage by vm.profileMessage.collectAsStateWithLifecycle(); val justLoggedIn by vm.justLoggedIn.collectAsStateWithLifecycle(); val themeKey by vm.themeColor.collectAsStateWithLifecycle()
    if (serverSession == null) {
        AuthGate(vm, authMessage, authBusy)
        return
    }
    LaunchedEffect(Unit) { requestLibraryAccess() }
    val now = activeQueue.firstOrNull { it.id.toString() == item?.mediaId } ?: songs.firstOrNull { it.id.toString() == item?.mediaId }; var tab by rememberSaveable { mutableIntStateOf(0) }; var player by rememberSaveable { mutableStateOf(false) }; var add by rememberSaveable { mutableStateOf(false) }; var queueOpen by rememberSaveable { mutableStateOf(false) }; var profileOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(justLoggedIn) {
        if (justLoggedIn) {
            profileOpen = true
            vm.consumeJustLoggedIn()
            vm.refreshAvatar()
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (profileOpen && serverSession != null) ProfilePage(vm, serverSession!!, avatarBitmap, themeKey, profileBusy, profileMessage, Modifier.fillMaxSize()) { profileOpen = false } else if (player && now != null) PlayerPage(now!!, activeQueue.ifEmpty { songs }, playing, vm) { player = false } else Scaffold(
        bottomBar = { Column { if (tab != 3) PlayerControls(item, playing, position, duration, vm, { player = true }, { queueOpen = true }); NavigationBar(containerColor = MaterialTheme.colorScheme.surface) { listOf("音乐库", "收藏", "云端音乐", "设置").forEachIndexed { i, name -> NavigationBarItem(selected = i == tab, onClick = { tab = i }, icon = { Icon(if (i == 0) Icons.Default.LibraryMusic else if (i == 1) Icons.Default.Favorite else if (i == 2) Icons.Default.Cloud else Icons.Default.Settings, null) }, label = { Text(name) }) } } } }
    ) { pad -> when (tab) { 0 -> Library(songs.filter { it.sourceType != "CLOUD" }, search, vm, Modifier.padding(pad)); 1 -> Favorites(songs.filter { it.isFavorite }, vm, Modifier.padding(pad)); 2 -> CloudLibrary(songs.filter { it.sourceType == "CLOUD" }, cloudMessage, cloudBusy, vm, Modifier.padding(pad), { add = true }); else -> Settings(vm, path, boost, fadeEnabled, fadeDuration, serverSession, avatarBitmap, authMessage, authBusy, cloudMessage, cloudBusy, Modifier.padding(pad), { profileOpen = true }) } }
        if (add) AddSong(vm) { add = false }
        if (queueOpen) QueueSheet(activeQueue.ifEmpty { songs }, item?.mediaId, vm) { queueOpen = false }
    }
}

@Composable private fun AuthGate(vm: MusicViewModel, message: String?, busy: Boolean) {
    val view = LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity ?: (view.context as? Activity)
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = true
        controller?.isAppearanceLightNavigationBars = true
        onDispose {
            if (previousStatus != null) controller?.isAppearanceLightStatusBars = previousStatus
            if (previousNavigation != null) controller?.isAppearanceLightNavigationBars = previousNavigation
        }
    }
    Surface(Modifier.fillMaxSize(), color = Color.White) {
            ServerSettingsPage(
                current = null,
                message = message,
                busy = busy,
                vm = vm,
                mod = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
                back = {},
                gate = true
            )
        }
}

@Composable private fun Library(songs: List<SongEntity>, query: String, vm: MusicViewModel, mod: Modifier) = Column(mod.fillMaxSize()) {
    Text("本地音乐", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(20.dp, 18.dp, 20.dp, 8.dp)); OutlinedTextField(query, vm::search, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, placeholder = { Text("搜索歌曲、歌手或专辑") }, leadingIcon = { Icon(Icons.Default.Search, null) })
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text("${songs.size} 首歌曲", Modifier.weight(1f)); TextButton(vm::scan) { Icon(Icons.Default.Refresh, null); Text("重新扫描") } }
    if (songs.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("没有找到音乐文件\n请在本地音乐配置中设置路径后重新扫描", textAlign = TextAlign.Center) } else LazyColumn(contentPadding = PaddingValues(bottom = 92.dp)) { items(songs, key = { it.id }) { SongRow(it, { vm.play(it, songs) }, { vm.favorite(it) }) } }
}
@Composable private fun Favorites(songs: List<SongEntity>, vm: MusicViewModel, mod: Modifier) = Column(mod.fillMaxSize()) { Text("收藏歌曲", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(20.dp)); if (songs.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("还没有收藏歌曲") } else LazyColumn { items(songs, key = { it.id }) { SongRow(it, { vm.play(it, songs) }, { vm.favorite(it) }) } } }
@Composable private fun CloudLibrary(songs: List<SongEntity>, message: String?, busy: Boolean, vm: MusicViewModel, mod: Modifier, addSong: () -> Unit) {
    var management by rememberSaveable { mutableStateOf(false) }
    val session by vm.serverSession.collectAsStateWithLifecycle()
    val directories by vm.cloudDirectories.collectAsStateWithLifecycle()
    val playlists by vm.cloudPlaylists.collectAsStateWithLifecycle()
    val selected by vm.selectedCloudDirectory.collectAsStateWithLifecycle()
    LaunchedEffect(session) { if (session != null && directories.isEmpty()) vm.refreshCloud() }
    LaunchedEffect(playlists) { if (session != null && selected == null) playlists.firstOrNull()?.let { vm.selectCloudDirectory(it.directory) } }
    if (session == null) {
        Column(mod.fillMaxSize()) {
            Text("云端音乐", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(20.dp))
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("请先在“设置 → 个人中心”登录自建服务器\n登录后即可同步歌单、上传和播放云端音乐", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    if (management) { CloudMusicManagement(songs, message, busy, vm, mod, addSong) { management = false }; return }
    Column(mod.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Text("云端音乐", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f)); IconButton({ management = true }) { Icon(Icons.Default.AddCircle, "添加和管理") }; IconButton(vm::refreshCloud, enabled = !busy) { Icon(Icons.Default.CloudSync, "同步云端") } }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (directories.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("暂无云端歌曲目录\n点击右上角添加创建歌单目录", textAlign = TextAlign.Center) }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            playlists.forEach { playlist ->
                item(key = "playlist-${playlist.directory}") { Surface(onClick = { vm.selectCloudDirectory(playlist.directory) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = if (playlist.directory == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(playlist.coverUrl ?: R.drawable.default_playlist_cover, "${playlist.name}海报", Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop, error = painterResource(R.drawable.default_playlist_cover)); Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(playlist.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium); Text("${playlist.songCount} 首歌曲", style = MaterialTheme.typography.bodySmall) }; Icon(if (playlist.directory == selected) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (playlist.directory == selected) "收起状态" else "展开歌单") }
                } }
                if (playlist.directory == selected) {
                    if (songs.isEmpty() && !busy) item(key = "empty-${playlist.directory}") { Text("该歌单暂无歌曲", Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else items(songs, key = { "${playlist.directory}-${it.id}" }) { song -> SongRow(song, { vm.play(song, songs) }, { vm.favorite(song) }) }
                }
            }
            item { Spacer(Modifier.height(84.dp)) }
        }
    }
}

@Composable private fun CloudMusicManagement(songs: List<SongEntity>, message: String?, busy: Boolean, vm: MusicViewModel, mod: Modifier, addSong: () -> Unit, back: () -> Unit) {
    var directoryDialog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SongEntity?>(null) }
    var moveTarget by remember { mutableStateOf<SongEntity?>(null) }
    var actionTarget by remember { mutableStateOf<SongEntity?>(null) }
    var editSongTarget by remember { mutableStateOf<SongEntity?>(null) }
    var downloadTarget by remember { mutableStateOf<SongEntity?>(null) }
    val directories by vm.cloudDirectories.collectAsStateWithLifecycle()
    val playlists by vm.cloudPlaylists.collectAsStateWithLifecycle()
    val selected by vm.selectedCloudDirectory.collectAsStateWithLifecycle()
    var editPlaylist by remember { mutableStateOf<CloudPlaylist?>(null) }
    val saveDownload = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/*")) { uri -> val song = downloadTarget; if (uri != null && song != null) vm.downloadCloudSong(song, uri); downloadTarget = null }
    LaunchedEffect(playlists) { if (selected == null) playlists.firstOrNull()?.let { vm.selectCloudDirectory(it.directory) } }
    Column(mod.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(back) { Icon(Icons.Default.ArrowBack, "返回") }; Text("云端音乐管理", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ directoryDialog = true }, Modifier.weight(1f), enabled = !busy) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text("添加歌单目录") }
            Button(addSong, Modifier.weight(1f), enabled = !busy) { Icon(Icons.Default.LibraryAdd, null); Spacer(Modifier.width(6.dp)); Text("添加歌曲") }
        }
        Text("云端歌曲目录", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.titleMedium)
        if (playlists.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("暂无歌曲目录，可以先添加歌单目录") }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { playlists.forEachIndexed { index, playlist ->
            item(key = "manage-${playlist.directory}") { Surface(onClick = { vm.selectCloudDirectory(playlist.directory) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = if (playlist.directory == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(playlist.coverUrl ?: R.drawable.default_playlist_cover, null, Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop, error = painterResource(R.drawable.default_playlist_cover)); Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(playlist.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium); Text("${playlist.songCount} 首歌曲", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Column { IconButton({ vm.moveCloudPlaylist(playlist.directory, -1) }, enabled = !busy && index > 0) { Icon(Icons.Default.KeyboardArrowUp, "上移歌单") }; IconButton({ vm.moveCloudPlaylist(playlist.directory, 1) }, enabled = !busy && index < playlists.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "下移歌单") } }; IconButton({ editPlaylist = playlist }) { Icon(Icons.Default.Edit, "编辑歌单") }; Icon(if (playlist.directory == selected) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) } } }
            if (playlist.directory == selected) {
                if (songs.isEmpty() && !busy) item(key = "manage-empty-${playlist.directory}") { Text("该歌单暂无歌曲", Modifier.fillMaxWidth().padding(20.dp), textAlign = TextAlign.Center) }
                else items(songs, key = { "manage-${playlist.directory}-${it.id}" }) { song -> ListItem(headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text("属于 ${playlist.name}") }, leadingContent = { Icon(Icons.Default.CloudQueue, null) }, trailingContent = { IconButton({ actionTarget = song }, enabled = !busy) { Icon(Icons.Default.MoreVert, "歌曲操作") } }) }
            }
        }; item { Spacer(Modifier.height(72.dp)) } }
    }
    if (directoryDialog) CloudDirectoryDialog(null, { directoryDialog = false }) { name, cover -> vm.createCloudDirectory(name, cover); directoryDialog = false }
    editPlaylist?.let { playlist -> CloudDirectoryDialog(playlist, { editPlaylist = null }) { name, cover -> vm.updateCloudDirectory(playlist.directory, name, cover); editPlaylist = null } }
    actionTarget?.let { song -> CloudSongActions(song, playlists.size > 1, { actionTarget = null }, download = { actionTarget = null; downloadTarget = song; val ext = song.audioObjectKey?.substringAfterLast('.', "mp3") ?: "mp3"; saveDownload.launch("${song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.$ext") }, move = { actionTarget = null; moveTarget = song }, edit = { actionTarget = null; editSongTarget = song }, delete = { actionTarget = null; deleteTarget = song }) }
    editSongTarget?.let { song -> EditCloudSongDialog(song, { editSongTarget = null }) { title, lyric, cover -> vm.updateCloudSong(song, title, lyric, cover); editSongTarget = null } }
    moveTarget?.let { song -> MoveSongDialog(song, playlists, { moveTarget = null }) { directory -> vm.moveCloudSong(song, directory); moveTarget = null } }
    deleteTarget?.let { song -> AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除云端歌曲") }, text = { Text("确定删除《${song.title}》吗？音乐、海报、歌词及云端清单记录都会被删除，此操作无法撤销。") }, confirmButton = { Button({ vm.deleteCloudSong(song); deleteTarget = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("确认删除") } }, dismissButton = { TextButton({ deleteTarget = null }) { Text("取消") } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CloudSongActions(song: SongEntity, canMove: Boolean, dismiss: () -> Unit, download: () -> Unit, move: () -> Unit, edit: () -> Unit, delete: () -> Unit) = ModalBottomSheet(onDismissRequest = dismiss) {
    Text(song.title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    ListItem({ Text("下载歌曲") }, leadingContent = { Icon(Icons.Default.Download, null) }, modifier = Modifier.clickable(onClick = download))
    ListItem({ Text("移动到其他歌单") }, supportingContent = { if (!canMove) Text("需要先创建其他歌单") }, leadingContent = { Icon(Icons.Default.DriveFileMove, null) }, modifier = Modifier.clickable(enabled = canMove, onClick = move))
    ListItem({ Text("编辑歌曲资料") }, supportingContent = { Text("修改歌名、歌词和海报") }, leadingContent = { Icon(Icons.Default.Edit, null) }, modifier = Modifier.clickable(onClick = edit))
    ListItem({ Text("删除云端歌曲", color = MaterialTheme.colorScheme.error) }, leadingContent = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, modifier = Modifier.clickable(onClick = delete))
    Spacer(Modifier.height(28.dp))
}

@Composable private fun EditCloudSongDialog(song: SongEntity, dismiss: () -> Unit, save: (String, Uri?, Uri?) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var title by remember(song.id) { mutableStateOf(song.title) }; var lyric by remember(song.id) { mutableStateOf<Uri?>(null) }; var cover by remember(song.id) { mutableStateOf<Uri?>(null) }
    fun keep(uri: Uri) { runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    val pickLyric = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> keep(uri); lyric = uri } }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> keep(uri); cover = uri } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("编辑歌曲资料") }, text = { Column { OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("歌曲名称") }, singleLine = true); OutlinedButton({ pickLyric.launch(arrayOf("text/plain", "application/octet-stream")) }, Modifier.fillMaxWidth().padding(top = 10.dp)) { Icon(Icons.Default.Description, null); Spacer(Modifier.width(8.dp)); Text(lyric?.let(ctx::fileName) ?: if (song.lyricObjectKey == null) "添加歌词文件" else "更换歌词文件") }; OutlinedButton({ pickCover.launch(arrayOf("image/*")) }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(8.dp)); Text(cover?.let(ctx::fileName) ?: if (song.coverObjectKey == null) "添加歌曲海报" else "更换歌曲海报") } } }, confirmButton = { Button({ save(title, lyric, cover) }, enabled = title.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}

@Composable private fun MoveSongDialog(song: SongEntity, playlists: List<CloudPlaylist>, dismiss: () -> Unit, move: (String) -> Unit) {
    val targets = playlists.filter { it.directory != song.album }
    var selected by remember(song.id, targets) { mutableStateOf(targets.firstOrNull()?.directory.orEmpty()) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("移动歌曲") }, text = { Column { Text("将《${song.title}》移动到：", Modifier.padding(bottom = 8.dp)); targets.forEach { playlist -> Row(Modifier.fillMaxWidth().clickable { selected = playlist.directory }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected == playlist.directory, { selected = playlist.directory }); AsyncImage(playlist.coverUrl ?: R.drawable.default_playlist_cover, null, Modifier.size(40.dp).clip(RoundedCornerShape(7.dp)), contentScale = ContentScale.Crop, error = painterResource(R.drawable.default_playlist_cover)); Column(Modifier.padding(start = 10.dp)) { Text(playlist.name); Text("${playlist.songCount} 首歌曲", style = MaterialTheme.typography.bodySmall) } } } } }, confirmButton = { Button({ move(selected) }, enabled = selected.isNotBlank()) { Text("确认移动") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}

@Composable private fun CloudDirectoryDialog(existing: CloudPlaylist?, dismiss: () -> Unit, save: (String, Uri?) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }; var cover by remember(existing) { mutableStateOf<Uri?>(null) }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { runCatching { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; cover = it } }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (existing == null) "添加歌单目录" else "编辑歌单目录") }, text = { Column { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("歌单名称") }, singleLine = true); OutlinedButton({ pickCover.launch(arrayOf("image/*")) }, Modifier.fillMaxWidth().padding(top = 10.dp)) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(8.dp)); Text(if (cover == null) { if (existing?.coverUrl == null) "添加歌单海报（可选）" else "更换歌单海报（可选）" } else ctx.fileName(cover!!), maxLines = 1, overflow = TextOverflow.Ellipsis) }; Text("没有歌单海报时，将自动使用第一首歌海报；仍没有则显示默认封面。", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) } }, confirmButton = { Button({ save(name, cover) }, enabled = name.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}
@Composable private fun PlayerControls(item: MediaItem?, playing: Boolean, position: Long, durationMs: Long, vm: MusicViewModel, open: () -> Unit, queue: () -> Unit) = Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { if (item != null) open() }) { Column { Row(Modifier.height(62.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary); IconButton(vm::previous) { Icon(Icons.Default.SkipPrevious, "上一首") }; IconButton(vm::toggle) { Icon(if (playing) Icons.Default.PauseCircle else Icons.Default.PlayCircle, "播放") }; IconButton(vm::next) { Icon(Icons.Default.SkipNext, "下一首") }; Column(Modifier.weight(1f)) { Text(item?.mediaMetadata?.title?.toString() ?: "请选择歌曲播放", maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${duration(position)} / ${duration(durationMs)}", style = MaterialTheme.typography.labelSmall) }; if (item != null) IconButton(queue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "打开当前歌单") } }; LinearProgressIndicator(progress = { if (durationMs > 0) position.toFloat() / durationMs else 0f }, modifier = Modifier.fillMaxWidth()) } }
@Composable private fun SongRow(s: SongEntity, play: () -> Unit, fav: () -> Unit) = ListItem({ Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.clickable(onClick = play), supportingContent = { Text("${s.artist}${if (s.durationMs > 0) " · ${duration(s.durationMs)}" else ""}") }, leadingContent = { Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary) }, trailingContent = { IconButton(fav) { Icon(if (s.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "收藏") } })

private val defaultSongCovers = intArrayOf(
    R.drawable.default_song_cover_lanterns,
    R.drawable.default_song_cover_moon,
    R.drawable.default_song_cover_library,
    R.drawable.default_song_cover_night
)

private data class LyricPreviewLine(val text: String, val active: Boolean = false)

private fun lyricPreview(raw: String, positionMs: Long): List<LyricPreviewLine> {
    val timestamp = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    val timed = raw.lineSequence().flatMap { line ->
        val text = line.replace(timestamp, "").trim()
        timestamp.findAll(line).map { match ->
            val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            val time = (match.groupValues[1].toLong() * 60_000L) + (match.groupValues[2].toLong() * 1_000L) + fraction
            time to text
        }
    }.filter { it.second.isNotBlank() }.sortedBy { it.first }.toList()
    if (timed.isNotEmpty()) {
        val active = timed.indexOfLast { it.first <= positionMs }.coerceAtLeast(0)
        val start = (active - 1).coerceIn(0, (timed.size - 4).coerceAtLeast(0))
        return timed.drop(start).take(4).mapIndexed { index, item -> LyricPreviewLine(item.second, start + index == active) }
    }
    return raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("[") }
        .take(4)
        .map(::LyricPreviewLine)
        .toList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerPage(song: SongEntity, songs: List<SongEntity>, playing: Boolean, vm: MusicViewModel, back: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val position by vm.position.collectAsStateWithLifecycle()
    val total by vm.duration.collectAsStateWithLifecycle()
    val mode by vm.playbackMode.collectAsStateWithLifecycle()
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var lyrics by remember(song.id) { mutableStateOf(song.lyricsText ?: "暂无歌词\n可在添加歌曲时导入 LRC 或 TXT 文件") }
    val defaultCover = remember(song.id) { defaultSongCovers[Math.floorMod(song.id.hashCode(), defaultSongCovers.size)] }
    val previewLines = remember(lyrics, position / 250L) { lyricPreview(lyrics, position) }
    LaunchedEffect(song.lyricsText, song.lyricUri) {
        if (song.lyricsText != null) lyrics = song.lyricsText
        else song.lyricUri?.let { lyrics = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(Uri.parse(it))?.bufferedReader()?.readText().orEmpty() }.getOrDefault("歌词读取失败") } }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White,
        contentColor = Color(0xFF171717)
    ) {
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth().height(68.dp), contentAlignment = Alignment.Center) {
            IconButton(onClick = back, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.Default.KeyboardArrowDown, "收起播放页", Modifier.size(34.dp))
            }
            Text("正在播放", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { queueOpen = true }, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, "当前播放队列", Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
            val artworkSize = minOf(maxWidth, maxHeight)
            AsyncImage(
                model = song.posterUri ?: defaultCover,
                contentDescription = "歌曲海报",
                modifier = Modifier.size(artworkSize).padding(6.dp).clip(RoundedCornerShape(30.dp)),
                contentScale = ContentScale.Crop,
                error = painterResource(defaultCover),
                fallback = painterResource(defaultCover)
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${song.artist.ifBlank { "未知艺术家" }} · ${song.album.ifBlank { if (song.sourceType == "CLOUD") "云端音乐" else "本地音乐" }}",
                    color = Color(0xFF5F5F5F),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { vm.favorite(song) }) {
                Icon(if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "收藏歌曲", tint = if (song.isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
        }
        Surface(
            onClick = { editorOpen = true },
            modifier = Modifier.fillMaxWidth().height(104.dp).padding(top = 4.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFFF4F4F4)
        ) {
            Box(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                if (previewLines.isEmpty()) {
                    Text("暂无歌词\n点击此处添加歌词", textAlign = TextAlign.Center, color = Color(0xFF666666))
                } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    previewLines.forEach { line ->
                        Text(
                            line.text,
                            modifier = Modifier.fillMaxWidth().height(22.dp),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = if (line.active) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                            color = if (line.active) Color.Black else Color(0xFF777777)
                        )
                    }
                }
            }
        }
        Slider(
            value = position.coerceAtMost(total.coerceAtLeast(1L)).toFloat(),
            onValueChange = { vm.seekTo(it.toLong()) },
            valueRange = 0f..total.coerceAtLeast(1L).toFloat(),
            modifier = Modifier.fillMaxWidth().graphicsLayer { scaleY = 0.55f },
            colors = SliderDefaults.colors(
                thumbColor = Color.Black,
                activeTrackColor = Color.Black,
                activeTickColor = Color.White,
                inactiveTrackColor = Color(0xFFD6D6D6),
                inactiveTickColor = Color.White
            )
        )
        Row(Modifier.fillMaxWidth()) {
            Text(duration(position), style = MaterialTheme.typography.labelMedium, color = Color(0xFF555555))
            Text(duration(total), style = MaterialTheme.typography.labelMedium, color = Color(0xFF555555), modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        ExpandedPlayerControls(playing, mode, vm, { queueOpen = true })
    }
    if (queueOpen) QueueSheet(songs, song.id.toString(), vm) { queueOpen = false }
    if (editorOpen) EditLyrics(song, lyrics, vm) { editorOpen = false }
    }
    }
}

@Composable private fun ExpandedPlayerControls(playing: Boolean, mode: PlaybackMode, vm: MusicViewModel, queue: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
            IconButton(vm::cyclePlaybackMode) {
                Icon(
                    when (mode) {
                        PlaybackMode.SEQUENTIAL -> Icons.AutoMirrored.Filled.QueueMusic
                        PlaybackMode.SHUFFLE -> Icons.Default.Shuffle
                        PlaybackMode.REPEAT_ONE -> Icons.Default.RepeatOne
                    },
                    mode.label,
                    Modifier.size(27.dp)
                )
            }
            Text(mode.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        IconButton(vm::previous, Modifier.size(50.dp)) { Icon(Icons.Default.SkipPrevious, "上一首", Modifier.size(36.dp)) }
        OutlinedIconButton(onClick = vm::toggle, modifier = Modifier.size(76.dp)) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "暂停" else "播放", Modifier.size(42.dp))
        }
        IconButton(vm::next, Modifier.size(50.dp)) { Icon(Icons.Default.SkipNext, "下一首", Modifier.size(36.dp)) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
            IconButton(queue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "当前播放队列", Modifier.size(27.dp)) }
            Text("播放队列", style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable private fun EditLyrics(song: SongEntity, currentLyrics: String, vm: MusicViewModel, dismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var title by remember(song.id) { mutableStateOf(song.title) }
    var lyrics by remember(song.id) { mutableStateOf(currentLyrics) }
    var poster by remember(song.id) { mutableStateOf(song.posterUri?.let(Uri::parse)) }
    var speed by remember(song.id) { mutableFloatStateOf(song.lyricSpeed) }
    var offset by remember(song.id) { mutableFloatStateOf(song.lyricsOffsetMs.toFloat()) }
    val pickPoster = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { runCatching { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; poster = it } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("编辑歌词与歌曲资料") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("歌名") }, singleLine = true)
            OutlinedButton(onClick = { pickPoster.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(8.dp)); Text(if (poster == null) "选择海报" else ctx.fileName(poster!!)) }
            OutlinedTextField(lyrics, { lyrics = it }, Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 160.dp), label = { Text("歌词") })
            Text("歌词显示速度：${"%.1f".format(speed)}×", modifier = Modifier.padding(top = 12.dp))
            Slider(speed, { speed = it }, valueRange = 0.5f..1.5f)
            Text("歌词时间偏移记录：${offset.toInt()} ms")
            Slider(offset, { offset = it }, valueRange = -3000f..3000f)
        }
    }, confirmButton = { Button(onClick = { vm.updateLyrics(song, title.ifBlank { song.title }, poster?.toString(), lyrics, speed, offset.toLong()); dismiss() }) { Text("保存记录") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun QueueSheet(songs: List<SongEntity>, currentId: String?, vm: MusicViewModel, dismiss: () -> Unit) = ModalBottomSheet(onDismissRequest = dismiss) {
    Text("当前歌单", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) { items(songs, key = { it.id }) { entry ->
        ListItem(headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(entry.artist) }, leadingContent = { Icon(if (entry.id.toString() == currentId) Icons.Default.Equalizer else Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { vm.play(entry, songs); dismiss() })
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AddSong(vm: MusicViewModel, dismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val loggedIn by vm.serverLoggedIn.collectAsStateWithLifecycle()
    val directories by vm.cloudDirectories.collectAsStateWithLifecycle()
    val playlists by vm.cloudPlaylists.collectAsStateWithLifecycle()
    val busy by vm.cloudBusy.collectAsStateWithLifecycle()
    var audio by remember { mutableStateOf<Uri?>(null) }; var lyric by remember { mutableStateOf<Uri?>(null) }; var poster by remember { mutableStateOf<Uri?>(null) }
    var title by remember { mutableStateOf("") }; var directory by remember { mutableStateOf("") }; var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(loggedIn) { if (loggedIn) vm.loadCloudDirectories() }
    LaunchedEffect(directories) { if (directory !in directories) directory = directories.firstOrNull().orEmpty() }
    fun keep(u: Uri) { runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> keep(u); audio = u } }
    val pickLyric = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> keep(u); lyric = u } }
    val pickPoster = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> keep(u); poster = u } }
    AlertDialog(dismiss, title = { Text("添加歌曲") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                if (loggedIn) "选择服务器歌单后，歌曲、歌词和海报会上传到你的自建服务器。" else "当前未登录服务器，歌曲会保存到本地音乐库。",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("歌曲名称（可选）") })
            if (loggedIn) {
                ExposedDropdownMenuBox(expanded, { if (directories.isNotEmpty()) expanded = it }, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    OutlinedTextField(playlists.firstOrNull { it.directory == directory }?.name.orEmpty(), {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text("服务器歌单") }, placeholder = { Text(if (busy) "正在读取歌单…" else "暂无歌单，请先创建") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
                    ExposedDropdownMenu(expanded, { expanded = false }) { playlists.forEach { item -> DropdownMenuItem({ Text(item.name) }, { directory = item.directory; expanded = false }) } }
                }
                if (directories.isEmpty() && !busy) TextButton(vm::loadCloudDirectories, Modifier.align(Alignment.End)) { Icon(Icons.Default.Refresh, null); Text("重新读取歌单") }
            }
            PickButton(if (audio == null) "选择歌曲" else ctx.fileName(audio!!), Icons.Default.MusicNote) { pickAudio.launch(arrayOf("audio/*")) }
            PickButton(if (lyric == null) "选择歌词（可选）" else ctx.fileName(lyric!!), Icons.Default.Description) { pickLyric.launch(arrayOf("text/plain", "application/octet-stream")) }
            PickButton(if (poster == null) "选择海报（可选）" else ctx.fileName(poster!!), Icons.Default.Image) { pickPoster.launch(arrayOf("image/*")) }
        }
    }, confirmButton = { Button({ vm.importSong(audio!!, lyric, poster, title, directory); dismiss() }, enabled = audio != null && (!loggedIn || directory.isNotBlank()) && !busy) { Text("添加") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}
@Composable private fun PickButton(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, pick: () -> Unit) = OutlinedButton(pick, Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
@Composable private fun Settings(
    vm: MusicViewModel, path: String?, loudnessBoost: Boolean, fadeEnabled: Boolean, fadeDuration: Int,
    session: ServerSession?, avatar: Bitmap?, authMessage: String?, authBusy: Boolean,
    cloudMessage: String?, cloudBusy: Boolean, mod: Modifier, openProfile: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf("main") }
    if (page == "server") { ServerSettingsPage(session, authMessage, authBusy, vm, mod, back = { page = "main" }); return }
    if (page == "playback") { PlaybackSettingsPage(loudnessBoost, fadeEnabled, fadeDuration, vm, mod) { page = "main" }; return }
    if (page == "local") { LocalMusicSettingsPage(path, vm, mod) { page = "main" }; return }
    Column(mod.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(10.dp))
            SectionCard {
                ListItem(
                    headlineContent = { Text(session?.displayName?.ifBlank { session.username } ?: "未登录", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(if (session == null) "点击登录自建服务器" else "账号：${session.username}") },
                    leadingContent = { AvatarThumb(session, avatar, 52) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                    modifier = Modifier.clickable(onClick = openProfile)
                )
            }
            Spacer(Modifier.height(10.dp))
            SectionCard {
                Column {
                    SettingsRow("播放设置", "音量平衡、渐入渐出及更多播放选项", Icons.Default.Tune) { page = "playback" }
                    RowDivider()
                    SettingsRow("本地音乐配置", if (path == null) "系统音乐库、路径与扫描设置" else "已配置音乐路径", Icons.Default.FolderOpen) { page = "local" }
                }
            }
            Spacer(Modifier.height(10.dp))
            SectionCard {
                ListItem(
                    headlineContent = { Text("播放缓存") },
                    supportingContent = { Text("云端音乐使用 5 GB LRU 磁盘缓存") },
                    leadingContent = { Icon(Icons.Default.Storage, null) }
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** 微信风格的分组白色卡片。 */
@Composable private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable private fun SettingsRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable private fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable private fun AvatarThumb(session: ServerSession?, avatar: Bitmap?, size: Int) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(size.dp)
    ) {
        if (avatar != null) {
            Image(avatar.asImageBitmap(), contentDescription = "头像", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    (session?.username?.take(1) ?: "?").uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable private fun LocalMusicSettingsPage(path: String?, vm: MusicViewModel, mod: Modifier, back: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { runCatching { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; vm.savePath(it) } }
    Column(mod.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(back) { Icon(Icons.Default.ArrowBack, "返回") }; Text("本地音乐配置", style = MaterialTheme.typography.headlineSmall) }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            ListItem({ Text("本地音乐路径") }, supportingContent = { Text(path ?: "未指定：使用系统音乐库", maxLines = 2, overflow = TextOverflow.Ellipsis) }, leadingContent = { Icon(Icons.Default.Folder, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.clickable { folder.launch(null) })
            HorizontalDivider()
            ListItem({ Text("扫描本地音乐") }, supportingContent = { Text("重新读取系统媒体库中的音频文件") }, leadingContent = { Icon(Icons.Default.LibraryMusic, null) }, trailingContent = { IconButton(vm::scan) { Icon(Icons.Default.Refresh, "开始扫描") } }, modifier = Modifier.clickable(onClick = vm::scan))
            Text("扫描会更新歌曲索引，不会复制、移动或删除设备上的音乐文件。", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ServerSettingsPage(
    current: ServerSession?, message: String?, busy: Boolean, vm: MusicViewModel, mod: Modifier,
    back: () -> Unit, gate: Boolean = false
) {
    var username by remember(current) { mutableStateOf(current?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("") }
    var registerMode by rememberSaveable { mutableStateOf(false) }
    Column(mod.fillMaxSize()) {
        if (!gate) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(back) { Icon(Icons.Default.ArrowBack, "返回") }
                Text("服务器账号", style = MaterialTheme.typography.headlineSmall)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
            if (gate) {
                Icon(
                    Icons.Default.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).align(Alignment.CenterHorizontally).padding(top = 24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "SelfMusic",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)
                )
                Text(
                    "登录后同步你的音乐库与云端歌单",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp, bottom = 8.dp)
                )
            } else {
                Text(
                    if (registerMode) "创建一个新账号，注册成功后会自动登录。" else "登录已有账号，云端音乐、歌单和上传都会保存到服务器。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            TabRow(
                selectedTabIndex = if (registerMode) 1 else 0,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Tab(
                    selected = !registerMode,
                    onClick = { registerMode = false; password = ""; confirmPassword = ""; vm.clearAuthMessage() },
                    text = { Text("登录") }
                )
                Tab(
                    selected = registerMode,
                    onClick = { registerMode = true; password = ""; confirmPassword = ""; vm.clearAuthMessage() },
                    text = { Text("注册") }
                )
            }
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth().padding(top = 18.dp), label = { Text("用户名") }, singleLine = true)
            if (username.isNotBlank() && !username.matches(Regex("[A-Za-z0-9_]{3,32}"))) {
                Text("用户名需为 3-32 位字母、数字或下划线", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            PasswordTextField(
                value = password,
                onValueChange = { password = it },
                label = "密码",
                visible = passwordVisible,
                onToggleVisibility = { passwordVisible = !passwordVisible },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )
            if (registerMode) {
                PasswordTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = "确认密码",
                    visible = confirmPasswordVisible,
                    onToggleVisibility = { confirmPasswordVisible = !confirmPasswordVisible },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                OutlinedTextField(displayName, { displayName = it }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("显示名称（可选）") }, singleLine = true)
                Text(
                    "用户名 3-32 位，只能包含字母、数字或下划线；密码 8-72 位。",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (password.isNotBlank() && password.length < 8) {
                    Text("密码至少需要 8 位", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (password.length > 72) {
                    Text("密码最多 72 位", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (confirmPassword.isNotBlank() && password != confirmPassword) {
                    Text("两次输入的密码不一致", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            message?.let {
                Text(
                    it,
                    Modifier.padding(top = 12.dp),
                    color = when {
                        it.contains("成功") -> MaterialTheme.colorScheme.primary
                        it.contains("失败") || it.contains("错误") || it.contains("过期") || it.contains("不一致") -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            val usernameValid = username.matches(Regex("[A-Za-z0-9_]{3,32}"))
            val canSubmit = !busy && username.isNotBlank() && usernameValid && password.isNotBlank() &&
                (!registerMode || (password.length in 8..72 && password == confirmPassword))
            Button(
                onClick = {
                    if (registerMode) vm.register(username, password, displayName)
                    else vm.login(username, password)
                },
                Modifier.fillMaxWidth().padding(top = 16.dp),
                enabled = canSubmit
            ) {
                Icon(if (registerMode) Icons.Default.PersonAdd else Icons.Default.Login, null)
                Spacer(Modifier.width(8.dp))
                Text(if (registerMode) "注册并登录" else "登录")
            }
            if (!registerMode && current == null) {
                TextButton(
                    onClick = { registerMode = true; password = ""; confirmPassword = ""; vm.clearAuthMessage() },
                    modifier = Modifier.align(Alignment.End)
                ) { Text("没有账号？立即注册") }
            }
            if (current != null) {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text("当前账号：${current.displayName.ifBlank { current.username }}（@${current.username}）", style = MaterialTheme.typography.bodyMedium)
                Text(current.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(vm::logout, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !busy) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
            }
            if (!gate) {
                Text(
                    "Token 使用 Android Keystore 加密保存在本机。公网部署请使用 HTTPS；HTTP 仅建议在可信局域网使用。",
                    Modifier.padding(top = 16.dp, bottom = 24.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable private fun ProfilePage(
    vm: MusicViewModel, session: ServerSession, avatar: Bitmap?, themeKey: String, busy: Boolean, message: String?,
    mod: Modifier, back: () -> Unit
) {
    var themePicker by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.uploadAvatar(uri)
    }
    LaunchedEffect(session.userId, session.avatarObjectKey) { vm.refreshAvatar() }
    val view = LocalView.current
    val activity = androidx.compose.ui.platform.LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = true
        controller?.isAppearanceLightNavigationBars = true
        onDispose {
            if (previousStatus != null) controller?.isAppearanceLightStatusBars = previousStatus
            if (previousNavigation != null) controller?.isAppearanceLightNavigationBars = previousNavigation
        }
    }
    Surface(mod.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                    Text("个人中心", style = MaterialTheme.typography.headlineSmall)
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(10.dp))
                    SectionCard {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                AvatarThumb(session, avatar, 72)
                                Surface(
                                    shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.BottomEnd).size(26.dp).clickable {
                                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    }
                                ) {
                                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                                        Icon(Icons.Default.PhotoCamera, "更换头像", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(session.displayName.ifBlank { session.username }, style = MaterialTheme.typography.titleLarge)
                                Text("账号：${session.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    message?.let {
                        Text(
                            it, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    SectionCard {
                        Column {
                            SettingsRow("修改头像", "从相册选择，压缩后上传到服务器", Icons.Default.PhotoCamera) {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                            if (session.hasAvatar) {
                                RowDivider()
                                SettingsRow("移除头像", "恢复为用户名首字母", Icons.Default.DeleteOutline) { vm.removeAvatar() }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SectionCard {
                        ListItem(
                            headlineContent = { Text("主题颜色") },
                            supportingContent = { Text(themeFor(themeKey).label) },
                            leadingContent = { Icon(Icons.Default.Palette, null) },
                            trailingContent = {
                                Box(Modifier.size(22.dp).clip(CircleShape).background(themeFor(themeKey).primary))
                            },
                            modifier = Modifier.clickable { themePicker = true }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    SectionCard {
                        Column {
                            InfoRow("服务器", session.baseUrl)
                            RowDivider()
                            InfoRow("账号", session.username)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SectionCard {
                        Text(
                            "退出登录",
                            Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = vm::logout).padding(vertical = 16.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        "头像保存在自建服务器，换设备登录会自动同步。Token 使用 Android Keystore 加密保存在本机。",
                        Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    if (themePicker) {
        AlertDialog(
            onDismissRequest = { themePicker = false },
            title = { Text("主题颜色") },
            text = {
                Column {
                    appThemes.forEach { theme ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.setThemeColor(theme.key); themePicker = false }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(26.dp).clip(CircleShape).background(theme.primary))
                            Spacer(Modifier.width(14.dp))
                            Text(theme.label, Modifier.weight(1f))
                            if (theme.key == themeKey) Icon(Icons.Default.Check, "已选", tint = theme.primary)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ themePicker = false }) { Text("完成") } }
        )
    }
}

@Composable private fun InfoRow(title: String, value: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    )
}

/** 相册图片先按长边 512 压缩成 JPEG，避免上传原图。 */
private fun compressAvatar(context: Context, uri: Uri): ByteArray {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: throw IllegalStateException("无法读取所选图片")
    val ratio = 512f / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
    val scaled = if (ratio < 1f) {
        Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    } else bitmap
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
    return out.toByteArray()
}

@Composable private fun PasswordTextField(
    value: String, onValueChange: (String) -> Unit, label: String,
    visible: Boolean, onToggleVisibility: () -> Unit, modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) {
            androidx.compose.ui.text.input.VisualTransformation.None
        } else {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "隐藏密码" else "显示密码"
                )
            }
        }
    )
}

@Composable private fun PlaybackSettingsPage(loudnessBoost: Boolean, fadeEnabled: Boolean, fadeDuration: Int, vm: MusicViewModel, mod: Modifier, back: () -> Unit) {
    Column(mod.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(back) { Icon(Icons.Default.ArrowBack, "返回") }; Text("播放设置", style = MaterialTheme.typography.headlineSmall) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            ListItem({ Text("音量平衡") }, supportingContent = { Text("温和提升音量较小的歌曲，减少不同歌曲间的响度差异") }, leadingContent = { Icon(Icons.Default.GraphicEq, null) }, trailingContent = { Switch(loudnessBoost, vm::setLoudnessBoost) })
            HorizontalDivider()
            ListItem({ Text("渐入渐出") }, supportingContent = { Text("播放、暂停和手动切歌时平滑改变音量") }, leadingContent = { Icon(Icons.Default.VolumeUp, null) }, trailingContent = { Switch(fadeEnabled, vm::setFadeEnabled) })
            Text("渐变时长：${fadeDuration} ms", Modifier.padding(start = 16.dp, top = 12.dp), color = if (fadeEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(fadeDuration.toFloat(), { vm.setFadeDurationMs(it.toInt()) }, valueRange = 200f..3000f, steps = 13, enabled = fadeEnabled, modifier = Modifier.padding(horizontal = 8.dp))
            Text("较短响应更快，较长过渡更柔和。自动播放到下一首时会执行渐入，手动切歌会执行完整渐出与渐入。", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
private fun Context.fileName(uri: Uri): String = runCatching { contentResolver.query(uri, null, null, null, null)?.use { c -> c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.moveToFirst(); c.getString(it) } } }.getOrNull() ?: "已选择文件"
private fun duration(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
/** 可选主题色。primaryContainer 用于头像底、播放条等浅色块。 */
data class AppTheme(val key: String, val label: String, val primary: Color, val primaryContainer: Color, val secondary: Color)

private val appThemes = listOf(
    AppTheme("gold", "鎏金", Color(0xFFB88925), Color(0xFFFFE9B0), Color(0xFF8B6919)),
    AppTheme("blue", "海蓝", Color(0xFF2F6FD0), Color(0xFFD8E6FF), Color(0xFF4A80D9)),
    AppTheme("green", "松绿", Color(0xFF2E8B57), Color(0xFFD3F0DF), Color(0xFF3D9E68)),
    AppTheme("purple", "雅紫", Color(0xFF7A4FD0), Color(0xFFE9DDFF), Color(0xFF8B63DE)),
    AppTheme("red", "绯红", Color(0xFFC0392B), Color(0xFFFFDAD3), Color(0xFFD05043)),
    AppTheme("pink", "樱粉", Color(0xFFD4557F), Color(0xFFFFDDE7), Color(0xFFE07098))
)

private fun themeFor(key: String?): AppTheme = appThemes.firstOrNull { it.key == key } ?: appThemes.first()

@Composable private fun GoldTheme(themeKey: String? = "gold", content: @Composable () -> Unit) {
    val theme = themeFor(themeKey)
    // 统一浅色外观（参考微信）：页面底色浅灰，内容块为白色卡片，主色由用户选择
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = theme.primary,
            onPrimary = Color.White,
            primaryContainer = theme.primaryContainer,
            onPrimaryContainer = Color(0xFF1B1B1B),
            secondary = theme.secondary,
            secondaryContainer = theme.primaryContainer,
            background = Color(0xFFF2F2F2),
            surface = Color.White,
            surfaceVariant = Color(0xFFF2F2F2),
            onSurfaceVariant = Color(0xFF666666)
        ),
        content = content
    )
}
