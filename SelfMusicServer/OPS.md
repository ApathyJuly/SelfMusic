# SelfMusic 运维手册：更新、升级与回滚

> 面向「部署已经跑起来」之后的所有日常操作。
> 首次部署看 [DEPLOY.md](DEPLOY.md)（Debian/Ubuntu）或 [deploy/DEPLOY-centos7.md](deploy/DEPLOY-centos7.md)（CentOS 7）。

---

## 0. 当前部署现状（速查）

| 项目 | 值 | 备注 |
|---|---|---|
| 服务器 | CentOS 7.9，`192.168.31.69`，用户 `dell` | Docker 26.1.4 |
| 服务端目录 | `/opt/SelfMusicServer` | 建议后续改成 git 仓库（第 1 节） |
| 容器 | `selfmusicserver-mysql-1` / `-minio-1` / `-server-1` | `minio-init` 跑完即退出，正常 |
| Compose 项目名 | `selfmusicserver`（已固定在 compose 里） | 数据卷名 `selfmusicserver_mysql_data`、`selfmusicserver_miniodata` |
| 后端监听 | `127.0.0.1:8080` | 不对外，所有外部访问都经过反代 |
| MySQL | 容器内 3306，宿主映射 `127.0.0.1:13306` | 宿主 3306 已被你自己的 MySQL 占用 |
| MinIO | `127.0.0.1:9000`，控制台 `127.0.0.1:9001` | 不对外 |
| 公网入口 | `https://miwifi-ra80v2-srv.taild2defd.ts.net/` | Tailscale Funnel → 8080，证书自动续期 |
| 密钥 | `/opt/SelfMusicServer/.env`（600） | 另存一份 `~/passwords.txt` |
| Android 服务端地址 | `SelfMusicApp/gradle.properties` 的 `selfmusic.serverBaseUrl.release` | 改完要重新打包 |

**三条铁律：**

1. **永远不要** `docker compose down -v`、`docker volume rm`、`docker system prune --volumes` —— 那是删数据库和所有歌曲。
2. **`.env` 永远不进 git**。
3. **升级前先备份**：`./deploy.sh backup`。

---

## 1. 第一次：把项目接入 Git

代码原来是用 scp 传的，没有版本历史，`deploy.sh update` 里那步 `git pull` 也一直跳过。接上 git 后，升级从「重传整个目录」变成「一条命令」。

### 1.1 本地建仓库并检查不该提交的文件

在**你的 Windows 电脑**上：

```powershell
cd G:\AI_Project\SelfMusic
git init
git add .
git status
```

**重点看 `git status` 的输出**，以下这些**绝对不能**出现在待提交列表里：

| 不该出现 | 为什么 |
|---|---|
| `SelfMusicApp/.android-sdk/`（15000+ 文件、几 GB） | 已在 `SelfMusicApp/.gitignore` 里排除，确认生效 |
| `SelfMusicServer/.env` | 真实密码 |
| `SelfMusicServer/target/`、`data/`、`backups/` | 构建产物和备份 |
| `SelfMusicApp/build/`、`*.jks`、`keystore.properties` | 构建产物和签名密钥 |

如果 `.android-sdk` 出现了，说明根目录的 ignore 规则没生效，执行：

```powershell
git rm -r --cached SelfMusicApp/.android-sdk
```

确认干净后提交：

```powershell
git commit -m "chore: 初始提交（服务端 + Android 客户端）"
```

### 1.2 在 Gitee 建私有仓库并推送

1. <https://gitee.com> → 新建仓库 → **私有** → **不要**勾选「使用 Readme 初始化」
2. 推送：

```powershell
git remote add origin https://gitee.com/你的账号/selfmusic.git
git branch -M main
git push -u origin main
```

> 为什么用 Gitee 不用 GitHub：你的服务器在国内，GitHub 拉取经常超时（这台机器的 DNS 已经被污染过一轮）。Gitee 快得多。

### 1.3 服务器改成从 git 拉取（★ 这一步有数据丢失风险，务必照做）

现在 `/opt/SelfMusicServer` 是个普通目录，里面有两个**不在 git 里**的关键东西：`.env`（密码）和正在用的数据卷。

```bash
cd /opt

# 1. 先确认当前是好的状态，并备份 .env
docker compose -f SelfMusicServer/docker-compose.yml ps
cp SelfMusicServer/.env /root/selfmusic.env.$(date +%F)

# 2. 记录当前数据卷（后面用来核对）
docker volume ls | grep selfmusicserver

# 3. 把旧目录改名（不要删！）
mv SelfMusicServer SelfMusicServer.old

# 4. clone 新目录，名字必须还是 SelfMusicServer
git clone git@gitee.com:你的账号/selfmusic.git SelfMusicServer

# 5. 把 .env 放回去
cp /root/selfmusic.env.$(date +%F) SelfMusicServer/.env
chmod 600 SelfMusicServer/.env
ls -l SelfMusicServer/.env

# 6. 启动（镜像已有，不用重新编译）
cd SelfMusicServer
docker compose up -d
docker compose ps

# 7. 核对数据卷没变
docker volume ls | grep selfmusicserver

# 8. 验证数据还在（之前注册的 demo 用户）
docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "select username from users;"'
curl -s 127.0.0.1:8080/actuator/health
```

**为什么不会丢数据**：compose 里已经固定了 `name: selfmusicserver`，数据卷名是 `selfmusicserver_mysql_data`，跟目录名无关，所以换目录、重新 clone 都还是同一份卷。

确认无误后再删旧目录：

```bash
rm -rf /opt/SelfMusicServer.old
```

> ⚠️ 如果第 7 步发现卷名变了（出现新的 `xxx_mysql_data`），说明项目名没固定住 —— **立刻停下**，把旧目录恢复回来，不要启动新容器，否则会往新卷里写数据。

### 1.4 配部署密钥（免密 pull）

否则每次 `git pull` 都要输密码，`deploy.sh update` 会卡住。

```bash
# 在服务器上，以 dell 用户
ssh-keygen -t ed25519 -C "selfmusic-server" -f ~/.ssh/id_ed25519 -N ""
cat ~/.ssh/id_ed25519.pub
```

把公钥贴到 Gitee：仓库 → **管理** → **部署公钥管理** → 添加部署公钥（只读即可）。

验证：

```bash
ssh -T git@gitee.com     # 看到 "Hi ... You've successfully authenticated" 就成了
cd /opt/SelfMusicServer && git pull
```

---

## 2. 服务端日常更新流程

### 2.1 先认清：你的改动属于哪一类

| 改了什么 | 需要做什么 | 大概耗时 |
|---|---|---|
| 只改 `.env`（密码、参数） | `docker compose up -d` | 10 秒 |
| 只改 `docker-compose.yml` | `docker compose up -d` | 30 秒 |
| 改 Java 代码（`src/main/java`） | `docker compose up -d --build` | 3～10 分钟（有缓存） |
| 改 `pom.xml`（加依赖） | `docker compose up -d --build` | 10～35 分钟（要重下依赖） |
| 新增 Flyway 迁移 `V2__xxx.sql` | 同「改 Java 代码」，启动时自动执行 | 同上 |
| 改前端/Android | 不在服务器重建，见第 5 节 | — |

### 2.2 标准升级五步

```bash
cd /opt/SelfMusicServer

# ① 备份（每次都要，别省）
./deploy.sh backup

# ② 看将要拉下来的改动，确认没有意外
git fetch
git log --oneline HEAD..origin/main

# ③ 拉取（--ff-only 避免产生 merge 提交，冲突会直接报错而不是静默合并）
git pull --ff-only

# ④ 重建并启动（后台跑，防掉线）
nohup docker compose up -d --build > /tmp/build.log 2>&1 &
tail -f /tmp/build.log

# ⑤ 验证
docker compose ps
curl -s 127.0.0.1:8080/actuator/health
docker compose logs --tail=50 server
```

**成功的标志**：`docker compose ps` 三个服务 `Up`、`curl` 返回 `{"status":"UP"}`、日志里出现 `Started SelfMusicApplication`。

### 2.3 关于构建耗时和缓存

第一次构建花了 32 分钟（`mvn dependency:go-offline` 下依赖）。之后的构建会复用 Docker 层缓存：

- 只改 `src/` → `COPY src` 之后的层失效，但 **依赖层命中缓存** → 1～3 分钟
- 改 `pom.xml` → 依赖层失效，重新下载 → 10～30 分钟

如果嫌慢，加阿里云 Maven 镜像（一次性配置）：

```bash
cd /opt/SelfMusicServer

cat > settings.xml <<'EOF'
<settings>
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <mirrorOf>central</mirrorOf>
      <url>https://maven.aliyun.com/repository/central</url>
    </mirror>
  </mirrors>
</settings>
EOF

# 在 Dockerfile 的 "COPY src ./src" 之前插入一行
sed -i '/^COPY src \.\/src$/i COPY settings.xml /root/.m2/settings.xml' Dockerfile
grep -n 'settings.xml' Dockerfile     # 确认插入成功

docker compose up -d --build
```

> `settings.xml` 已在 `.gitignore` 里，不会入库。

---

## 3. 数据库迁移（Flyway）规则

迁移文件放 `src/main/resources/db/migration/`，命名 `V{N}__{描述}.sql`，**启动时自动执行，执行过的不会再跑**。

### 3.1 必须遵守的兼容性原则

Flyway 社区版**没有自动回滚**。所以对线上库做破坏性变更时，要拆成多个版本逐步来：

| 想做的变更 | 错误做法 | 正确做法 |
|---|---|---|
| 删一个字段 | 直接 `ALTER TABLE ... DROP COLUMN` | V2 停用代码读写 → V3 再 drop |
| 改字段名 | 直接 `RENAME COLUMN` | V2 加新列 → V3 双写 → V4 停用旧列 → V5 删旧列 |
| 改字段类型 | 直接 `MODIFY` | 加新列 → 迁移数据 → 切换 → 删旧列 |
| 加非空字段 | `ADD COLUMN ... NOT NULL`（旧行会失败） | 先加可空列 → 回填数据 → 再加 NOT NULL |

**自己一个人用、能接受短暂停机的场景**可以简化：停机 → 备份 → 改代码 + 改库 → 启动。但**备份不能省**。

### 3.2 为什么回滚必须连数据库一起回

`application.yml` 里配的是：

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
```

`validate` 的意思是**启动时校验实体和表结构是否一致，不一致直接启动失败**。

所以：你把代码回滚到上一版，但数据库还停在新结构上 → 旧代码的实体对不上 → **后端起不来**。处理办法见下一节 4.2。

---

## 4. 回滚预案

### 4.1 只回滚代码（数据库结构没变）

适用于：只改了业务逻辑、bug 修复，没加迁移文件。

```bash
cd /opt/SelfMusicServer

git log --oneline -5          # 找到要回的那个提交
git checkout <commit-hash>    # 或 git checkout v1.2.0（tag）

docker compose up -d --build
curl -s 127.0.0.1:8080/actuator/health
```

回滚后处于 detached HEAD 状态，想回到主线：

```bash
git checkout main && git pull --ff-only
```

**更推荐的做法**：不用 `checkout`，而是用 `git revert` 生成一个「反向提交」再推上去，这样服务器始终在 main 上：

```bash
# 本地
git revert <bad-commit>
git push
```

### 4.2 代码 + 数据库一起回滚（结构变了）

这是最容易出事的情况。**顺序不能错**：

```bash
cd /opt/SelfMusicServer

# 1. 停服务（保留数据卷）
docker compose down

# 2. 代码回滚
git checkout <old-commit>

# 3. 先只起数据库
docker compose up -d mysql
docker compose ps mysql          # 等 healthy

# 4. 恢复备份（mysqldump 默认带 DROP TABLE IF EXISTS，会覆盖同名表）
ls -lh backups/
docker compose exec -T mysql \
  sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
  < backups/mysql-YYYYMMDD-HHMMSS.sql

# 5. 起全部服务
docker compose up -d

# 6. 验证
curl -s 127.0.0.1:8080/actuator/health
docker compose logs --tail=50 server
```

**MinIO 的对象不用回滚** —— 对象只增不删（删歌才删对象），回滚数据库后可能出现「库里有记录、对象已删」的不一致，重新上传那首歌即可。

### 4.3 用 tag 标记稳定版本

每次上线并验证通过后打个 tag，回滚时非常省事：

```bash
# 本地
git tag -a v1.0.0 -m "上线版本：多用户 + 歌单 + 上传"
git push --tags

# 服务器回滚到某个 tag
git checkout v1.0.0
docker compose up -d --build
```

### 4.4 回滚后的必查项

```bash
docker compose ps                          # 全部 Up / healthy
curl -s 127.0.0.1:8080/actuator/health     # UP
docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "select count(*) from songs;"'
docker logs --tail=100 selfmusicserver-server-1 | grep -i error
```

再用手机 App 实际登录、播放一首 —— 数据库回滚后 token 可能失效，重新登录即可。

---

## 5. Android App 更新方案

### 5.1 版本号（每次发版必改）

`SelfMusicApp/app/build.gradle.kts`：

```kotlin
versionCode = 1          // 每次发版 +1，必须严格递增，否则覆盖安装会失败
versionName = "0.1.0"    // 给人看的，语义化：主版本.次版本.修订号
```

规则：

| 改动类型 | versionCode | versionName |
|---|---|---|
| 修 bug | +1 | 0.1.1 |
| 加功能（向下兼容） | +1 | 0.2.0 |
| 接口不兼容升级 | +1 | 1.0.0 |

> 手机上的 Android 系统只认 `versionCode`：新包的 code 必须 ≥ 已装的，否则报「应用未安装」。

### 5.2 签名配置（只做一次，但极其重要）

`assembleRelease` 默认产出**未签名** APK，装不上。必须配一个签名密钥。

**① 生成 keystore（一次，之后永远用同一个）**

```powershell
keytool -genkeypair -v -keystore C:\keys\selfmusic.jks `
  -alias selfmusic -keyalg RSA -keysize 2048 -validity 10000
```

记下你设的密码。

**② 在项目里填配置**

复制 `SelfMusicApp/keystore.properties.example` 为 `SelfMusicApp/keystore.properties`：

```properties
storeFile=C:/keys/selfmusic.jks
storePassword=你的keystore密码
keyAlias=selfmusic
keyPassword=你的key密码
```

`keystore.properties` 和 `*.jks` 都已在 `.gitignore` 里，不会入库。

> ⚠️ **keystore 丢了会怎样**：以后所有新版本都无法覆盖安装，只能卸载重装，用户本地数据（登录态、缓存歌曲）全部丢失。所以：
> - 备份到两个地方（电脑 + 网盘/密码管理器）
> - **同时记下 alias 和两个密码**，光有 jks 文件没用

### 5.3 构建

```powershell
cd G:\AI_Project\SelfMusic\SelfMusicApp
.\gradlew clean assembleRelease
```

产物：`app\build\outputs\apk\release\app-release.apk`

装到手机：

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

或直接把 apk 传到手机点安装（首次需允许「安装未知来源应用」）。

### 5.4 服务端地址变更时

只有一种情况需要改地址：换了公网入口（换机器名、换 Funnel、换域名）。

```properties
# SelfMusicApp/gradle.properties
selfmusic.serverBaseUrl.release=https://新地址/
```

改完**必须重新打包**（地址是编译期注入的 `BuildConfig`，不是运行时配置）。注意结尾**必须有斜杠**，且**不要带路径前缀**（后端没有 context-path）。

> 已装的老版本 App 会连旧地址。如果旧地址失效，用户升级前会连不上 —— 所以换地址期间新旧地址要并行一段时间。

### 5.5 App 与后端的兼容

升级顺序建议：

| 场景 | 建议顺序 |
|---|---|
| 后端只加接口、不改老接口 | 先后端，后 App（老 App 继续能用） |
| 后端改接口签名/返回结构 | 先发 App 兼容新旧两种，再改后端 |
| 强制升级 | 后端对老版本返回 426，App 提示「请升级」 |

单人自用可以粗暴一点：**后端和 App 同时更新**，停机几分钟。

---

## 6. 注意事项（按风险分类）

### 安全

| 事项 | 说明 |
|---|---|
| `.env` 泄露 | 里面是所有密码。别提交、别发微信、别贴到聊天工具里问人 |
| Funnel 暴露公网 | 你的 API 现在任何知道 URL 的人都能访问。JWT 保护了数据，但登录接口没有限流 —— 密码用 16 位以上随机串 |
| 别做路由器端口转发 | 有 Funnel 就够了，把 8080/3306/9000 转发出去纯属找打 |
| Docker 端口绑定 | 所有端口都应绑定在 `127.0.0.1`，改 compose 时别手滑改成 `0.0.0.0` |
| 密钥轮换 | 换 `JWT_SECRET` 会让所有人重新登录；换 `MEDIA_TOKEN_SECRET` 会让旧播放链接失效（影响小） |
| 密码文件 | `~/passwords.txt` 在服务器上，记得同步一份到你电脑 |

### 数据

| 事项 | 说明 |
|---|---|
| 备份频率 | 建议每天凌晨自动备份，保留 7 天（见 DEPLOY.md 的 crontab 示例） |
| 备份要能恢复 | 每季度**真的**恢复一次到测试环境，不然备份只是心理安慰 |
| 数据卷 | 不要手动改 `/var/lib/docker/volumes/...` 里的文件，MySQL 会损坏 |
| `down -v` | 禁止。`down`（不带 -v）是安全的 |
| 迁移不可回滚 | 见第 3 节，破坏性变更要拆版本 |
| 磁盘 | 音频只增不减，40GB 盘迟早不够，提前规划 |

### 网络

| 事项 | 说明 |
|---|---|
| Docker Hub 拉不动 | 这台机器 DNS 被污染，官方源基本不可用。用「镜像站显式拉 + docker tag 成本地名」（见 DEPLOY-centos7.md 的排错节） |
| 镜像站会失效 | `docker.m.daocloud.io`、`docker.1ms.run`、`docker.1panel.live` 都是第三方，可能某天挂掉。挂掉时换下一个，或预拉镜像离线导入 |
| Funnel 带宽 | 走 Tailscale 中继，几十 Mbps 封顶。传大文件慢是正常的，不是 bug |
| Tailscale 掉线 | 服务器和手机都要在线；`tailscale status` 看状态 |
| Funnel 配置持久化 | `--bg` 的配置存在 tailscaled 状态里，重启后自动恢复，但重启后建议 `tailscale funnel status` 确认一次 |

### 构建

| 事项 | 说明 |
|---|---|
| 首次构建慢 | 正常，32 分钟。之后有缓存 |
| 构建掉线 | 用 `nohup ... &` + `tail -f`，SSH 断开不影响 |
| 磁盘涨得快 | `docker image prune -f` 清旧镜像（**别带 --volumes**） |
| 日志占空间 | compose 已限制 10MB×3，仍建议定期 `du -sh /var/log` |

---

## 7. 例行维护日历

| 频率 | 做什么 | 命令 |
|---|---|---|
| **每次升级前** | 备份 | `./deploy.sh backup` |
| **每周** | 看磁盘、看错误日志 | `df -h`、`docker compose logs --since 7d server \| grep -i error` |
| **每月** | 清旧镜像、验证服务健康 | `docker image prune -f`、`docker compose ps`、`tailscale funnel status` |
| **每季度** | 演练恢复备份 | 恢复到一个测试目录/测试库，确认能起来 |
| **每年** | 检查系统 EOL、依赖安全更新 | CentOS 7 已 EOL，长期建议迁 Debian 12（见 [deploy/REINSTALL-TO-UBUNTU.md](deploy/REINSTALL-TO-UBUNTU.md)） |

一键健康检查：

```bash
cd /opt/SelfMusicServer
echo "== 容器 =="; docker compose ps
echo "== 健康 =="; curl -s 127.0.0.1:8080/actuator/health; echo
echo "== 磁盘 =="; df -h / | tail -1
echo "== Docker 占用 =="; docker system df
echo "== Funnel =="; tailscale funnel status
echo "== 备份 =="; ls -lh backups/ | tail -5
```

---

## 8. 故障速查

| 症状 | 最可能的原因 | 处理 |
|---|---|---|
| `docker compose up` 报端口被占用 | 宿主 3306/8080 被别的程序占了 | 改 `.env` 的 `MYSQL_HOST_PORT`，或停掉占用程序 |
| 容器一直 `Restarting` | 配置错 / 数据库连不上 / seccomp | `docker compose logs --tail=60 server` 看真实报错 |
| `Could not resolve placeholder` | `.env` 缺变量（prod profile 无默认值） | 对照 `.env.example` 补齐 |
| `Schema-validation: missing table` | Flyway 没跑成功 / 连错库 | 看日志里 Flyway 的报错，检查 `DB_URL` |
| App 提示网络错误 | 地址错（带路径/没斜杠）、Funnel 挂了、手机 Tailscale 没开 | 先用浏览器开 `https://.../actuator/health` 验证 |
| App 能登录但播放无声 | 媒体 token 过期 / MinIO 里对象缺失 | 重新获取播放地址；检查 MinIO 控制台对应路径 |
| 上传大文件失败 | Funnel 带宽/超时 | 改用手机 Tailscale 直连，或换 Nginx 前置 |
| `curl https://...ts.net` 报证书错误 | Funnel 没启动 / HTTPS 未在后台开启 | `tailscale funnel status`，检查 admin 控制台 |
| 磁盘满 | 镜像堆积 / 音频过多 | `docker system df`，`docker image prune -f`，清理旧备份 |
| 修改代码后行为没变 | 忘了 `--build`，用的旧镜像 | `docker compose up -d --build` |

---

## 9. 变更记录模板

每次升级在仓库根目录的 `CHANGELOG.md` 里加一条，出问题时能快速定位是哪个版本引入的：

```markdown
## v1.1.0 — 2026-11-25

### 变更
- 新增：歌词偏移量编辑
- 修复：上传 2GB 文件时超时

### 数据库
- V2__add_lyrics_offset.sql（新增列，可空，兼容旧代码）

### 回滚
- 代码可回滚到 v1.0.0
- 数据库无需回滚（只加列）

### 验证
- [x] 健康检查 UP
- [x] 手机登录 + 播放
- [x] 上传 50MB 音频
```
