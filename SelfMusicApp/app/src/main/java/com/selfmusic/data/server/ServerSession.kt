package com.selfmusic.data.server

data class ServerSession(
    val baseUrl: String,
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val username: String,
    val displayName: String,
    val accessExpiresAt: Long
) {
    val isLoggedIn: Boolean
        get() = baseUrl.isNotBlank() && accessToken.isNotBlank() && refreshToken.isNotBlank()

    fun withTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long): ServerSession = copy(
        accessToken = accessToken,
        refreshToken = refreshToken,
        accessExpiresAt = System.currentTimeMillis() + expiresInSeconds * 1000
    )
}
