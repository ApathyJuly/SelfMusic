package com.selfmusic

/**
 * 应用级默认配置。
 *
 * 后端地址来自 gradle.properties 的 selfmusic.serverBaseUrl.debug / .release，
 * 按 buildType 写入 BuildConfig，App 界面不再填写服务器地址。
 */
object AppConfig {
    val defaultServerBaseUrl: String = BuildConfig.DEFAULT_SERVER_BASE_URL
}
