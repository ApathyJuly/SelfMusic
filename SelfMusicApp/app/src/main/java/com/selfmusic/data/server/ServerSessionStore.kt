package com.selfmusic.data.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 服务器会话只保存在本机：Access/Refresh Token 使用 Android Keystore 中的 AES-GCM 密钥加密。
 * 不写入 Room、日志或云备份（应用已关闭 allowBackup）。
 */
class ServerSessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(load())
    val session: StateFlow<ServerSession?> = _session

    @Synchronized
    fun save(session: ServerSession) {
        val json = JSONObject().apply {
            put("baseUrl", session.baseUrl)
            put("accessToken", session.accessToken)
            put("refreshToken", session.refreshToken)
            put("userId", session.userId)
            put("username", session.username)
            put("displayName", session.displayName)
            put("accessExpiresAt", session.accessExpiresAt)
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        prefs.edit()
            .putString("payload", Base64.encodeToString(cipher.doFinal(json.toString().toByteArray()), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        _session.value = session
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        _session.value = null
    }

    private fun load(): ServerSession? = runCatching {
        val payload = prefs.getString("payload", null) ?: return null
        val iv = prefs.getString("iv", null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        val json = JSONObject(String(cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP))))
        ServerSession(
            baseUrl = json.getString("baseUrl"),
            accessToken = json.getString("accessToken"),
            refreshToken = json.getString("refreshToken"),
            userId = json.optString("userId"),
            username = json.optString("username"),
            displayName = json.optString("displayName"),
            accessExpiresAt = json.optLong("accessExpiresAt")
        )
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "selfmusic_server_session"
        const val KEY_ALIAS = "selfmusic_server_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
