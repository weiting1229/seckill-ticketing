# 交接文件 — M7 剩餘項目(子項 6 k6 壓測 + 子項 7 收尾)

> 產出於 2026-07-18。**此文件可直接貼給下個 session 當任務書。**
> 使用者將先處理前端 UI,之後回來由新 session 依本文件完成壓測與收尾。
> 本檔與 `docs/handover-M7.md`(M7 開工時的原始任務書)並存;原始檔的「API 契約速查」「壓測坑」仍有效,本檔會重述重點但請兩份對照。

先完整閱讀:`docs/design/01-系統設計文件-搶票系統MVP.md` 第 **11、13、14 節**(監控指標、壓測計畫、驗收);`docs/handover-M7.md`(原始任務書,尤其第七節 API 契約、第六節壓測坑);`CLAUDE.md`;`docs/adr/0004`(§4 限流與取 IP、§10 待壓測調整項)、`docs/adr/0007`(告警閾值待校準)。使用者 auto-memory 有 `seckill-project-status`(完整進度與本 session 所有踩坑)。

**每完成一個可獨立驗證的子項就停下來讓使用者 review 並 commit,再繼續下一個。** 回答用繁體中文;Conventional Commits;commit 訊息結尾一律加
`Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`(repo 慣例,使用者明確指示維持,即使實際模型不是 Fable 5)。

---

## 一、現況:M7 子項 1–5 全部完成,系統已上線

**正式網址:https://tixco.kozow.com**(外部實測 HTTPS 200、合法 Let's Encrypt 憑證、前端「搶票系統」+ 後端 API 皆通、HTTP/3 啟用)。

main HEAD = `f903427`,已 push、CI/CD 綠。M7 已完成的 commit:

| commit | 子項 |
|---|---|
| `1187e1b` | 修 flaky 測試(JwtServiceTest 竄改簽章首字元) |
| `c79cfdb` | 子項1 backend/frontend Dockerfile(arm64、非 root) |
| `050460f` | 子項2 docker-compose.prod.yml(11 服務) |
| `7cbf260` | 子項3 Caddyfile |
| `9a5b2f1` `5689d63` | 子項4 setup-server.sh + backup-db.sh(Oracle Linux) |
| `dbb4b48` `e80e637` `ec81f3c` `a4708ff` `f903427` | 子項5 cd.yml + 4 個部署修正 |

**部署環境事實(與原始交接文件的假設有出入,務必知道):**
- OCI 主機是 **Oracle Linux 9.7**(SSH 帳號 `opc`,非 ubuntu),ARM64,公開 IP `132.145.121.46`,應用目錄 `/opt/seckill`。dnf/yum;防火牆 firewalld。
- GitHub Secrets 已齊:`OCI_PUBLIC_IP`/`OCI_SSH_USER`/`OCI_SSH_KEY`、`POSTGRES_PASSWORD`/`REDIS_PASSWORD`/`RABBITMQ_PASSWORD`/`JWT_SECRET`/`GRAFANA_ADMIN_PASSWORD`、`SITE_ADDRESS=tixco.kozow.com`、`ACME_EMAIL`。
- 重新部署:`gh workflow run cd.yml -R weiting1229/seckill-ticketing`(workflow_dispatch,跑 main HEAD);或 push main 後 CI 綠自動觸發(workflow_run)。
- gh CLI:在 Bash tool 內用 `"/c/Program Files/GitHub CLI/gh.exe"`(已登入),不要用 PowerShell `&`。

---

## 二、剩餘任務

### 子項 6:k6 壓測(先本機跑通,設計第 13 節)

**壓測打哪裡:使用者決策「先本機跑通,之後再決定是否打正式站」。** 正式站是 OCI A1 只有 4C/24G,2000 VU 很可能打爆,故先對本機 dev 後端壓,驗證腳本正確性與驗收指標;要打正式站再降 VU 並另議。

**腳本置於 `load-test/`**(目前只有 6 行 placeholder README,腳本要從零寫):
- **setup**:批量註冊 **5000** 測試用戶並各自取得 token。
- **情境 A「開賣瞬間」**:30 秒 ramp 至 **2000 VU**,全部打同一票種(**庫存 1000**),持續 2 分鐘。
- **情境 B「持續高壓」**:**1000 VU** 恆定 10 分鐘,混合流量(70% 搶購、20% 查活動、10% 輪詢結果)。
- 全程開著 Grafana(M6 四個 dashboard)與 Prometheus `/alerts` 觀察,**截圖存證**。

#### 🔥 壓測最大的坑:限流會把 k6 掐死(務必先處理)
`SeckillPurchaseService` 限流三層(ADR 0004 §4):**全域 3000 QPS、單 IP 10/s、單用戶 2/s**;領 token 端點另有單用戶 5/s。
- **k6 從單機打 → 所有 VU 同一來源 IP → 單 IP 10/s 會把整場壓測卡在 10 QPS!**
  解法:壓測時以 env 調高 `SECKILL_RL_IP`(閾值皆可 env 覆寫:`SECKILL_RL_GLOBAL`/`SECKILL_RL_USER`/`SECKILL_RL_IP`/`SECKILL_RL_TOKEN_USER`)。啟動 dev 後端前設大值,例如 `export SECKILL_RL_IP=1000000 SECKILL_RL_GLOBAL=1000000`。單用戶 2/s 也要視情境調(見下)。
  (另一解法是每 VU 帶不同 `X-Forwarded-For`,取 IP 邏輯取 XFF 最左值——但正式站 Caddy 會覆寫掉,見下方 XFF 說明;本機無 Caddy 可用此法。建議直接調 env 較單純。)
- **單用戶 2/s + 每人限購 1 張** → 情境 A 的 2000 VU 必須是 **2000 個不同帳號**(setup 的 5000 用戶就是為此)。每 VU 綁定唯一帳號與 token。

#### k6 如何執行(本機 Windows)
k6 尚未安裝。兩個選項:
- **原生安裝(建議,結果最準)**:`winget install k6 --source winget` 或到 k6.io 下載 Windows binary。直接 `k6 run load-test/xxx.js` 打 `http://localhost:8080`。
- **Docker**:`docker run --rm -i grafana/k6 run - <script.js`;打本機後端要用 `host.docker.internal:8080`(Docker Desktop 支援)。容器網路會略增延遲,影響 p99 量測,故原生較準。

#### 本機環境啟動(壓測前置;prod compose 只跑在 OCI,本機是 dev)
> dev 與 prod compose 共用同一組 `container_name`,本機兩套不能並存;但本機目前沒有 prod stack(只在 OCI),可直接起 dev。dev/monitoring 的 named volume 都還在(`seckill-dev_*`/`seckill-monitoring_*`),資料保留。
```
# repo 根,務必帶 --env-file .env
docker compose --env-file .env -f infra/docker-compose.dev.yml up -d          # 中介軟體
docker compose --env-file .env -f infra/docker-compose.monitoring.yml up -d   # 監控(觀察壓測)
# 後端(dev,本機非容器):
cd backend
export JAVA_HOME="/c/Users/USER/.jdks/temurin-25/jdk-25.0.3+9"
set -a && . ../.env && set +a
export SECKILL_ADMIN_USERNAME=admin_local SECKILL_ADMIN_PASSWORD=AdminLocal123
export SECKILL_RL_IP=1000000 SECKILL_RL_GLOBAL=1000000   # 解除限流以利壓測(見上方坑)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev     # 約 12s 啟動
```
- admin 帳號 `admin_local / AdminLocal123`(env 建立、DB 持久)。註冊 API 一律建 USER。
- Docker Desktop daemon 沒起時:`Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"`,等 10–30s(有時 1–3 分鐘);`docker info --format '{{.ServerVersion}}'` 回版本才算好。
- Git Bash 內 Windows 商店版 python 壞掉(exit 49),API/驗證腳本用 `node xxx.mjs` + fetch。
- Grafana `http://localhost:3000`(dev 開匿名可直接看)、Prometheus `http://localhost:9090`(`/targets`、`/alerts`)。

#### 壓測前要用 admin API 建「當前開賣中」的票種
舊種子資料時間窗多已過期。壓測要自己用 admin API 建一個「已 warmup(ONLINE)且在開賣時間窗內、庫存 1000」的票種,拿到 ticketTypeId 給 k6。流程:登入 admin → 建 event → 建 ticket-type(seckillStart 設過去、seckillEnd 設未來、totalStock=1000)→ warmup。

### 子項 7:收尾

1. **`docs/load-test-report.md`**:壓測結論、調優過程、Grafana 截圖。
2. **依壓測結果校準**:
   - `infra/prometheus/alert-rules.yml` 的 9 條告警閾值(M6 為設計初值,ADR 0007 §5 明列待 M7 校準)。
   - 限流 / 消費併發 / confirm 逾時(ADR 0004 §10 列為待壓測調整:限流閾值、`listener.simple.concurrency=4`、publisher confirm 逾時 5s)。
3. **ADR 0008**(M7 決策記錄,格式:背景/決策/理由/取捨)。**內容見下方第四節,已備妥**。
4. push 後用 `gh` 確認 CI 綠;若動 alert-rules.yml 可對 OCI 重新部署驗證。

---

## 三、驗收(對齊設計第 13、14 節)

- **零超賣**:壓測後跑對帳 API(`GET /api/v1/admin/ticket-types/{id}/reconcile`),`total_stock - stock_remaining` = 有效訂單數(PAID + PENDING)= Redis 扣減量,**三方一致**。
- **零重複**:同一 user 對同一票種訂單數 ≤ 1。
- 搶購接口 **p99 < 300ms**(Redis 路徑,不含非同步落庫)。
- 流量結束後 **2 分鐘內佇列積壓消化完畢**。
- 全程 Grafana 截圖,結論寫入 `docs/load-test-report.md`。
- (正式站驗收:HTTPS 網址可訪問——已達成,https://tixco.kozow.com。)

---

## 四、ADR 0008 要記的內容(本 session 的 M7 決策,已備妥可直接寫)

> 格式照其他 ADR:背景 / 決策 / 理由 / 取捨。以下逐項是本 session 實際做的決策與踩坑,壓測部分待子項 6 完成後補「壓測發現與調優」。

1. **部署拓撲**:OCI Ampere A1(ARM64)、單機 docker-compose.prod.yml 11 服務(設計第12節減 alertmanager,被動監控);Caddy 為對外唯一入口(80/443),backend 與中介軟體不對外開 port;Prometheus/Grafana 只綁 127.0.0.1,經 SSH tunnel 觀察。

2. **主機是 Oracle Linux 9.7 而非交接假設的 Ubuntu**:`opc` 帳號、dnf/yum、firewalld(nftables)。`setup-server.sh` 改為自動偵測套件管理器(dnf/apt)與防火牆(firewalld/iptables)。firewalld 分支用 `firewall-cmd` 開 http/https/443udp;iptables 殘留規則清除。OCI 雲端側 Security List 另需在主控台開 ingress 80/443(主機腳本碰不到)。

3. **映像/建置決策**:多階段;建置階段標 `--platform=$BUILDPLATFORM`(jar/Vite 產物與平台無關,固定原生 amd64 建置避免 QEMU 拖慢 Maven,實測 5s),只有執行層是 arm64;backend 非 root uid 1001、frontend 用 `nginx-unprivileged`(uid 101);healthcheck 用 bash `/dev/tcp`(執行層無 curl/wget/nc,不裝以縮小 Trivy 面),須 `["CMD","bash",...]` 不能 CMD-SHELL(dash 不支援 /dev/tcp)。

4. **基底映像 CVE 從源頭清除(非 .trivyignore 掩蓋)**:backend 的 `eclipse-temurin:25-jre` 內建 Canonical Pebble(`/usr/bin/pebble`,~10MB Go 檔)帶 5 HIGH(golang.org/x/net、stdlib);我們用 java -jar ENTRYPOINT 不經它 → Dockerfile `rm -f`。frontend 的 `nginx-unprivileged:1.27-alpine` 基底套件(openssl/c-ares/expat)33 HIGH + 2 CRITICAL → 建置時 `apk upgrade`。修後皆 0 HIGH/CRITICAL。

5. **XFF 覆寫移除(更正 ADR 0004 §4)**:ADR 0004 §4 建議 Caddy 寫 `header_up X-Forwarded-For {remote_host}` 防偽造。實測 Caddy 2.9 預設即採 trusted_proxies 信任模型、丟棄不可信 XFF,該行為 no-op 且 Caddy 發 Unnecessary 警告 → 移除。**警告:日後若設 trusted_proxies 或在 Caddy 前置 CDN,真實 client IP 來源會變,必須重檢限流取 IP 邏輯(ClientRequestInfo 取 XFF 最左值),否則限流可能失效。**

6. **Trivy 掃描取捨**:(a) `ignore-unfixed=true` 只擋有修補的 CVE(無修補者擋了無從修、永久卡部署);(b) `--scanners vuln` 關 secret 掃描;(c) 用 `aquasec/trivy:0.65.0` Docker 映像而非 `aquasecurity/trivy-action`——後者以 install.sh 下載 trivy 執行檔在 runner 上常 exit 1(首次部署即中);(d) `--platform linux/arm64`——映像 arm64-only,amd64 runner 遠端拉取預設找 amd64 會失敗;(e) `TRIVY_USERNAME/PASSWORD` 認證 ghcr.io 拉私有映像與 DB。

7. **CD 管線**:workflow_run(CI 於 main 綠後自動觸發)+ workflow_dispatch(手動);映像維持私有,主機以內建 GITHUB_TOKEN 登入 GHCR 拉取;prod .env 由 GitHub Secrets 以 printf %s 逐行產生(含特殊字元亦安全、不進 log)、主機上 600 權限;infra/ 以 tar 串流過 SSH 同步(免 rsync);每次部署 image prune。ACME_EMAIL 必填(Caddy email 指令留空會解析失敗)。略過設計第12節第5步 Telegram 通知(使用者決策)。

8. **網域**:免費 DDNS(Dynu/DuckDNS)只給自家網域的子網域,不給頂級 `.com`。正式站用 Dynu 免費子網域 `tixco.kozow.com`(A record 指 132.145.121.46)。(使用者曾誤用他人求售的 premium `tixgo.com`。)

9. **壓測發現與調優**(待子項 6 完成後補):限流/消費併發/confirm 逾時的最終值、告警閾值校準結果、零超賣/零重複/p99 驗收數據。

---

## 五、API 契約速查(寫 k6 用;皆已實測。詳見 handover-M7.md 第七節)

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

- 統一回應 `{code, message, data}`,`code=0` 成功;3xxx 搶購錯誤(3005 售罄 / 3006 重複 / 3007 無效 token / 3004 限流 429)。
- **票種必須「已 warmup(ONLINE)且在開賣時間窗內」**才能領 token,否則 3002/3001。壓測前用 admin API 建當前開賣中的票種。

---

## 六、參考

- 原始任務書:`docs/handover-M7.md`(第六節壓測坑、第七節 API、第八節 memory 提示)。
- 設計:`docs/design/01-系統設計文件-搶票系統MVP.md`(第 11 監控、13 壓測、14 里程碑)。
- ADR:`0004`(搶購/限流/取 IP/待壓測項)、`0005`(訂單生命週期)、`0007`(監控/告警閾值待校準)。M7 收尾要新增 `0008`(內容見上方第四節)。
- runbook:`docs/runbook.md`(壓測時對照 9 條告警的意義與排查)。
- memory:`seckill-project-status`(完整進度、工具鏈路徑、本 session 所有踩坑細節、E2E 啟動小抄)。
