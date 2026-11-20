#!/usr/bin/env bash
# SelfMusic 服务端运维脚本（不懂 Docker 也能用）
#
# 用法：
#   ./deploy.sh up        首次部署 / 改完配置后重建并启动
#   ./deploy.sh ps        看各容器是否在跑
#   ./deploy.sh logs      看后端实时日志（Ctrl+C 退出，不会停服务）
#   ./deploy.sh restart   重启后端
#   ./deploy.sh down      停止全部（数据保留）
#   ./deploy.sh update    拉代码 + 重建 + 启动（升级用）
#   ./deploy.sh backup    备份数据库和 MinIO 文件到 ./backups
#   ./deploy.sh health    检查后端健康状态
#
# 第一次用先给执行权限：chmod +x deploy.sh

set -euo pipefail

cd "$(dirname "$0")"

COMPOSE="docker compose"

need_env() {
  if [ ! -f .env ]; then
    echo "缺少 .env 文件。先执行：cp .env.example .env 并填写密码和密钥" >&2
    exit 1
  fi
}

case "${1:-}" in
  up)
    need_env
    $COMPOSE up -d --build
    echo
    echo "已启动。等待后端就绪后执行 ./deploy.sh health 验证。"
    ;;
  ps)
    $COMPOSE ps
    ;;
  logs)
    $COMPOSE logs -f --tail=100 server
    ;;
  restart)
    $COMPOSE restart server
    ;;
  down)
    $COMPOSE down
    echo "已停止，数据卷（数据库 + 音频文件）仍然保留。"
    ;;
  update)
    need_env
    if [ -d .git ]; then
      git pull --ff-only
    fi
    $COMPOSE up -d --build
    $COMPOSE images
    ;;
  health)
    if curl -fsS --max-time 5 http://127.0.0.1:8080/actuator/health; then
      echo
    else
      echo "后端未就绪，看看日志：./deploy.sh logs" >&2
      exit 1
    fi
    ;;
  backup)
    need_env
    mkdir -p backups
    stamp=$(date +%Y%m%d-%H%M%S)

    echo "==> 备份 MySQL"
    $COMPOSE exec -T mysql sh -c \
      'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers "$MYSQL_DATABASE"' \
      > "backups/mysql-$stamp.sql"

    echo "==> 备份 MinIO 数据卷（音频可能很大，注意磁盘空间）"
    vol=$(docker volume ls -q --filter name=miniodata | head -n1)
    docker run --rm \
      -v "$vol":/data:ro \
      -v "$PWD/backups":/backup \
      alpine tar czf "/backup/minio-$stamp.tar.gz" -C /data .

    echo
    echo "备份完成："
    ls -lh backups/ | tail -n 3
    ;;
  *)
    sed -n '2,16p' "$0"
    exit 1
    ;;
esac
