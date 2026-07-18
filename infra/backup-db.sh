#!/usr/bin/env bash
# 每日 PostgreSQL 備份(設計文件第 12 節)。由 setup-server.sh 安裝的 cron 每日呼叫。
#
# 設計要點:
#   - postgres 跑在容器內、且不對外開 port,故以 `docker exec` 進容器內做 pg_dump。
#     容器內經 unix socket 連本機 postgres,官方映像對本機連線預設 trust,免帶密碼
#     (祕密因此不會出現在這支腳本或 cron 記錄裡)。
#   - 用 custom format(-Fc):本身已壓縮,且支援 pg_restore 選擇性還原,優於純 SQL dump。
#   - 保留天數可由環境變數 BACKUP_RETENTION_DAYS 覆寫(預設 7 天),舊檔自動清理。
#
# 還原示例(緊急時):
#   docker exec -i seckill-postgres pg_restore -U <user> -d <db> --clean --if-exists < 某個.dump
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/seckill}"
BACKUP_DIR="${BACKUP_DIR:-$APP_DIR/backups}"
ENV_FILE="${ENV_FILE:-$APP_DIR/.env}"
CONTAINER="${POSTGRES_CONTAINER:-seckill-postgres}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-7}"

# 由 .env 取 DB 名稱與使用者(不取密碼:容器內本機連線走 trust)
if [[ -f "$ENV_FILE" ]]; then
  # 只挑需要的兩個變數,避免把整個 .env 灌進環境
  POSTGRES_USER="$(grep -E '^POSTGRES_USER=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
  POSTGRES_DB="$(grep -E '^POSTGRES_DB=' "$ENV_FILE" | tail -1 | cut -d= -f2-)"
fi
POSTGRES_USER="${POSTGRES_USER:-seckill}"
POSTGRES_DB="${POSTGRES_DB:-seckill}"

mkdir -p "$BACKUP_DIR"

timestamp="$(date +%Y%m%d-%H%M%S)"
outfile="$BACKUP_DIR/${POSTGRES_DB}-${timestamp}.dump"

echo "[$(date -Is)] 開始備份 $POSTGRES_DB → $outfile"

# 先 dump 到暫存檔,成功後才 rename 成正式檔名:避免 cron 中途被中斷而留下半截的壞備份
tmpfile="$outfile.partial"
if docker exec "$CONTAINER" pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom > "$tmpfile"; then
  mv "$tmpfile" "$outfile"
  echo "[$(date -Is)] 備份完成($(du -h "$outfile" | cut -f1))"
else
  rm -f "$tmpfile"
  echo "[$(date -Is)] 備份失敗:pg_dump 非零退出" >&2
  exit 1
fi

# 清理超過保留天數的舊備份
deleted="$(find "$BACKUP_DIR" -maxdepth 1 -name "${POSTGRES_DB}-*.dump" -type f -mtime "+$RETENTION_DAYS" -print -delete | wc -l)"
echo "[$(date -Is)] 清理逾 ${RETENTION_DAYS} 天舊備份:${deleted} 個"
