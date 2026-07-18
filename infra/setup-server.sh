#!/usr/bin/env bash
# ============================================================================
# OCI 主機一次性初始化(M7,設計文件第 12 節)。
#
# 目標主機:OCI Ampere A1(ARM64)、Ubuntu。首次上線時以 root(或 sudo)執行一次:
#   sudo APP_USER=ubuntu bash setup-server.sh
#
# 本腳本做四件事,全部「可重複執行」(idempotent):重跑不會壞事、也不會重複疊加。
#   1. 安裝 Docker Engine + compose plugin,並讓部署帳號免 sudo 用 docker
#   2. 主機防火牆只放行 80/443(SSH 既有規則不動)
#   3. 建好應用目錄 /opt/seckill(compose 與設定檔之後由 cd.yml 同步進來)
#   4. 安裝每日 pg_dump 備份的 cron
#
# 【重要,無法從主機內完成的部分】OCI 有「雲端側」防火牆(VCN Security List / NSG),
# 與主機防火牆是兩層,各自獨立。本腳本只能處理主機這層;你必須另外在 OCI 主控台把
# ingress 80/443 打開,否則封包在到達主機前就被雲端擋掉。
# ============================================================================
set -euo pipefail

# 部署帳號:cd.yml 會以這個帳號 SSH 進來跑 docker compose。預設沿用呼叫 sudo 的原帳號。
APP_USER="${APP_USER:-${SUDO_USER:-ubuntu}}"
APP_DIR="${APP_DIR:-/opt/seckill}"

log() { echo "[setup] $*"; }

if [[ "$(id -u)" -ne 0 ]]; then
  echo "請以 root 或 sudo 執行:sudo APP_USER=$APP_USER bash $0" >&2
  exit 1
fi

if ! id "$APP_USER" &>/dev/null; then
  echo "部署帳號 '$APP_USER' 不存在,請確認 OCI_SSH_USER 對應的帳號名稱後以 APP_USER=<帳號> 重跑" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 1. Docker Engine(官方 apt repo;非 Docker Desktop)
# ---------------------------------------------------------------------------
if command -v docker &>/dev/null; then
  log "Docker 已安裝($(docker --version)),略過安裝"
else
  log "安裝 Docker Engine…"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y
  apt-get install -y ca-certificates curl gnupg
  install -m 0755 -d /etc/apt/keyrings
  # 官方 GPG 金鑰(可重複執行:--yes 覆寫既有)
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor --yes -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg
  # arch 用 dpkg 偵測(A1 為 arm64),codename 用 os-release
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -y
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  log "Docker 安裝完成:$(docker --version)"
fi

systemctl enable --now docker

# 讓部署帳號免 sudo 執行 docker(cd.yml 的 SSH 動作需要)
if id -nG "$APP_USER" | tr ' ' '\n' | grep -qx docker; then
  log "$APP_USER 已在 docker 群組"
else
  usermod -aG docker "$APP_USER"
  log "已將 $APP_USER 加入 docker 群組(該帳號需重新登入 SSH 才生效)"
fi

# ---------------------------------------------------------------------------
# 2. 主機防火牆:只放行 80/443(含 HTTP/3 的 443/udp);不動 SSH
#
# OCI 的 Ubuntu 映像預設帶一組 iptables 規則,INPUT 鏈末端通常有一條 REJECT 把
# 其餘流量擋掉。這裡把 80/443 的 ACCEPT 規則「插到 REJECT 之前」,並以 -C 檢查
# 避免重複插入(idempotent)。刻意不用 ufw:OCI 映像未啟用 ufw,直接動 iptables
# 與映像既有規則同一套,避免兩套防火牆打架。
# ---------------------------------------------------------------------------
ensure_accept_rule() {
  local proto="$1" port="$2"
  if iptables -C INPUT -p "$proto" --dport "$port" -j ACCEPT 2>/dev/null; then
    log "iptables 已放行 ${proto}/${port}"
  else
    # 插在 INPUT 第一條 REJECT/DROP 之前;找不到就 append 到最後
    local rej_line
    rej_line="$(iptables -L INPUT --line-numbers -n | awk '/REJECT|DROP/ {print $1; exit}')"
    if [[ -n "${rej_line:-}" ]]; then
      iptables -I INPUT "$rej_line" -p "$proto" --dport "$port" -j ACCEPT
    else
      iptables -A INPUT -p "$proto" --dport "$port" -j ACCEPT
    fi
    log "iptables 放行 ${proto}/${port}"
  fi
}
if command -v iptables &>/dev/null; then
  ensure_accept_rule tcp 80
  ensure_accept_rule tcp 443
  ensure_accept_rule udp 443
  # 持久化,重開機後仍生效
  if ! command -v netfilter-persistent &>/dev/null; then
    DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent
  fi
  netfilter-persistent save
  log "iptables 規則已持久化"
else
  log "找不到 iptables,略過主機防火牆設定(請確認主機防火牆狀態)"
fi

# ---------------------------------------------------------------------------
# 3. 應用目錄
# ---------------------------------------------------------------------------
mkdir -p "$APP_DIR" "$APP_DIR/backups"
chown -R "$APP_USER":"$APP_USER" "$APP_DIR"
log "應用目錄就緒:$APP_DIR(compose 與設定檔由 cd.yml 同步進來;.env 由部署流程寫入)"

# ---------------------------------------------------------------------------
# 4. 每日 pg_dump 備份 cron
#
# 備份邏輯在受版控的 infra/backup-db.sh(隨 cd.yml 同步到 $APP_DIR/backup-db.sh);
# 這裡只裝一條 cron。每日 03:30 執行,輸出附加到 log 便於事後查。
# ---------------------------------------------------------------------------
cat > /etc/cron.d/seckill-db-backup <<EOF
# 搶票系統每日 DB 備份(setup-server.sh 安裝)。時間為主機時區。
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
30 3 * * * $APP_USER APP_DIR=$APP_DIR bash $APP_DIR/backup-db.sh >> $APP_DIR/backups/backup.log 2>&1
EOF
chmod 0644 /etc/cron.d/seckill-db-backup
log "已安裝每日備份 cron(每日 03:30 → $APP_DIR/backups)"

log "完成。後續:於 OCI 主控台開 ingress 80/443;部署由 .github/workflows/cd.yml 接手。"
