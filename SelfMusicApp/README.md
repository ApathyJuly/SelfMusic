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

默认地址统一放在 `gradle.properties`：

```properties
selfmusic.serverBaseUrl=http://10.0.2.2:8080
```

- **模拟器**：`http://10.0.2.2:8080`（`10.0.2.2` 映射到宿主机 `localhost`）
- **真机同局域网**：改成 `http://<电脑局域网IP>:8080`
- **临时覆盖构建默认值**：

```powershell
.\gradlew.bat :app:assembleDebug -Pselfmusic.serverBaseUrl=http://192.168.1.20:8080
```

修改后重新构建即可；该值会被写入 `BuildConfig.DEFAULT_SERVER_BASE_URL`。

运行时仍可在 App 内覆盖并保存到加密会话，优先级为：

```text
已保存的服务器会话  >  gradle.properties 的默认地址
```

如果 App 已经登录过旧地址，需要退出登录或在设置页直接修改地址。

## 连接后端

打开 **设置 → 服务器账号**：

```text
服务器地址：http://<服务器IP>:8080
用户名 / 密码：注册或登录
```

登录后即可在 **云端音乐** 中创建歌单、上传歌曲、播放和下载。

- 模拟器访问宿主机后端：`http://10.0.2.2:8080`
- 真机访问局域网后端：`http://<电脑局域网IP>:8080`
- 公网部署建议使用 HTTPS

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
