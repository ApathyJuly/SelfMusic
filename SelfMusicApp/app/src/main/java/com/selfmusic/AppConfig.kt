package com.selfmusic

/**
 * 应用级默认配置。
 *
 * 后端地址来自 gradle.properties 的 selfmusic.serverBaseUrl，
 * 构建时写入 BuildConfig；运行时设置页仍可覆盖并加密保存。
 */
object AppConfig {
    val defaultServerBaseUrl: String = BuildConfig.DEFAULT_SERVER_BASE_URL
}
