# SelfMusic

SelfMusic 是一个自托管的 Android 音乐播放器：本地曲库 + 自己的服务器云端音乐库。

- **Android**：Kotlin + Jetpack Compose + Room + DataStore + Media3/ExoPlayer
- **后端**：Java 17 + Spring Boot 3.3 + Spring Security JWT + JPA + Flyway
- **数据库**：MySQL 8 / 5.7
- **文件存储**：S3 兼容对象存储（MinIO / 腾讯云 COS / AWS S3）
- **部署**：Docker Compose 一键启动

## 架构

```text
Android App
  ├─ 本地音乐：Room + MediaStore + ExoPlayer
  └─ 云端音乐：ServerApi（JWT + 自动刷新）
          │
          ▼
Spring Boot Server
  ├─ 认证：注册 / 登录 / Refresh Token 轮换
  ├─ 歌单与歌曲：CRUD / 上传 / 移动 / 收藏 / 歌词
  ├─ 媒体：短时 HMAC token + HTTP Range 流式转发
  ├─ MySQL：用户、歌单、歌曲元数据（Flyway 迁移）
  └─ S3 StorageService：MinIO / COS / S3
```

S3 保持私有，Android 不持有对象存储密钥；所有云端请求只访问后端。

## 目录

```text
SelfMusic/
├─ SelfMusicApp/                # Android 工程（独立 Gradle 项目）
│  ├─ app/
│  │  └─ src/main/java/com/selfmusic/
│  │     ├─ MainActivity.kt    # Compose UI + MusicViewModel
│  │     ├─ data/
│  │     │  ├─ local/          # Room、DataStore
│  │     │  ├─ media/          # MediaStore 扫描
│  │     │  ├─ server/         # ServerApi + 加密会话
│  │     │  └─ cloud/          # CloudPlaylist 模型
│  │     └─ playback/          # MediaSessionService、5GB LRU 缓存
│  ├─ gradle/ + gradlew*       # Gradle Wrapper
│  ├─ settings.gradle.kts / build.gradle.kts
│  ├─ .android-sdk/            # 本地 Android SDK
│  ├─ .android-avd/            # 模拟器 AVD
│  ├─ design/                  # 图标与动画素材
│  ├─ tools/                   # 素材处理脚本
│  ├─ docs/                    # 架构文档
│  └─ SelfMusic.bat / .ps1     # 一键启动/停止模拟器与 App
└─ SelfMusicServer/             # Spring Boot 后端（独立 Maven 项目）
   ├─ src/main/java/            # 认证、歌单/歌曲、媒体流、S3 存储
   ├─ src/main/resources/       # application.yml、Flyway
   ├─ docker-compose.yml        # Postgres + MinIO + Server
   ├─ README.md                 # 部署与 API 文档
   └─ legacy/cos-scf/           # 旧版 COS 签名服务（保留，不再被 App 使用）
```

## 启动后端

```bash
cd SelfMusicServer
cp .env.example .env
# 修改 .env 中的数据库密码、JWT_SECRET、MEDIA_TOKEN_SECRET、MinIO 密码
docker compose up -d --build
```

- API：`http://<服务器IP>:8080`
- 健康检查：`http://<服务器IP>:8080/actuator/health`
- MinIO 控制台：`http://<服务器IP>:9001`

完整说明见 [SelfMusicServer/README.md](../../SelfMusicServer/README.md)。

本地开发（H2 + 本地磁盘，不需要 Docker）：

```bash
cd SelfMusicServer
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

## 构建 Android

```bash
cd SelfMusicApp
./gradlew :app:assembleDebug
```

Windows：

```powershell
cd SelfMusicApp
.\gradlew.bat :app:assembleDebug
```

安装后打开 **设置 → 服务器账号**。服务器地址不需要手填：

- `debug` 构建使用 `gradle.properties` 的 `selfmusic.serverBaseUrl.debug`
- `release` 构建使用 `selfmusic.serverBaseUrl.release`

```text
用户名 / 密码：登录，或切换到“注册”创建账号
```

登录后即可在 **云端音乐** 中创建歌单、上传歌曲、播放和下载。

> 生产环境使用 HTTPS；本地开发如果没有 HTTPS 反向代理，请把 debug 地址改为 `http://...`。Android 已允许明文 HTTP 以支持局域网自托管。

## 安全说明

- 密码使用 BCrypt 哈希；Access Token 默认 30 分钟，Refresh Token 默认 30 天且每次刷新轮换。
- Android 的 Access/Refresh Token 使用 Android Keystore AES-GCM 加密保存，`allowBackup=false`。
- 媒体播放地址使用短时 HMAC token，不把 JWT 放在媒体 URL 中。
- S3/MinIO 保持私有；Android 不保存对象存储密钥。
- 生产环境务必修改 `.env` 中所有默认密码和 secret，并限制 9000/9001 端口只对内网开放。

## 旧版 COS 代码

`SelfMusicServer/legacy/cos-scf` 属于旧的 COS 签名方案，当前 Android 已不再使用；它被放在 server 目录内仅作历史参考。新部署请使用 `SelfMusicServer`。
