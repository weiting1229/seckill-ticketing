# 搶票系統(Seckill Ticketing)— Phase 1 MVP 系統設計文件

> 版本:v1.0|適用階段:Phase 1(搶票系統 MVP + CI/CD + Prometheus/Grafana 監控)
> 本文件是 Claude Code 開發的唯一事實來源(Single Source of Truth),放置於 repo 的 `docs/design/` 目錄下。

---

## 1. 專案概述

模擬台灣演唱會搶票場景的高併發售票系統。核心挑戰:瞬間高流量下**不超賣、不重複賣、系統不崩潰**,並具備完整的可觀測性與自動化部署管線。

**Phase 1 交付範圍:**
- 搶票核心流程(Redis 預扣庫存 + RabbitMQ 削峰 + PostgreSQL 最終落庫)
- 分散式 ID 產生器(Snowflake 變體,以內建模組實作)
- 訂單生命週期(排隊 → 建單 → 模擬支付 → 超時自動取消回補庫存)
- 使用者認證(JWT)與管理後台(活動/票種管理、庫存預熱、對帳)
- Vue 3 前端(搶票頁 + 管理後台)
- Prometheus + Grafana + Alertmanager 監控告警
- GitHub Actions CI/CD,自動部署至 OCI ARM 主機
- k6 壓測腳本與壓測報告

**明確不做(留給後續 Phase):**
- 自研 Rate Limiter(Phase 2,MVP 先用 Bucket4j + Redis 頂替)
- Feature Flag 服務(Phase 3)
- 自製 MQ(Phase 4,屆時替換 RabbitMQ)
- 自製監控核心(Phase 5,選做)
- 真實金流、座位選位、多實例水平擴展

---

## 2. 總體架構

```
                        ┌─────────────────────────────────────────┐
                        │           OCI ARM A1 (4C/24G)           │
  使用者                 │                                         │
    │  HTTPS             │  ┌────────┐      ┌──────────────────┐  │
    ▼                    │  │ Caddy  │─────▶│ frontend (Nginx) │  │
 ┌──────┐   443          │  │ 反向代理│      │  Vue 3 靜態檔     │  │
 │瀏覽器 │───────────────▶│  │ 自動TLS│      └──────────────────┘  │
 └──────┘                │  └───┬────┘                            │
                         │      │ /api                            │
                         │      ▼                                 │
                         │  ┌──────────────────────────────┐      │
                         │  │  backend (Spring Boot 3.5)    │      │
                         │  │  - JWT 認證 / RBAC            │      │
                         │  │  - Bucket4j 限流(MVP)        │      │
                         │  │  - Snowflake ID 模組          │      │
                         │  └──┬──────────┬──────────┬─────┘      │
                         │     │          │          │            │
                         │     ▼          ▼          ▼            │
                         │  ┌──────┐  ┌────────┐ ┌──────────┐    │
                         │  │Redis │  │RabbitMQ│ │PostgreSQL│    │
                         │  │庫存預扣│  │ 削峰    │ │ 最終落庫  │    │
                         │  │冪等   │  │ 延遲取消 │ │          │    │
                         │  └──────┘  └────────┘ └──────────┘    │
                         │                                        │
                         │  ┌────────────────────────────────┐    │
                         │  │ Prometheus + Grafana +          │    │
                         │  │ Alertmanager + exporters        │──▶ Telegram 告警
                         │  └────────────────────────────────┘    │
                         └─────────────────────────────────────────┘
```

**搶購核心資料流:**

```
用戶點擊搶購
  → [API] JWT 驗證 + Bucket4j 限流(全域/單用戶/單 IP)
  → [API] 校驗一次性搶購 token(防腳本繞過頁面直刷下單接口)
  → [Redis Lua] 原子操作:查重複購買 → 查庫存 → 扣庫存 → 記錄已購
      ├─ 失敗(售罄/重複)→ 立即返回,流量到此為止,絕不觸碰 DB
      └─ 成功 → 產生 Snowflake orderId → 發訊息到 RabbitMQ → 返回「排隊中 + requestId + orderId」
  → [前端] 以 requestId 輪詢排隊結果(1 秒起步,指數退避)
  → [Consumer] 消費訊息 → 冪等檢查 → DB 事務:條件 UPDATE 扣 DB 庫存 + 建訂單 + 寫庫存流水
      ├─ 成功 → 訂單 PENDING_PAYMENT,同時發延遲訊息(15 分鐘 TTL)
      └─ DB 扣減失敗(理論上不應發生,屬異常訊號)→ 回補 Redis 庫存 + 移除已購記錄 + 結果標記 FAIL
  → 用戶 15 分鐘內模擬支付 → PAID
  → 超時未支付 → 延遲訊息到期 → 取消訂單 + 回補 DB 與 Redis 庫存
```

---

## 3. 技術棧(版本鎖定)

| 分類 | 選型 | 說明 |
|---|---|---|
| 語言 | Java 25 (LTS) | 開啟 Virtual Threads(`spring.threads.virtual.enabled=true`) |
| 後端框架 | Spring Boot 3.5.x | 不用 Boot 4(生態相容性考量,後期再升級並寫升級記錄) |
| ORM | MyBatis 3.x(原生,非 MyBatis-Plus) | 全部手寫 SQL,參數一律 `#{}` |
| 資料庫 | PostgreSQL 17(官方 Docker 映像) | 遷移管理用 Flyway |
| 快取 | Redis 7.x | Lua 腳本保證原子性 |
| MQ | RabbitMQ 3.13(management + prometheus plugin) | TTL + DLX 實現延遲佇列 |
| 認證 | Spring Security + JWT | access 15 分鐘 / refresh 7 天,refresh 存 Redis 可撤銷 |
| 限流(MVP) | Bucket4j + Redis | Phase 2 換自研 Rate Limiter |
| 前端 | Vue 3 + TypeScript + Vite + Pinia + Vue Router | UI 庫用 Element Plus |
| 監控 | Micrometer → Prometheus、Grafana、Alertmanager | node / postgres / redis exporter |
| 壓測 | k6 | 腳本入 repo:`load-test/` |
| 容器 | Docker + Docker Compose | **映像必須 build 成 linux/arm64**(OCI A1 是 ARM 架構) |
| CI/CD | GitHub Actions + GHCR | 整合測試用 Testcontainers |
| 反向代理 | Caddy 2 | 自動 Let's Encrypt HTTPS |
| 建置工具 | Maven(後端)、pnpm(前端) | |

---

## 4. Monorepo 目錄結構

```
seckill-ticketing/
├── CLAUDE.md                    # Claude Code 開發規範(必讀)
├── docs/
│   ├── design/                  # 本設計文件
│   ├── adr/                     # 架構決策記錄(每個重大決策一篇)
│   ├── runbook.md               # 告警處理手冊(M6 產出)
│   └── load-test-report.md      # 壓測報告(M7 產出)
├── backend/
│   ├── pom.xml
│   └── src/main/java/com/seckill/
│       ├── common/              # 統一回應格式、全域例外處理、Snowflake ID(common/id)
│       ├── config/              # Security、Redis、RabbitMQ、MyBatis 配置
│       ├── auth/                # 認證模組(controller / service / mapper)
│       ├── event/               # 活動與票種模組
│       ├── seckill/             # 搶購核心(Lua 腳本置於 resources/lua/)
│       ├── order/               # 訂單模組(含 MQ consumer 與延遲取消)
│       └── admin/               # 管理後台 API
├── frontend/
│   ├── package.json
│   └── src/
│       ├── views/               # 搶票頁、活動列表、訂單頁、登入頁、admin/
│       ├── stores/              # Pinia
│       └── api/                 # axios 封裝(自動帶 token、401 自動 refresh 重放)
├── infra/
│   ├── docker-compose.dev.yml   # 本地開發(僅中介軟體)
│   ├── docker-compose.prod.yml  # 正式環境(全套服務)
│   ├── caddy/Caddyfile
│   ├── prometheus/prometheus.yml 與 alert-rules.yml
│   ├── grafana/provisioning/    # 資料源與 dashboard 自動載入
│   ├── alertmanager/alertmanager.yml
│   └── setup-server.sh          # OCI 主機初始化腳本
├── load-test/                   # k6 腳本與說明
└── .github/workflows/
    ├── ci.yml                   # PR / main push 觸發:測試 + 建置
    └── cd.yml                   # main merge 觸發:build → scan → deploy
```

---

## 5. 資料庫設計(PostgreSQL DDL)

> 遷移工具:Flyway,檔案置於 `backend/src/main/resources/db/migration/`。
> 所有主鍵使用 Snowflake ID(BIGINT),不用資料庫自增(為未來分庫分表鋪路)。
> 所有表帶 `created_at` / `updated_at`(`TIMESTAMPTZ`,由應用層寫入)。

```sql
-- V1__init.sql

CREATE TABLE users (
    id            BIGINT PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,           -- BCrypt
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',   -- USER / ADMIN
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE events (                               -- 演唱會活動
    id          BIGINT PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,
    description TEXT,
    venue       VARCHAR(200),
    event_time  TIMESTAMPTZ  NOT NULL,              -- 演出時間
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',   -- DRAFT / PUBLISHED / CLOSED
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE ticket_types (                         -- 票種(搶購標的)
    id              BIGINT PRIMARY KEY,
    event_id        BIGINT        NOT NULL REFERENCES events(id),
    name            VARCHAR(100)  NOT NULL,          -- 例:搖滾區、看台 A
    price           NUMERIC(10,2) NOT NULL CHECK (price >= 0),
    total_stock     INT           NOT NULL CHECK (total_stock >= 0),
    stock_remaining INT           NOT NULL CHECK (stock_remaining >= 0),  -- 防超賣的 DB 底線
    seckill_start   TIMESTAMPTZ   NOT NULL,
    seckill_end     TIMESTAMPTZ   NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'OFFLINE',  -- OFFLINE / ONLINE
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_ticket_types_event ON ticket_types(event_id);

CREATE TABLE orders (
    id              BIGINT PRIMARY KEY,              -- Snowflake,直接作為訂單號
    user_id         BIGINT        NOT NULL REFERENCES users(id),
    event_id        BIGINT        NOT NULL,
    ticket_type_id  BIGINT        NOT NULL,
    price           NUMERIC(10,2) NOT NULL,
    status          VARCHAR(30)   NOT NULL,          -- PENDING_PAYMENT / PAID / CANCELLED / EXPIRED
    request_id      VARCHAR(64)   NOT NULL,          -- 冪等鍵(來自 MQ 訊息)
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    paid_at         TIMESTAMPTZ,
    expire_at       TIMESTAMPTZ   NOT NULL,          -- 支付截止時間
    CONSTRAINT uq_orders_request     UNIQUE (request_id),               -- 消費冪等
    CONSTRAINT uq_orders_user_ticket UNIQUE (user_id, ticket_type_id)   -- 每人每票種限購一張
);
CREATE INDEX idx_orders_user ON orders(user_id, created_at DESC);
CREATE INDEX idx_orders_status_expire ON orders(status, expire_at)
    WHERE status = 'PENDING_PAYMENT';                -- 兜底掃描超時訂單用(部分索引)

CREATE TABLE stock_logs (                            -- 庫存流水(審計 + 對帳)
    id             BIGINT PRIMARY KEY,
    ticket_type_id BIGINT      NOT NULL,
    order_id       BIGINT      NOT NULL,
    delta          INT         NOT NULL,             -- -1 扣減 / +1 回補
    type           VARCHAR(20) NOT NULL,             -- DEDUCT / REVERT
    created_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_stock_logs_ticket ON stock_logs(ticket_type_id, created_at);
```

**關鍵 SQL(防超賣的最後一道防線):**

```sql
UPDATE ticket_types
SET stock_remaining = stock_remaining - 1, updated_at = now()
WHERE id = #{ticketTypeId} AND stock_remaining > 0;
-- 影響行數 = 0 代表扣減失敗;消費者必須回補 Redis 並將該 requestId 標記為 FAIL
```

---

## 6. Redis 資料結構設計

| Key | 型別 | 說明 | TTL |
|---|---|---|---|
| `seckill:stock:{ticketTypeId}` | String(int) | 預扣庫存,活動上線時由 admin 預熱 API 寫入 | 活動結束 + 1 天 |
| `seckill:bought:{ticketTypeId}` | Set(userId) | 已購用戶集合,防重複購買第一層 | 同上 |
| `seckill:token:{userId}:{ticketTypeId}` | String | 一次性搶購 token,校驗後即刪 | 60 秒 |
| `seckill:result:{requestId}` | String | 排隊結果(SUCCESS:orderId / FAIL:原因),供輪詢 API | 10 分鐘 |

**核心 Lua 腳本 `seckill_deduct.lua`(原子執行,杜絕競態):**

```lua
-- KEYS[1] = stock key, KEYS[2] = bought set key, ARGV[1] = userId
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1                          -- 重複購買
end
local stock = tonumber(redis.call('GET', KEYS[1]) or '-999')
if stock == -999 then return -3 end    -- 活動未預熱 / 不存在
if stock <= 0 then return -2 end       -- 售罄
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1                               -- 成功
```

**回補腳本 `seckill_revert.lua`:** `INCR` 庫存 + `SREM` 已購集合,用於 DB 落庫失敗與訂單超時取消兩個場景。

**一次性 token 的校驗與刪除**也必須用 Lua 原子執行(GET 比對後 DEL),避免兩個併發請求用同一 token 都通過。

---

## 7. RabbitMQ 拓撲設計

```
seckill.exchange (direct)
  └── routing key: order.create → seckill.order.queue       # 建單佇列
        └── x-dead-letter → seckill.order.dlq               # 消費失敗 3 次進死信,人工介入

order.delay.exchange (direct)
  └── routing key: order.delay → order.delay.queue          # 無消費者,x-message-ttl = 900000(15 分鐘)
        └── x-dead-letter → order.timeout.queue             # 到期後路由至此
              └── consumer:查訂單狀態,仍為 PENDING_PAYMENT 則取消 + 回補庫存
```

**訊息格式(JSON):**
```json
{ "requestId": "uuid", "userId": 123, "ticketTypeId": 456, "orderId": 789, "timestamp": 1720000000000 }
```

**可靠性要求:**
- 生產端:開啟 publisher confirms;發送失敗記錄結構化 log 並回補 Redis
- 消費端:手動 ack;業務成功才 ack;例外時依 header 重試計數,超過 3 次丟 DLQ
- 冪等:靠 `orders.request_id` 唯一約束;重複訊息插入時捕獲唯一鍵衝突,視為已處理,直接 ack
- Consumer 併發數從 4 起步,依壓測結果與 DB 承受度調整

---

## 8. 分散式 ID 模組(Snowflake 變體)

- 位元結構:1 bit 符號 + 41 bit 時間戳(自訂 epoch:2026-01-01T00:00:00Z)+ 5 bit datacenterId + 5 bit workerId + 12 bit 序列號
- workerId 來源:環境變數 `WORKER_ID`(單機 MVP 固定 0;未來多實例改為啟動時從 Redis `INCR` 分配並保留該擴充點)
- 時鐘回撥處理:回撥 ≤ 5ms 自旋等待;> 5ms 拋異常拒絕發號,並累加 Micrometer counter 觸發告警
- 實作為獨立 package `com.seckill.common.id`,對外提供 `IdGenerator` 介面
- 測試要求:單執行緒唯一性;32 執行緒併發產 100 萬 ID 零碰撞;趨勢遞增驗證

---

## 9. API 規格(核心)

- 統一回應格式:`{ "code": 0, "message": "ok", "data": { ... } }`
- 業務錯誤碼 4 位數:1xxx 認證、2xxx 活動、3xxx 搶購、4xxx 訂單
- 所有 API 前綴 `/api/v1`

| Method | Path | 認證 | 說明 |
|---|---|---|---|
| POST | `/auth/register` | 無 | 註冊(username 3–50 字,密碼強度校驗) |
| POST | `/auth/login` | 無 | 回 accessToken + refreshToken |
| POST | `/auth/refresh` | refresh | 換發 accessToken |
| GET | `/events` | 無 | 已發布活動列表(分頁) |
| GET | `/events/{id}` | 無 | 活動詳情,含票種、即時剩餘(讀 Redis,允許短暫不精確)、server time(供前端倒數校準) |
| POST | `/seckill/token` | USER | 領取一次性搶購 token(body: ticketTypeId;不在開賣時間窗內一律拒絕) |
| POST | `/seckill/purchase` | USER | 搶購(body: ticketTypeId + token);成功回 requestId + orderId |
| GET | `/seckill/result/{requestId}` | USER | 輪詢排隊結果(QUEUING / SUCCESS / FAIL) |
| GET | `/orders` | USER | 我的訂單列表 |
| GET | `/orders/{id}` | USER | 訂單詳情(校驗歸屬;他人訂單回 404 而非 403,避免洩漏訂單存在性) |
| POST | `/orders/{id}/pay` | USER | 模擬支付(狀態機:僅 PENDING_PAYMENT 可轉 PAID) |
| CRUD | `/admin/events`、`/admin/ticket-types` | ADMIN | 活動/票種管理 |
| POST | `/admin/ticket-types/{id}/warmup` | ADMIN | 票種上線 + 庫存預熱至 Redis(必須冪等,不可覆蓋已扣減的庫存) |
| GET | `/admin/ticket-types/{id}/reconcile` | ADMIN | 對帳:DB 庫存 vs Redis 庫存 vs 有效訂單數 vs stock_logs 淨值 |

**狀態機約束(必須落實在 SQL 層):** 訂單狀態轉移一律 `UPDATE ... WHERE id = ? AND status = '舊狀態'`,影響行數為 0 即視為非法轉移並回錯誤。取消與支付的併發競態靠此機制天然互斥。

---

## 10. 安全性設計(高優先)

1. **認證/授權**:Spring Security + JWT;BCrypt(strength 10);refresh token 存 Redis、可主動撤銷;RBAC 兩角色,admin API 以 URL 層 + 方法層(`@PreAuthorize`)雙重防護
2. **注入防護**:MyBatis 參數一律 `#{}`;動態排序欄位採白名單;Jakarta Validation 校驗所有入參(長度、範圍、格式)
3. **搶購防刷三層**:
   - Bucket4j + Redis 限流:全域 QPS 上限(依壓測定,初始 3000)、單用戶對 `/seckill/purchase` 每秒 2 次、單 IP 每秒 10 次
   - 一次性 token:下單必須先領 token,60 秒有效、用後即焚(Lua 原子校驗+刪除),使腳本無法跳過頁面流程直刷下單接口
   - Redis 已購集合 + DB 唯一約束,雙層防重複購買
4. **傳輸與邊界**:Caddy 全站 HTTPS(自動憑證);CORS 白名單只允許正式網域與 `localhost:5173`;安全 header(HSTS、X-Content-Type-Options、基本 CSP);後端與所有中介軟體容器不對外開 port,只在 Docker 內網被 Caddy / Prometheus 存取
5. **祕密管理**:所有密碼/金鑰走環境變數;`.env` 進 `.gitignore`,repo 只放 `.env.example`;正式環境祕密存 GitHub Actions Secrets 於部署時注入;JWT secret 至少 256 bit 隨機
6. **容器安全**:多階段建置;執行層 backend 用 `eclipse-temurin:25-jre` 基底;容器內以非 root 使用者執行;CI 中 Trivy 掃描映像,HIGH/CRITICAL 漏洞即管線失敗;開啟 Dependabot
7. **Actuator**:僅暴露 `health`、`prometheus`,且僅限 Docker 內網存取(供 Prometheus 抓取),Caddy 不轉發 `/actuator`
8. **稽核**:登入成功/失敗、admin 操作、庫存預熱與對帳結果寫結構化 log(JSON,含 traceId)
9. **效能與安全的權衡原則**:安全檢查前置且 O(1)(限流與 token 校驗都在 Redis 層完成),熱路徑不觸碰 DB;BCrypt 僅存在於登入/註冊路徑,不影響搶購熱路徑

---

## 11. 監控設計

**業務指標(Micrometer 自訂,帶 `ticket_type_id` 標籤):**

| 指標 | 型別 | 說明 |
|---|---|---|
| `seckill_requests_total{result=...}` | Counter | 搶購請求數;result = success / sold_out / duplicate / rate_limited / invalid_token |
| `seckill_order_create_duration_seconds` | Histogram | 從發 MQ 到訂單落庫的耗時(以訊息內 timestamp 計算) |
| `seckill_redis_stock` | Gauge | 各票種 Redis 剩餘庫存(定時任務每 5 秒同步) |
| `seckill_stock_revert_total` | Counter | 庫存回補次數(異常訊號,理想值為僅來自超時取消) |
| `id_generator_clock_backwards_total` | Counter | 時鐘回撥事件 |

佇列積壓深度由 RabbitMQ prometheus plugin 原生提供,不需自行埋點。

**系統指標**:node_exporter(主機)、postgres_exporter(連線數/慢查詢/鎖等待)、redis_exporter(命中率/記憶體)、RabbitMQ prometheus plugin、JVM(Micrometer 內建:heap / GC / virtual threads)。

**告警規則(Alertmanager → Telegram):**
- 佇列積壓 > 10000 持續 30s(warning);> 50000 持續 60s(critical)
- API p99 延遲 > 500ms 持續 1 分鐘
- 訂單消費失敗率 > 1% 持續 1 分鐘;DLQ 出現任何訊息(critical)
- `seckill_stock_revert_total` 5 分鐘增量 > 100
- 主機 CPU > 85% 持續 5 分鐘;PostgreSQL 連線數 > 上限 80%
- 任一目標 `up == 0` 持續 30 秒

**Grafana Dashboard(provisioning 自動載入,JSON 檔入 repo):**
1. 搶購總覽:即時 QPS、成功/失敗結果分布、各票種庫存水位、佇列深度
2. 系統資源:CPU / 記憶體 / 磁碟 IO / 網路
3. 中介軟體:PG 連線與鎖、Redis 命中率、RabbitMQ 吞吐
4. JVM:heap、GC 停頓、virtual thread 數量

---

## 12. CI/CD 設計(GitHub Actions)

> **重要:OCI A1 主機是 ARM64(Ampere)架構,所有 Docker 映像必須以 `docker buildx` 建置 `linux/arm64` 平台,否則無法在主機上執行。**

**`ci.yml` — PR 與 main push 觸發:**
1. `backend-test`:JDK 25(temurin)→ `mvn verify`;整合測試以 Testcontainers 拉起 PostgreSQL / Redis / RabbitMQ;產出測試報告與 Jacoco 覆蓋率(seckill 與 order 模組行覆蓋率 ≥ 80%)
2. `frontend-check`:pnpm install → ESLint → type-check → `vite build`
3. 兩個 job 平行執行,全綠才允許 merge(設定 branch protection)

**`cd.yml` — main merge 後觸發(需 CI 通過):**
1. `docker buildx` 建置 backend 與 frontend 映像(`linux/arm64`),tag 用 git SHA + `latest`,push 至 GHCR
2. Trivy 掃描兩個映像,HIGH/CRITICAL 即管線失敗
3. SSH(金鑰存 GitHub Secrets)至 OCI 主機:`docker compose -f docker-compose.prod.yml pull && up -d`
4. Smoke test:檢查 `/actuator/health`(經內網)與前端首頁,非 200 即失敗
5. 部署結果(成功/失敗)推 Telegram 通知

**部署環境(docker-compose.prod.yml 服務清單):**
caddy、frontend、backend、postgres、redis、rabbitmq、prometheus、grafana、alertmanager、node_exporter、postgres_exporter、redis_exporter。
資料持久化:postgres / redis / rabbitmq / prometheus / grafana 全部掛 named volume;每日 `pg_dump` cron 備份至主機磁碟(未來上傳 OCI Object Storage)。

---

## 13. 壓測計畫(k6)

**場景腳本(`load-test/`):**
- 資料準備:setup 階段批量註冊 5000 個測試用戶並取得 token
- 情境 A「開賣瞬間」:30 秒內 ramp 至 2000 VU,全部打同一票種(庫存 1000 張),持續 2 分鐘
- 情境 B「持續高壓」:1000 VU 恆定 10 分鐘,混合流量(70% 搶購、20% 查活動、10% 輪詢結果)

**驗收指標:**
- **零超賣**:壓測後執行對帳 API,`total_stock - stock_remaining` = 有效訂單數(PAID + PENDING)= Redis 扣減量,三方一致
- **零重複**:同一 user 對同一票種訂單數 ≤ 1
- 搶購接口 p99 < 300ms(Redis 路徑,不含非同步落庫)
- 流量結束後 2 分鐘內佇列積壓消化完畢
- 全程 Grafana 截圖,結論與調優過程寫入 `docs/load-test-report.md`

---

## 14. 里程碑(交付順序)

| 里程碑 | 內容 | 驗收標準 |
|---|---|---|
| M0 | Monorepo 骨架、docker-compose.dev、Flyway 初始遷移、CI 骨架 | `docker compose up` 中介軟體全 healthy;PR 觸發 CI 成功 |
| M1 | 認證模組(註冊/登入/refresh/RBAC)+ Snowflake ID 模組 | 單元 + 整合測試綠;ID 併發唯一性測試通過 |
| M2 | 活動/票種管理(admin CRUD + 預熱 + 對帳 API) | 預熱冪等;對帳三方一致 |
| M3 | 搶購核心(token + Lua 扣減 + MQ 生產/消費 + 冪等落庫) | 整合測試涵蓋:售罄、重複購買、訊息重複投遞、DB 扣減失敗回補 |
| M4 | 訂單生命週期(輪詢結果、模擬支付、延遲取消回補、狀態機、兜底排程) | 超時訂單自動取消且庫存三方對帳一致 |
| M5 | 前端(登入/活動列表/搶票頁含倒數與輪詢/訂單頁/admin 後台) | 手動 E2E 流程全通 |
| M6 | 監控全套(埋點、exporters、dashboards、告警到 Telegram) | 手動觸發告警規則可收到通知 |
| M7 | CD 上線(GHCR + Trivy + SSH 部署 + Caddy HTTPS)+ k6 壓測 + 報告 | 正式網址可訪問;壓測驗收指標全數達成 |

---

## 15. 後續 Phase 路線圖(本文件不展開)

- **Phase 2 — Rate Limiter as a Service**:獨立服務 + Java SDK,實作固定窗口/滑動窗口/令牌桶,替換 Bucket4j,以監控數據對比前後效果
- **Phase 3 — Feature Flag 服務**:開關/百分比灰度/緊急熔斷,SDK 本地快取 + 變更推送,首個場景是搶票開賣總開關
- **Phase 4 — 自製 MQ**:實作 topic / partition / consumer group / offset / 持久化,以相同抽象介面替換 RabbitMQ,壓測對比吞吐與延遲
- **Phase 5(選做)— 自製監控核心**:時序儲存 + 告警引擎,以 Grafana 自訂資料源接入,與 Prometheus 並行對比
