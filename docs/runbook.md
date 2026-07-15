# 告警處理手冊(Runbook)— 搶票系統 M6 監控

> 設計文件第 11 節六類告警的處置手冊。**目前為被動觀察模式**:不接任何主動通知平台
> (Telegram / email 皆略過,使用者決策)。告警由 Prometheus 定期評估,狀態於
> **http://localhost:9090/alerts** 直接觀察;各項指標於 Grafana(http://localhost:3000)四個 dashboard 呈現。
> 規則定義:[`infra/prometheus/alert-rules.yml`](../infra/prometheus/alert-rules.yml)。

## 如何觀察告警

- **Prometheus /alerts**:每條規則的狀態為 `inactive`(正常)/ `pending`(已超閾值、尚未滿足 `for` 持續時間)/ `firing`(已觸發)。
- **PromQL**:`ALERTS{alertstate="firing"}` 可列出當前 firing 的告警。
- **Grafana**:對應面板變紅或超出 threshold 帶(dashboard ①~④)。
- 壓測時建議開著 `/alerts` 頁與 dashboard ①(搶購總覽)、②(系統資源)。

## 觸發驗證(M6 已實測)

停掉任一 exporter 製造 `up==0`,可觀察 `TargetDown` 由 `pending → firing`,復原後自動回 `inactive`:

```bash
docker stop seckill-redis-exporter     # 約 30s 後 TargetDown firing(job=redis)
docker start seckill-redis-exporter    # 約 15s 後自動 resolved
```

---

## 告警逐條處置

### 1. QueueBacklogWarning / QueueBacklogCritical — 建單佇列積壓
- **指標**:`sum(rabbitmq_queue_messages_ready{queue="seckill.order.queue"})`;warning > 10000(30s)、critical > 50000(60s)。
- **意義**:建單消費者(落庫)追不上搶購生產速率,訊息在 work queue 堆積。
- **注意**:此規則**只看 `seckill.order.queue`**,不含 `order.timeout.queue`(後者堆積的是合法的 15 分鐘支付逾時 timer,壓測時本就會有大量訊息,非積壓)。
- **可能原因**:消費併發不足(`spring.rabbitmq.listener.simple.concurrency`,預設 4);DB 落庫變慢(鎖等待、慢查詢、連線耗盡);消費者拋例外一直重試。
- **排查**:
  1. Grafana dashboard ③ 看 RabbitMQ 吞吐(deliver/ack 是否遠低於 publish)。
  2. dashboard ③ PG 連線與鎖、dashboard ① 訂單落庫 p99 是否飆高。
  3. 後端日誌是否有消費例外堆疊。
- **處置**:提高消費併發並重啟後端;排除 DB 瓶頸(見 PgConnectionsHigh);必要時暫時關閉搶購入口洩壓。

### 2. OrderDlqNotEmpty — 訂單 DLQ 出現訊息(critical)
- **指標**:`sum(rabbitmq_queue_messages{queue="seckill.order.dlq"}) > 0`(30s)。
- **意義**:建單消費重試耗盡(`x-retry-count` 用完 → `basicNack(requeue=false)`),訊息死信到 DLQ,**需人工介入**(ADR 0004 §7)。
- **可能原因**:落庫持續失敗(非預期例外,如序列化、約束衝突以外的錯誤、DB 不可用);毒訊息。
- **排查**:
  1. RabbitMQ 管理 UI(http://localhost:15672)開 `seckill.order.dlq`,`Get messages` 看內容與 `x-death` 死信原因。
  2. 對照後端日誌該 `requestId` 的例外。
  3. 注意:DLQ 訊息不會寫 `seckill:result:{requestId}`,對應前端輪詢會逾時(已知限制)。
- **處置**:修正根因後,人工重新投遞或補單;確認庫存對帳(admin `reconcile` API)三方一致,必要時手動回補 Redis 庫存。

### 3. OrderConsumeFailing — 訂單消費出現失敗
- **指標**:`rate(rabbitmq_global_messages_dead_lettered_rejected_total[5m]) > 0`(1m)。
- **意義**:建單消費因 `reject/nack(requeue=false)` 而死信的速率 > 0,代表消費正在失敗。
- **簡化說明**:設計原文為「消費失敗率 > 1%」,但現有 RabbitMQ 指標無乾淨的「總消費數」分母可算比率,故改以 **rejected 死信速率 > 0** 近似(見 ADR 0007)。此為全域計數,本系統中 rejected 死信僅來自建單 work queue(`order.timeout.queue` 走 expiry 死信,不計入 rejected)。
- **排查 / 處置**:同 OrderDlqNotEmpty;此告警通常早於或伴隨 DLQ 累積,可視為 DLQ 的前導訊號。

### 4. StockRevertSpike — 庫存回補激增
- **指標**:`sum(increase(seckill_stock_revert_total[5m])) > 100`(1m)。
- **意義**:5 分鐘內庫存回補 > 100 次。回補理想上只來自「超時未支付取消」(M4);短時間激增代表異常。
- **可能原因**:MQ 發送未確認導致熱路徑回補(`3009`,見 ADR 0004 §8);建單消費 DB 售罄回補;大量訂單同時逾時取消。
- **排查**:
  1. dashboard ① 結果分布是否伴隨 `rate_limited` / 入列失敗上升。
  2. RabbitMQ / broker 是否曾短暫不可用(publisher confirms 逾時)。
  3. dashboard ③ 是否有 DB 售罄跡象(落庫失敗)。
- **處置**:若為 broker 抖動,確認 broker 恢復;若為 DB 售罄(Redis 與 DB 庫存不一致),跑對帳 API 校正。

### 5. ApiP99LatencyHigh — API p99 延遲 > 500ms
- **指標**:`histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[1m]))) > 0.5`(1m)。
- **前置**:後端 `application.yml` 已開 `management.metrics.distribution.percentiles-histogram.http.server.requests=true`。
- **意義**:對外 API p99 延遲超過 500ms。搶購熱路徑(Redis+MQ)設計目標 p99 < 300ms(設計文件第 13 節)。
- **可能原因**:限流桶 Redis 往返變慢;Lua 執行熱點;MQ publish confirm 等待;GC 停頓;主機 CPU 飽和;virtual thread 被 pin(阻塞 carrier thread)。
- **排查**:
  1. dashboard ④ JVM:heap 壓力、GC 停頓是否升高。
  2. dashboard ② CPU 是否 > 85%。
  3. dashboard ③ Redis 命中率 / ops、PG 連線是否異常。
  4. 用 uri 標籤細分:`histogram_quantile(0.99, sum by (le,uri) (rate(http_server_requests_seconds_bucket[1m])))` 找出慢端點。
- **處置**:定位瓶頸資源擴充或調參;壓測情境下記錄並調整限流 / 消費併發(M7)。

### 6. HostCpuHigh — 主機 CPU > 85%
- **指標**:`100 - (avg(rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100) > 85`(5m)。
- **意義**:主機 CPU 持續高負載,可能影響延遲與吞吐。
- **可能原因**:搶購尖峰;GC 密集;鄰近程序搶佔(OCI A1 共享)。
- **排查**:dashboard ② CPU / load、dashboard ④ GC;`docker stats` 看各容器 CPU。
- **處置**:確認是否壓測預期尖峰;必要時降流量、調整資源配額或優化熱路徑。
- **註**:dev 環境 node_exporter 觀察的是 Docker Desktop VM;prod(OCI Linux)為真實主機(M7 prod compose 補完整主機掛載)。

### 7. PgConnectionsHigh — PG 連線使用率 > 80%
- **指標**:`100 * sum(pg_stat_activity_count) / max(pg_settings_max_connections) > 80`(1m);上限預設 100。
- **意義**:PostgreSQL 連線逼近上限,再攀升將出現拿不到連線的錯誤。
- **可能原因**:連線池上限過大或洩漏;慢查詢 / 鎖等待使連線久佔;消費併發過高。
- **排查**:dashboard ③ PG 連線(依狀態,`active` vs `idle in transaction`)、鎖(依 mode)、`pg_stat_activity_max_tx_duration`。
- **處置**:調整 HikariCP `maximum-pool-size`;排除長交易 / 鎖;殺掉異常長連線。

### 8. TargetDown — 抓取目標離線(critical)
- **指標**:`up == 0`(30s),帶 `job` / `instance` 標籤。
- **意義**:某個被抓取目標(backend / rabbitmq / node / postgres / redis / prometheus exporter)無法抓取。
- **可能原因**:該服務或 exporter 當掉 / 重啟中;網路 / DNS 問題;(dev)後端未啟動或 `host.docker.internal` 不通。
- **排查**:
  1. Prometheus `/targets` 頁看哪個 job down、`lastError`。
  2. `docker ps` 看容器狀態;`docker logs <container>`。
  3. backend target:確認本機後端在跑、:8080 可達(dev 經 `backend:host-gateway`)。
- **處置**:重啟對應容器 / 服務;確認 `--env-file .env` 與網路 `seckill-dev_default` 正常。

---

## 附:啟動與冷啟動

```bash
# 於 repo 根,務必帶 --env-file .env
docker compose --env-file .env -f infra/docker-compose.dev.yml up -d
docker compose --env-file .env -f infra/docker-compose.monitoring.yml up -d
# 後端(dev,本機):cd backend;設 JAVA_HOME、載 .env;./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

- Prometheus:http://localhost:9090(`/targets`、`/alerts`)
- Grafana:http://localhost:3000(匿名可看;admin 密碼走 `.env` `GRAFANA_ADMIN_PASSWORD`)
- RabbitMQ 管理 UI:http://localhost:15672
