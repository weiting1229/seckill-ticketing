#!/usr/bin/env bash
# 每日備份(設計文件第 12 節)。由 setup-server.sh 安裝的 cron 每日呼叫。
# 兩份產出:PostgreSQL dump + **海報目錄 tar(M8 新增,見下方該段的理由)**。
# 檔名沿用檔案名稱 backup-db.sh 不改,避免動到 cron 與 cd.yml 的同步路徑。
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

# ---------------------------------------------------------------------------
# 海報目錄備份(M8)
#
# **這一段是先決條件不是加分項。** 海報從 M8 起刻意脫離 git(圖片不進 repo,由匯入端點
# 寫進主機目錄),所以在這裡被打包之前,它是全系統唯一一份**沒有任何保護**的資料 ——
# 主機重建或誤刪就沒了,而重做要回 poster-forge 重跑整條生圖 / 挑圖 / 核可流程。
#
# 用 tar 而非 rsync/cp:與 dump 同一個保留天數、同一個目錄、同一條 cron,運維面只有一套規則。
# 空目錄也照打包(還沒匯入過的主機),tar 對空目錄不會失敗,而「沒有備份檔」與
# 「備份了但目錄是空的」在事後排查時是兩件不同的事。
# ---------------------------------------------------------------------------
POSTERS_DIR="${POSTERS_DIR:-$APP_DIR/posters}"
posters_out="$BACKUP_DIR/posters-${timestamp}.tar.gz"

if [[ -d "$POSTERS_DIR" ]]; then
  echo "[$(date -Is)] 開始備份海報 $POSTERS_DIR → $posters_out"
  posters_tmp="$posters_out.partial"
  # -C 進到父目錄再打包目錄名:還原時不會帶出主機的絕對路徑結構。
  if tar czf "$posters_tmp" -C "$(dirname "$POSTERS_DIR")" "$(basename "$POSTERS_DIR")"; then
    mv "$posters_tmp" "$posters_out"
    echo "[$(date -Is)] 海報備份完成($(du -h "$posters_out" | cut -f1),$(find "$POSTERS_DIR" -type f | wc -l) 個檔案)"
  else
    rm -f "$posters_tmp"
    # 不 exit:DB dump 已經成功了,不該因為海報這一段失敗就讓整條 cron 看起來全滅。
    echo "[$(date -Is)] 海報備份失敗:tar 非零退出" >&2
  fi
else
  echo "[$(date -Is)] 海報目錄不存在,略過:$POSTERS_DIR" >&2
fi

# 清理超過保留天數的舊備份
deleted="$(find "$BACKUP_DIR" -maxdepth 1 -name "${POSTGRES_DB}-*.dump" -type f -mtime "+$RETENTION_DAYS" -print -delete | wc -l)"
echo "[$(date -Is)] 清理逾 ${RETENTION_DAYS} 天舊 DB 備份:${deleted} 個"

deleted_posters="$(find "$BACKUP_DIR" -maxdepth 1 -name "posters-*.tar.gz" -type f -mtime "+$RETENTION_DAYS" -print -delete | wc -l)"
echo "[$(date -Is)] 清理逾 ${RETENTION_DAYS} 天舊海報備份:${deleted_posters} 個"
