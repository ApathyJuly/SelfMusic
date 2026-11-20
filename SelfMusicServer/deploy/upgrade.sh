#!/usr/bin/env bash
# SelfMusic 一键升级：备份 → 拉代码 → 重建 → 健康检查
#
# 用法（在服务端目录里）：
#   ./deploy/upgrade.sh
#
# 做了什么：
#   1. 显示当前版本
#   2. 备份 MySQL + MinIO（失败就停，不会带着没备份的状态继续）
#   3. git fetch + pull --ff-only（分叉会直接报错，不会静默合并）
#   4. 后台重建镜像并启动（SSH 掉线不会中断构建）
#   5. 轮询 /actuator/health 直到 UP（最多 3 分钟）
#
# 日志：/tmp/upgrade-时间戳.log
# 失败时：日志里看真实报错；回滚见 OPS.md 第 4 节

set -euo pipefail

cd "$(dirname "$0")/.."
SERVICE_DIR=$(pwd)

if [ ! -f docker-compose.yml ]; then
  echo "!! 当前目录没有 docker-compose.yml：$SERVICE_DIR" >&2
  echo "!! 应该在 /opt/SelfMusicServer/SelfMusicServer 这一层执行" >&2
  exit 1
fi

if [ ! -f .env ]; then
  echo "!! 缺少 .env（生产配置），先补齐再升级" >&2
  exit 1
fi

if [ ! -d .git ]; then
  echo "!! 不是 git 仓库，无法拉取代码。见 OPS.md 第 1 节" >&2
  exit 1
fi

STAMP=$(date +%Y%m%d-%H%M%S)
LOG="/tmp/upgrade-$STAMP.log"

echo "=============================================="
echo "服务端目录: $SERVICE_DIR"
echo "当前版本:   $(git rev-parse --short HEAD) $(git log -1 --pretty=%s 2>/dev/null || echo '')"
echo "=============================================="

echo
echo "==> [1/4] 备份数据库与对象存储"
./deploy.sh backup

echo
echo "==> [2/4] 拉取代码"
BEFORE=$(git rev-parse HEAD)
git fetch --quiet
git pull --ff-only
AFTER=$(git rev-parse HEAD)

if [ "$BEFORE" = "$AFTER" ]; then
  echo "    代码无变化（仅重建容器，用于 .env 或基础镜像变更）"
else
  echo "    更新内容："
  git log --oneline "$BEFORE..$AFTER" | sed 's/^/    /'
fi

echo
echo "==> [3/4] 重建并启动（后台执行，掉线不中断）"
echo "    日志: $LOG"
nohup docker compose up -d --build > "$LOG" 2>&1 &
BUILD_PID=$!

while kill -0 "$BUILD_PID" 2>/dev/null; do
  printf '\r    构建中... 日志已有 %s 行' "$(wc -l < "$LOG" 2>/dev/null || echo 0)"
  sleep 5
done
printf '\r    构建结束，日志共 %s 行\n' "$(wc -l < "$LOG" 2>/dev/null || echo 0)"

if ! grep -qE 'Container .*(Started|Running|Healthy|Exited)' "$LOG"; then
  echo
  echo "!! 看起来没有容器成功启动，最后 30 行日志：" >&2
  tail -30 "$LOG" >&2
  exit 1
fi

echo
echo "==> [4/4] 等待后端健康检查（最多 180 秒）"
for _ in $(seq 1 36); do
  if curl -fsS -m 3 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
    echo
    echo "==> 健康检查通过"
    curl -s http://127.0.0.1:8080/actuator/health
    echo
    echo
    docker compose ps
    echo
    echo "升级完成。别忘了："
    echo "  - 手机 App 若也有改动，需要重新打包安装"
    echo "  - 实际传一首歌、播放一次，确认媒体链路正常"
    exit 0
  fi
  sleep 5
done

echo
echo "!! 健康检查超时（3 分钟）" >&2
echo "!! 看应用日志：" >&2
echo "   docker compose logs --tail=80 server" >&2
echo "!! 回滚方法见 OPS.md 第 4 节" >&2
exit 1
