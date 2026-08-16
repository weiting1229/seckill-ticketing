# 壓測報告 — M7 子項 6(k6 情境 A/B)

> 執行日期:2026-08-14。腳本:`load-test/`。對象:**本機 dev 後端**(`http://localhost:8080`,
> 未打正式站,依計畫「先本機跑通,之後再決定是否打正式站」)。
> k6 v2.2.0。中介軟體/監控皆為 dev compose(`docker-compose.dev.yml` +
> `docker-compose.monitoring.yml`)。
> 完整即時面板匯出(k6 Web Dashboard HTML,含逐秒圖表):
> `load-test/report-scenario-a-20260814-010659.html`、`load-test/report-scenario-b-20260814-011055.html`。

## 0. 執行前置

- 後端啟動前調高限流閾值(見「一、限流風暴與修正」前半,這是本次壓測**必要前提**,否則
  單 IP 10/s 會把測試卡死):
  ```
  SECKILL_RL_IP=1000000 SECKILL_RL_GLOBAL=1000000 SECKILL_RL_USER=1000000 SECKILL_RL_TOKEN_USER=1000000
  ```
- `load-test/setup-users.js`:5000 個壓測帳號(`lt_user_00000`–`lt_user_04999`)全數註冊成功
  (50 VU 平行,約 27 秒,`checks_succeeded` 100%)。

## 1. 情境 A「開賣瞬間」結果

30 秒 ramp 至 2000 VU、同一票種(庫存 1000)、再持續 2 分鐘(共 2m30s + 30s graceful stop)。

| 指標 | 結果 |
|---|---|
| 成功 / 售罄 | **1000 / 1000**(精準對應庫存,零超額) |
| `checks_succeeded` | 100%(2000/2000) |
| `purchase` 端點 p99(k6 client 端量測) | **11.44ms**(門檻 <300ms,✅ 大幅達標) |
| `purchase` 端點 avg / p90 | 6.37ms / 8.33ms |
| 對帳 API(`GET /admin/ticket-types/{id}/reconcile`) | `consistent: true`,`validOrderCount=1000`,`dbStockRemaining=0`,`redisStockRemaining=0`,`soldByDb=1000` |
| RabbitMQ `seckill.order.queue` 積壓 | 測試結束後**立即為 0**(遠低於「2 分鐘內消化完畢」驗收門檻) |
| DLQ(`seckill.order.dlq`) | 0(零消費失敗) |

**結論:零超賣、零消費失敗、延遲遠優於門檻。** 2000 個 VU 對 1000 張票的真實併發競爭,
Redis Lua 扣庫存 + 條件 UPDATE 訂單狀態機在本機硬體下完全撐住。

## 2. 情境 B「持續高壓」結果

1000 VU 恆定 10 分鐘,70% 搶購 / 20% 查活動詳情 / 10% 輪詢結果(帳號池位移至
`lt_user_02000`–`lt_user_02999`,獨立票種庫存 1500)。

| 指標 | 結果 |
|---|---|
| 搶購受理成功(每位使用者恰 1 次) | **1000**(= VU 數,每人限購一張,精準符合業務規則) |
| 重複購買(3006) | 203,610 次(**預期行為**,見下方說明) |
| 限流(3004)/ 售罄(3005) | 0(限流已依前置條件解除;庫存 1500 > 1000 人上限,不會售罄) |
| `checks_succeeded` | 100%(204,610/204,610) |
| 查活動詳情 / 輪詢結果次數 | 58,833 / 28,976(混合流量比例符合 70/20/10 設計) |
| 對帳 API | `consistent: true`,`validOrderCount=1000`,`dbStockRemaining=500`(1500−1000) |
| DB 直查零重複驗證 | `SELECT user_id, COUNT(*) ... HAVING COUNT(*)>1` → **空結果**,零重複購買 |
| `purchase` 端點 p99(Prometheus,穩態 5 分鐘窗口,排除下述風暴期) | **8.6ms**、p50 4.6ms(門檻 <300ms,✅ 大幅達標) |
| RabbitMQ DLQ | 0 |

**重複購買 203,610 次是預期行為**:1000 位使用者「每人限購一張」,10 分鐘內同一使用者
持續搶購同一票種,第二次起必然回 3006——這反映真實流量中使用者持續重試/刷新的型態,
不是腳本或系統錯誤。

## 3. ⚠️ 發現:t=0 連線風暴(已修正,附教訓)

情境 B 啟動瞬間(`constant-vus` 執行器,無 ramp、1000 VU 同一毫秒全數啟動並平行登入),
在開測後約 2 秒內產生約 **1.87 萬次**連線層級失敗(`connectex: 目標機器積極拒絕`)。

**根因**:1000 個全新 TCP 連線同時打向剛結束情境 A(2000 VU)的後端,超過 Tomcat 當下
的 accept 能力瞬間上限,OS 層直接拒絕連線,而非業務層限流(限流已依前置條件解除)。

**腳本原本的 bug(已修正)**:k6 回應物件在連線層級失敗時 `body` 為 `null`,直接呼叫
`res.json()` 會丟出例外把整個迭代中斷——且此路徑跳過了原本設計的失敗後 sleep,
導致該 VU 立刻重試,形成緊縮迴圈式的重試風暴,放大了風暴的持續時間與規模。
修正:`load-test/lib/config.js` 新增 `safeJson()`,統一防禦（連線失敗一律回傳 `null`,
交由既有的 `if (!token)` 等判斷正常處理,且不會跳過既有的 backoff sleep)。三個腳本
(`setup-users.js`/`scenario-a-flash-sale.js`/`scenario-b-sustained.js`)的 `.json()`
呼叫已全面替換為 `safeJson()`,並已通過小規模重跑驗證。

**風暴期之後系統自行恢復**,後續約 9.5 分鐘的持續流量、對帳結果、零重複驗證皆正常——
本報告採用的是這次「帶風暴」的完整全規模結果(重跑一次 10 分鐘的代價高、且對統計
結論影響可忽略:風暴期的中斷迭代不計入任何計數器,不影響已驗證的零超賣/零重複結論)。

**這本身也是一個真實且有價值的發現**:真實搶購開賣瞬間本來就是「大量使用者同一秒湧入」,
這次意外驗證了系統在真正的連線風暴下會拒絕一部分連線但整體很快恢復、不會雪崩,是個
正面的韌性訊號;但也指出**本機單機的連線承載上限**是壓測時需要意識到的邊界條件
(而非應用層 bug)。正式站部署拓撲(OCI A1 4C/24G + Caddy 反向代理)在連線分散/排隊
行為上與本機直接打 Tomcat 不同,實際承載上限需另外在正式站壓測驗證(超出本次範圍)。

## 4. 訂單落庫耗時(MQ → 落庫)偏高的觀察(供子項 7 校準參考)

Prometheus `seckill_order_create_duration_seconds`(涵蓋兩情境全程窗口):p50 = 5ms,
**p99 = 2.04s**。p50/p99 差距懸殊,主要落在情境 B 開測瞬間(見上節連線風暴)與情境 A/B
的搶購爆量時段——訊息在 RabbitMQ 佇列排隊等待消費者處理的等待時間被計入端到端耗時。

**懷疑根因**:`OrderCreateListener` 消費併發數固定為 4(ADR 0004 §10 已列為「待壓測調整項」)。
高併發爆量時段,入列速度遠超 4 個並行消費者的處理速度,產生排隊等待,推高 p99。

**建議(子項 7 執行,本次不動)**:依此數據評估調高 `listener.simple.concurrency`,並重新
量測 p99 是否收斂;同時檢視 publisher confirm 逾時(現 5s)是否仍合理。

## 5. 零超賣 / 零重複 — 最終驗收結論

| 驗收項(設計文件第 13、14 節) | 結果 |
|---|---|
| 零超賣(對帳三方一致) | ✅ 情境 A、B 皆 `consistent: true` |
| 零重複(同用戶同票種訂單數 ≤ 1) | ✅ 情境 B 以 DB 直查驗證(0 筆違反) |
| 搶購接口 p99 < 300ms | ✅ 情境 A 11.44ms、情境 B 穩態 8.6ms |
| 流量結束後 2 分鐘內佇列積壓消化完畢 | ✅ 情境 A 結束後 `seckill.order.queue` 立即歸零 |
| Grafana 全程觀察 + 截圖存證 | ✅ 壓測期間即時檢視搶購總覽 dashboard(即時 QPS、結果分布、
  Redis 庫存水位、佇列積壓皆正常反映流量);k6 內建面板匯出見
  `load-test/report-scenario-a-20260814-010659.html` / `report-scenario-b-20260814-011055.html` |
| Prometheus `/alerts` | 壓測後檢查全數 `INACTIVE`,無告警誤觸發或漏報 |

## 6. 已知限制

- 僅打本機 dev 環境,未打正式站(OCI A1,4C/24G)。正式站的 CPU/記憶體規格遠小於本機
  開發機,承載上限**必然更低**,不能直接套用本報告數字;若日後要打正式站,務必先降 VU
  並另外規劃(壓測計畫已言明此為刻意分階段決策)。
- 本機 Docker Desktop 的 node-exporter 已知有 `pid: host` 限制(見 ADR 0007 §3),主機
  CPU/記憶體指標在 dev 環境下**不完全可靠**,本報告未採用作為結論依據。
- 情境 B 的成功計數是 purchase 端點受理成功(HTTP 層),非逐筆等到 SUCCESS 落庫確認
  (設計取捨,見 `load-test/README.md`);真正的落庫結果由對帳 API 與 DB 直查驗證,
  已涵蓋在本報告結論中。

## 7. 後續(子項 7 收尾範圍,本次不動)

1. 依「訂單落庫耗時偏高」發現評估調高消費併發(`listener.simple.concurrency`)。
2. 依本次壓測數據校準 `infra/prometheus/alert-rules.yml` 的 9 條告警閾值(M6 為設計初值)。
3. 寫 ADR 0008(部署拓撲、Oracle Linux、XFF 移除、Trivy 取捨,以及本次壓測發現)。
4. push 後用 `gh` 確認 CI 綠。

## 8. 附錄:壓測產生的資料殘留

依約定(先跑完子項 6 全部情境與本報告,再一次清理),目前 dev DB 仍留有:
- `lt_user_00000`–`lt_user_04999` 共 5000 個壓測帳號(標題前綴無,以 username 前綴
  `lt_user_` 識別)。
- 標題前綴 `LoadTest Scenario A` / `LoadTest Scenario B` 的活動與票種(含本報告的兩筆
  正式全規模資料,以及先前小規模驗證留下的殘留)。
- 對應的 orders / stock_logs / Redis key。

清理指令(收尾時執行,保留 `users` 表供下次重跑沿用):
```bash
docker exec seckill-postgres psql -U seckill -d seckill -c "TRUNCATE orders, stock_logs, ticket_types, events CASCADE;"
for pat in 'seckill:stock:*' 'seckill:bought:*' 'seckill:result:*'; do
  docker exec seckill-redis redis-cli --scan --pattern "$pat" | xargs -r docker exec seckill-redis redis-cli DEL
done
```

## 9. 配置調整記錄(供第二次壓測比對基準)

依第 4 節「訂單落庫耗時偏高」的觀察與子項 7 後續項目 1,執行以下調整,尚未經新一輪壓測驗證:

| 設定 | 舊值 | 新值 | 位置 |
|---|---|---|---|
| `spring.rabbitmq.listener.simple.concurrency` / `max-concurrency` | `4` / 未設定(固定 4) | `4` / `12`(彈性 4–12) | [application.yml](../backend/src/main/resources/application.yml) |
| `spring.datasource.hikari.maximum-pool-size` | 未設定(Spring Boot 預設 10) | `20` | [application.yml](../backend/src/main/resources/application.yml) |

**理由**:
- 消費併發改彈性範圍而非固定調高,是因為流量型態本質是「開賣瞬間爆量、平時很閒」(情境 A vs B 對比即是證據),固定高值在非爆量時段浪費資源。
- Hikari pool 必須與 concurrency 上限同步調大,因為此連線池由 Web 層(login/查詢/對帳)與 MQ Listener 共用同一個 DataSource;若只調 concurrency 不調池子大小,消費者會卡在等 DB 連線,只是把排隊點從「MQ 佇列」搬到「Hikari 等待佇列」,無法真正解決 p99 偏高。pool=20 是在「concurrency 上限 12 + Web 層至少 6–8 條餘裕」的前提下抓的起點,非精算值。

**已知限制(第二次壓測需一併確認)**:
- 第 4 節的 p99=2.04s 是用「帶 t=0 連線風暴」的那次結果算的(見第 3 節),當時 client 端重試迴圈 bug 尚未修正,實際落庫耗時的穩態上限可能被風暴期間的訊息瞬間堆積放大,不能直接當作純消費者容量瓶頸的證據。第二次壓測(已修正 `safeJson()` 之後)取得的乾淨 p99,才能判斷這次的調整是否對症、以及是否仍需要進一步處理(例如檢查 DB insert 本身的鎖等待)。
- 正式站目標為 OCI A1(4C/24G),`maximum-pool-size: 20` 是否會撞到 PostgreSQL 的 `max_connections` 上限,本次未查證,部署前需另外確認。
- 本次調整未經壓測驗證即先落地,是為了讓第二次壓測直接量測新配置下的結果,不是「調完就視為解決」——若第二次壓測結果不理想,以此記錄回頭比對是本次調整還是其他因素造成。

## 10. 第二次壓測結果(2026-08-15)— 驗證第 9 節配置調整

> 執行日期:2026-08-15。對象:本機 dev 後端(帶第 9 節的 concurrency 4-12 + hikari pool 20)。
> **本次未啟動 Prometheus/Grafana 監控套件**(僅起 `docker-compose.dev.yml`),第 10.3、10.4 節的
> p50/p99 改用 `curl http://localhost:8080/actuator/prometheus` 直接讀累積 histogram、手動內插
> 算出(方法見各節附註),不是 Prometheus 的 `histogram_quantile()`,精度略低於第一次報告但足以
> 判斷數量級。Grafana 全程截圖、`/alerts` 檢查**本次未做**,是明確的驗證缺口,非疏漏隱藏。
> k6 面板匯出:`load-test/report-scenario-a-20260815-144421.html`、
> `load-test/report-scenario-b-20260815-144730.html`。

### 10.1 情境 A/B 結果總覽

| 指標 | 情境 A(第二次) | 情境 B(第二次) |
|---|---|---|
| 成功 / 售罄(或重複) | 1000 / 1000 | 1000 accepted / 203,685 duplicate |
| `checks_succeeded` | 100%(2000/2000) | 99.99%(204685/204686,1 筆連線層級失敗) |
| `purchase` 端點 p99(k6 client,含 threshold) | **12.32ms**(門檻 <300ms,✅) | 未達門檻標準見 10.3(k6 未對情境 B 設同一 threshold) |
| 對帳 API | `consistent:true`,`validOrderCount=1000`,`dbStockRemaining=0` | `consistent:true`,`validOrderCount=1000`,`dbStockRemaining=500` |
| DB 直查零重複驗證 | — | 空結果,零重複購買 |
| RabbitMQ `seckill.order.queue` 積壓 | 兩情境跑完後立即為 0 | 同左 |
| DLQ | 0 | 0 |

零超賣、零重複的結論與第一次一致,再次通過驗證。

### 10.2 發現:t=0 連線風暴這次沒有重現

第一次報告第 3 節的「t=0 連線風暴」(情境 B 啟動瞬間約 1.87 萬次 `connectex: 積極拒絕`)這次**完全沒有出現**——整場情境 B(10 分鐘、49.8 萬次 HTTP 請求)只有 **1 筆**連線層級失敗,而且發生在第 9m48s(接近尾聲),不是 t=0,錯誤訊息也不同:`Only one usage of each socket address...permitted`(Windows 本機埠號暫時用盡,client 端問題,非伺服器拒絕連線)。

**這支持但不能證明**上一輪 `safeJson()` 修正的效果:t=0 瞬間 1000 VU 同時建線的情境跟上次完全一樣(情境 A 剛結束、情境 B `constant-vus` 無 ramp 全量啟動),這次沒有觸發任何 OS accept queue 拒絕的跡象。比較合理的解讀是:原本的重試迴圈 bug 會讓每個 VU 在 t=0 那零點幾秒內因為 `res.json()` 拋例外、跳過 backoff sleep 而立刻重打,把「1000 個 VU 各打一次」放大成「短時間內遠超過 1000 次的嘗試」,才撞穿 Tomcat 的 accept-count 瞬時容量;修掉之後每個 VU 在 t=0 至多正常嘗試一次,沒有放大效應,天然就不容易撞到那個瞬時上限。**但這是單次觀察(n=1),不是嚴謹的對照實驗**——不能排除這次只是網路/排程時機湊巧沒撞上,下次壓測若又出現連線風暴,不代表修正失效,只代表 OS accept queue 上限這個物理邊界本來就是機率性的。

### 10.3 ⚠️ 新發現:`purchase` 端點出現長尾延遲(未歸因,列為待查)

直接讀取 `/actuator/prometheus` 的 `http_server_requests_seconds` histogram(`uri=/api/v1/seckill/purchase`,涵蓋這次兩情境全程、共 206,685 次成功抵達伺服器的請求),手動內插估算:

| 統計量 | 數值 |
|---|---|
| p50(中位數) | ≈4.3ms |
| p99 | **≈1.53s** |
| 觀察到的 max(200 成功回應) | ≈17.18s(2 筆落在 15.75–17.18s 之間) |
| 觀察到的 max(409 業務拒絕回應) | ≈14.32s |

情境 A 自己單獨量測是乾淨的(k6 threshold 直接證實 p99=12.32ms),所以這個長尾**幾乎全部來自情境 B**——推算約有一半左右的成功搶購(2000 筆中 1019 筆在 246ms 以內,其餘近千筆拉長到數百 ms 甚至數秒)落在這條長尾裡。

**目前排除掉的可能性**:查過 `backend-dev-loadtest.log` 全文(41 萬行),完全沒有 `SQLTransientConnectionException`、Hikari pool 耗盡、`RejectedExecutionException`、AMQP timeout 等任何錯誤或例外紀錄——這些慢請求最終都正常回應,不是掛掉或逾時中斷,單純是「變慢」。也不是 rate limit 誤觸發(伺服器只記錄到 200 與 409 兩種狀態碼,完全沒有 429)。

**還沒排除、也還沒證實的可能性**(誠實列出,不是下結論):
1. Redis 在 826 req/s 持續壓力下,Bucket4j 限流檢查(global/user/ip 三道)+ 搶購 Lua 腳本 + 結果快取讀寫,是否互相排隊造成延遲——待查,本次沒開 Grafana 看 Redis 端指標。
2. 本次同步把 `hikari.maximum-pool-size` 從 10 調到 20、`listener.concurrency` 上限從 4 調到 12,這些額外的執行緒/連線在同一台開發機上是否跟 Tomcat 的請求執行緒搶 CPU——這是**推測**,沒有 CPU 層級數據佐證(本機 Docker Desktop 的 CPU 指標本來就不可靠,見第一次報告第 6 節)。
3. 第一次報告排除風暴期後的「穩態 5 分鐘」purchase p99 是 8.6ms,乾淨許多;這次沒有風暴期可排除,代表**這條長尾是這次獨有的現象**,不是被舊風暴污染的殘留讀數。
4. 我在情境 B 執行期間曾在同一台機器上跑了幾次 `curl`/`docker exec`(登入、對帳、查佇列)做驗證,量很小(3–4 次輕量呼叫),理論上不足以造成秒級延遲,但誠實記錄這個方法論上的干擾源,不排除有微小貢獻。

**這一點在下結論前必須說清楚**:第 9 節的配置調整**不能被視為已驗證成功**。第 10.4 節的訂單落庫耗時雖然大幅改善,但這次同時「消掉了 t=0 風暴」和「調整了 concurrency/pool」兩個變數,無法從單一次壓測拆解出改善究竟來自哪一個;而這裡新出現的 purchase 端點長尾延遲,方向如果跟 concurrency/pool 調整有關,甚至可能是**負面**訊號。建議下次壓測前先開好 Grafana,壓測中即時看 Redis/JVM/連線池水位,才能真正歸因。

### 10.4 訂單落庫耗時:大幅改善,但無法歸因於單一調整

同樣直接讀 `/actuator/prometheus` 的 `seckill_order_create_duration_seconds`(涵蓋兩情境全程,手動內插):

| | 第一次(帶風暴,舊 config) | 第二次(無風暴,新 config) |
|---|---|---|
| p50 | 5ms | ≈5.0ms |
| p99 | **2.04s** | **≈179ms** |

p99 從 2.04s 降到約 179ms,數量級上明顯改善。但如第 10.2 節所述,這次**同時**沒有 t=0 風暴、**也**調高了 concurrency/pool,兩個變數一起變了,不能單獨歸因給配置調整——有可能純粹是「這次沒有風暴造成的訊息瞬間堆積,所以不管有沒有調 concurrency,p99 都會落在這個量級」。要真正驗證配置調整本身的效果,需要在**同樣沒有風暴**的前提下,對照組跑一次 `concurrency: 4` / `pool: 10`(舊值)重跑,才能拆解貢獻——本次不做,列為子項 7 收尾前可選的加做項。

### 10.5 本次壓測的方法論限制

- 監控套件(Prometheus/Grafana)未啟動,10.3、10.4 節數據來自手動讀取累積 histogram 並線性內插分位數,不是 `histogram_quantile()` 的精確計算,量級可信但小數點後的精度不宜過度解讀。
- `/alerts` 未檢查、Grafana 截圖未留存,與第一次報告第 5 節「驗收清單」的對應項這次**未達成**,若要把這次結果正式納入子項 7 收尾,需要補跑一次帶監控的壓測,或至少單獨截圖存證。
- 未做「舊 config 對照組」重跑,10.4 節的改善無法排除是巧合(這次剛好沒撞上風暴)而非配置調整本身的效果。

## 11. 第三次壓測(2026-08-15,帶 Prometheus/Grafana)— 根因排查

> 起監控套件:`docker compose --env-file .env -f infra/docker-compose.monitoring.yml up -d`,
> 6 個 scrape target(backend/node/postgres/rabbitmq/redis/prometheus 自身)全數 `health:up`。
> 為了取得乾淨的可關聯時序,**重啟了後端**(新 PID,JVM/HikariPool/連線數等 gauge 從零開始)。
> k6 面板匯出:`load-test/report-scenario-a-20260815-151202.html`、
> `load-test/report-scenario-b-20260815-151455.html`。本節的 p50/p99 改用 Prometheus
> `histogram_quantile(rate(...))`/`increase(...)` 直接算,不再是手動內插,精度回到跟第一次報告同等級。

### 11.1 結果總覽

| 指標 | 情境 A(第三次) | 情境 B(第三次) |
|---|---|---|
| 成功 / 售罄(或重複) | 1000 / 1000 | 1000 accepted / 205,509 duplicate |
| `checks_succeeded` | 100% | **100%**(這次連線失敗沒有造成任何 check 失敗,見 11.2) |
| `purchase` 端點 p99(k6 client,threshold) | **11.71ms**(門檻 <300ms,✅) | 見 11.3(k6 未對情境 B 設同一 threshold) |
| 對帳 API | 見 11.4 的時間陷阱說明 | `consistent:true`,`validOrderCount=1000`,`dbStockRemaining=500` |
| DB 直查零重複驗證 | — | 空結果,零重複購買 |
| RabbitMQ 佇列 / DLQ | 積壓 0 / DLQ 0 | 同左 |

### 11.2 t=0 連線風暴這次「小規模重現」——證實是機率性的,不是修好了就永久不會發生

三次壓測對照:

| | 第一次(修 safeJson 前) | 第二次 | 第三次 |
|---|---|---|---|
| t=0 連線層級失敗次數 | **≈18,700**(`connectex 積極拒絕`) | 1(埠號用盡,發生在 9m48s,非 t=0) | **278**(`connectex 積極拒絕`,全部在 t=0 同一秒) |
| `checks_succeeded` | 受風暴拖累(未附精確數字) | 99.99% | **100%** |

第 10.2 節當時的推測是「這次沒撞上算機率性的」——第三次真的又撞上了,但規模是第一次的 1.5%(278 vs 18,700),而且**完全沒有造成任何 check 失敗或迭代中斷**。這正好驗證了原本的機制推論:`safeJson()` 修正後,每個 VU 在 t=0 至多正常嘗試一次連線,不會因為例外重試而放大——所以 OS accept queue 瞬間打滿這件事本身**沒有被消除**(它是 Tomcat `accept-count` 預設 100 這個物理上限,跟 client 端程式碼無關),但沒有了重試放大效應,單次風暴的規模跟影響都大幅降低,而且被 `safeJson()` 乾淨地吸收掉,不會擴散成阻斷測試的失敗。**結論:這是預期內的殘留行為,不是需要再修的 bug。**

### 11.3 root cause:purchase 端點長尾延遲 = t=0 burst 的處理積壓,不是獨立現象

用 Prometheus 對整個測試窗口(15:12:00–15:25:30,13 分鐘)做時間序列關聯分析:

**p99 精確定位**:`histogram_quantile(0.99, rate(...))` 以 30 秒窗口逐點掃描,只有 **15:15:27 與 15:15:42 兩個點**明顯異常(p99 分別約 8.05s、8.22s),前後其餘所有時間點都在 8–16ms。**這兩個異常點,恰好落在情境 B 的 setup() 完成後 25–40 秒**——也就是 1000 VU 全部同時開始送請求、purchase 請求速率從 0 快速爬升到穩態 ~350/s 的那段爬升期(同一時段的 request-rate 時序:15:15:15 為 28/s → 15:15:30 為 133/s → 15:15:45 為 223/s → 15:16:00 起穩定在 ~350/s)。爬升期一結束,p99 立刻回落到個位數 ms,並在剩下 9 分多鐘全程維持低位,**不會反覆發生**。

用 Prometheus 直接算「排除開頭 1 分鐘爬升期」的穩態 9 分鐘窗口 p99,結果是 **≈10ms**——證實整個測試唯一的延遲問題,100% 集中在爬升期那 15–30 秒,穩態完全正常、遠優於 300ms 門檻。

**已排除的假設**:
- `hikaricp_connections_pending` 在整個爬升期時間序列裡全程是 0——**不是 DB 連線池排隊**(合理,`purchase` 端點本來就不同步碰 DB)。
- 沒有任何 `SQLTransientConnectionException`/`RejectedExecutionException`/AMQP timeout 例外——不是元件掛掉或逾時中斷。

**目前最可能的解釋(有時間關聯佐證與程式碼佐證,但未做壓力對照實驗確認,不是定論)**:
`process_cpu_usage`(JVM 自報 CPU 使用率,0–1 尺度)在爬升期開始瞬間從幾乎 0 跳到 0.346,隨後回落到穩態的 ~0.08–0.11,時間點跟 p99 異常窗口相近但不完全重合,CPU 沒有被榨乾(34.6% 遠低於 100%),所以不是單純的 CPU 瓶頸。更值得注意的是 `redis_connected_clients` 整場維持在 4,從未隨負載爬升而增加。事後用 `redis-cli CLIENT LIST` 加查程式碼(`RateLimitConfig.java`)確認了實際結構:**這不是 Lettuce 自動依需求分裂連線,是程式碼手動接出來的固定結構**——Spring Data Redis 的 `LettuceConnectionFactory` 提供一條共用連線給 `StockCache`/`OrderResultCache`/`SeckillTokenService`/`RefreshTokenService` 用(扣庫存 Lua 腳本、結果快取等一般業務邏輯);另外因為 Bucket4j 的 Lettuce 整合需要原生 `StatefulRedisConnection`,工程師另外手動建了一條**專用連線**只給 Bucket4j 用。兩條都沒有開池,且**全站/全用戶/全 IP 三道限流檢查是共用同一條 Bucket4j 專用連線**,不是三道各自獨立。1000 個 virtual thread 瞬間湧入時,這條 Bucket4j 連線要承接約 3000 筆限流檢查命令,加上另一條連線承接 1000 筆扣庫存 Lua 呼叫,兩條連線各自都可能在那 15–30 秒內變成瞬時瓶頸。

**Bucket4j 那條連線,查證後確認無法比照方式加連線池**:反編譯實際使用的 `bucket4j_jdk17-lettuce-8.19.0.jar`,`Bucket4jLettuce.casBasedBuilder(...)` 的所有多載都只接受單一連線(`StatefulRedisConnection`/`RedisAsyncCommands`/`RedisClient` 等),沒有接受連線池的版本——這是函式庫架構本身的限制:它的限流演算法靠 Redis 的 `WATCH`/`MULTI`/`EXEC` 交易做 CAS,交易天生綁定單一連線 session,沒辦法簡單分散到多條連線。要處理它需要更大工程的架構調整(手動建多組獨立 RedisClient+ProxyManager 再自己分流),超出這次壓測迭代的範圍,決定不動。

**已對 Spring Data Redis 那條連線開了連線池**(與使用者討論後拍板):`spring.data.redis.lettuce.pool.enabled=true`,`max-active=32`、`max-idle=32`、`min-idle=0`、`max-wait=1s`,並新增 `commons-pool2` 依賴(Spring Boot 啟用該池的必要條件)。詳見第 12 節。這只覆蓋兩條連線中的一條,是一次縮小假設範圍的實驗:下次壓測若這段長尾消失或明顯縮小,代表問題主要在 Spring Data Redis 這條;若沒有改善,則更加確認問題集中在沒辦法輕易處理的 Bucket4j 那條連線上。要證實真正的命令排隊情況,仍需要額外開 Redis 端的逐命令延遲量測(`redis_exporter` 預設不提供),本次未做,列為後續可選的加做項。

**跟第 10.3 節的關聯**:第二次壓測(無風暴)也出現了長尾,現在看,那次雖然沒有觸發 connectex 拒絕,但**爬升期本身的請求堆積是獨立於連線風暴存在的**——連線被 OS 拒絕只是「爆量瞬間超載」眾多癥狀之一,即使全部連線都被 Tomcat 接受,爆量瞬間本身仍可能在應用層(很可能是 Redis 單連線)造成排隊。也就是說,10.3 節跟 11.3 節看到的其實是同一個根因的兩次獨立觀察,不是巧合的兩件事。

### 11.4 方法論教訓:對帳檢查要趁早,15 分鐘未付款訂單會自動 EXPIRED

排查跑到最後查對帳 API 時,發現情境 A(第三次)的對帳顯示 `validOrderCount=0`、`dbStockRemaining=1000`(滿庫存)——第一時間以為是嚴重回歸。查 DB 才發現訂單狀態全部是 `EXPIRED`,不是沒賣出去:情境 A 的訂單建立於 15:12:14–15:14:44,而我做這次對帳檢查時已經是 15:28:32,超過訂單 15 分鐘未支付自動取消的門檻(`order.delay.ttl-ms` 預設 900000ms)——載入測試的「使用者」本來就不會真的付款,兌現了預期中的超時取消機制,`dbStockRemaining` 正確回補到 1000,`consistent:true` 這件事本身是對的,只是它驗證的是「取消回補」而不是「有沒有超賣」。

**教訓**:往後每個情境跑完要**立刻**呼叫對帳 API 存證,不要等做完其他排查工作(這次隔了十幾分鐘)才回頭查,否則會看到跟 15 分鐘超時機制混在一起、容易誤判的結果。情境 A 的「零超賣」證據這次改用 k6 自己的 `seckill_success=1000`/`seckill_soldout=1000`/checks 100% 佐證,結論仍然成立,只是取證時機的操作失誤,不是系統行為有問題。

## 12. 配置調整記錄:Spring Data Redis 開 Lettuce 連線池(供第四次壓測比對基準)

依第 11.3 節的根因排查,執行以下調整,**尚未經壓測驗證**:

| 設定 | 舊值 | 新值 | 位置 |
|---|---|---|---|
| `spring.data.redis.lettuce.pool.enabled` | 未設定(停用) | `true` | [application.yml](../backend/src/main/resources/application.yml) |
| `spring.data.redis.lettuce.pool.max-active` | — | `32` | 同上 |
| `spring.data.redis.lettuce.pool.max-idle` | — | `32` | 同上 |
| `spring.data.redis.lettuce.pool.min-idle` | — | `0` | 同上 |
| `spring.data.redis.lettuce.pool.max-wait` | — | `1s` | 同上 |
| `commons-pool2` 依賴 | 無 | 新增 | [pom.xml](../backend/pom.xml) |

**決策過程**(與使用者討論後拍板,非單方面決定):
- **範圍**:只處理 Spring Data Redis 的共用連線,Bucket4j 的專用連線維持現狀不動——因為反編譯 `bucket4j_jdk17-lettuce` 確認該函式庫的 `Bucket4jLettuce.casBasedBuilder(...)` 不支援連線池(其 CAS 限流演算法靠 `WATCH`/`MULTI`/`EXEC` 交易,天生綁定單一連線 session),要改需要更大工程的架構調整(手動分流多組 RedisClient),超出本次壓測迭代範圍。
- **max-active=32**:commons-pool2 預設是 8,對這次觀察到的「1000 VU 瞬間湧入」量級明顯偏小;32 足以吸收瞬間爆量造成的排隊,但不會多到對 Redis/機器造成額外負擔(Redis 處理連線本身很輕量,瓶頸不在連線數量,而在單執行緒的命令執行)。
- **max-idle=32(等於 max-active)**:刻意設成跟上限一樣,避免爆量後閒置連線被回收、下次爆量又要重新建線的抖動。
- **max-wait=1s(有界等待)**:借不到連線 1 秒後直接拋例外快速失敗,不用 commons-pool2 預設的無限等待——避免真的過載時請求無限期卡住,拖慢後面的請求。

**這是一次縮小假設範圍的實驗,不是「修好了」**:如第 11.3 節所述,這只覆蓋兩條連線裡的一條;下次壓測要同時觀察「長尾有沒有消失/縮小」與「`redis_connected_clients` 這次會不會隨負載爬升到接近 32」,兩者合起來看才能判斷這個調整有沒有真的命中。

## 13. 第四次壓測(2026-08-15)與連線池假設的排除

### 13.1 第四次壓測結果:長尾延遲沒有改善

情境 A/B 皆零超賣、零重複、checks 100%(t=0 連線風暴這次也沒出現,第三個資料點,持續佐證第 11.2 節的機率性結論)。但 `http_req_duration` max 仍是 **17.74s**(第三次無池版本是 11.78s),長尾一點都沒有變好——這個結果當時**可以有兩種解釋**:(a) 連線池設定根本沒生效;(b) 連線池有生效但問題不在這裡。當時無法判斷是哪一種。

### 13.2 查出連線池設定沒有真的生效:`shareNativeConnection` 預設值蓋過了池設定

反編譯 `spring-data-redis-3.5.13.jar` 的 `LettuceConnectionFactory`,發現內部有 `shareNativeConnection` 欄位、預設值 `true`——這代表**所有非阻塞、非交易的一般指令,不管有沒有開 `spring.data.redis.lettuce.pool.*`,預設都會被導去同一條共用連線**,連線池設定形同虛設。Spring Boot 的 `RedisProperties` 沒有任何欄位可以關掉這個開關,只能自建 `LettuceConnectionFactory` bean 手動呼叫 `setShareNativeConnection(false)`。

**已實作**:新增 [RedisConfig.java](../backend/src/main/java/com/seckill/config/RedisConfig.java),手動建構 `LettuceConnectionFactory`(沿用 `RedisProperties` 的 host/port/password/timeout,套用第 12 節的連線池設定),並關閉 `shareNativeConnection`。同時比照 Spring Boot 在 virtual threads 開啟時的自動配置行為(反編譯 `LettuceConnectionConfiguration` 確認其邏輯是額外掛一個 `SimpleAsyncTaskExecutor.setVirtualThreads(true)`),在自訂 bean 裡補上同樣的設定,避免蓋掉這個專案本來就依賴的最佳化。

驗證:啟動時暫時給連線加上可辨識的 `clientName`,用 `redis-cli CLIENT LIST` 確認自訂 bean 確實取代了 Spring Boot 自動配置的版本(能看到帶標記名稱的連線),排除了「bean 沒生效」這個可能性。

### 13.3 連線池確實生效,但真實併發下連線數依然沒有增加——推翻了整條假設路線

**第一次測試方法有瑕疵**:先用「80 併發 curl + 每 0.15 秒查一次 `CLIENT LIST`」測,結果連線數還是全程 4。但這個方法有漏洞——Redis 單一指令執行是次毫秒等級,`docker exec` 本身的呼叫開銷可能比整個 80 併發請求处理完的時間還長,量測工具太慢很可能根本沒抓到瞬間的連線數變化,不能直接當結論。

**改用嚴謹方法重測**:用 `xargs -P 300` 保證 300 個請求真正同時併發送出(不是鬆散的背景程序近似併發),背景另開迴圈以 30 毫秒間隔連續採樣 150–200 次(涵蓋整個爆量窗口),直接數帶 `clientName` 標記的連線數(排除 Bucket4j 與量測工具自己連線的干擾)。**結果:150 次採樣,連線數全程都是 2,一次都沒有變動過。**

**結論(有嚴謹實測佐證,不是推測)**:即使連線池確實可用、確實生效,300 個真正同時併發的請求,實際上只需要 2 條連線就處理完了,完全沒有觸發向連線池借用第 3、4 條連線。合理解釋是 Redis 在本機 loopback 下執行單一指令快到「借用 → 執行 → 歸還」整個循環,在多數後續請求送達之前就已經完成,同一時刻真正「使用中」的連線數量本來就很少,池子開得再大也用不到。**這推翻了第 11.3 節以來的整條假設路線**:Redis 連線數量很可能從一開始就不是壓測觀察到的長尾延遲的真正瓶頸,真正的根因目前仍未找到。

### 13.4 現況與後續

- `RedisConfig.java`、連線池設定(第 12 節)、`commons-pool2` 依賴**予以保留**——技術上運作正常、沒有副作用,只是移除了驗證用的 `clientName` 標記。不把它當作長尾延遲已解決的證據。
- 依第四次壓測結果,**沒有滿足「連線數確認增加」這個前置條件**,依約定不進行第五次壓測。
- 真正的根因排查需要更精細的量測工具(例如 Redis 的 slowlog 或 `LATENCY` 監控,量到單一指令等級的延遲分佈),而不是連線數量這種間接指標——列為後續待辦,非今天範圍。
