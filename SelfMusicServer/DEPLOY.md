# SelfMusic 生产部署指南（Docker Compose）

面向**没学过 Docker** 的人。跟着从上往下敲完，你就能得到一个跑在 `https://你的域名` 上的后端。

全程**不需要**在服务器上装 JDK、Maven、MySQL、MinIO —— 这些都在容器里，这就是用 Docker 的意义。

> **本文按 Debian / Ubuntu 写**（`apt`、`ufw`、`certbot`）。
> 如果你的服务器是 **CentOS 7 / RHEL 7**，命令差异很大（yum 源已失效、Docker 版本锁死、SELinux、firewalld），
> 请改用 [deploy/DEPLOY-centos7.md](deploy/DEPLOY-centos7.md)。

---

## 第 0 步：先花 3 分钟搞懂 4 个词

不懂概念直接敲命令也能跑起来，但出了问题你会完全不知道从哪下手。所以先看这张表：

| 概念 | 一句话解释 | 在 SelfMusic 里对应什么 |
|---|---|---|
| **镜像 (image)** | 一个装好环境、但还没运行的「安装包」，类似一个压缩好的系统盘 | `mysql:8.0`、`minio/minio`，以及你自己代码构建出的 `selfmusic-server` |
| **容器 (container)** | 镜像**跑起来**之后的那个进程，可以理解成一个轻量级虚拟机 | 一个 MySQL 容器、一个 MinIO 容器、一个后端容器，共 3 个 |
| **数据卷 (volume)** | 容器删了数据也不丢的「外接硬盘」 | `mysql_data` 存数据库，`miniodata` 存所有音频/封面 |
| **Compose** | 一个 YAML 文件，描述「要起哪几个容器、怎么连、怎么配」，一条命令全起 | 仓库里的 `docker-compose.yml` |

**最重要的一条**：容器本身是「用完即弃」的，改代码后要**重建**容器；但数据在**数据卷**里，重建不会丢。这也是为什么你会看到 `up -d --build` 这种命令。

还有一条新手最容易踩的：**绝对不要执行 `docker compose down -v`，也不要 `docker system prune --volumes`**。那个 `-v` 就是删数据卷，等于把数据库和所有歌曲一起删掉，且无法恢复。

---

## 第 1 步：准备服务器

| 项目 | 要求 | 说明 |
|---|---|---|
| 系统 | Ubuntu 22.04 / 24.04 LTS（Debian 12 也可以） | 下面命令都按 Ubuntu 写 |
| CPU / 内存 | 2 核 4GB 起步 | MySQL + MinIO + JVM 大概要吃 2.5～3GB，2GB 机器容易 OOM |
| 磁盘 | 40GB+ | 音乐文件很占地方，且 MinIO 的数据量只增不减 |
| 网络 | 有公网 IP，80/443 可访问 | HTTPS 证书要用 80 端口验证 |
| 域名 | 一条 A 记录指向服务器 IP | 例：`music.example.com`，**Android 端必须用 https** |

登录服务器：

```bash
ssh root@你的服务器IP
```

后面的命令如果不用 root，记得前面加 `sudo`。

---

## 第 2 步：安装 Docker

官方一键脚本，装完自带 `docker compose`（v2，注意是**空格**不是 `docker-compose`）：

```bash
curl -fsSL https://get.docker.com | sh
systemctl enable --now docker

# 验证
docker --version
docker compose version
```

看到版本号就成功了。如果 `docker compose version` 报错说找不到命令，说明装的是老版本，补一个插件：

```bash
apt-get update && apt-get install -y docker-compose-plugin
```

---

## 第 3 步：把代码放到服务器

推荐直接 `git clone`，以后升级只要 `git pull`：

```bash
apt-get update && apt-get install -y git
mkdir -p /opt && cd /opt
git clone <你的仓库地址> SelfMusic
cd /opt/SelfMusic/SelfMusicServer
```

没有 git 仓库的话，在你**本机**用 `scp` 传上去：

```powershell
# 在本机执行（PowerShell），把整个 SelfMusicServer 目录传上去
cd G:\AI_Project\SelfMusic
scp -r .\SelfMusicServer root@你的服务器IP:/opt/SelfMusic/
```

> 注意：不要把本地的 `.env.local` 传上去。服务器上用的是 `.env`，两者无关。

确认目录里这几个文件在：

```bash
ls
# Dockerfile  docker-compose.yml  pom.xml  src  .env.example  deploy.sh  deploy/
```

---

## 第 4 步：写配置文件 `.env`

这是**唯一需要你认真填**的地方。Compose 会自动读取同目录下的 `.env`，把变量注入到容器里。

```bash
cp .env.example .env
chmod 600 .env        # 只有 root 能读，防止密码泄露

# 下面要填 5 个密钥/密码，每个都跑一次这条命令，把输出粘到 .env 里
openssl rand -base64 48
```

> 生成结果形如 `K7f2...xQ==`，可能含 `+` `/` `=`，直接粘贴没问题，不要自己删字符。

然后编辑：

```bash
nano .env
```

填成这样（`<...>` 换成你自己的值）：

```ini
# --- 数据库 ---
MYSQL_DATABASE=selfmusic
MYSQL_USER=selfmusic
MYSQL_PASSWORD=<20位以上随机密码>
MYSQL_ROOT_PASSWORD=<另一个20位以上随机密码>

# --- 应用密钥（必须是上面 openssl 生成的那种长随机串）---
JWT_SECRET=<openssl rand -base64 48 的输出>
MEDIA_TOKEN_SECRET=<再跑一次 openssl 的输出>

# --- MinIO 对象存储 ---
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=<20位以上随机密码>
S3_BUCKET=selfmusic
S3_REGION=us-east-1

# 只有 Android 客户端，保持 *
CORS_ALLOWED_ORIGINS=*
```

保存退出：`nano` 里按 `Ctrl+O` 回车，再 `Ctrl+X`。

**为什么这些必须换？** 因为 `application-prod.yml` 里这些变量**没有默认值**。你留着 `.env.example` 的占位密码，等于数据库和对象存储对全世界开放；而如果哪个变量忘了填，后端会**直接启动失败**（这是刻意设计的，宁可起不来也不要带着弱密钥上线）。

密码里避免 `$`、`"`、`'`、反引号，Compose 会对它们做变量替换，容易出玄学问题。用纯字母数字最省心。

---

## 第 5 步：启动

```bash
cd /opt/SelfMusic/SelfMusicServer

# 先空跑一遍配置，能在启动前抓出 .env 里缺变量/拼错的问题
docker compose config >/dev/null && echo "配置 OK"

docker compose up -d --build
```

两个参数的含义：`-d` = 后台运行（不占住终端），`--build` = 先用 `Dockerfile` 把你的 Java 代码编译成镜像。

**第一次会比较慢，5～15 分钟很正常**，因为要下载 Maven 依赖、编译、再下载 MySQL 和 MinIO 镜像。之后有缓存就快了。

看进度：

```bash
docker compose ps          # 看 4 个容器状态
docker compose logs -f server
```

`ps` 里 `mysql`、`minio`、`server` 都应该是 `Up`。日志里出现这一行就代表后端起来了：

```
Started SelfMusicApplication in 12.3 seconds
```

`minio-init` 是初始化任务，跑完就退出，状态显示 `Exited (0)` 是**正常的**，不是报错。

验证接口：

```bash
curl http://127.0.0.1:8080/actuator/health
# {"status":"UP"}
```

到这一步，后端已经在本机跑通了。**但先别急着填 Android 地址**，还差最后一步 HTTPS。

---

## 第 6 步：Nginx + HTTPS（Android 必需）

现在后端只监听 `127.0.0.1:8080`，公网访问不到——这是故意的，让它只能被 Nginx 访问。

```bash
apt-get install -y nginx certbot python3-certbot-nginx
```

**顺序很重要**：先签证书，再启用站点配置。因为配置文件里引用了证书文件，证书不存在时 `nginx -t` 会直接失败。

**第一步，签证书。** 此时 nginx 刚装好、还没配置我们的站点，所以先停掉它，让 certbot 自己临时占用 80 端口：

```bash
systemctl stop nginx
certbot certonly --standalone \
  --pre-hook "systemctl stop nginx" \
  --post-hook "systemctl start nginx" \
  -d music.example.com
```

按提示填邮箱、同意条款。成功后证书在 `/etc/letsencrypt/live/music.example.com/`：

```bash
ls /etc/letsencrypt/live/music.example.com/
# fullchain.pem  privkey.pem  ...
```

> `--pre-hook` / `--post-hook` 会被记录到续期配置里，以后每 90 天自动续期时 certbot 会自己停一下 nginx 再续，不用你管。

**第二步，启用站点配置。** 先改域名：

```bash
cd /opt/SelfMusic/SelfMusicServer
cp deploy/nginx.conf /etc/nginx/sites-available/selfmusic.conf
nano /etc/nginx/sites-available/selfmusic.conf
# 把里面 4 处 music.example.com 换成你的真实域名（2 个 server_name + 2 个证书路径）

ln -s /etc/nginx/sites-available/selfmusic.conf /etc/nginx/sites-enabled/
rm -f /etc/nginx/sites-enabled/default
```

**第三步，测语法并加载：**

```bash
nginx -t          # 必须看到 syntax is ok / test is successful
systemctl start nginx
systemctl enable nginx
curl https://music.example.com/actuator/health
```

**第四步，验证自动续期能跑通：**

```bash
certbot renew --dry-run
systemctl list-timers | grep certbot
```

---

## 第 7 步：防火墙

只放 80/443，其他一律关掉：

```bash
# 云服务器还要去控制台的安全组里放行 80/443，两层都要开
ufw allow 22/tcp
ufw allow 80,443/tcp
ufw enable
ufw status
```

检查一下端口确实没暴露到公网：

```bash
ss -tlnp | grep -E '8080|9000|9001'
# 应该只看到 127.0.0.1:8080 这种，不能是 0.0.0.0:8080
```

> MinIO 控制台（9001）和 S3 接口（9000）都只需要内网访问，后端在容器网络里直连它们，**不要对公网开放**。

---

## 第 8 步：接上 Android

改 `SelfMusicApp/gradle.properties`：

```properties
selfmusic.serverBaseUrl.release=https://music.example.com/
```

注意**结尾带斜杠**，然后重新打 release 包。

---

## 第 9 步：从本地查看数据库和 MinIO

先明确一件事：**数据库和 MinIO 都在服务器的 Docker 里**，而且只监听服务器的 `127.0.0.1`，所以你在自己电脑上直接连是连不上的（这是故意的，防止数据库裸奔公网）。要看得用下面两种办法之一。

### 办法一：SSH 隧道（推荐，不用改任何配置）

原理：把服务器上的本地端口「借」到你电脑上，隧道期间你电脑的 `localhost:3306` 就是服务器的 MySQL。

**保持这个终端开着不关**：

```bash
# 本机执行；非 22 端口就加 -p 2222
ssh -N -L 3306:127.0.0.1:3306 -L 9001:127.0.0.1:9001 -L 9000:127.0.0.1:9000 root@你的服务器IP
```

`-N` 表示只做端口转发、不执行命令。它跑起来后**没有任何输出也不返回**，这就是正常的，关掉窗口即断开。

然后在你自己电脑上：

| 要访问什么 | 地址 | 账号 |
|---|---|---|
| MinIO 控制台 | `http://localhost:9001` | `.env` 里的 `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` |
| MinIO S3 接口 | `http://localhost:9000` | 同上 |
| MySQL | `localhost:3306` | 用户 `selfmusic`，密码 `.env` 里的 `MYSQL_PASSWORD`，库 `selfmusic` |

> MySQL 的 3306 之所以能通，是因为 compose 里映射了 `127.0.0.1:3306:3306`。那一行删掉的话隧道也连不上。

用 DBeaver / HeidiSQL / Navicat 连库时，如果报 `Public Key Retrieval is not allowed`，在驱动属性里加 `allowPublicKeyRetrieval=true`（MySQL 8 默认用 `caching_sha2_password`）。

### 办法二：在服务器上直接命令操作（一个端口都不用开）

```bash
cd /opt/SelfMusic/SelfMusicServer

# 进 MySQL 交互式命令行（会提示输入 root 密码，就是 .env 里的 MYSQL_ROOT_PASSWORD）
docker compose exec mysql mysql -uroot -p selfmusic
```

常用查询：

```sql
show tables;

-- 有哪些用户
select id, username, display_name, created_at from users;

-- 最近上传的歌
select id, title, artist, duration_ms, is_favorite
from songs order by created_at desc limit 5;

-- 各歌单歌曲数
select p.name, count(s.id) as songs
from playlists p left join songs s on s.playlist_id = p.id
group by p.id, p.name;

-- 这首歌在 MinIO 里对应哪些文件
select audio_object_key, cover_object_key, lyric_object_key from songs limit 3;
```

不进交互式界面，直接跑一条 SQL：

```bash
docker compose exec -T mysql mysql -uroot -p你的root密码 selfmusic \
  -e "select count(*) from songs;"
```

> 注意 `-e` 里的 SQL 会出现在 shell 历史里，密码也会。多用户服务器上别这么干，用交互式或 `mysql_config_editor`。

### MinIO 里到底存了什么

浏览器打开 `http://localhost:9001` → 登录 → **Buckets** → `selfmusic`，目录结构是：

```text
users/{userId}/songs/{songId}/audio.mp3
                        /cover.jpg
                        /lyrics.lrc
users/{userId}/playlists/{playlistId}/cover.jpg
```

数据库里只存 key（路径字符串）和元数据，不存文件本身，也不存永久 URL。所以「数据库里删了、MinIO 里还在」这种不一致是可能发生的——正常删除歌曲走 API 才会两边都清。

### 数据实际存在服务器的哪里

```bash
docker volume ls
# DRIVER  VOLUME NAME
# local   selfmusic_mysql_data
# local   selfmusic_miniodata

docker volume inspect selfmusic_miniodata --format '{{.Mountpoint}}'
```

物理路径在 `/var/lib/docker/volumes/selfmusic_*/_data`。**可以只读查看，绝不要在容器运行时往里写或删文件**——MySQL 会直接损坏，MinIO 会丢对象。要动数据请用上面的 SQL 或控制台，要备份用 `./deploy.sh backup`。

---

## 日常运维：只用 `deploy.sh`

我写了个脚本包住常用命令，不用记 Docker 语法：

```bash
chmod +x deploy.sh     # 只需执行一次

./deploy.sh up         # 首次部署 / 改过配置后重建
./deploy.sh ps         # 看容器是否在跑
./deploy.sh logs       # 看后端实时日志（Ctrl+C 退出，不停服务）
./deploy.sh restart    # 重启后端
./deploy.sh health     # 检查健康状态
./deploy.sh update     # 升级：git pull + 重建
./deploy.sh backup     # 备份数据库 + 音频文件
./deploy.sh down       # 停止全部（数据保留）
```

> 如果你是从 Windows 传上去的，脚本可能带 Windows 换行，运行报 `/usr/bin/env: 'bash\r': No such file or directory` 时执行一次：`sed -i 's/\r$//' deploy.sh`

等价的原生命令，知道一下就行，别硬背：

| 想做什么 | 命令 |
|---|---|
| 启动 | `docker compose up -d` |
| 改完代码/配置重新构建 | `docker compose up -d --build` |
| 看状态 | `docker compose ps` |
| 看日志 | `docker compose logs -f --tail=100 server` |
| 重启后端 | `docker compose restart server` |
| 进数据库改数据 | `docker compose exec mysql mysql -uroot -p selfmusic` |
| 进后端容器看看 | `docker compose exec server sh` |
| 停止（保留数据） | `docker compose down` |

---

## 备份（别跳过）

**先立刻做一次**，确认备份能用：

```bash
./deploy.sh backup
ls -lh backups/
```

会生成 `backups/mysql-时间.sql` 和 `backups/minio-时间.tar.gz`。音频可能很大，注意磁盘。

建议加个每天凌晨 3 点的定时备份，并保留 7 天：

```bash
crontab -e
# 加一行（路径按实际改）
0 3 * * * cd /opt/SelfMusic/SelfMusicServer && ./deploy.sh backup >/dev/null 2>&1 && find backups -mtime +7 -delete
```

**再强调一次**：数据在数据卷里，容器重建不会丢，但 `docker compose down -v` 和 `docker volume rm` 会永久删除。备份是唯一兜底。

---

## 升级版本

```bash
cd /opt/SelfMusic/SelfMusicServer
./deploy.sh backup     # 升级前先备份
./deploy.sh update     # git pull + 重建 + 启动
```

数据库结构变化由 Flyway 在启动时自动迁移，不用手动执行 SQL。旧镜像会留下来占空间，偶尔清理一下（**注意别带 `--volumes`**）：

```bash
docker image prune -f
```

---

## 排错对照表

| 现象 | 原因 | 解决 |
|---|---|---|
| `docker compose up` 报 `set MYSQL_PASSWORD in .env` | `.env` 里少了这个变量，或文件不在 `SelfMusicServer/` 目录 | `ls -a` 确认 `.env` 位置，补齐变量 |
| 后端日志报 `Could not resolve placeholder 'DB_URL'` | 同上，prod profile 缺环境变量 | 检查 `.env` 变量名拼写 |
| 后端反复重启 (`Restarting`) | 连不上 MySQL，或 `DB_URL` 带了 `?useUnicode=...` 参数 | 日志里看 `Communications link failure`；`DB_URL` 只写到库名 |
| 上传歌曲报 `413` | Nginx 的 `client_max_body_size` 太小 | 已配 2048m，改过就对齐 `MAX_FILE_SIZE` |
| 播放几秒就断 | 反代超时 | 配置里 `proxy_read_timeout 3600s` 已处理，确认没被别的配置覆盖 |
| 502 Bad Gateway | 后端容器没起来 | `./deploy.sh logs` 看报错，`curl 127.0.0.1:8080/actuator/health` |
| 容器自己 OOM 被杀 (`Killed`, exit 137) | 内存不足 | 升到 4GB；或在 compose 的 `server.environment` 里加 `JAVA_TOOL_OPTIONS: "-Xmx1g"` 限制堆 |
| MySQL 起不来、日志说权限/ibdata 问题 | 数据卷目录被改动过 | 别手动改 `/var/lib/mysql`；有备份就 `down` 后恢复 |
| 磁盘满了 | MinIO 数据只增不减 / 旧镜像堆积 | `docker system df` 看占用，`docker image prune -f`，清理旧备份 |
| 修改 `.env` 后不生效 | 没重启容器，或变量名拼错 | 重新执行 `docker compose up -d`（Compose 会检测到环境变量变化并重建容器）；仍不生效加 `--force-recreate` |

看日志的三个层次，按顺序用：

```bash
docker compose ps                          # 1. 谁挂了
docker compose logs --tail=200 server      # 2. 应用报什么错
dmesg -T | tail -20                        # 3. 是不是被内核 OOM 杀了
```

---

## 可选：国内加速

构建镜像和拉依赖慢的话：

**Docker Hub 镜像加速**（改完重启 docker）：

```bash
nano /etc/docker/daemon.json
```

```json
{
  "registry-mirrors": ["https://你的加速器地址"]
}
```

```bash
systemctl restart docker
```

**Maven 依赖加速**：如果 `--build` 卡在下载 jar，在 `~/.m2/settings.xml` 加阿里云源，或者干脆在**本机**打好包再传上去（`mvn -DskipTests package` 得到 `target/*.jar`，然后改 `Dockerfile` 只做 `COPY`）。

---

## 一页速查

```bash
# 首次部署
git clone <仓库> /opt/SelfMusic && cd /opt/SelfMusic/SelfMusicServer
cp .env.example .env && chmod 600 .env && nano .env   # 填密码和密钥
docker compose up -d --build                          # 等 5~15 分钟
curl 127.0.0.1:8080/actuator/health                   # {"status":"UP"}

# Nginx + 证书（先签证书，再启用配置）
systemctl stop nginx
certbot certonly --standalone --pre-hook "systemctl stop nginx" --post-hook "systemctl start nginx" -d music.example.com
cp deploy/nginx.conf /etc/nginx/sites-available/selfmusic.conf   # 改域名
ln -s /etc/nginx/sites-available/selfmusic.conf /etc/nginx/sites-enabled/
nginx -t && systemctl start nginx && systemctl enable nginx
ufw allow 80,443/tcp

# 之后
./deploy.sh backup    # 备份
./deploy.sh update    # 升级
./deploy.sh logs      # 看日志

# 本地看数据库 / MinIO（开一个终端保持不关，然后访问 localhost:3306 / localhost:9001）
ssh -N -L 3306:127.0.0.1:3306 -L 9001:127.0.0.1:9001 root@你的服务器IP
```
