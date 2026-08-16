# HANDOFF:交接事項

> 最後更新:2026-08-17。**本文件只放需要交接給下一個 session 的待辦事項**,不放已結案的
> 排查過程或原始數據——那些在
> [`load-test/long-tail-latency-investigation.md`](load-test/long-tail-latency-investigation.md)
> (長尾延遲根因排查,run5–11)與
> [`docs/adr/0008-CD上線與k6壓測(M7).md`](docs/adr/0008-CD上線與k6壓測(M7).md)
> (M7 全部決策的正式記錄)。

## 待辦:找下游真實承載上限(OCI 正式站壓測,分階段進行中)

**目標**:在繞過限流(`SECKILL_RL_BYPASS=true`)的乾淨狀態下,對 OCI 正式站逐步加大流量,量出 DB/MQ/建單流程真正的承載上限,回答「`global-capacity=3000` 這個門檻夠不夠」。根因(`seckill:rl:global` 熱 key 排隊)已在本機坐實,不用重查,詳見上方連結;本機測試拓樸撞過 Windows 埠耗盡的牆,已定案改在 OCI 正式站測。

**進度**:

| 階段 | 內容 | 狀態 |
|---|---|---|
| 0 | 小規模探路(~20 VU、~30 秒、庫存 20,不覆寫限流),純粹確認打正式站不會有預期外的計費、app 對真實網域/TLS/Caddy 路徑正常運作 | ✅ 已完成,對帳與計費皆確認正常 |
| 1 | 正式規模(情境 A 2000 VU / 情境 B 1000 VU),**不開 bypass**,在正式站真實限流設定下驗證根因是否在真實硬體重現 | ⏳ 未開始 |
| 2 | 正式站部署啟用 `SECKILL_RL_BYPASS=true`(修改正式環境設定,需另外確認才動手),重新做下游承載測試 | ⏳ 未開始 |
| 3 | 獨立小型壓力測試腳本,量出單一 Redis 節點面對瞬間爆量的限流 CAS 檢查實際能撐多少併發(在 OCI 上測) | ⏳ 未開始 |
| 4 | 合併步驟 2、3 的數字(目標流量 ÷ 單節點上限 ≈ 至少要幾個分片),判斷 sharded counter 該拆幾片 | ⏳ 未開始 |

**OCI 計費風險已確認安全**(使用者已於 2026-08-16 親自查證):Cost Analysis 當月花費 $0、運算實例有 Always Free 標籤、已設定 OCI Budgets(超過 0.01 SGD 即 mail 告警)。之後每次拉高壓測規模,建議還是回 Cost Analysis 確認一次沒有異常變化,不要假設一定安全。

**執行方式(下一步進階段 1 時沿用)**:對正式站送流量的指令,一律由**使用者自己在自己的終端機執行**,不透過我的 Bash 工具代跑——admin 帳密是部署 secret,不應貼進對話;且是對有計費風險的正式環境送流量,使用者需要親自在旁邊看著 Cost Analysis 確認。

**階段 1 指令模板**(供使用者在自己終端機執行,`ADMIN_USERNAME`/`ADMIN_PASSWORD` 換成正式站實際帳密;PowerShell 用 `$env:VAR="value"` 逐行設定,不是 bash 的 `VAR=value` 行內語法):

```bash
BASE_URL=https://tixco.kozow.com \
ADMIN_USERNAME=<正式站 admin 帳號> ADMIN_PASSWORD=<正式站 admin 密碼> \
k6 run load-test/scenario-a-flash-sale.js
# 情境 B 同理,注意事先要有足夠帳號池(見 load-test/README.md)
```

跑完立刻呼叫對帳 API 存證,並回 OCI Cost Analysis 確認花費無異常。

## 環境現況(如果要接續本機開發)

- 專案路徑:`C:\Users\USER\Documents\seckill-ticketing`
- 標準啟動流程(dev 中介軟體 + backend):
  ```bash
  docker compose --env-file .env -f infra/docker-compose.dev.yml up -d
  docker compose --env-file .env -f infra/docker-compose.monitoring.yml up -d
  cd backend
  export JAVA_HOME="C:\Users\USER\.jdks\temurin-25\jdk-25.0.3+9"
  set -a && . ../.env && set +a
  export SECKILL_ADMIN_USERNAME=admin_local SECKILL_ADMIN_PASSWORD=AdminLocal123
  # 若要在本機重跑消融測試(繞過限流),加這行:
  export SECKILL_RL_BYPASS=true
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
  ```
- 本機監控/GUI 工具:Grafana `http://localhost:3000`、Prometheus `http://localhost:9090`、RabbitMQ management `http://localhost:15672`、RedisInsight(`docker compose -f infra/docker-compose.tools.yml up -d` 另外啟動)`http://localhost:5540`
- OCI 正式站的對應工具需先開 SSH tunnel(帳密皆在 `/opt/seckill/.env` 或對應 GitHub Secret 裡查):
  ```bash
  ssh -L 13000:localhost:3000 -L 19090:localhost:9090 -L 15540:localhost:5540 -L 25672:localhost:15672 -L 5432:localhost:5432 oci-seckill
  ```
- git 狀態:ADR 0008、`docs/adr/0004-*.md` 狀態行更新、`load-test/long-tail-latency-investigation.md`、`.gitignore`、`load-test/reports/` 搬移、本文件精簡,皆尚未 commit。
