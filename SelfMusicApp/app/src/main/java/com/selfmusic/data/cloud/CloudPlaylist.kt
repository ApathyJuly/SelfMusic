package com.selfmusic.data.cloud

data class CloudPlaylist(
    val directory: String,
    val name: String,
    val coverUrl: String?,
    val songCount: Int,
    val updatedAt: Long,
    val sortOrder: Int
)
