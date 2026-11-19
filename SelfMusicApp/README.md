# SelfMusicApp

SelfMusic 的 Android 客户端，独立 Gradle 工程。

## 构建

```bash
cd SelfMusicApp
./gradlew :app:assembleDebug
```

Windows：

```powershell
cd SelfMusicApp
.\gradlew.bat :app:assembleDebug
```

APK 输出：

```text
SelfMusicApp/app/build/outputs/apk/debug/app-debug.apk
```

也可以直接运行本目录的 `SelfMusic.bat`，它会自动启动模拟器、构建并安装 App。

## 后端地址配置

后端地址固定写在 `gradle.properties`，App 界面不再填写服务器地址：

```properties
# release 构建：生产后端
selfmusic.serverBaseUrl.release=https://miwifi-ra80v2-srv.taild2defd.ts.net/SelfMUsicServer

# debug 构建：本地开发后端
selfmusic.serverBaseUrl.debug=https://127.0.0.1:8080
```

构建时会按 buildType 写入 `BuildConfig.DEFAULT_SERVER_BASE_URL`：

| 构建类型 | 使用地址 |
|---|---|
| `debug` | `selfmusic.serverBaseUrl.debug` |
| `release` | `selfmusic.serverBaseUrl.release` |

临时覆盖：

```powershell
# 本地调试
.\gradlew.bat :app:assembleDebug -Pselfmusic.serverBaseUrl.debug=http://10.0.2.2:8080

# 生产包
.\gradlew.bat :app:assembleRelease -Pselfmusic.serverBaseUrl.release=https://your-domain/SelfMUsicServer
```

本地开发注意：

- Android 模拟器里的 `127.0.0.1` 是模拟器自己，不是电脑。要访问电脑本地 8080：
  - 用 `adb reverse tcp:8080 tcp:8080`，然后 `https://127.0.0.1:8080` 才指向电脑；
  - 或把 `selfmusic.serverBaseUrl.debug` 改成 `http://10.0.2.2:8080`。
- 本地 Spring Boot 默认是 **HTTP**，不是 HTTPS；如果本地没有 HTTPS 反向代理，请把 debug 地址改成 `http://...`，否则会 SSL 握手失败。

## 连接后端

App 启动时会先显示登录门禁，**未登录无法进入音乐库**。服务器地址会自动使用上面的配置：

```text
登录：输入用户名 / 密码
注册：切换到“注册”，填写用户名、密码、确认密码和显示名称
```

注册成功后会自动登录。用户名规则：3-32 位字母、数字或下划线；密码 8-72 位。

登录后即可在 **云端音乐** 中创建歌单、上传歌曲、播放和下载。

## 工程结构

```text
SelfMusicApp/
├─ app/src/main/java/com/selfmusic/
│  ├─ MainActivity.kt          # Compose UI + MusicViewModel
│  ├─ data/
│  │  ├─ local/                # Room、DataStore
│  │  ├─ media/                # MediaStore 扫描
│  │  ├─ server/               # ServerApi + 加密会话
│  │  └─ cloud/                # CloudPlaylist 模型
│  └─ playback/                # MediaSessionService、5GB LRU 缓存
├─ gradle/ + gradlew*
├─ settings.gradle.kts
├─ build.gradle.kts
├─ .android-sdk/               # 本地 Android SDK
├─ .android-avd/               # 模拟器 AVD
├─ design/                     # 图标与动画素材
├─ tools/                      # 素材处理脚本
├─ docs/                       # 架构与项目总览
├─ SelfMusic.bat / .ps1        # 模拟器与 App 启停
└─ local.properties            # sdk.dir 指向本目录 .android-sdk
```

后端说明见 [../SelfMusicServer/README.md](../SelfMusicServer/README.md)，整体架构见 [docs/architecture.md](docs/architecture.md)，项目总览见 [docs/OVERVIEW.md](docs/OVERVIEW.md)。
