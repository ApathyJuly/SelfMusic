#!/usr/bin/env bash
# 重装系统前的信息采集脚本（在老的 CentOS 7 上跑，只读，不改任何东西）
#
# 用途：重装成 Ubuntu 前，把「重装后可能找不到」的信息先存下来：
#   - CPU 架构（决定下载 x86_64 还是 arm64 的 ISO）
#   - 磁盘布局（哪块是系统盘、有没有数据盘）
#   - 网络配置（静态 IP 的话，重装后要照抄，否则连不上）
#   - SSH 端口、已启用服务、已装软件、定时任务
#   - SelfMusicServer 目录（排除 .env 和 target）
#
# 用法：
#   chmod +x deploy/preflight-backup.sh
#   sudo ./deploy/preflight-backup.sh
#   # 产出一个 tar.gz，拉到你自己电脑上保存：
#   # scp root@服务器IP:/root/preflight-*.tar.gz .
#
# 注意：采集结果里包含 /etc/shadow 之外的敏感配置，压缩包权限已设为 600。

set -uo pipefail

STAMP=$(date +%Y%m%d-%H%M%S)
NAME="preflight-$STAMP"
OUT="/root/$NAME"
ARCHIVE="/root/$NAME.tar.gz"

if [ "$(id -u)" -ne 0 ]; then
  echo "请用 root 运行：sudo $0" >&2
  exit 1
fi

mkdir -p "$OUT"

run() {
  # $1 = 输出文件名, $2.. = 命令
  local f="$OUT/$1"; shift
  {
    echo "### 命令: $*"
    echo "### 时间: $(date -Is)"
    echo "----------------------------------------"
    "$@" 2>&1
  } > "$f"
}

echo "==> 采集系统信息"
run "01-uname.txt"      uname -a
run "02-arch.txt"       uname -m
run "03-release.txt"    cat /etc/redhat-release
run "04-lscpu.txt"      lscpu
run "05-memory.txt"     free -h
run "06-disk.txt"       lsblk -f
run "07-filesystem.txt" df -hT
run "08-dmi.txt"        dmidecode -t system

echo "==> 采集网络信息（重装后最关键）"
run "10-ip-addr.txt"    ip -o addr show
run "11-ip-route.txt"   ip route show
run "12-resolv.txt"     cat /etc/resolv.conf
run "13-hosts.txt"      cat /etc/hosts
run "14-ssh-port.txt"   grep -E '^\s*Port\s+' /etc/ssh/sshd_config

# 网卡配置文件整目录拷贝（静态 IP 就写在这里）
if [ -d /etc/sysconfig/network-scripts ]; then
  mkdir -p "$OUT/network-scripts"
  cp -a /etc/sysconfig/network-scripts/ifcfg-* "$OUT/network-scripts/" 2>/dev/null || true
fi

echo "==> 采集防火墙 / SELinux / 服务"
run "20-firewall.txt"   firewall-cmd --list-all
run "21-selinux.txt"    getenforce
run "22-services.txt"   systemctl list-unit-files --type=service --state=enabled
run "23-running.txt"    systemctl list-units --type=service --state=running
run "24-crontab.txt"    crontab -l
run "25-packages.txt"   rpm -qa

echo "==> 采集磁盘占用和数据目录"
run "30-disk-usage.txt" du -sh /opt /home /var/www /srv /data 2>/dev/null
run "31-opt-ls.txt"     ls -al /opt
run "32-home-ls.txt"    ls -al /home

echo "==> 备份 SelfMusicServer（排除依赖和密钥）"
for d in /opt/SelfMusic/SelfMusicServer /opt/SelfMusicServer /root/SelfMusic/SelfMusicServer; do
  if [ -d "$d" ]; then
    mkdir -p "$OUT/selfmusic-server"
    tar -C "$d" \
      --exclude='./target' --exclude='./.env' --exclude='./.env.local' \
      --exclude='./data' --exclude='./backups' \
      -cf - . 2>/dev/null | tar -C "$OUT/selfmusic-server" -xf - 2>/dev/null || true
    echo "$d" > "$OUT/selfmusic-server-path.txt"
    break
  fi
done

echo "==> 打包"
tar -czf "$ARCHIVE" -C /root "$NAME" 2>/dev/null
chmod 600 "$ARCHIVE"
rm -rf "$OUT"

echo
echo "================ 完成 ================"
echo "压缩包：$ARCHIVE"
ls -lh "$ARCHIVE"
echo
echo "下一步：把它拉到你自己电脑上保存（Windows PowerShell 里执行）："
echo "  scp root@$(hostname -I 2>/dev/null | awk '{print $1}'):$ARCHIVE ."
echo
echo "重装前请特别确认这几件事："
echo "  1. 网卡是静态 IP 还是 DHCP —— 看上面的 network-scripts/ifcfg-* 和 11-ip-route.txt"
echo "  2. 有没有第二块数据盘 —— 看 06-disk.txt，重装时千万别格式化它"
echo "  3. SSH 端口是不是 22 —— 看 14-ssh-port.txt"
echo "  4. 有没有在跑别的服务 —— 看 23-running.txt"
