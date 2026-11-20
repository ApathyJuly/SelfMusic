# 在 CentOS 7.9 上部署 SelfMusic（Docker Compose）

> 适用：`CentOS Linux release 7.9.2009 (Core)`
> 如果你的服务器可以重装系统，**建议直接看本文最后的「方案 A」**，能省掉下面 80% 的坑。

---

## 先看清三个硬约束

| 问题 | 现状 | 影响 |
|---|---|---|
| **CentOS 7 已 EOL** | 2024-06-30 停止维护，`mirror.centos.org` 下线 | `yum install` 全挂，必须先换源（第 1 步） |
| **Docker 官方放弃 CentOS 7** | 最后一个 el7 版本是 `docker-ce 26.1.4`（2024-06） | `get.docker.com` 一键脚本不能用，必须指定版本装 |
| **内核 3.10 太老** | glibc ≥ 2.34 的镜像会调用 `clone3`，老 seccomp 返回 EPERM | 后端容器可能起不来（第 6 步有兜底） |

另外还有两个 CentOS 特有的坑，本文都已处理：`ufw` 不存在（用 firewalld）、SELinux 会挡住 Nginx 反代。

---

## 第 1 步：把 yum 源换成阿里云

原来的 `mirror.centos.org` 已经 404，先修源，否则后面什么都装不上。

```bash
sudo -i        # 后面都用 root，省得每条都 sudo

cd /etc/yum.repos.d/
mkdir -p backup-centos7 && mv *.repo backup-centos7/

curl -o /etc/yum.repos.d/CentOS-Base.repo https://mirrors.aliyun.com/repo/Centos-7.repo
curl -o /etc/yum.repos.d/epel.repo       https://mirrors.aliyun.com/repo/epel-7.repo

yum clean all
yum makecache
```

验证（应该能看到 base/updates/extras/epel）：

```bash
yum repolist
yum install -y git curl vim
```

> 如果 `yum makecache` 还是报错，说明服务器 DNS 或出网有问题，先解决网络再往下走。
> 备用源：`https://mirrors.tuna.tsinghua.edu.cn/repo/Centos-7.repo`（清华）。

---

## 第 2 步：安装指定版本的 Docker

**不要**执行 `curl -fsSL https://get.docker.com | sh`，那条路在 CentOS 7 上已经断了。

```bash
# 依赖
yum install -y yum-utils device-mapper-persistent-data lvm2

# 用阿里云的 docker-ce 镜像源（官方源在国内也不稳）
cat > /etc/yum.repos.d/docker-ce.repo <<'EOF'
[docker-ce-stable]
name=Docker CE Stable - $basearch
baseurl=https://mirrors.aliyun.com/docker-ce/linux/centos/7/$basearch/stable
enabled=1
gpgcheck=0
EOF

# 装固定版本：engine 26.1.4 是 el7 的最后一版，compose 2.27.1 是 el7 可用的较新版
yum install -y \
  docker-ce-26.1.4 \
  docker-ce-cli-26.1.4 \
  containerd.io-1.6.33 \
  docker-compose-plugin-2.27.1

systemctl enable --now docker
```

> `gpgcheck=0` 是因为阿里云镜像不一定能取到 Docker 的 GPG key。介意的话改成 `gpgcheck=1` 并先执行
> `rpm --import https://mirrors.aliyun.com/docker-ce/linux/centos/gpg`。

验证：

```bash
docker version
docker compose version
```

看到 `Server: Docker Engine 26.1.4` 和 `Docker Compose version v2.27.1` 就对了。

**把 dell 加进 docker 组**，以后不用每条命令都 sudo：

```bash
usermod -aG docker dell
# 退出重新登录（或 newgrp docker）才生效
```

---

## 第 3 步：确认存储驱动是 overlay2（重要）

CentOS 7 内核老，如果 `/var/lib/docker` 落在 ext4 上，Docker 可能退回 `vfs` 驱动 —— 那会慢十倍并且疯狂占磁盘。

```bash
docker info | grep -E 'Storage Driver|Cgroup Driver'
```

- 看到 `Storage Driver: overlay2` → 正常，继续
- 看到 `Storage Driver: vfs` → 有问题，需要给 `/var/lib/docker` 单独一块 **xfs 且 ftype=1** 的盘（`xfs_info /挂载点 | grep ftype` 应该是 `ftype=1`）

同时确认一下内存，够不够跑这一套：

```bash
free -h
```

低于 4GB 会比较紧张（MySQL + MinIO + JVM 约 2.5～3GB）。

---

## 第 4 步：部署项目

和 Ubuntu 版完全一样，只是没有 `apt`：

```bash
cd /opt
git clone <你的仓库地址> SelfMusic
cd /opt/SelfMusic/SelfMusicServer

cp .env.example .env
chmod 600 .env
openssl rand -base64 48     # 跑 5 次，分别填进下面 5 个变量

vim .env
```

`.env` 要填的 5 个值（`MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`MINIO_ROOT_PASSWORD`、`JWT_SECRET`、`MEDIA_TOKEN_SECRET`）和 Ubuntu 版一致，见 [DEPLOY.md](DEPLOY.md) 第 4 步。

启动前先空跑一遍配置：

```bash
docker compose config >/dev/null && echo "配置 OK"
docker compose up -d --build
```

首次构建 5～15 分钟。国内拉镜像慢的话，先配加速器：

```bash
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": ["https://你的阿里云加速器地址.mirror.aliyuncs.com"]
}
EOF
systemctl restart docker
```

（阿里云控制台 → 容器镜像服务 → 镜像加速器，能拿到专属地址）

验证：

```bash
docker compose ps
curl 127.0.0.1:8080/actuator/health
```

---

## 第 5 步：Nginx（CentOS 用 yum + SELinux 必调）

```bash
yum install -y nginx
systemctl enable --now nginx
```

**关键一步**：CentOS 默认 SELinux enforcing，不放开的话 Nginx 反代会 502，日志里是 `Permission denied`：

```bash
getenforce                                    # 应该是 Enforcing
setsebool -P httpd_can_network_connect 1      # 允许 nginx 反代到 127.0.0.1:8080
```

> 用 `grep -i denied /var/log/audit/audit.log | tail` 可以确认是不是 SELinux 干的。
> **不建议**直接 `setenforce 0` 关掉 SELinux，那是把整机安全策略废掉，用 booleans 精确放行更好。

站点配置放在 `/etc/nginx/conf.d/`（CentOS 没有 `sites-available` 那套）：

```bash
cd /opt/SelfMusic/SelfMusicServer
cp deploy/nginx.conf /etc/nginx/conf.d/selfmusic.conf
vim /etc/nginx/conf.d/selfmusic.conf
```

要改两处：

1. 所有 `music.example.com` → 你的域名（4 处）
2. **证书路径**要换成 acme.sh 的路径（见下一步），把
   ```
   /etc/letsencrypt/live/music.example.com/fullchain.pem
   /etc/letsencrypt/live/music.example.com/privkey.pem
   ```
   改成
   ```
   /etc/nginx/ssl/music.example.com.crt
   /etc/nginx/ssl/music.example.com.key
   ```

---

## 第 6 步：HTTPS 证书（用 acme.sh，不用 certbot）

**为什么不用 certbot**：EPEL7 里的 certbot 依赖 Python 2，版本老且容易出问题；CentOS 7 也不支持 snap。
`acme.sh` 是纯 shell 脚本，不依赖任何包管理器，在这套已 EOL 的系统上最稳。

```bash
# 安装（会自己写 crontab 做自动续期）
curl https://get.acme.sh | sh -s email=你的邮箱@example.com
source ~/.bashrc

# 新版 acme.sh 默认用 ZeroSSL，显式切回 Let's Encrypt
~/.acme.sh/acme.sh --set-default-ca --server letsencrypt
```

签证书（先停 nginx，让 acme.sh 独占 80 端口，签完自动拉起）：

```bash
systemctl stop nginx

~/.acme.sh/acme.sh --issue -d music.example.com --standalone \
  --pre-hook  "systemctl stop nginx" \
  --post-hook "systemctl start nginx"
```

把证书安装到固定路径（acme.sh 的 cert 在它自己的目录里，不稳定，必须 install-cert 落地）：

```bash
mkdir -p /etc/nginx/ssl

~/.acme.sh/acme.sh --install-cert -d music.example.com \
  --key-file       /etc/nginx/ssl/music.example.com.key \
  --fullchain-file /etc/nginx/ssl/music.example.com.crt \
  --reloadcmd      "systemctl reload nginx"
```

`--reloadcmd` 会被记录下来，以后每 60 天自动续期后自动 reload nginx，不用你管。

启用站点并验证：

```bash
nginx -t
systemctl reload nginx
curl https://music.example.com/actuator/health
```

---

## 第 7 步：防火墙（firewalld，不是 ufw）

```bash
firewall-cmd --permanent --add-service=ssh
firewall-cmd --permanent --add-service=http
firewall-cmd --permanent --add-service=https
firewall-cmd --reload
firewall-cmd --list-all
```

**注意**：Docker 发布端口时会自己插 iptables 规则，**绕过 firewalld**。所以哪怕 firewalld 没开 8080，
只要 compose 里写的是 `0.0.0.0:8080`，它就是公网可达的。本仓库的 compose 已经绑成 `127.0.0.1`，所以是安全的 ——
**不要**为了图省事把它改成 `8080:8080`。

确认一下：

```bash
ss -tlnp | grep -E '8080|9000|9001|3306'
# 都必须带 127.0.0.1，不能是 0.0.0.0 或 *:
```

---

## 第 8 步：如果容器起不来（CentOS 7 特有）

症状：`docker compose ps` 里 server 一直 `Restarting`，日志报

```
Operation not permitted
clone3: Operation not permitted
runc: unable to start container process
```

原因：CentOS 7 的内核 3.10 和自带的旧 libseccomp 不认识 `clone3` 系统调用，而
`eclipse-temurin:17-jre`（Ubuntu 22.04 基础镜像，glibc 2.35）会用到它。

**兜底方案**：给这个容器关掉 seccomp 过滤。新建 `docker-compose.override.yml`（Compose 会自动合并）：

```yaml
services:
  server:
    security_opt:
      - seccomp=unconfined
```

然后：

```bash
docker compose up -d --force-recreate server
```

> 这会降低容器隔离强度，但你的场景是"单机跑自己的服务"，可以接受。
> 更干净的做法是换一个 glibc 更老的基础镜像（`eclipse-temurin:17-jre-focal`），需要改 `Dockerfile` 第 8 行。

其他 CentOS 7 高频报错：

| 报错 | 原因 | 解决 |
|---|---|---|
| `curl: (51) ... does not match the server's certificate` | `/etc/hosts` 里被写死了错误的 IP（典型：`223.5.5.5 mirrors.aliyun.com`），或网关在拦 TLS | 见下方「证书本机名不匹配」 |
| `yum ... Cannot find a valid baseurl` | 源还是老的 mirror.centos.org | 重做第 1 步 |
| `package docker-ce-26.1.4 requires container-selinux` | extras 源没启用 | `yum install -y container-selinux --enablerepo=extras` |
| `docker: Error response ... iptables: No chain` | firewalld 与 docker 冲突 | `systemctl restart docker`，必要时 `yum install -y iptables-services` |
| Nginx 502 且 error.log 有 `Permission denied` | SELinux 拦截反代 | `setsebool -P httpd_can_network_connect 1` |
| `acme.sh` 签证书报 `Timeout` | 80 端口被 nginx 或防火墙占着 | 确认 `systemctl stop nginx`、firewalld 放行 http |
| 构建卡在 `Step 3/9 RUN mvn` | Maven Central 慢 | 配 Docker 加速器 + `~/.m2/settings.xml` 加阿里云源 |
| `curl`/`yum` 长时间无响应（不报错） | 机器有 IPv6 地址但没有可用 IPv6 路由 | `curl -4 ...`，并在 `/etc/yum.conf` 加 `ip_resolve=4` |

### 证书与本机名不匹配（`curl: (51)`）

报错原文：

```
curl: (51) Unable to communicate securely with peer: requested domain name does not match the server's certificate.
```

意思是 TLS 握手成功了，但对方递过来的证书不是你要访问的域名。按顺序排查：

```bash
# 1. 最高频原因：hosts 里被写死了错误 IP
grep -n ali<域名关键字> /etc/hosts
#    例如 grep -n aliyun /etc/hosts 输出 "223.5.5.5 mirrors.aliyun.com"
#    223.5.5.5 是阿里 DNS 的地址，不是镜像站地址 → 删掉这一行
sed -i '/mirrors\.aliyun\.com/d' /etc/hosts
getent hosts mirrors.aliyun.com      # 应返回真实公网 IP

# 2. 确认 DNS 没有被全量劫持
getent hosts this-domain-should-not-exist-4f7a1c.com
#    有输出 → DNS 劫持，改 /etc/resolv.conf 为 223.5.5.5 / 119.29.29.29
#    无输出 → DNS 正常，问题就在 hosts 或网关

# 3. 看对方证书到底是谁的
echo | openssl s_client -connect mirrors.aliyun.com:443 -servername mirrors.aliyun.com 2>/dev/null \
  | openssl x509 -noout -subject -issuer
#    出现 *.xiaomi.com / 内网域名 → 路由器或网关在做 TLS 中间人，需要关掉路由器的安全防护
```

处理完再用 `-4` 强制 IPv4 下载：

```bash
curl -4 -o /etc/yum.repos.d/CentOS-Base.repo https://mirrors.aliyun.com/repo/Centos-7.repo
head -3 /etc/yum.repos.d/CentOS-Base.repo    # 必须是 repo 文件，不能是 HTML
```


---

## 方案 A：重装成 Debian 12（更省事，推荐）

如果你的机器上没有别的业务、数据可以丢（或已备份），**重装系统是最快的路**：

1. 云服务器：控制台 → 更换操作系统 → Debian 12 / Ubuntu 24.04
2. 物理机/家用机：U 盘装 Debian 12 minimal
3. 装完直接照 [DEPLOY.md](DEPLOY.md) 从「第 2 步：安装 Docker」往下走，全部命令都是现成的

代价对比：

| | 留在 CentOS 7 | 重装 Debian 12 |
|---|---|---|
| 上手耗时 | 半天（要绕源、版本、seccomp） | 1～2 小时（含重装） |
| 后续维护 | 无安全更新，包全是老的 | 官方源正常，`apt` 一行装 |
| Docker 版本 | 锁死在 26.1.4 | 最新稳定版 |
| 证书工具 | 得用 acme.sh 绕开 certbot | certbot 一条命令 |

CentOS 7 已经 EOL 一年半了，没有安全补丁的机器长期放公网是有风险的。如果这台机器还要长期用，建议尽早换。
