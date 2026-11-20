package com.selfmusic.data.server

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.selfmusic.data.cloud.CloudPlaylist
import com.selfmusic.data.local.SongEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 自建服务器 API 客户端。
 *
 * 说明：
 * - 登录/刷新令牌保存在 [ServerSessionStore]（Keystore 加密）。
 * - Access Token 过期时自动刷新一次并重试。
 * - 云端媒体由后端签发预签名 URL（本地存储用短时 token），播放地址可直接交给 ExoPlayer。
 */
class ServerApi(context: Context, private val store: ServerSessionStore) {

    private val resolver = context.applicationContext.contentResolver
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()
    private val refreshLock = Any()

    suspend fun login(baseUrl: String, username: String, password: String): ServerSession =
        withContext(Dispatchers.IO) {
            val normalized = normalizeBaseUrl(baseUrl)
            val body = JSONObject().apply {
                put("username", username.trim())
                put("password", password)
            }
            client.newCall(
                Request.Builder()
                    .url("$normalized/api/v1/auth/login")
                    .post(jsonBody(body))
                    .build()
            ).execute().use { response ->
                ensureSuccess(response)
                val session = parseAuth(normalized, JSONObject(response.body?.string().orEmpty()))
                store.save(session)
                session
            }
        }

    suspend fun register(baseUrl: String, username: String, password: String, displayName: String): ServerSession =
        withContext(Dispatchers.IO) {
            val normalized = normalizeBaseUrl(baseUrl)
            val body = JSONObject().apply {
                put("username", username.trim())
                put("password", password)
                put("displayName", displayName.trim())
            }
            client.newCall(
                Request.Builder()
                    .url("$normalized/api/v1/auth/register")
                    .post(jsonBody(body))
                    .build()
            ).execute().use { response ->
                ensureSuccess(response)
                val session = parseAuth(normalized, JSONObject(response.body?.string().orEmpty()))
                store.save(session)
                session
            }
        }

    /** 上传头像；图片会先在本地压缩，服务端只接受 JPEG/PNG/WebP 且不超过 5 MB。 */
    suspend fun uploadAvatar(image: ByteArray, contentType: String = "image/jpeg"): ServerSession =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "avatar.${if (contentType == "image/png") "png" else "jpg"}",
                    image.toRequestBody(contentType.toMediaType())
                )
                .build()
            requestWithAuth("/api/v1/users/me/avatar", "PUT", body).use { response ->
                ensureSuccess(response)
                val user = JSONObject(response.body?.string().orEmpty())
                val latest = store.session.value ?: throw ServerAuthException("请先登录服务器")
                val updated = latest.copy(
                    displayName = user.optString("displayName", latest.displayName),
                    avatarObjectKey = user.optString("avatarObjectKey").takeIf { it.isNotBlank() && it != "null" }
                )
                store.save(updated)
                updated
            }
        }

    /** 下载当前账号头像；未设置头像时返回 null。 */
    suspend fun fetchAvatar(): ByteArray? = withContext(Dispatchers.IO) {
        val session = store.session.value ?: return@withContext null
        if (!session.hasAvatar) return@withContext null
        requestWithAuth("/api/v1/users/me/avatar").use { response ->
            if (response.code == 404) return@withContext null
            ensureSuccess(response)
            response.body?.bytes()
        }
    }

    suspend fun deleteAvatar(): ServerSession? = withContext(Dispatchers.IO) {
        val session = store.session.value ?: return@withContext null
        requestWithAuth("/api/v1/users/me/avatar", "DELETE").use { response ->
            if (response.code != 404) ensureSuccess(response)
        }
        val updated = session.copy(avatarObjectKey = null)
        store.save(updated)
        updated
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val session = store.session.value
        if (session != null) {
            runCatching {
                val body = JSONObject().put("refreshToken", session.refreshToken)
                client.newCall(
                    Request.Builder()
                        .url("${session.baseUrl}/api/v1/auth/logout")
                        .post(jsonBody(body))
                        .build()
                ).execute().close()
            }
        }
        store.clear()
    }

    suspend fun playlists(): List<CloudPlaylist> = withContext(Dispatchers.IO) {
        requestWithAuth("/api/v1/playlists").use { response ->
            ensureSuccess(response)
            parsePlaylists(JSONArray(response.body?.string().orEmpty()))
        }
    }

    suspend fun createPlaylist(name: String, cover: Uri? = null): CloudPlaylist =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("name", name)
            cover?.let {
                body.addFormDataPart("cover", displayName(it, "cover.jpg"), UriRequestBody(it))
            }
            requestWithAuth("/api/v1/playlists", "POST", body.build()).use { response ->
                ensureSuccess(response)
                parsePlaylist(JSONObject(response.body?.string().orEmpty()))
            }
        }

    suspend fun updatePlaylist(playlistId: String, name: String, cover: Uri? = null): CloudPlaylist =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("name", name)
            cover?.let {
                body.addFormDataPart("cover", displayName(it, "cover.jpg"), UriRequestBody(it))
            }
            requestWithAuth("/api/v1/playlists/${encode(playlistId)}", "PUT", body.build()).use { response ->
                ensureSuccess(response)
                parsePlaylist(JSONObject(response.body?.string().orEmpty()))
            }
        }

    suspend fun reorderPlaylists(playlistIds: List<String>): List<CloudPlaylist> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("playlistIds", JSONArray(playlistIds))
            requestWithAuth("/api/v1/playlists/order", "PATCH", jsonBody(body)).use { response ->
                ensureSuccess(response)
                parsePlaylists(JSONArray(response.body?.string().orEmpty()))
            }
        }

    suspend fun songs(playlistId: String): List<SongEntity> = withContext(Dispatchers.IO) {
        requestWithAuth("/api/v1/playlists/${encode(playlistId)}/songs").use { response ->
            ensureSuccess(response)
            parseSongs(JSONArray(response.body?.string().orEmpty()))
        }
    }

    suspend fun allSongs(): List<SongEntity> = withContext(Dispatchers.IO) {
        requestWithAuth("/api/v1/songs").use { response ->
            ensureSuccess(response)
            parseSongs(JSONArray(response.body?.string().orEmpty()))
        }
    }

    suspend fun uploadSong(
        playlistId: String,
        audio: Uri,
        lyric: Uri?,
        cover: Uri?,
        title: String,
        durationMs: Long
    ): SongEntity = withContext(Dispatchers.IO) {
        try {
            // 优先直传 MinIO；本地存储/旧服务不支持预签名时回落到后端 multipart
            uploadSongDirect(playlistId, audio, lyric, cover, title, durationMs)
        } catch (exception: DirectUploadUnavailableException) {
            uploadSongMultipart(playlistId, audio, lyric, cover, title, durationMs)
        }
    }

    private suspend fun uploadSongDirect(
        playlistId: String,
        audio: Uri,
        lyric: Uri?,
        cover: Uri?,
        title: String,
        durationMs: Long
    ): SongEntity {
        val audioBody = UriRequestBody(audio)
        val contentType = audioBody.contentType().toString()
        val fileSize = audioBody.contentLength()
        if (fileSize <= 0) throw IOException("无法读取音频文件大小")

        val init = JSONObject()
            .put("filename", displayName(audio, "audio.mp3"))
            .put("contentType", contentType)
            .put("fileSize", fileSize)
            .put("title", title)
            .put("artist", "")
            .put("durationMs", durationMs)
        val direct = try {
            requestWithAuth(
                "/api/v1/playlists/${encode(playlistId)}/songs/direct-upload",
                "POST",
                jsonBody(init)
            ).use { response ->
                ensureSuccess(response)
                JSONObject(response.body?.string().orEmpty())
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw DirectUploadUnavailableException(exception)
        }
        val songId = direct.getString("songId")
        val audioKey = direct.getString("audioKey")
        val uploadUrl = direct.getString("uploadUrl")
        val signedContentType = direct.optString("contentType", contentType)

        // 音频字节直接写入 MinIO，不经过 Spring Boot
        try {
            client.newCall(
                Request.Builder()
                    .url(uploadUrl)
                    .put(UriRequestBody(audio))
                    .header("Content-Type", signedContentType)
                    .build()
            ).execute().use { response ->
                ensureSuccess(response)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw DirectUploadUnavailableException(exception)
        }

        val complete = JSONObject()
            .put("playlistId", playlistId)
            .put("audioKey", audioKey)
            .put("title", title)
            .put("artist", "")
            .put("mimeType", contentType)
            .put("durationMs", durationMs)
        val created = requestWithAuth(
            "/api/v1/songs/${encode(songId)}/direct-upload/complete",
            "POST",
            jsonBody(complete)
        ).use { response ->
            ensureSuccess(response)
            parseSong(JSONObject(response.body?.string().orEmpty()))
        }
        return if (lyric != null || cover != null) updateSong(created, title, lyric, cover) else created
    }

    private suspend fun uploadSongMultipart(
        playlistId: String,
        audio: Uri,
        lyric: Uri?,
        cover: Uri?,
        title: String,
        durationMs: Long
    ): SongEntity {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", displayName(audio, "audio.mp3"), UriRequestBody(audio))
            .addFormDataPart("title", title)
            .addFormDataPart("durationMs", durationMs.toString())
        lyric?.let { body.addFormDataPart("lyric", displayName(it, "lyrics.lrc"), UriRequestBody(it)) }
        cover?.let { body.addFormDataPart("cover", displayName(it, "cover.jpg"), UriRequestBody(it)) }
        return requestWithAuth(
            "/api/v1/playlists/${encode(playlistId)}/songs",
            "POST",
            body.build()
        ).use { response ->
            ensureSuccess(response)
            parseSong(JSONObject(response.body?.string().orEmpty()))
        }
    }

    suspend fun updateSong(
        song: SongEntity,
        title: String,
        lyric: Uri?,
        cover: Uri?
    ): SongEntity = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("title", title)
        lyric?.let { body.addFormDataPart("lyric", displayName(it, "lyrics.lrc"), UriRequestBody(it)) }
        cover?.let { body.addFormDataPart("cover", displayName(it, "cover.jpg"), UriRequestBody(it)) }
        requestWithAuth(
            "/api/v1/songs/${encode(cloudId(song))}",
            "PUT",
            body.build()
        ).use { response ->
            ensureSuccess(response)
            parseSong(JSONObject(response.body?.string().orEmpty()))
        }
    }

    suspend fun moveSong(song: SongEntity, targetPlaylistId: String): SongEntity =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("playlistId", targetPlaylistId)
            requestWithAuth(
                "/api/v1/songs/${encode(cloudId(song))}/move",
                "PATCH",
                jsonBody(body)
            ).use { response ->
                ensureSuccess(response)
                parseSong(JSONObject(response.body?.string().orEmpty()))
            }
        }

    suspend fun deleteSong(song: SongEntity) = withContext(Dispatchers.IO) {
        requestWithAuth("/api/v1/songs/${encode(cloudId(song))}", "DELETE").use { response ->
            ensureSuccess(response)
        }
    }

    suspend fun setFavorite(song: SongEntity, favorite: Boolean): SongEntity =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("favorite", favorite)
            requestWithAuth(
                "/api/v1/songs/${encode(cloudId(song))}/favorite",
                "PUT",
                jsonBody(body)
            ).use { response ->
                ensureSuccess(response)
                parseSong(JSONObject(response.body?.string().orEmpty()))
            }
        }

    suspend fun updateLyrics(
        song: SongEntity,
        lyricsText: String,
        speed: Float,
        offsetMs: Long
    ): SongEntity = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("lyricsText", lyricsText)
            put("lyricSpeed", speed.toDouble())
            put("lyricsOffsetMs", offsetMs)
        }
        requestWithAuth(
            "/api/v1/songs/${encode(cloudId(song))}/lyrics",
            "PUT",
            jsonBody(body)
        ).use { response ->
            ensureSuccess(response)
            parseSong(JSONObject(response.body?.string().orEmpty()))
        }
    }

    suspend fun playUrl(songId: String): String = withContext(Dispatchers.IO) {
        requestWithAuth("/api/v1/songs/${encode(songId)}/play-url").use { response ->
            ensureSuccess(response)
            absoluteUrl(JSONObject(response.body?.string().orEmpty()).getString("url"))
        }
    }

    suspend fun playUrls(songIds: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        songIds.chunked(400).flatMap { batch ->
            val body = JSONObject().put("songIds", JSONArray(batch))
            requestWithAuth("/api/v1/songs/play-urls", "POST", jsonBody(body)).use { response ->
                ensureSuccess(response)
                val array = JSONArray(response.body?.string().orEmpty())
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    item.getString("songId") to absoluteUrl(item.getString("url"))
                }
            }
        }.toMap()
    }

    suspend fun downloadSong(song: SongEntity, destination: Uri) = withContext(Dispatchers.IO) {
        val url = requestWithAuth("/api/v1/songs/${encode(cloudId(song))}/download-url").use { response ->
            ensureSuccess(response)
            absoluteUrl(JSONObject(response.body?.string().orEmpty()).getString("url"))
        }
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            ensureSuccess(response)
            val output = resolver.openOutputStream(destination) ?: throw IOException("无法打开保存位置")
            output.use { target ->
                response.body?.byteStream()?.use { source -> source.copyTo(target, 256 * 1024) }
                    ?: throw IOException("下载内容为空")
            }
        }
    }

    private fun requestWithAuth(
        path: String,
        method: String = "GET",
        body: RequestBody? = null
    ): Response {
        val session = store.session.value ?: throw ServerAuthException("请先登录服务器")
        var response = client.newCall(buildRequest(session, path, method, body)).execute()
        if (response.code == 401) {
            response.close()
            val refreshed = refreshBlocking(session.accessToken)
            response = client.newCall(buildRequest(refreshed, path, method, body)).execute()
            if (response.code == 401) {
                response.close()
                store.clear()
                throw ServerAuthException("登录已过期，请重新登录")
            }
        }
        return response
    }

    private fun buildRequest(
        session: ServerSession,
        path: String,
        method: String,
        body: RequestBody?
    ): Request = Request.Builder()
        .url(session.baseUrl + path)
        .header("Authorization", "Bearer ${session.accessToken}")
        .method(method, body)
        .build()

    private fun refreshBlocking(failedAccessToken: String): ServerSession = synchronized(refreshLock) {
        val latest = store.session.value ?: throw ServerAuthException("请先登录服务器")
        if (latest.accessToken.isNotBlank() && latest.accessToken != failedAccessToken) {
            return latest
        }
        val body = JSONObject().put("refreshToken", latest.refreshToken)
        client.newCall(
            Request.Builder()
                .url("${latest.baseUrl}/api/v1/auth/refresh")
                .post(jsonBody(body))
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                store.clear()
                throw ServerAuthException("登录已过期，请重新登录")
            }
            val json = JSONObject(response.body?.string().orEmpty())
            val session = latest.copy(
                accessToken = json.getString("accessToken"),
                refreshToken = json.getString("refreshToken"),
                accessExpiresAt = System.currentTimeMillis() + json.optLong("expiresIn", 1800) * 1000
            )
            store.save(session)
            session
        }
    }

    private fun buildError(response: Response): IOException {
        val text = response.body?.string().orEmpty()
        val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        return IOException(message ?: "请求失败：HTTP ${response.code}")
    }

    private fun ensureSuccess(response: Response) {
        if (!response.isSuccessful) {
            throw buildError(response)
        }
    }

    private fun parseAuth(baseUrl: String, json: JSONObject): ServerSession {
        val user = json.getJSONObject("user")
        return ServerSession(
            baseUrl = baseUrl,
            accessToken = json.getString("accessToken"),
            refreshToken = json.getString("refreshToken"),
            userId = user.getString("id"),
            username = user.getString("username"),
            displayName = user.optString("displayName", user.getString("username")),
            accessExpiresAt = System.currentTimeMillis() + json.optLong("expiresIn", 1800) * 1000,
            avatarObjectKey = user.optString("avatarObjectKey").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    private fun parsePlaylists(array: JSONArray): List<CloudPlaylist> =
        (0 until array.length()).map { parsePlaylist(array.getJSONObject(it)) }

    private fun parsePlaylist(json: JSONObject): CloudPlaylist = CloudPlaylist(
        directory = json.getString("id"),
        name = json.optString("name", "未命名歌单"),
        coverUrl = json.optionalString("coverUrl")?.let(::absoluteUrl),
        songCount = json.optLong("songCount").toInt(),
        updatedAt = parseInstant(json.optString("updatedAt")),
        sortOrder = json.optInt("sortOrder")
    )

    private fun parseSongs(array: JSONArray): List<SongEntity> =
        (0 until array.length()).map { parseSong(array.getJSONObject(it)) }

    private fun parseSong(json: JSONObject): SongEntity {
        val cloudId = json.getString("id")
        val coverUrl = json.optionalString("coverUrl")?.let(::absoluteUrl)
        return SongEntity(
            id = stableId(cloudId),
            title = json.optString("title", "未命名歌曲"),
            artist = json.optString("artist", "未知艺术家"),
            album = json.optString("album", "云端音乐"),
            albumId = 0,
            durationMs = json.optLong("durationMs"),
            uri = "server://$cloudId",
            mimeType = json.optString("mimeType", "audio/mpeg"),
            fileSize = json.optLong("fileSize"),
            dateAdded = parseInstant(json.optString("createdAt")),
            isFavorite = json.optBoolean("isFavorite", false),
            playCount = 0,
            lastPositionMs = 0,
            lyricUri = null,
            posterUri = coverUrl,
            isImported = true,
            lyricsText = json.optionalString("lyricsText"),
            lyricSpeed = json.optDouble("lyricSpeed", 1.0).toFloat(),
            lyricsOffsetMs = json.optLong("lyricsOffsetMs"),
            sourceType = "CLOUD",
            cloudId = cloudId,
            audioObjectKey = null,
            coverObjectKey = null,
            lyricObjectKey = null,
            objectVersion = json.optionalString("objectVersion") ?: ""
        )
    }

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun absoluteUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path
        }
        val base = store.session.value?.baseUrl ?: throw ServerAuthException("请先登录服务器")
        return base + path
    }

    private fun normalizeBaseUrl(value: String): String {
        var candidate = value.trim().trimEnd('/')
        require(candidate.isNotBlank()) { "服务器地址不能为空" }
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
            candidate = "http://$candidate"
        }
        val parsed = candidate.toHttpUrlOrNull() ?: throw IllegalArgumentException("服务器地址格式不正确")
        return parsed.newBuilder().build().toString().trimEnd('/')
    }

    private fun displayName(uri: Uri, fallback: String): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: fallback

    private fun cloudId(song: SongEntity): String =
        requireNotNull(song.cloudId) { "歌曲缺少服务器 ID" }

    private fun jsonBody(json: JSONObject): RequestBody = json.toString().toRequestBody(JSON_MEDIA_TYPE)

    private fun parseInstant(value: String?): Long = runCatching {
        if (value.isNullOrBlank()) 0L else java.time.Instant.parse(value).toEpochMilli()
    }.getOrDefault(0L)

    private fun stableId(value: String): Long {
        val hash = value.fold(1125899906842597L) { current, c -> current * 31 + c.code }
        return -(if (hash == Long.MIN_VALUE) Long.MAX_VALUE else abs(hash)).coerceAtLeast(1)
    }

    private fun encode(value: String): String = Uri.encode(value)

    private inner class UriRequestBody(private val uri: Uri) : RequestBody() {
        override fun contentType(): MediaType =
            resolver.getType(uri)?.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()

        override fun contentLength(): Long = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L

        override fun writeTo(sink: BufferedSink) {
            val input = resolver.openInputStream(uri) ?: throw IOException("无法读取文件")
            input.source().use { sink.writeAll(it) }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class ServerAuthException(message: String) : IOException(message)

private class DirectUploadUnavailableException(cause: Throwable) : IOException("直接上传不可用", cause)
