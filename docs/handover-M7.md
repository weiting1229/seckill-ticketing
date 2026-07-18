# 交接文件 — 請接續執行 M7(CD 上線 + k6 壓測)

> 產出於 2026-07-16(M6 完成當日)。**此文件可直接貼給下個 session 當任務書**。
> 本檔未 commit(untracked);用完可刪,或自行決定是否入 repo。

先完整閱讀 `docs/design/01-系統設計文件-搶票系統MVP.md` 的
**第 2、3、4、10、12、13 節**(架構、版本鎖定、目錄結構、安全、**第 12 節 CI/CD 與 prod 服務清單**、**第 13 節壓測計畫為本里程碑主軸之一**),
以及 `CLAUDE.md`、`docs/adr/0001–0007`(**0004 §4 的 XFF 信任模型**與 **0007 監控佈局**與 M7 直接相關),然後執行里程碑 **M7**。
M0–M6 已完成並 push 到 origin/main;CI 兩個 job(backend、frontend)為綠。
**每完成一個可獨立驗證的子項就停下來讓我 review 並 commit,再繼續下一個。**

---

## ⛔ 開工前必須先問我的前置條件(M7 硬阻塞,別擅自假設)

M7 是「真的部署到網路上」,以下我(使用者)不給就做不完,**請先問**:

1. **OCI A1 主機**:是否已開通?公開 IP、SSH 使用者、SSH 私鑰(要放 GitHub Secrets)。
2. **網域名稱**:Caddy 自動 Let's Encrypt **需要真實網域 A record 指向主機 IP**。沒網域就只能退階(自簽 / 純 IP + 關 TLS),需先確認。
3. **GitHub Secrets 清單**:SSH 金鑰、主機 IP/使用者、prod 的 `JWT_SECRET`、`POSTGRES_PASSWORD`、`RABBITMQ_PASSWORD`、`REDIS_PASSWORD`、`GRAFANA_ADMIN_PASSWORD`。GHCR 推送通常用內建 `GITHUB_TOKEN`(需 `packages: write` 權限)。
4. **壓測打哪裡**:打正式主機(OCI A1 只有 4C/24G,2000 VU 可能打爆)還是本機?建議先本機跑通再決定。

> 若前置條件一時不齊,**可先做不需要主機的子項**(Dockerfile、prod compose、Caddyfile、k6 腳本本機跑),把 SSH 部署留到最後。

---

## ⚠️ 使用者範圍決策(務必遵守,與設計文件原文有出入)

**我不要任何主動通知平台(Telegram / email 全部略過),只要被動觀察。** 這推翻設計文件兩處:

- **設計文件第 12 節 cd.yml 第 5 步「部署結果推 Telegram 通知」→ 略過**。部署成功/失敗看 GitHub Actions 頁即可。
- **設計文件第 12 節 prod 服務清單含 `alertmanager` → 不要裝**。M6 已決策不用 Alertmanager(見 ADR 0007 §5),告警由 Prometheus 評估、於 `/alerts` 與 Grafana 被動觀察。**prod compose 服務清單 = 原清單減去 alertmanager**。

其餘設計文件內容照做。

---

## 一、環境與工具鏈(本機 Windows,冷啟動必讀)

- 專案根:`C:\Users\USER\Documents\seckill-ticketing`(git repo,branch `main`,remote private repo `github.com/weiting1229/seckill-ticketing`;**已授權推送**)。
- **Docker Desktop**:daemon 沒起時先 `Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"`,等 10–30s(實測有時要 1–3 分鐘);`docker info --format '{{.ServerVersion}}'` 回版本才算好。
- **compose 指令務必帶 `--env-file .env`**(compose 檔在 `infra/`、`.env` 在 repo 根,不會自動載入;用了 `${VAR:?…}` 必填語法,省略會報錯)。於 repo 根執行:
  ```
  docker compose --env-file .env -f infra/docker-compose.dev.yml up -d          # 中介軟體
  docker compose --env-file .env -f infra/docker-compose.monitoring.yml up -d   # 監控(M6)
  ```
- **後端(dev 跑在本機,非容器)**:
  ```
  cd backend
  export JAVA_HOME="/c/Users/USER/.jdks/temurin-25/jdk-25.0.3+9"
  set -a && . ../.env && set +a
  export SECKILL_ADMIN_USERNAME=admin_local SECKILL_ADMIN_PASSWORD=AdminLocal123
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev     # 約 12s 啟動
  ```
  admin 帳號 `admin_local / AdminLocal123`(env 建立、DB 持久)。註冊 API 一律建 USER,admin 只能靠 env 種子。
- 前端:`cd frontend && pnpm dev`(5173)。
- **Git Bash 內 Windows 商店版 python 壞掉(exit 49)**,要跑 API/驗證腳本改用 `node xxx.mjs` + fetch。
- **CI 自查**(gh 已登入):
  ```
  "/c/Program Files/GitHub CLI/gh.exe" run list -R weiting1229/seckill-ticketing -L 5
  ```
  (在 Bash tool 內直接呼叫 `gh.exe`,不要用 PowerShell 的 `&` 呼叫運算子。)
- 回答用**繁體中文**;Conventional Commits;commit 訊息結尾加
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`(repo 既有慣例,全部 commit 都用這個)。一個子項一個 commit。

---

## 二、專案現況(M0–M6 已完成,HEAD = `1a00158`)

| 里程碑 | 狀態 | ADR |
|---|---|---|
| M0 骨架 / M1 認證+Snowflake / M2 活動票種 / M3 搶購核心 / M4 訂單生命週期 / M5 前端 / M6 監控 | 全部完成、push、CI 綠 | 0001–0007 |

**M7 需要、但目前「尚未存在」的檔案(幾乎從零開始):**

```
[無] backend/Dockerfile              [無] frontend/Dockerfile
[無] infra/caddy/Caddyfile           [無] infra/setup-server.sh
[無] infra/docker-compose.prod.yml   [無] .github/workflows/cd.yml
[無] docs/load-test-report.md
[有] load-test/README.md             ← 只有 6 行 placeholder,k6 腳本要從零寫
```

**已存在可直接沿用:** `.github/workflows/ci.yml`(backend-test + frontend-check)、`infra/docker-compose.dev.yml`、`infra/docker-compose.monitoring.yml`、`infra/prometheus/{prometheus.yml,alert-rules.yml}`、`infra/grafana/**`、`infra/rabbitmq/{enabled_plugins,rabbitmq.conf}`、`docs/runbook.md`。

---

## 三、M6 監控產出與 M7 的銜接點(重要,別重造)

M6 的監控設定**設計上就是要讓 prod 直接沿用**,搬進 prod compose 時注意:

1. **`prometheus.yml` 不用改**:backend 目標寫 `backend:8080`,dev 靠 prometheus 容器 `extra_hosts: ["backend:host-gateway"]` 解析到本機後端;**prod 同一 compose 網路,Docker DNS 直接解析到 backend 容器 → prod compose 不要加 `extra_hosts` 即可**(ADR 0007 §2)。
2. **`alert-rules.yml` 沿用**;9 條規則的閾值是設計初值,**壓測後回頭校準**(這也是 M7 的產出之一)。
3. **node-exporter**:dev 因 Docker Desktop 限制只唯讀掛 `/`;**prod(OCI Linux)要補 `pid: host` 與 `- /:/host:ro,rslave`** 取得真實主機視角(ADR 0007 §3)。
4. **Grafana**:dev 開了匿名 Viewer(僅綁 127.0.0.1);**prod 不開匿名**,admin 密碼走 Secrets。
5. **redis_exporter**:dev Redis 無密碼;**prod 要傳 `REDIS_PASSWORD`**。
6. **rabbitmq.conf(`return_per_object_metrics=true`)prod 也要掛**,否則 backlog 告警會被 `order.timeout.queue` 的合法 15 分鐘 timer 誤觸(ADR 0007 §4)。
7. **不要裝 alertmanager**(見上方使用者決策)。
8. 映像版本已 pin(prometheus v3.1.0 / grafana 11.4.0 / node v1.8.2 / postgres-exporter v0.16.0 / redis_exporter v1.66.0),prod 沿用同版本。

**backend prod profile 需要的環境變數**(`application.yml` 已定義,prod compose 要注入):
`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_USER`、`RABBITMQ_PASSWORD`、`JWT_SECRET`、`CORS_ALLOWED_ORIGINS`(prod 改正式網域)、`WORKER_ID`/`DATACENTER_ID`(單機 0)、限流 `SECKILL_RL_*`(見下方壓測坑)。

---

## 四、M7 任務拆解(子項;每項獨立可驗證,做完停下來讓我 review + commit)

### 1. Dockerfile(backend + frontend)
- **多階段建置**、執行層 backend 用 `eclipse-temurin:25-jre`、**容器內非 root 使用者**(CLAUDE.md 安全規範、設計第 10.6 節)。
- frontend:Vite build → Nginx 靜態檔。
- **必須能 build `linux/arm64`**(OCI A1 是 ARM;本機 Windows 是 x86,用 `docker buildx` + QEMU 驗證能 build 出 arm64)。
- 驗收:本機 `docker buildx build --platform linux/arm64` 兩個映像都成功;容器以非 root 跑。

### 2. `infra/docker-compose.prod.yml`(全服務組裝)
- 服務清單(設計第 12 節**減去 alertmanager**):`caddy`、`frontend`、`backend`、`postgres`、`redis`、`rabbitmq`、`prometheus`、`grafana`、`node_exporter`、`postgres_exporter`、`redis_exporter`。
- **後端與所有中介軟體不對外開 port**,只在 Docker 內網被 Caddy / Prometheus 存取(設計第 10.4 節)。
- 全部走 named volume(postgres/redis/rabbitmq/prometheus/grafana)。
- 沿用 M6 監控設定(見上方第三節注意事項)。
- 驗收:本機以 prod compose 起一套(可用假網域 / 關 TLS)全部 healthy。

### 3. `infra/caddy/Caddyfile`
- 自動 HTTPS(Let's Encrypt)、`/api` 反代到 backend、其餘給 frontend。
- **不轉發 `/actuator`**(設計第 10.7 節,actuator 只給 Docker 內網的 Prometheus)。
- **`header_up X-Forwarded-For {remote_host}` 覆寫 XFF**,否則單 IP 限流可被偽造 XFF 繞過(ADR 0004 §4 明確建議)。
- 安全 header:HSTS、X-Content-Type-Options、基本 CSP(設計第 10.4 節)。

### 4. `infra/setup-server.sh`(OCI 主機初始化)
- 裝 Docker、開防火牆(只開 80/443)、建目錄、拉 compose 等。
- 每日 `pg_dump` cron 備份至主機磁碟(設計第 12 節)。

### 5. `.github/workflows/cd.yml`
- 觸發:main merge 後(需 CI 通過)。
- 步驟:`docker buildx` 建 **linux/arm64** backend+frontend → tag = git SHA + `latest` → push **GHCR** → **Trivy 掃描,HIGH/CRITICAL 即失敗** → SSH 至 OCI(金鑰走 Secrets)`docker compose -f docker-compose.prod.yml pull && up -d` → **smoke test**(內網 `/actuator/health` 與前端首頁,非 200 即失敗)。
- **第 5 步「推 Telegram」略過**(使用者決策)。

### 6. k6 壓測(`load-test/`)
- setup:批量註冊 **5000** 測試用戶並取 token。
- **情境 A「開賣瞬間」**:30 秒 ramp 至 **2000 VU**,全部打同一票種(**庫存 1000**),持續 2 分鐘。
- **情境 B「持續高壓」**:**1000 VU** 恆定 10 分鐘,混合流量(70% 搶購、20% 查活動、10% 輪詢結果)。
- 全程開著 Grafana(M6 dashboard ①②③④)與 Prometheus `/alerts` 觀察,**截圖存證**。

### 7. 收尾
- `docs/load-test-report.md`:壓測結論、調優過程、Grafana 截圖。
- **依壓測結果校準告警閾值**(`infra/prometheus/alert-rules.yml`)與限流 / 消費併發 / confirm 逾時(ADR 0004 §10 列為待壓測調整)。
- ADR 0008(M7 決策:部署拓撲、映像/建置決策、壓測發現與調優)。
- push 後用 `gh` 確認 CI 綠。

---

## 五、驗收(對齊設計文件第 13、14 節)

- **正式網址可訪問**(HTTPS,Caddy 自動憑證)。
- **零超賣**:壓測後跑對帳 API,`total_stock - stock_remaining` = 有效訂單數(PAID + PENDING)= Redis 扣減量,**三方一致**。
- **零重複**:同一 user 對同一票種訂單數 ≤ 1。
- 搶購接口 **p99 < 300ms**(Redis 路徑,不含非同步落庫)。
- 流量結束後 **2 分鐘內佇列積壓消化完畢**。
- 全程 Grafana 截圖,結論寫入 `docs/load-test-report.md`。
- Trivy 掃描無 HIGH/CRITICAL。

---

## 六、已知坑 / 注意(血淚,務必先讀)

### 🔥 壓測最大的坑:限流會把你的 k6 掐死
`SeckillPurchaseService` 的限流分三層(ADR 0004 §4):**全域 3000 QPS、單 IP 10/s、單用戶 2/s**;領 token 端點另有單用戶 5/s。
**k6 從單一機器打 → 所有 VU 同一個來源 IP → 單 IP 10/s 會把整場壓測卡在 10 QPS!**
兩個解法(擇一,建議寫進 ADR 0008):
- 壓測時以 env 調高 `SECKILL_RL_IP`(限流閾值皆可 env 覆寫:`SECKILL_RL_GLOBAL` / `SECKILL_RL_USER` / `SECKILL_RL_IP` / `SECKILL_RL_TOKEN_USER`);或
- k6 每個 VU 帶不同的 `X-Forwarded-For`(取 IP 邏輯是**取 XFF 最左值**,見 `ClientRequestInfo`)—— 但注意這與 Caddy 覆寫 XFF 的設定衝突,打正式站時 Caddy 會蓋掉。
**另外:單用戶 2/s 限購 + 每人限購 1 張** → 情境 A 的 2000 VU 必須是 2000 個**不同**帳號(setup 的 5000 用戶就是為此)。

### 其他
- **映像必須 `linux/arm64`**(OCI A1 是 Ampere ARM),本機 x86 要用 `docker buildx`;跑錯架構部署上去起不來。
- **祕密一律走 env / GitHub Secrets**,`.env` 已在 `.gitignore`,repo 只放 `.env.example` 佔位。**絕不 commit `.env`**。
- **勿 commit** `node_modules/`、`frontend/dist/`、`.env`。
- 動到前端要先本機跑 `pnpm ci:lint`(CI 用不帶 `--fix` 的 `ci:lint`)再推。
- **CI 有一支既有 flaky 測試**:`JwtServiceTest.tamperedTokenShouldFailVerification`(竄改 JWT **末字元**;末字元低 2 bit 是 base64 padding,`a`↔`b` 只差 padding bit → 解碼後簽章不變 → 偶發不丟例外 → 斷言失敗)。**M6 push 首跑就中這個 flake、重跑即綠**。
  **建議 M7 開工前先修掉**(`fix(test):` 一個 commit),修法比照 M2 已修過的 `AuthFlowIT`:**改竄改「簽章首字元」**而非末字元。
- ADR 0004 §9 / 0007:`@SpringBootTest` 預設關 metrics export,相關測試需 `@AutoConfigureObservability`。
- **virtual thread 指標缺口**:Micrometer 內建只有平台執行緒;要真實 vthread 指標需加 `io.micrometer:micrometer-java21` 的 `VirtualThreadMetrics` binder(+1 依賴,**我還沒決定要不要加**,ADR 0007 §9)。壓測若要看 vthread 行為可再問我。
- 整合測試用 Testcontainers,**硬性需要 Docker**(多測試類共用 singleton container,見 memory)。

---

## 七、API 契約速查(寫 k6 腳本用;皆已實測)

| 端點 | Body / 回應 |
|---|---|
| `POST /api/v1/auth/register` | `{username, password}`(password 8–72 字) |
| `POST /api/v1/auth/login` | `{username, password}` → `data.accessToken`(Bearer) |
| `POST /api/v1/admin/events` | `{title, description, venue, eventTime}` → `data.id`(需 ADMIN) |
| `POST /api/v1/admin/ticket-types` | `{eventId, name, price, totalStock, seckillStart, seckillEnd}` → `data.id` |
| `POST /api/v1/admin/ticket-types/{id}/warmup` | 上線 + 寫 Redis 庫存(status → ONLINE) |
| `GET /api/v1/admin/ticket-types/{id}/reconcile` | 對帳(壓測驗收用) |
| `POST /api/v1/seckill/token` | `{ticketTypeId}` → `data.token`(60s、用後即焚) |
| `POST /api/v1/seckill/purchase` | `{ticketTypeId, token}` → `data.requestId/orderId` |
| `GET /api/v1/seckill/result/{requestId}` | QUEUING / SUCCESS(orderId)/ FAIL |

- 統一回應 `{code, message, data}`,`code=0` 為成功;3xxx 為搶購錯誤(3005 售罄 / 3006 重複 / 3007 無效 token / 3004 限流 429)。
- **票種必須「已 warmup(ONLINE)且在開賣時間窗內」**才能領 token,否則回 3002「搶購已結束」/ 3001「未開賣」。舊種子資料的時間窗多已過期,**壓測要自己用 admin API 建當前開賣中的票種**。

---

## 八、memory 參考

使用者 auto-memory 有 `seckill-project-status`(M0–M6 完成細節、工具鏈路徑、E2E 啟動小抄、admin 帳號、M6 監控產出與已知 flake)、`user-profile-taiwan-dev`。
開場會載入 `MEMORY.md` 索引,需要時讀對應檔。M6 監控決策見 `docs/adr/0007`。
