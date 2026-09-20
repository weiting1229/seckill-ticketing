# OCI 正式站分階段壓測計畫(階段 1–4)

> 建立:2026-09-15|狀態:**階段 1 進行中**(1-0、1-1 完成),階段 2–4 待設計決策
>
> 本文是 `HANDOFF.md`「待辦:找下游真實承載上限」那張階段表的**展開版**:每個階段要回答什麼、
> 怎麼跑、看哪些指標、怎麼判定、什麼時候喊停。HANDOFF 只留進度與連結。
>
> 背景(已結案,不重查):
> - 根因與證據:[`load-test/long-tail-latency-investigation.md`](../../load-test/long-tail-latency-investigation.md)
> - 本機四輪數據:[`docs/load-test-report.md`](../load-test-report.md) §10–13
> - 決策摘要:[`docs/adr/0008-CD上線與k6壓測(M7).md`](../adr/0008-CD上線與k6壓測(M7).md) §10–12
> - k6 腳本用法與環境切換:[`load-test/README.md`](../../load-test/README.md)

---

## 0. 總目標與各階段關係

最終要回答:**`seckill:rl:global` 這把全站唯一的限流 key 要不要拆成 sharded counter、拆幾片。**

```
階段 1  真實限流設定下,根因在 OCI 硬體上重不重現?  ──否──▶ 重新評估階段 2–4 的必要性
          │是
          ▼
階段 2  拿掉限流,下游(DB / MQ / 建單)真實承載上限是多少?  ─┐
階段 3  單一 Redis 節點能撐多少限流 CAS 併發?               ─┤
          ▼                                                   │
階段 4  目標流量 ÷ 單節點上限 = 至少幾片  ◀───────────────────┘
```

---

## 1. 所有階段共用的規則

### 1.1 分工(沿用既有約定,不可省略)

| 誰 | 做什麼 |
|---|---|
| **使用者** | 所有**對正式站送流量**的 k6 指令(admin 帳密是部署 secret,不進對話);開跑前後看 OCI Cost Analysis |
| **Claude** | 唯讀健檢、SSH 對帳(DB + Redis,不需要 token)、Grafana/Prometheus 判讀、清殘骸、寫結果 |

改正式站設定(環境變數、profile、重新部署)**每一次**都要先取得使用者確認。

### 1.2 每一輪的固定流程

1. **健檢**(Claude,唯讀):容器全 healthy、磁碟 < 80%、無 `LoadTest Scenario` 殘骸、DLQ 為 0
2. **重啟 backend 容器**(會中斷數秒):
   `ssh oci-seckill 'cd /opt/seckill && docker compose --env-file .env -f docker-compose.prod.yml restart backend'`
   ——方法論教訓:同一 JVM 連續跑多輪會放大異常(investigation §方法論教訓)
3. **使用者**:Cost Analysis 看一眼 → 設 `$env:ADMIN_*` → 跑 k6(`-e TARGET_ENV=prod -e CONFIRM_PROD=yes`)
   → **看橫幅確認 `PROD` 與 VU 數**,倒數 10 秒內可 Ctrl+C
4. **跑完立刻對帳**(§1.4)——訂單 15 分鐘未付款會自動 `EXPIRED`,拖久了數字會混進超時取消
5. **截存指標**(§1.5 的查詢,時間窗對齊壓測起訖)
6. **清殘骸**(§1.6)
7. **紀錄**(§1.7)→ 使用者看過數字才決定下一輪

### 1.3 喊停條件(任一成立就 Ctrl+C,不必跑完)

- k6 出現大量 `status=0`(連線層失敗)——量到的會是測試端而不是系統(參考 run11 本機埠耗盡)
- 出現 **5xx** 或 backend 容器重啟 / unhealthy
- `seckill.order.dlq` 有訊息
- Cost Analysis / OCI Budgets 告警出現任何非零花費
- 主機 CPU 持續 > 95% 超過 1 分鐘**且**不是這一輪要量的東西

### 1.4 對帳(SSH,不需 admin token)

邏輯等同 `ReconcileService`:`redis 庫存 == db 剩餘`、`db 已售 == 有效訂單(PAID/PENDING_PAYMENT)`、
`db 剩餘 == 總量 + stock_logs 淨變動`。`ticketTypeId` 取自 k6 `setup()` 印出的 `[setup] ... ticketTypeId=`。

```bash
TT=<ticketTypeId>
ssh oci-seckill "docker exec seckill-postgres psql -U seckill -d seckill -tAc \"
  select t.total_stock, t.stock_remaining,
         (select count(*) from orders o where o.ticket_type_id=t.id and o.status in ('PAID','PENDING_PAYMENT')),
         (select coalesce(sum(delta),0) from stock_logs s where s.ticket_type_id=t.id)
  from ticket_types t where t.id=$TT\";
  docker exec seckill-redis sh -c 'redis-cli \${REDIS_PASSWORD:+-a \"\$REDIS_PASSWORD\"} --no-auth-warning get seckill:stock:$TT'"
```

### 1.5 要截存的 Prometheus 查詢

在 Grafana(SSH tunnel 後 `http://localhost:13000`)的 **Explore** 查;限流計時器不在任何 dashboard 上。

| 看什麼 | PromQL |
|---|---|
| 搶購 HTTP p99 | `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/v1/seckill/purchase"}[30s])))` |
| **限流各層 p99**(核心指標) | `histogram_quantile(0.99, sum by (le, layer) (rate(seckill_ratelimit_check_duration_seconds_bucket[30s])))` |
| 搶購狀態碼分布 | `sum by (status) (rate(http_server_requests_seconds_count{uri="/api/v1/seckill/purchase"}[30s]))` |
| 主機 CPU | `100 - avg(rate(node_cpu_seconds_total{mode="idle"}[30s])) * 100` |
| DB 連線池排隊 | `hikaricp_connections_pending` |
| MQ 積壓 | `sum(rabbitmq_queue_messages_ready{queue="seckill.order.queue"})` |
| 建單耗時 p99 | `histogram_quantile(0.99, sum by (le) (rate(seckill_order_create_duration_seconds_bucket[30s])))` |

k6 端同時匯出 HTML 報告,檔名帶 `prod`:
`$env:K6_WEB_DASHBOARD="true"; $env:K6_WEB_DASHBOARD_EXPORT="load-test/reports/report-prod-<情境>-<yyyyMMdd-HHmmss>.html"`
(這兩個不是祕密,但同樣會殘留在視窗裡,跑完一起 `Remove-Item`)

### 1.6 清殘骸

- **每輪都清**:`LoadTest Scenario` 活動、票種、訂單、stock_logs(`scripts/demo-seed/cleanup-loadtest-residue.sql`,
  `docker exec -i`,跑完 `SELECT count(*)` 複查)+ 對應的 `seckill:stock:*` / `seckill:bought:*` Redis key
  (SQL 不會清 Redis)
- **不清**:`lt_user_*` 壓測帳號(2026-09-15 使用者決定保留,後續階段沿用)
- ⚠️ 情境腳本的 `setup()` 會**發佈**活動,壓測期間與清除前,`LoadTest Scenario A/B` 會出現在正式站首頁

### 1.7 紀錄位置

結果寫進 `docs/load-test-report.md` **新增一節「OCI 正式站壓測」**(不改既有章節),每輪一個小節:
參數、k6 摘要(自訂 Counter + purchase p99)、§1.5 各查詢的峰值與時序、對帳四個數字、結論。
本文只更新狀態行與各階段的「結果」欄位。

---

## 2. 階段 1:真實限流設定下,根因是否在 OCI 重現

### 2.1 要回答的問題

本機坐實的根因——所有搶購請求在 `tryGlobal` 共用 `seckill:rl:global`、在 Redis 單執行緒佇列排隊——
在 **OCI A1(4C/24G ARM、原生 Linux、Caddy 反代、k6 從外部網路打入)** 上會不會重現。

### 2.2 前提與已確認的事實(2026-09-15 唯讀檢查)

| 項目 | 現況 | 對本階段的意義 |
|---|---|---|
| 正式站限流 | `SECKILL_RL_GLOBAL=3000` / `IP=10` / `USER=2` / `TOKEN_USER=5`(compose 預設值,`.env` 未覆寫) | **維持原樣**(2026-09-15 使用者決定) |
| 攔截器順序 | `tryGlobal()` → `tryIp()` → `tryUser()`(`SeckillRateLimitInterceptor`) | 即使請求最後被 IP 層 429,**也已經先打過 global key** —— 根因路徑一定會被觸發 |
| 帳號池 | 30 個(`lt_user_00000`–`00029`,階段 0 殘留) | 需補到 3000 |
| DB | `max_connections=100`,Hikari pool 20 | 不會撞 |
| 磁碟 | 清舊映像後 71%(剩 8.9G) | 足夠 |
| 階段 0 數字 | repo 內**無紀錄** | 無對照組,本階段 1-2 就是新基準 |

### 2.3 預期會看到的東西(不是失敗)

- **絕大多數 purchase 回 429**:單機 k6 = 單一 IP,IP 層 10/s。每秒只成交約 10 張,庫存賣不完。
  本機四輪都有調高限流所以**沒有 429**,兩邊的成交數與狀態碼分布**不可直接比較**
- 腳本 threshold `http_req_duration{name:purchase} p(99)<300` 可能標紅——資訊性,不代表壓測失敗
- `seckill_ratelimited` Counter 會很高

### 2.4 執行步驟

| 輪 | 內容 | 指令參數(皆加 `-e TARGET_ENV=prod -e CONFIRM_PROD=yes`) | 看完決定 |
|---|---|---|---|
| 1-0 | 補帳號池 | `load-test/setup-users.js` + `-e USER_POOL_SIZE=3000 -e SETUP_VUS=20` | 註冊是 BCrypt 吃 CPU,VU 刻意壓低;冪等可重跑。`checks_succeeded` 要 100% |
| 1-1 | 情境 A 小規模基準 | `scenario-a-flash-sale.js` + `-e SCENARIO_A_VUS=200 -e SCENARIO_A_HOLD_SECONDS=60 -e SCENARIO_A_STOCK=100` | 看 §2.5 的「登入污染」與 CPU,決定能否上 2000 |
| 1-2 | 情境 A 正式規模 | `scenario-a-flash-sale.js`(預設 2000 VU / ramp 30s / hold 120s / 庫存 1000),**預設已是集中預登入**,setup 會多花約 1 分鐘 | 根因判定(§2.6) |
| 1-3 | 情境 B 正式規模 | `scenario-b-sustained.js`(預設 1000 VU / 10 分鐘 / 庫存 1500 / 帳號 2000 起) | 根因判定;本機長尾延遲主要是在這個情境出現 |

每一輪都走 §1.2 的完整流程(含重啟 backend、立刻對帳、清殘骸)。

### 2.5 要特別盯的干擾因素:登入 BCrypt 污染

情境 A 每個 VU 開頭都 `POST /auth/login`,BCrypt(10) 在 4 核上每秒只驗得了數十次。
30 秒 ramp 到 2000 VU ≈ 每秒 66 次登入,**CPU 可能被登入吃滿,連帶拖高 purchase 延遲**,
這樣就分不清「global key 排隊」還是「CPU 飽和」。

- 1-1 時對照 `http_server_requests_seconds{uri="/api/v1/auth/login"}` 的 p99 與主機 CPU
- 若登入明顯吃滿 CPU:**先停下來討論**是否把腳本改成 `setup()` 內集中預登入(access token 15 分鐘,
  足夠涵蓋情境 A)。改了會失去與本機歷史數字的可比性,是需要使用者確認的取捨
- ✅ **2026-09-20 使用者拍板:1-2 起改用集中預登入。** 1-0 量到的 BCrypt 上限(36–40 次/秒)
  對上 1-2 的 ramp 需求(約 66 次/秒),登入必定先撞天花板,不必再花一輪去確認。實作見
  `load-test/lib/config.js` 的 `preLoginTokens()` 與 `load-test/README.md`「集中預登入」。
  三個要盯的副作用:token TTL 15 分鐘(setup 會印剩餘秒數)、k6 會把 setup 回傳值複製給每個 VU
  (2000 token 約多吃 1 GB 記憶體)、與本機歷史四輪不可直接比較(對照組設
  `SCENARIO_A_PRELOGIN=false`)
- 區分方法:限流 global 層 p99 是**端到端含 Redis 排隊**的計時,若它維持毫秒級而 HTTP p99 很高,
  延遲在別處(CPU / 登入 / Caddy),不是根因重現

### 2.6 判定標準

| 觀察 | 判定 | 下一步 |
|---|---|---|
| purchase HTTP p99 達**秒級**,且 `layer="global"` p99 時序**逐點貼著** HTTP p99(比照 investigation §證據 1 的表),`user`/`token_user` 層維持毫秒級 | ✅ **根因在 OCI 重現** | 進階段 2 設計決策(§3.2) |
| HTTP p99 與 global 層 p99 都維持毫秒級 | ❌ 未重現 | 本機現象可能是測試拓樸造成(k6 與 Redis 同機搶資源),**停下來重新評估階段 2–4 是否還需要** |
| HTTP p99 高,但 global 層 p99 低 | ⚠️ 延遲在別處 | 查 CPU / 登入 / Caddy / MQ,不算重現也不算排除 |
| global 層 p99 高,但只出現在開頭幾秒 | ⚠️ 部分重現 | 本機也出現過「僅開頭」與「持續整場」兩種模式(investigation §方法論教訓),**不可只憑一次下結論**,至少再跑一輪 |

### 2.7 結果

逐輪原始數據見 [`docs/load-test-report.md`](../load-test-report.md) §14。

| 輪 | 日期 | 摘要 | 判定 |
|---|---|---|---|
| 1-0 | 2026-09-15 | 帳號池補到 3000;**BCrypt 吞吐上限 ≈36–40 次/秒**,CPU 峰值 ≈100% | —(不量測) |
| 1-1 | 2026-09-15 | 200 VU:purchase p99 11–31ms、global p99 3–4ms、login p99 ≈110ms、CPU 峰值 23%;售罄 100/100、0 個 429/5xx;對帳一致 | 乾淨基準,此規模未觀察到根因(預期如此) |

---

## 3. 階段 2:拿掉限流,找下游真實承載上限

### 3.1 要回答的問題

限流器完全不介入時,DB 條件 UPDATE、RabbitMQ、建單消費者這條下游鏈能撐多少流量?
`global-capacity=3000` 這個門檻是太鬆(下游先倒)還是太緊(浪費容量)?

### 3.2 ⚠️ 開工前必須先解決:bypass 開關在 prod 無效

HANDOFF 原本寫「正式站部署啟用 `SECKILL_RL_BYPASS=true`」,**這樣做不會生效**:

- `seckill.ratelimit.bypass-enabled: ${SECKILL_RL_BYPASS:false}` **只寫在 `application.yml` 的 dev profile 區塊**
- prod profile 沒有這個 key → `RateLimiterService` 的 `@Value("${seckill.ratelimit.bypass-enabled:false}")` 永遠取預設 false
- 註解明寫「即使誤設 SECKILL_RL_BYPASS 環境變數也不會在 prod 生效」——這是**刻意的安全設計**

所以階段 2 需要先做一個**改變正式站行為的設定決策**(需使用者確認,可能要補進 ADR):

| 選項 | 做法 | 取捨 |
|---|---|---|
| A. prod profile 暫時加 key | prod 區塊加 `bypass-enabled: ${SECKILL_RL_BYPASS:false}` → CD 部署 → `.env` 設 true → 測完改回 false 並移除 key 再部署 | 最直接;但**測試期間正式站完全沒有限流**,且要部署兩次 |
| B. 只調高閾值 | 不動程式,`.env` 設 `SECKILL_RL_IP/GLOBAL/USER/TOKEN_USER=1000000` | 限流器仍在跑(global key 仍排隊),**量到的是「限流器 + 下游」而不是純下游**,與階段目標不符 |
| C. 另起一個壓測專用 profile | 新增 `loadtest` profile,啟動時疊加 `prod,loadtest` | 與 prod 設定隔離較乾淨;但要改 compose 的 `SPRING_PROFILES_ACTIVE`,同樣要部署兩次 |

### 3.3 測法要點(待 §3.2 決定後細化)

- **成交上限是 VU 數,不是庫存**(investigation run11):情境 B 每人限購一張,只調 `SCENARIO_B_STOCK` 沒用,
  必須同步調高 `SCENARIO_B_VUS` 與帳號池(可能要 5000 以上)
- 階梯式加壓(例如 1000 → 2000 → 3000 VU),每階一輪、每輪完整走 §1.2,找延遲或錯誤率的**拐點**
- 主要指標:`hikaricp_connections_pending`、`seckill.order.queue` 積壓與消化速度、建單耗時 p99、主機 CPU
- ⚠️ 單機 k6 的連線數 / 頻寬本身可能先到頂——若出現 `status=0` 大量增加,先確認是測試端瓶頸

### 3.4 結果

(待填)

---

## 4. 階段 3:量單一 Redis 節點的限流 CAS 上限

### 4.1 要回答的問題

一個 Redis 節點面對瞬間爆量的 Bucket4j CAS 檢查(`casBasedBuilder`,Lettuce),
在 OCI 上最多能撐多少併發而不讓排隊延遲失控?

### 4.2 未決定:用什麼工具量(需另外設計)

k6 本身不能直接連 Redis,且要量的是 **Redis 本身**,必須避開 HTTP / Tomcat / Caddy 的干擾:

| 選項 | 說明 | 取捨 |
|---|---|---|
| xk6-redis 擴充 | 自建含 Redis 模組的 k6 binary | 沿用 k6 生態;但要重現 Bucket4j 的 CAS 語意得自己寫 Lua,與正式程式碼不同路徑 |
| 主機上跑獨立 Java 小程式 | 在 OCI 主機的 Docker 網路內,直接用 Bucket4j + Lettuce 打同一種 key | **與正式程式碼路徑一致**;要多維護一支程式,且與正式 Redis 共用時會影響線上 |
| `redis-benchmark` | 直接壓 `EVALSHA` | 最快;但只量指令吞吐,不含 CAS 重試的語意 |

另一個待決:**對正式 Redis 量還是另起一個同規格的 Redis 容器**(避免污染線上限流狀態)。

### 4.3 結果

(待填)

---

## 5. 階段 4:算出 sharded counter 要拆幾片

### 5.1 計算

```
至少分片數 = ceil( 目標流量 ÷ 階段 3 的單節點安全上限 )
```

### 5.2 未決定:「目標流量」的定義

目前沒有定案,候選:

- 設計文件的 `global-capacity=3000`/s
- 階段 2 量到的下游承載上限(限流門檻不該高於下游能吃的量)
- 另訂一個業務目標

需要使用者決定;並注意若「目標 ≤ 單節點上限」,結論可能是**不需要分片**,而是調整參數即可。

### 5.3 結論

(待填)
