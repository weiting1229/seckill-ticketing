# 搶票系統(Seckill Ticketing)

模擬台灣演唱會搶票場景的高併發售票系統。核心目標:瞬間高流量下**不超賣、不重複賣、系統不崩潰**,並具備完整可觀測性與自動化部署管線。

- 設計文件(唯一事實來源):[docs/design/01-系統設計文件-搶票系統MVP.md](docs/design/01-系統設計文件-搶票系統MVP.md)
- 開發規範:[CLAUDE.md](CLAUDE.md)

## 技術棧

Java 25 + Spring Boot 3.5(Virtual Threads)/ MyBatis / PostgreSQL 17 + Flyway / Redis 7 / RabbitMQ 3.13 / Vue 3 + TypeScript + Vite + Pinia + Element Plus / Prometheus + Grafana / GitHub Actions → OCI ARM。

## 本地開發環境需求

| 工具 | 版本 |
|---|---|
| JDK | 25(Temurin) |
| Maven | 免安裝,用 `backend/mvnw` wrapper |
| Node.js | ≥ 24.12(或 22.18+) |
| pnpm | 10.x |
| Docker + Docker Compose | 跑中介軟體與 Testcontainers 整合測試必需 |

## 本地啟動步驟

### 1. 準備環境變數

```bash
cp .env.example .env
# 編輯 .env,填入本地開發用的密碼(不可 commit)
```

### 2. 啟動中介軟體(PostgreSQL / Redis / RabbitMQ)

```bash
docker compose -f infra/docker-compose.dev.yml --env-file .env up -d
docker compose -f infra/docker-compose.dev.yml ps   # 三個服務都應為 healthy
```

- RabbitMQ management UI:<http://localhost:15672>(帳密見 .env)
- RabbitMQ Prometheus metrics:<http://localhost:15692/metrics>

### 3. 啟動後端(dev profile)

```bash
cd backend
# Windows PowerShell 請改用 $env:POSTGRES_PASSWORD="..." 逐一設定,或由 IDE 載入 .env
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

> dev profile 會從環境變數讀取 `POSTGRES_PASSWORD`、`RABBITMQ_USER`、`RABBITMQ_PASSWORD`
> (Spring 不會自動讀 `.env`,請由 shell 或 IDE 注入)。

驗證:`curl http://localhost:8080/api/v1/health` 應回 `{"code":0,...}`。

### 4. 啟動前端

```bash
cd frontend
pnpm install
pnpm dev    # http://localhost:5173,/api 會 proxy 到 localhost:8080
```

## 測試

```bash
cd backend
./mvnw verify   # 單元測試 + 整合測試(整合測試用 Testcontainers,需要 Docker;無 Docker 時自動跳過)

cd frontend
pnpm ci:lint && pnpm type-check && pnpm build-only
```

## CI

GitHub Actions([.github/workflows/ci.yml](.github/workflows/ci.yml)):PR 與 main push 觸發,`backend-test`(mvn verify + Testcontainers)與 `frontend-check`(lint + type-check + build)兩個 job 平行執行。

## 目錄結構

見設計文件第 4 節。`backend/` 為 Spring Boot 單體(模組化 package),`frontend/` 為 Vue 3 SPA,`infra/` 為 Docker Compose 與監控設定,`load-test/` 為 k6 壓測腳本(M7)。
