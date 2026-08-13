# load-test

k6 壓測腳本(M7 子項 6,見 `docs/design/01-系統設計文件-搶票系統MVP.md` 第 13 節、
`docs/handover-M7-loadtest.md`)。**先本機跑通,之後再決定是否打正式站**。

## 檔案

| 檔案 | 用途 |
|---|---|
| `lib/config.js` | 共用設定(BASE_URL、admin 帳密、帳號池大小、username 產生規則) |
| `setup-users.js` | 批量註冊壓測帳號(冪等,可重跑) |
| `scenario-a-flash-sale.js` | 情境 A「開賣瞬間」:30 秒 ramp 至 2000 VU,同一票種(庫存 1000),再持續 2 分鐘 |
| `scenario-b-sustained.js` | 情境 B「持續高壓」:1000 VU 恆定 10 分鐘,混合流量(70% 搶購 / 20% 查活動 / 10% 輪詢結果) |

兩個情境腳本的 `setup()` 都會**自動建立、發布、warmup 一個專用活動與票種**,不需要手動用
admin API 準備資料;每次執行都會新建一個(標題帶時間戳),方便重跑。

## 前置需求

1. **安裝 k6**(已完成,本機為 v2.2.0):`winget install k6 --source winget`,或到
   [k6.io](https://k6.io/docs/get-started/installation/) 下載。
2. **啟動中介軟體與後端**(dev profile),見 `docs/handover-M7-loadtest.md` 第 61–78 行,
   或 repo 根目錄 CLAUDE.md 旁的 runbook。
3. ⚠️ **啟動後端前務必調高限流閾值**,否則單 IP 10/s 會把整場壓測卡死(本目錄的 dry-run
   已實測驗證:預設限流下 5 個 VU、200–500ms 節奏就會打到 429):
   ```bash
   export SECKILL_RL_IP=1000000 SECKILL_RL_GLOBAL=1000000 SECKILL_RL_USER=1000000 SECKILL_RL_TOKEN_USER=1000000
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```
4. **註冊壓測帳號**(只需跑一次,重跑安全):
   ```bash
   k6 run load-test/setup-users.js
   ```
   預設註冊 5000 個帳號(`lt_user_00000`–`lt_user_04999`,密碼 `LoadTest123`),50 VU 平行送出。
   可用 `USER_POOL_SIZE` / `SETUP_VUS` 覆寫。

## 執行壓測

```bash
# 情境 A
k6 run load-test/scenario-a-flash-sale.js

# 情境 B
k6 run load-test/scenario-b-sustained.js
```

### 即時面板

需要一邊跑一邊看即時數據時,加 `K6_WEB_DASHBOARD=true`(k6 內建功能,啟動後終端機會印出
本機網址,預設 `http://127.0.0.1:5665`,瀏覽器打開即可看即時 RPS/延遲/VU 數):

```bash
K6_WEB_DASHBOARD=true k6 run load-test/scenario-a-flash-sale.js
```

想在測試結束後留一份 HTML 報告(適合放進 `docs/load-test-report.md` 的附件),可再加:

```bash
K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_EXPORT=load-test/report-scenario-a.html k6 run load-test/scenario-a-flash-sale.js
```

**這個面板跟現有 Prometheus + Grafana 不衝突**,兩者看的是不同層面:k6 面板是「壓測工具自己
看到的結果」(client 端請求量/延遲/VU 數),Grafana 是「被測系統內部發生什麼事」(JVM、Redis
庫存、RabbitMQ 佇列積壓等)。壓測時建議兩邊都開著觀察,全程截圖存證(見驗收清單)。

### 常用環境變數

| 變數 | 預設 | 說明 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | 打正式站時改成 `https://tixco.kozow.com` 並降低 VU |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `admin_local` / `AdminLocal123` | setup() 建活動用 |
| `USER_POOL_SIZE` | 5000 | 帳號池大小(`setup-users.js`) |
| `SCENARIO_A_VUS` / `SCENARIO_A_RAMP_SECONDS` / `SCENARIO_A_HOLD_SECONDS` / `SCENARIO_A_STOCK` | 2000 / 30 / 120 / 1000 | 情境 A 參數 |
| `SCENARIO_B_VUS` / `SCENARIO_B_DURATION_SECONDS` / `SCENARIO_B_STOCK` | 1000 / 600 / 1500 | 情境 B 參數 |
| `THINK_TIME_MIN_MS` / `THINK_TIME_MAX_MS`(情境 B) | 1000 / 3000 | 每輪迭代間的隨機思考時間 |

## 判讀結果時的重要提醒

- **`http_req_failed` 會偏高,這是預期的,不是腳本壞掉**:k6 內建把任何非 2xx 狀態碼算作
  「失敗」,但後端刻意把業務結果映射到對應 HTTP 狀態(例如售罄/重複購買回 409、限流回
  429,見 `BizCode.java`)。真正該看的是各腳本自訂的 Counter(`seckill_success` /
  `seckill_soldout` / `seckill_duplicate` / `seckill_ratelimited` / `b_seckill_*`)與
  `checks_succeeded`(應為 100%)。
- 情境 B 的 `b_seckill_duplicate` 佔多數是正常的:1000 位使用者「每人限購一張」,10 分鐘內
  同一使用者重複搶購同一票種本來就會回 3006,這反映真實流量中使用者持續重試的型態。
- 零超賣 / 零重複的**權威驗證**不是看 k6 的 Counter,是壓測後呼叫對帳 API:
  `GET /api/v1/admin/ticket-types/{id}/reconcile`(ticketTypeId 從各腳本 `setup()` 的
  console log 取得),核對 `total_stock - stock_remaining` = 有效訂單數 = Redis 扣減量。

## 已知限制 / 自主決策(供結尾報告參照)

- 情境 A 每個 VU 只送出一次完整搶購流程,定局後改為 sleep 撐滿測試時長,不會無限重試——
  這樣量到的是開賣瞬間的真實併發競爭,而非同一使用者的重複攻擊。
- 情境 B 的「搶購成功」計的是 `purchase` 端點受理成功(HTTP 層,code=0),不逐一輪詢到底
  SUCCESS/FAIL,避免拖慢單一 VU 的節奏、稀釋「持續高壓」的意義。
- 兩情境各自建立獨立票種與帳號區段(A 用 0–1999、B 預設用 2000+),互不污染。
- 壓測產生的活動/票種會留在 dev DB(標題前綴 `LoadTest Scenario A/B`),目前 admin API
  不允許刪除已有票種的活動(`2003 活動已含票種,不可刪除`),需要清理的話直接用 SQL
  `TRUNCATE`(見 `docs/design/03-交接文件-UIUX-M7.md` 或專案 memory 的清資料指令)。

## 情境目標(原始規格)

- 情境 A「開賣瞬間」:30 秒 ramp 至 2000 VU,全部打同一票種(庫存 1000),持續 2 分鐘。
- 情境 B「持續高壓」:1000 VU 恆定 10 分鐘,混合流量(70% 搶購、20% 查活動、10% 輪詢結果)。
