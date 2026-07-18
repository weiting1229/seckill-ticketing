#!/usr/bin/env bash
# ============================================================================
# OCI 主機一次性初始化(M7,設計文件第 12 節)。
#
# 目標主機:OCI Ampere A1(ARM64)。首次上線時以 root(或 sudo)執行一次:
#   sudo APP_USER=<你的SSH帳號> bash setup-server.sh
#   (Oracle Linux 預設帳號為 opc;Ubuntu 為 ubuntu)
#
# 自動偵測作業系統與防火牆,支援:
#   - 套件管理器:dnf / yum(Oracle Linux、RHEL、CentOS)或 apt(Ubuntu、Debian)
#   - 防火牆:firewalld(OL/RHEL 預設)或 iptables(部分 Ubuntu 映像)
# 全部「可重複執行」(idempotent):重跑不會壞事、也不會重複疊加。
#
# 做四件事:
#   1. 安裝 Docker Engine + compose plugin,並讓部署帳號免 sudo 用 docker
#   2. 主機防火牆只放行 80/443(含 443/udp HTTP/3);SSH 既有規則不動
#   3. 建好應用目錄 /opt/seckill(compose 與設定檔之後由 cd.yml 同步進來)
#   4. 安裝每日 pg_dump 備份的 cron
#
# 【重要,無法從主機內完成的部分】OCI 有「雲端側」防火牆(VCN Security List / NSG),
# 與主機防火牆是兩層,各自獨立。本腳本只能處理主機這層;你必須另外在 OCI 主控台把
# ingress 80/443 打開,否則封包在到達主機前就被雲端擋掉。
# ============================================================================
set -euo pipefail

# 部署帳號:cd.yml 會以這個帳號 SSH 進來跑 docker compose。預設沿用呼叫 sudo 的原帳號。
APP_USER="${APP_USER:-${SUDO_USER:-opc}}"
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

# 偵測套件管理器
if command -v dnf &>/dev/null; then
  PKG=dnf
elif command -v yum &>/dev/null; then
  PKG=yum
elif command -v apt-get &>/dev/null; then
  PKG=apt
else
  echo "找不到 dnf/yum/apt-get,無法辨識套件管理器" >&2
  exit 1
fi
log "作業系統套件管理器:$PKG"

# ---------------------------------------------------------------------------
# 1. Docker Engine(官方 repo;非 Docker Desktop)
# ---------------------------------------------------------------------------
if command -v docker &>/dev/null; then
  log "Docker 已安裝($(docker --version)),略過安裝"
else
  log "安裝 Docker Engine…"
  case "$PKG" in
    apt)
      export DEBIAN_FRONTEND=noninteractive
      apt-get update -y
      apt-get install -y ca-certificates curl gnupg
      install -m 0755 -d /etc/apt/keyrings
      curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor --yes -o /etc/apt/keyrings/docker.gpg
      chmod a+r /etc/apt/keyrings/docker.gpg
      . /etc/os-release
      echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
        > /etc/apt/sources.list.d/docker.list
      apt-get update -y
      apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
      ;;
    dnf|yum)
      # Docker CE 的 CentOS repo 在 Oracle Linux / RHEL 9 通用($releasever 解析為 9)
      $PKG install -y dnf-plugins-core || $PKG install -y yum-utils
      $PKG config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo 2>/dev/null \
        || (command -v dnf &>/dev/null && dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo)
      $PKG install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
      ;;
  esac
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
# 優先用 firewalld(Oracle Linux / RHEL 預設,且走 nftables)。若主機改用純 iptables
# 才走 iptables 分支。刻意不兩套並用:firewalld 在時,直接動 iptables 會與其 nftables
# 規則表打架、且不生效(這正是本腳本第一版在 OL9 上踩到的坑)。
# ---------------------------------------------------------------------------
firewalld_running() { command -v firewall-cmd &>/dev/null && firewall-cmd --state &>/dev/null; }

if firewalld_running; then
  log "偵測到 firewalld 運行中,使用 firewall-cmd 設定"
  # 清掉本腳本第一版可能殘留在 iptables ip-filter 表的 80/443 ACCEPT(firewalld 才是真管理者,
  # 這些殘留規則多餘且易誤導)。刪除失敗(本來就沒有)不視為錯誤。
  if command -v iptables &>/dev/null; then
    for spec in "tcp 80" "tcp 443" "udp 443"; do
      set -- $spec
      while iptables -C INPUT -p "$1" --dport "$2" -j ACCEPT 2>/dev/null; do
        iptables -D INPUT -p "$1" --dport "$2" -j ACCEPT 2>/dev/null || break
        log "清除殘留 iptables 規則 $1/$2"
      done
    done
  fi
  firewall-cmd --permanent --add-service=http
  firewall-cmd --permanent --add-service=https
  firewall-cmd --permanent --add-port=443/udp   # https service 只含 443/tcp,HTTP/3 需另開 udp
  firewall-cmd --reload
  log "firewalld 放行:$(firewall-cmd --list-services 2>/dev/null) / ports $(firewall-cmd --list-ports 2>/dev/null)"
elif command -v iptables &>/dev/null; then
  log "未偵測到 firewalld,使用 iptables 設定"
  ensure_accept_rule() {
    local proto="$1" port="$2"
    if iptables -C INPUT -p "$proto" --dport "$port" -j ACCEPT 2>/dev/null; then
      log "iptables 已放行 ${proto}/${port}"
    else
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
  ensure_accept_rule tcp 80
  ensure_accept_rule tcp 443
  ensure_accept_rule udp 443
  # 持久化(依 OS 不同):Debian 系用 iptables-persistent;RHEL 系用 iptables-services
  case "$PKG" in
    apt)
      command -v netfilter-persistent &>/dev/null || DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent
      netfilter-persistent save
      ;;
    dnf|yum)
      $PKG install -y iptables-services
      iptables-save > /etc/sysconfig/iptables
      systemctl enable iptables
      ;;
  esac
  log "iptables 規則已持久化"
else
  log "找不到 firewalld 或 iptables,略過主機防火牆設定(請自行確認主機防火牆狀態)"
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
# 確保 cron 服務啟用(OL/RHEL 為 crond,Debian 系為 cron)
systemctl enable --now crond 2>/dev/null || systemctl enable --now cron 2>/dev/null || log "警告:找不到 cron 服務,備份排程可能不會執行"

cat > /etc/cron.d/seckill-db-backup <<EOF
# 搶票系統每日 DB 備份(setup-server.sh 安裝)。時間為主機時區。
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
30 3 * * * $APP_USER APP_DIR=$APP_DIR bash $APP_DIR/backup-db.sh >> $APP_DIR/backups/backup.log 2>&1
EOF
chmod 0644 /etc/cron.d/seckill-db-backup
log "已安裝每日備份 cron(每日 03:30 → $APP_DIR/backups)"

log "完成。後續:於 OCI 主控台開 ingress 80/443;部署由 .github/workflows/cd.yml 接手。"
