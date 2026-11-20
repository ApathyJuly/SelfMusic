# 把 CentOS 7.9 换成 Ubuntu 24.04 LTS

> **重装会清空系统盘，不可回滚。** 先跑完第 0 步再动手。
> 装完 Ubuntu 之后，部署走 [DEPLOY.md](DEPLOY.md)（那份就是按 Ubuntu 写的）。

---

## 第 0 步：重装前先采集信息（5 分钟，别跳过）

很多东西重装后就找不回来了：静态 IP 的网关、SSH 端口、第二块数据盘的挂载点、在跑的其它服务。

在**现在的 CentOS 上**执行：

```bash
cd /opt/SelfMusic/SelfMusicServer      # 换成你实际放了代码的目录
chmod +x deploy/preflight-backup.sh
sudo ./deploy/preflight-backup.sh
```

脚本只读不改，产出一个压缩包 `/root/preflight-时间戳.tar.gz`，然后**拉到你自己电脑上**：

```powershell
# 在 Windows PowerShell 里执行
scp root@你的服务器IP:/root/preflight-*.tar.gz .
```

解压后重点看这四个文件：

| 文件 | 你在找什么 |
|---|---|
| `02-arch.txt` | `x86_64` → 下载 amd64 ISO；`aarch64` → 下载 arm64 ISO |
| `06-disk.txt` + `07-filesystem.txt` | 有几块盘？哪块是系统盘、哪块是数据盘（**数据盘不要格式化**） |
| `11-ip-route.txt` + `network-scripts/ifcfg-*` | 是 DHCP 还是静态 IP。静态的必须抄下 IP / 网关 / DNS |
| `14-ssh-port.txt` + `23-running.txt` | SSH 端口是不是 22；有没有别的服务在跑 |

> 家用路由器环境下大概率是 **DHCP**，那重装完会自动拿到地址，最省事。
> 如果是静态 IP 且没记下来，重装完机器会「消失」——只能接显示器或者进路由器后台看。

---

## 第 1 步：选一条路线（按你的机器形态）

### 路线 A：云服务器（阿里云 / 腾讯云 / 华为云）

最简单，5 分钟。

1. 云控制台 → 实例 → **停机**
2. 「更换操作系统」/「重装系统」→ 选 **Ubuntu Server 24.04 LTS 64位**
3. **注意数据盘**：如果除了系统盘还有数据盘，重装时**不要勾选格式化数据盘**
4. 重装后重置密码或绑定 SSH 密钥
5. 云厂商的 Ubuntu 镜像默认用户是 **`ubuntu`**（不是 root），登录后 `sudo -i` 提权

```bash
ssh ubuntu@你的公网IP
sudo -i
```

---

### 路线 B：家里/办公室的物理机，有显示器和键盘

也是最干净的一条，20～30 分钟。

**B1. 做启动 U 盘（在 Windows 上）**

1. 下载 **Ubuntu Server 24.04 LTS**：<https://ubuntu.com/download/server>
   （选 `Ubuntu Server 24.04.x LTS`，不是 Desktop）
2. 下载 **Rufus**：<https://rufus.ie>
3. Rufus 里选 U 盘 → 选 ISO → 分区类型 GPT / 目标 UEFI → 写入
   （如果 Rufus 提示「以 DD 模式写入」，选「是」）

**B2. 从 U 盘启动**

- 插上 U 盘，重启，开机时连按 **F2 / F12 / Del / F11**（不同主板不一样）进启动菜单
- 选 U 盘（名字里带 `UEFI:` 的那项）

**B3. 安装时这样选**

| 界面 | 选什么 |
|---|---|
| 语言 / 键盘 | English（中文安装界面有乱码风险） |
| 安装类型 | **Ubuntu Server (minimized)** —— 不要装任何 snap 附加组件 |
| 网络 | DHCP 就自动过；静态 IP 在这一步填 |
| 磁盘 | **Use an entire disk**（会清空这块盘），并勾选 **Set up this disk as an LVM group**（不勾也行） |
| 用户 | 设一个用户名（例如 `dell`），勾选 **Install OpenSSH server** |
| 附加软件 | **一个都不要勾**（不要 Docker、不要图形界面） |

> ⚠️ 安装器只会动你选中的那块盘。有其他数据盘的话，务必核对清楚 /dev/sda、/dev/sdb 哪个是系统盘。
> 拿不准就**先把数据盘的数据线拔了**再装，装完插回去。

**B4. 装完记 IP**

```bash
ip -br a
```

---

### 路线 C：物理机，但没有显示器，只能 SSH

这条路风险最高，**前提是你确认整块盘可以清空、且确实没有带外管理可用**。

**先确认有没有更安全的办法：**

- 机器支持 **IPMI / iKVM / iLO / 远程管理卡**？→ 用那个挂 ISO，等同路线 B，最安全
- 是 **PVE / ESXi / 群晖 VMM 里的虚拟机**？→ 用路线 D
- 主板/迷你主机支持 **PXE 网络启动**，且你路由器能搭 DHCP+TFTP？→ 也可以，但配置成本高

**都没有，才用第三方一键重装脚本（DD 重装）：**

这类脚本的原理是把目标系统的镜像直接 `dd` 到你的系统盘，然后重启进入新系统。

```bash
# 示例：bin456789/reinstall（开源，GitHub 上可查源码，用前务必自己看一遍 README）
curl -O https://raw.githubusercontent.com/bin456789/reinstall/main/reinstall.sh
less reinstall.sh          # 先看一眼再执行，不要盲跑

bash reinstall.sh ubuntu 24.04
```

执行后：

1. 脚本会让你设 **root 密码 / SSH 公钥** —— 务必设好，否则重装完进不去
2. 如果是**静态 IP**，要额外传网关、掩码、DNS 参数（看脚本 README 的 `--ip`/`--gateway` 选项），
   DHCP 环境一般不用管
3. 确认后机器重启，**SSH 会断开**，这是正常的
4. 等 **10～30 分钟**（取决于下载速度），然后用新密码重新 SSH

> ⚠️ 必须知道的四件事：
> 1. **全盘清空，不可回滚**，第 0 步的备份一定先做完
> 2. 脚本是第三方开源项目，不是官方工具 —— 执行前读一遍源码，确认它没做奇怪的事
> 3. 需要能访问 GitHub；访问不了就换 `leitbogioro/Tools` 之类的镜像源（同样要自己审）
> 4. 重装中途断电/网络断，机器可能直接变砖（需要接显示器救），所以**别在半夜做、确保 UPS 或不在乎**

---

### 路线 D：虚拟机（PVE / ESXi / 群晖 VMM / VirtualBox / Hyper-V）

挂 ISO 重装，或者干脆**新建一台虚拟机**（更推荐，老机器留着还能回去看）：

1. 下载 `ubuntu-24.04.x-live-server-amd64.iso`
2. PVE：上传到 `local` 存储 → 虚拟机 → Hardware → CD/DVD → 选这个 ISO → Options 里 Boot Order 把它排第一 → 启动
3. 安装过程同路线 B3
4. 装完记得把 ISO 从光驱卸载，否则会一直进安装器

---

## 第 2 步：装完 Ubuntu 后的 10 分钟配置

```bash
# 登录（云服务器用 ubuntu 用户，物理机用你安装时设的用户）
ssh 你的用户@你的IP
sudo -i

# 1. 更新 + 基础工具（国内先换源更快，见下方可选步骤）
apt update && apt upgrade -y
apt install -y git curl vim htop

# 2. 时区
timedatectl set-timezone Asia/Shanghai

# 3. 装 Docker（官方源，自带 compose v2）
curl -fsSL https://get.docker.com | sh

# 4. 免 sudo 用 docker
usermod -aG docker 你的用户名     # 例如 usermod -aG docker dell
# 退出重新登录生效

# 5. 验证
docker version
docker compose version
docker info | grep 'Storage Driver'   # 必须是 overlay2
```

**可选：apt 换国内源**（服务器在国内时明显更快）

```bash
cp /etc/apt/sources.list /etc/apt/sources.list.bak
sed -i 's|http://archive.ubuntu.com|https://mirrors.aliyun.com|g; s|http://security.ubuntu.com|https://mirrors.aliyun.com|g' /etc/apt/sources.list
apt update
```

> Ubuntu 24.04 用的是新格式 `sources.list.d/ubuntu.sources`，如果上面 sed 没生效，
> 直接改 `/etc/apt/sources.list.d/ubuntu.sources` 里的 `URIs:` 为 `https://mirrors.aliyun.com/ubuntu/`。

**可选：静态 IP（只有第 0 步显示原来是静态 IP 才需要）**

```bash
vim /etc/netplan/50-cloud-init.yaml    # 文件名可能不同，ls /etc/netplan/ 看

# 内容示例（缩进必须用空格，不能用 Tab）：
# network:
#   version: 2
#   ethernets:
#     eth0:                              # 网卡名用 ip -br a 确认
#       dhcp4: false
#       addresses: [192.168.31.10/24]
#       routes:
#         - to: default
#           via: 192.168.31.1
#       nameservers:
#         addresses: [223.5.5.5, 119.29.29.29]

netplan apply
ip -br a           # 确认地址生效
ping -c 3 223.5.5.5
```

---

## 第 3 步：部署 SelfMusic

从这里开始，**完全照 [DEPLOY.md](DEPLOY.md) 走**，不用改任何命令：

```bash
cd /opt
git clone <你的仓库地址> SelfMusic
cd /opt/SelfMusic/SelfMusicServer

cp .env.example .env
chmod 600 .env
openssl rand -base64 48      # 跑 5 次，填 5 个密钥
vim .env

docker compose config >/dev/null && echo "配置 OK"
docker compose up -d --build

curl 127.0.0.1:8080/actuator/health
```

接着配 Nginx + 证书（DEPLOY.md 第 6 步），Ubuntu 上直接用 `certbot`，一条命令，不用 acme.sh。

---

## 第 4 步：换完之后逐项验收

```bash
uname -a                                  # Ubuntu 24.04
docker version                            # 25+ 都行
docker compose version                    # v2.x
docker info | grep 'Storage Driver'       # overlay2
docker compose ps                         # mysql/minio/server 都 Up
curl 127.0.0.1:8080/actuator/health       # {"status":"UP"}
ss -tlnp | grep -E '8080|9000|9001'       # 必须都是 127.0.0.1
sudo ufw status                           # 只开 22/80/443
```

对照第 0 步采集到的 `23-running.txt`，确认原来跑的服务你在新系统上都重新装回去了（或者说确实不需要了）。

---

## 一句话总结

| 你的情况 | 走哪条 |
|---|---|
| 云服务器 | **A**，控制台点两下 |
| 有显示器和键盘 | **B**，U 盘装，最稳 |
| 只有 SSH，有 IPMI/iKVM | 当 **B** 处理，远程挂 ISO |
| 只有 SSH，什么带外都没有 | **C**，先备份，再考虑 DD 脚本 |
| PVE/ESXi 虚拟机 | **D**，新建一台更省事 |
