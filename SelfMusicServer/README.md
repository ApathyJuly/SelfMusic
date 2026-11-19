# SelfMusic Server

SelfMusic 的自建后端：Java 17 + Spring Boot 3.3 + Spring Security + JPA + MySQL 8 / 5.7 + Flyway + S3 兼容对象存储。

Android 只访问这个后端；音频、封面、歌词存入 S3/MinIO，由后端做鉴权并以 HTTP Range 流式转发。S3 不需要对公网开放。

## 功能

- 多用户注册、登录、刷新令牌、退出登录
- Access Token（JWT，默认 30 分钟）+ 可轮换的 Refresh Token（默认 30 天）
- 每个用户独立的歌单与歌曲库
- 歌单创建、重命名、封面上传、排序、删除
- 歌曲上传（音频 + 歌词 + 封面）、编辑、移动、删除、下载
- 收藏、歌词文本/速度/偏移保存
- 播放地址使用短时 HMAC token，支持 ExoPlayer 的 Range 请求
- S3 兼容存储（MinIO / 腾讯云 COS / AWS S3）；开发环境可用本地磁盘
- Flyway 数据库迁移、Actuator 健康检查、Docker Compose 一键部署

## 快速启动（Docker Compose）

```bash
cd SelfMusicServer
cp .env.example .env
# 编辑 .env，至少修改 MYSQL_PASSWORD、MYSQL_ROOT_PASSWORD、JWT_SECRET、MEDIA_TOKEN_SECRET、MINIO_ROOT_PASSWORD
docker compose up -d --build
```

启动后：

- API：`http://<服务器IP>:8080`
- 健康检查：`http://<服务器IP>:8080/actuator/health`
- MinIO 控制台：`http://<服务器IP>:9001`

Compose 包含：

| 服务 | 作用 |
|---|---|
| `mysql` | 用户、歌单、歌曲元数据 |
| `minio` | 音频/封面/歌词对象存储 |
| `minio-init` | 创建 Bucket |
| `server` | Spring Boot API |

## 配置与 profile

配置按 profile 叠加，后加载的同名 key 覆盖前面的：

| 文件 | 作用 |
|---|---|
| `application.yml` | 公共配置：端口、JPA、Flyway、上传限制、Actuator、CORS、JDBC 驱动与连接参数。**不含**数据库地址和任何密钥 |
| `application-dev.yml` | 开发：MySQL（读 `.env.local`）+ 本地磁盘存储 + 开发占位密钥 |
| `application-prod.yml` | 生产：数据库、对象存储、密钥全部来自环境变量，**没有**开发兜底值 |

- 不指定 profile 时默认走 **dev**（`spring.profiles.default: dev`）
- 生产显式激活 **prod**：`SPRING_PROFILES_ACTIVE=prod`，`Dockerfile` 与 `docker-compose.yml` 已配置
- 生产 profile 下 `DB_URL`、`DB_USER`、`DB_PASSWORD`、`JWT_SECRET`、`MEDIA_TOKEN_SECRET`、`S3_*` 缺失会直接启动失败，不会再静默回落到开发值

## 本地开发（不用 Docker）

```bash
cd SelfMusicServer
cp .env.local.example .env.local
# 编辑 .env.local，填入 MySQL 地址、用户名和密码
mvn spring-boot:run
```

默认即 dev profile，**不需要**再传 `-Dspring-boot.run.profiles=dev`。

- MySQL：由 `.env.local` 的 `DB_URL` / `DB_USER` / `DB_PASSWORD` 指定；文件不存在时回落到 `jdbc:mysql://localhost:3306/selfmusic`（用户 `selfmusic`、空密码）
- 上传文件：`./data/uploads`
- 端口：`8080`

`.env.local` 是相对**工作目录**读取的，在 IDE 里必须把 working directory 设成 `SelfMusicServer` 这一层，否则读不到且不会报错。

`.env.local` 已被 `.gitignore` 忽略，不要把真实密码提交到仓库。也可以直接使用环境变量
`DB_URL`、`DB_USER`、`DB_PASSWORD`，环境变量优先级更高。

运行测试（H2 的 MySQL 兼容模式，不需要本地 MySQL）：

```bash
mvn test
```

> JDBC URL 建议只写到数据库名，例如 `jdbc:mysql://host:3306/selfmusic`。不要在 URL 后追加
> `?useUnicode=true&...` 参数；编码、时区和公钥读取等选项已在 `application.yml` 的
> `spring.datasource.hikari.data-source-properties` 中配置，避免 Flyway 把查询串误当作数据库名。

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP 端口 |
| `SPRING_PROFILES_ACTIVE` | `dev` | 激活的 profile；生产固定 `prod` |
| `DB_URL` | `jdbc:mysql://localhost:3306/selfmusic` | JDBC 地址（不要带查询参数） |
| `DB_USER` / `DB_PASSWORD` | `selfmusic` | 数据库账号 |
| `DB_DRIVER` | `com.mysql.cj.jdbc.Driver` | JDBC 驱动 |
| `MYSQL_DATABASE` | `selfmusic` | Docker Compose 数据库名 |
| `MYSQL_USER` / `MYSQL_PASSWORD` | `selfmusic` | Docker Compose 应用账号 |
| `MYSQL_ROOT_PASSWORD` | 无 | Docker Compose root 密码，必填 |
| `JWT_SECRET` | 开发占位值 | JWT HMAC 密钥，至少 32 字节 |
| `JWT_ACCESS_TTL` | `PT30M` | Access Token 有效期 |
| `JWT_REFRESH_TTL` | `P30D` | Refresh Token 有效期 |
| `MEDIA_TOKEN_SECRET` | 开发占位值 | 媒体 URL 签名密钥，至少 32 字节 |
| `MEDIA_TOKEN_TTL` | `PT6H` | 播放地址有效期 |
| `MEDIA_COVER_TTL` | `P7D` | 封面地址有效期 |
| `STORAGE_TYPE` | `s3` | `s3` 或 `local` |
| `S3_ENDPOINT` | `http://localhost:9000` | MinIO/COS/S3 地址 |
| `S3_REGION` | `us-east-1` | 区域；COS 填 `ap-guangzhou` 等 |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | `minioadmin` | 对象存储密钥 |
| `S3_BUCKET` | `selfmusic` | Bucket |
| `S3_PATH_STYLE` | `true` | MinIO 需要 `true`；AWS S3 通常 `false` |
| `S3_CREATE_BUCKET` | `true` | 启动时尝试创建 Bucket |
| `MAX_FILE_SIZE` | `2GB` | 单文件上限 |

> 生产环境必须替换 `JWT_SECRET`、`MEDIA_TOKEN_SECRET`、数据库密码和 MinIO 密码（`application-prod.yml` 中这些变量没有默认值，缺失会启动失败）。不要把 `.env` 提交到 Git。

## API

所有业务接口需要：

```http
Authorization: Bearer <accessToken>
```

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/register` | 注册并返回 Token |
| POST | `/api/v1/auth/login` | 登录 |
| POST | `/api/v1/auth/refresh` | 用 Refresh Token 换新 Token |
| POST | `/api/v1/auth/logout` | 撤销 Refresh Token |
| GET | `/api/v1/auth/me` | 当前用户 |
| GET | `/api/v1/playlists` | 歌单列表（含歌曲数、封面） |
| POST | `/api/v1/playlists` | 创建歌单，multipart：`name`、可选 `cover` |
| PUT | `/api/v1/playlists/{id}` | 修改歌单，multipart：`name`、可选 `cover` |
| PATCH | `/api/v1/playlists/order` | 排序：`{"playlistIds":["..."]}` |
| DELETE | `/api/v1/playlists/{id}` | 删除歌单及其歌曲/对象 |
| GET | `/api/v1/playlists/{id}/songs` | 歌单内歌曲 |
| POST | `/api/v1/playlists/{id}/songs` | 上传歌曲，multipart：`audio`、可选 `lyric`/`cover`、`title`、`artist`、`durationMs` |
| GET | `/api/v1/songs?playlistId=` | 歌曲列表（不传 playlistId 为全部） |
| PUT | `/api/v1/songs/{id}` | 编辑歌曲，multipart：`title`、可选 `lyric`/`cover` |
| PATCH | `/api/v1/songs/{id}/move` | 移动：`{"playlistId":"..."}` |
| PUT | `/api/v1/songs/{id}/favorite` | 收藏：`{"favorite":true}` |
| PUT | `/api/v1/songs/{id}/lyrics` | 歌词：`{"lyricsText":"...","lyricSpeed":1.0,"lyricsOffsetMs":0}` |
| DELETE | `/api/v1/songs/{id}` | 删除歌曲及对象 |
| GET | `/api/v1/songs/{id}/play-url` | 获取播放地址 |
| GET | `/api/v1/songs/{id}/download-url` | 获取下载地址 |
| GET | `/api/v1/media/songs/{id}?token=` | 媒体流（支持 `Range`） |
| GET | `/api/v1/media/songs/{id}/cover?token=` | 歌曲封面 |
| GET | `/api/v1/media/playlists/{id}/cover?token=` | 歌单封面 |

`play-url` 返回的是后端相对路径，Android 会自动拼上服务器地址。媒体接口用短时 token 鉴权，所以 ExoPlayer 不需要额外 Header。

### 示例

```bash
# 注册
curl -s -X POST http://127.0.0.1:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"password123","displayName":"Demo"}'

# 创建歌单
curl -s -X POST http://127.0.0.1:8080/api/v1/playlists \
  -H "Authorization: Bearer $TOKEN" \
  -F "name=我的歌单"

# 上传歌曲
curl -s -X POST http://127.0.0.1:8080/api/v1/playlists/$PLAYLIST_ID/songs \
  -H "Authorization: Bearer $TOKEN" \
  -F "audio=@song.mp3;type=audio/mpeg" \
  -F "lyric=@song.lrc;type=text/plain" \
  -F "cover=@cover.jpg;type=image/jpeg" \
  -F "title=夜曲" \
  -F "durationMs=240000"
```

## 对象存储布局

```text
users/{userId}/
  songs/{songId}/
    audio.mp3
    cover.jpg
    lyrics.lrc
  playlists/{playlistId}/
    cover.jpg
```

数据库中只保存 ObjectKey 和元数据，不保存永久 URL。删除歌曲/歌单时会同步删除对象；删除失败只记录日志，不回滚业务数据。

## 数据库

Flyway 迁移位于 `src/main/resources/db/migration`。

核心表：

- `users`：用户名、BCrypt 密码哈希、显示名
- `refresh_tokens`：SHA-256 哈希、过期时间、撤销状态
- `playlists`：用户歌单、封面对象、排序
- `songs`：用户、歌单、标题、歌手、时长、对象 Key、歌词、收藏、版本

## 用腾讯云 COS 代替 MinIO

把 Compose 中的 `server` 环境变量改为：

```text
STORAGE_TYPE=s3
S3_ENDPOINT=https://cos.ap-guangzhou.myqcloud.com
S3_REGION=ap-guangzhou
S3_ACCESS_KEY=<CAM SecretId>
S3_SECRET_KEY=<CAM SecretKey>
S3_BUCKET=<bucket-name-with-appid>
S3_PATH_STYLE=false
S3_CREATE_BUCKET=false
```

Bucket 建议保持私有读写。后端会通过 S3 SDK 读写对象，Android 不直接接触 COS 密钥。

## 生产建议

> 从零开始的新手部署流程（Docker 安装、Nginx、HTTPS、备份、排错）见 **[DEPLOY.md](DEPLOY.md)**。

- 使用 Nginx/Caddy 反向代理并启用 HTTPS；Android 端地址由 `gradle.properties` 的 `selfmusic.serverBaseUrl.release` 配置。
- 将 MySQL 数据目录和 MinIO 数据目录纳入备份。
- 限制服务器防火墙，只暴露 443/80；9000/9001 仅内网访问。
- 定期轮换 `JWT_SECRET` 会使用户重新登录；`MEDIA_TOKEN_SECRET` 轮换会使旧播放链接失效，影响较小。
- 单文件上限默认 2GB；如要调整，同时修改 `MAX_FILE_SIZE` 和反向代理的 `client_max_body_size`。
