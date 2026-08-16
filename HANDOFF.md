# HANDOFF:壓測長尾延遲根因排查(交接文件)

> 建立日期:2026-08-15,2026-08-16 大幅精簡(移除已證實的探索雜訊,只留結論與原始數據)。
> 完整逐次原始數據(run1–4)見 [`docs/load-test-report.md`](docs/load-test-report.md);run5 之後的數據只存在本文件。

## 已結案:長尾延遲根因(不用重查)

**症狀**:情境 B(1000 VU 恆定 10 分鐘)壓測時,`/api/v1/seckill/purchase` 出現 p99 飆到 8–30 秒、少數請求 max 到 17–60 秒的長尾延遲,穩態 p99 只有個位數毫秒。每次壓測穩定重現。**不影響業務正確性**——零超賣零重複購買,每次都有對帳 API 驗證通過。

**根因**:`seckill:rl:global`(全站唯一共用的限流 key)在 Redis 單執行緒佇列裡因熱 key 爭用而排隊,瞬間並發峰值越高,佇列就堆得越深。

**證據(兩階段,由弱到強)**:

1. **Timer 相關性**(run9,幫 `RateLimiterService` 四個 `try*` 方法加 Micrometer Timer 量測端到端耗時,含排隊等待):`seckill_ratelimit_check_duration_seconds{layer="global"}` 的 p99 時序幾乎逐點貼著 HTTP p99:

   | 時間點 | HTTP p99(秒) | 限流 global 層 p99(秒) | 限流 ip 層 p99(秒) |
   |---|---|---|---|
   | 22:17:50 | 30.00 | 27.13 | 4.79 |
   | 22:18:20 | 27.82 | 26.47 | 6.22 |
   | 22:19:50 | 23.70 | 21.61 | 11.17 |
   | 22:25:50 | 22.07 | 18.26 | 12.82 |
   | 22:27:20 | 21.95 | 16.80 | 14.28 |

   `user`/`token_user` 兩層全程 p99 都是 30–40 毫秒,完全正常(這兩層 key 按 userId 分散,不會撞)。低併發對照組(情境 A)同時段 `global` 層 p99 只有 2 毫秒。

2. **消融測試(因果坐實)**(run10,dev-only 開關讓 `tryGlobal`/`tryUser`/`tryIp`/`tryTokenUser` 整段跳過、直接 `return true`,不呼叫 Redis):

   | 狀態碼 | bypass 開啟後 p99 | bypass 關閉基準(run5–9) |
   |---|---|---|
   | 409(重複/售罄,佔絕大多數) | 開頭 170ms,之後穩定 3.0–3.4ms,整整 10 分鐘 | 8–30 秒,max 17–60 秒 |
   | 200(成功) | 唯一有效窗口 443ms | 同上量級 |

   量級差三到四個數量級,異常實質消失。開關程式碼保留在 [RateLimiterService.java](backend/src/main/java/com/seckill/seckill/ratelimit/RateLimiterService.java)(`bypassEnabled`)+ [application.yml](backend/src/main/resources/application.yml) dev profile(`bypass-enabled`),預設 `false`、prod 沒有這個 key,不影響正常環境。

**已排除的假設**(逐一測過,不用重查):

| 假設 | 排除方式 |
|---|---|
| DB(Hikari)連線池排隊 | `hikaricp_connections_pending` 全程 0 |
| 元件掛掉/例外中斷 | 查過 41 萬行 log,無相關例外 |
| 限流誤觸發(429) | 伺服器只有 200/409,無 429 |
| Redis 連線數/連線池不夠 | 開了連線池(`RedisConfig.java`)並驗證生效,300 併發實測連線數仍只有 2,長尾延遲無改善 |
| RabbitMQ publisher-confirm 同步等待 | counter 顯示 confirm 幾乎即時完成,且 `OrderMessagePublisher.publish()` 硬 timeout 5 秒、無重試,理論上限遠低於實測 p99(8–15秒),矛盾到足以排除 |
| Virtual thread pinning | 加 `-Djdk.tracePinnedThreads=full` 跑完整場 10 分鐘,pinning log 零筆記錄 |
| JIT 暖機不足 | 先跑情境 A 熱身再接情境 B,異常模式完全沒有改善(甚至更差) |

**方法論教訓(以後每次壓測都要遵守)**:
- **每次壓測前完全重啟 docker + backend**,不要連續多輪沿用同一行程——同一 JVM/容器連續跑多輪會放大異常(run5/6 連續跑三輪 vs run7 單輪乾淨對照,差異明顯,已用對照實驗確認)。但**乾淨重啟不保證只重現「僅開頭異常」的模式**,run7/8/9 都是乾淨重啟卻分別出現「僅開頭」跟「持續整場」兩種模式——這個變異性不影響根因結論,但解讀單次測試結果時不要只憑一次乾淨對照就下定論。
- **每次情境跑完立刻呼叫對帳 API**,不要拖到後面——訂單 15 分鐘未付款會自動 `EXPIRED`,拖久了對帳會混進超時取消的雜訊。
- 排查新假設優先找**直接量測**的方法(Timer/slowlog),不要只靠間接指標(連線數、CPU%)去猜——這次排查曾用間接推理誤判過 Redis 連線數是瓶頸,後來被直接量測推翻。

## 目前唯一待解問題:找下游真實承載上限(步驟 2,進行中)

**目標**:在繞過限流(`SECKILL_RL_BYPASS=true`)的乾淨狀態下,逐步加大流量,量出 DB/MQ/建單流程真正的承載上限,回答「`global-capacity=3000` 這個門檻夠不夠」。

**已知限制**:情境 B 腳本(`scenario-b-sustained.js`)每個 VU 全程固定綁定一個帳號,「每人限購一張」是 DB 條件 UPDATE 強制的業務規則——**成功購買數的天花板是 VU 數,不是庫存數**。單純調高 `SCENARIO_B_STOCK` 沒用,必須同步調高 `SCENARIO_B_VUS`(帳號池 `USER_POOL_SIZE` 目前 5000,扣掉情境 A 占用的 0–1999,情境 B 最多用到 2000–4999 共 3000 個帳號)。

**卡住的地方(run11)**:`SCENARIO_B_VUS=3000` 一測就把測試環境自己打壞,數據作廢——k6 client、Spring Boot backend、Redis 全部擠在同一台 Windows 機器上共用同一個 TCP 臨時連接埠池(預設約 16384 個,連線關閉後要 `TIME_WAIT` 約 120 秒才能回收)。3000 VU 在 10 分鐘內開的連線數遠超這個池子,一旦「已用埠數 + TIME_WAIT 卡住的埠數」超過總量,新連線被作業系統直接拒絕且連鎖不會自己恢復。實測:87.8% 請求(785,816/895,160)失敗於 `connectex: Only one usage of each socket address...`,不只 k6→backend,連 backend→Redis 也中箭(`RedisConnectionFailureException: ...Address already in use`)。1000→3000 VU 不是線性變差,是斷崖式(從幾乎 0 直接跳到 87.8%),證實是測試機資源耗盡,不是後端真的撐不住。

**已定案(2026-08-16):放棄修本機測試拓樸,直接對 OCI 正式站壓測**——原本列的三個本機修復選項(調 Windows 動態連接埠範圍、換機器/WSL2 分離 k6、本機分段找安全 VU 上限)**全部沒有 OCI 參考價值,不用再花時間**:這三個選項解的是「Windows + Docker Desktop + k6/backend/Redis 擠在同一台機器」這個本機特有拓樸的自爆問題,量到的任何數字(安全 VU 上限、埠設定)都不會反映 OCI(原生 Linux、4C/24G ARM、k6 天生從外部機器打入)的真實承載能力,本機修完也不會、不需要帶到 OCI 上。

**下一步直接做**:k6 從開發筆電打正式站 `tixco.kozow.com` 公網 IP(天然滿足「load generator 跟被測系統分開」,不需要額外搭環境)。正式站本來就不對外開放給其他人用,不需要另建 staging OCI 實例(且免費額度也不夠開第二台機器)。**開測前務必先確認 OCI 計費風險**(見下方「OCI 計費風險評估」),不要假設一定不會計費就直接跑大流量。

**後續步驟(還沒開始)**:
- 找到能用的測試拓樸後,另外用一支獨立的小型壓力測試腳本,量出「單一 Redis 節點面對瞬間爆量的限流 CAS 檢查,實際能撐多少併發」——這個也要在 OCI 上測才有意義,理由同上
- 合併「下游承載上限」與「單節點 CAS 上限」的數字(目標流量 ÷ 單節點上限 ≈ 至少要幾個分片),判斷 sharded counter 該拆幾片。分片數越多精確度越差(固定總量下,相對波動約與 `1/√(總量/N)` 成正比),不是拆越多越好

### OCI 計費風險評估(2026-08-16,開測前必讀)

使用者帳號是 **Pay As You Go**,擔心從開發筆電對 OCI 公網 IP 打壓測會不會產生計費。**這件事沒有在這次 session 裡實際查證(無法連進使用者的 OCI Console/帳單),以下是基於已知資訊的評估,不是確認過的事實,開測前使用者務必自己在 OCI Console 核對**:

- OCI 主機規格「4C/24G ARM」剛好對應 OCI Always Free 方案 Ampere A1 的免費總額度上限,計算資源大機率落在 Always Free 範圍內——但 Always Free 是否吃滿、有沒有跟其他資源共用額度,需要在 OCI Console 的資源頁面確認有沒有「Always Free」標籤,不能只憑規格數字推測。
- 计算資源即使壓測期間 CPU 滿載,Always Free 額度是固定配額,不是按使用量計費,理論上滿載不會讓它變成計費資源。
- 真正的風險點是**出向流量(egress)**:OCI 對外流出流量通常有較大的免費月額度,一般 API 壓測(JSON request/response,先前本機 3000 VU 測試 10 分鐘也才 279MB 流出)離免費額度通常有數量級差距——但**具體額度數字、是否所有帳戶類型都適用,沒有查證,不能當作保證**。

**開測前建議動作**(使用者自行執行,不是我能代做的事)——**已於 2026-08-16 全部完成**:
1. ✅ OCI Console → Cost Analysis 確認當月至今花費為 $0
2. ✅ 確認計算實例有 Always Free 標籤
3. ✅ 設定 OCI Budgets,超過 0.01 SGD 就 mail 告警(比原本只依賴帳單 email 更即時)

**下一步:循序漸進對 OCI 壓測,分階段執行**:

- **階段 0(現在,小規模探路)**:極小規模(~20 VU、~30 秒、庫存 20)對正式站跑一次情境 A,**不覆寫任何限流參數**(讓正式站用真實的限流設定,順便也是額外的安全邊界),純粹確認打正式站公網 IP 不會產生預期外的計費,且 app 對真實網域/TLS/Caddy 反向代理的路徑正常運作。跑完立刻回 OCI Cost Analysis 確認沒有異常變化。指令見下方「OCI 壓測準備指令」。
- **階段 1(探路確認安全後)**:用預設規模(情境 A 2000 VU / 情境 B 1000 VU)但**不開 bypass**,在正式站真實限流設定下跑——這是這次排查坐實的根因(`seckill:rl:global` 熱 key 排隊)第一次在真正的 OCI 硬體上驗證,也是真實使用者會經歷的狀況。
- **階段 2(找下游真實承載上限)**:需要在正式站部署啟用 `SECKILL_RL_BYPASS=true`(修改正式環境設定,是比階段 0/1 更大的一步,執行前需另外確認),才能重新做 HANDOFF.md 前面「目前唯一待解問題」段落規劃的下游承載測試。

**執行方式的決定**:階段 0 的指令由**使用者自己在自己的終端機執行**,不透過我的 Bash 工具代跑——原因是需要正式站的 admin 帳密(不在這個 repo 或 `.env` 裡,是部署時的 secret),不應該貼進對話或讓我經手;而且這是第一次真正對有計費風險的正式站送流量,使用者想親自在旁邊盯著 Cost Analysis 看,由使用者親自執行、親眼確認才是最保險的做法。

**OCI 壓測準備指令(供使用者在自己終端機執行,`ADMIN_USERNAME`/`ADMIN_PASSWORD` 換成正式站實際帳密)**:
```bash
# 1. 先在正式站註冊一小批壓測帳號(公開註冊 API,不需要 admin 權限)
BASE_URL=https://tixco.kozow.com USER_POOL_SIZE=30 SETUP_VUS=10 k6 run load-test/setup-users.js

# 2. 極小規模探路(~20 VU、~30 秒、庫存 20,不覆寫限流參數)
BASE_URL=https://tixco.kozow.com \
ADMIN_USERNAME=<正式站 admin 帳號> ADMIN_PASSWORD=<正式站 admin 密碼> \
SCENARIO_A_VUS=20 SCENARIO_A_RAMP_SECONDS=10 SCENARIO_A_HOLD_SECONDS=15 SCENARIO_A_STOCK=20 \
k6 run load-test/scenario-a-flash-sale.js
```
跑完立刻回 OCI Console Cost Analysis 確認花費仍是 $0,再決定要不要進階段 1。

## 已解決的旁支(不影響主線,僅記錄)

**`OrderCreateListener` 冪等分支卡死 bug**:run11 的埠耗盡連帶讓 backend→Redis 連線失敗,意外踩到 `onOrderCreate()` 的 `DuplicateKeyException` 分支裡 `resultCache.writeSuccess()` 失敗時會繞過 `handleUnexpected()` 的重試/DLQ 保護,導致 `seckill.order.queue` 卡了 2465 筆訊息、consumer 靜止 18+ 分鐘不會自己恢復(對帳一度 `consistent:false`)。已由另一個 session 修復並 commit(`66f11d1`),修法是把該分支的 `writeSuccess` 呼叫納入既有的統一重試/死信機制。**已用真實卡住的資料驗證**:重啟 backend 後佇列歸零,對帳恢復 `consistent:true`(`validOrderCount` 從 1815 補齊到 2851,無資料遺失)。

## 環境現況

- 專案路徑:`C:\Users\USER\Documents\seckill-ticketing`
- 目前 docker(dev + monitoring)與 backend 都在跑、健康(2026-08-16 確認),backend **目前沒有帶 `SECKILL_RL_BYPASS`**(正常限流模式)
- 已 commit(2026-08-16):`a7bf782` Timer 量測、`74b9e86` 消融測試開關、`66f11d1` OrderCreateListener 修復
- 若中斷,標準啟動流程:
  ```bash
  docker compose --env-file .env -f infra/docker-compose.dev.yml up -d
  docker compose --env-file .env -f infra/docker-compose.monitoring.yml up -d
  cd backend
  export JAVA_HOME="C:\Users\USER\.jdks\temurin-25\jdk-25.0.3+9"
  set -a && . ../.env && set +a
  export SECKILL_ADMIN_USERNAME=admin_local SECKILL_ADMIN_PASSWORD=AdminLocal123
  # 若要繼續步驟 2(繞過限流找下游上限),加這行:
  export SECKILL_RL_BYPASS=true
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
  ```
- 跑壓測:
  ```bash
  K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_EXPORT="load-test/report-<timestamp>.html" k6 run load-test/scenario-a-flash-sale.js
  SCENARIO_B_VUS=<N> SCENARIO_B_STOCK=<N+500> k6 run load-test/scenario-b-sustained.js
  ```
- Grafana:`http://localhost:3000`(dev 匿名 Viewer)。Prometheus:`http://localhost:9090`
- **多 session 平行開發注意**:`spawn_task` 不會自動建立隔離 git worktree(用 `git worktree list` 確認過),預設會在同一份工作目錄同一分支上直接改。要真隔離需手動 `git worktree add ../<新目錄> -b <新分支>`,且 `.env`(gitignore 排除)要手動複製過去,`infra/docker-compose.dev.yml` 的 container 名稱與 port 是寫死的,兩個 worktree 不能同時各跑一份 dev compose

## 對帳存證記錄(原始數據,全部 `consistent:true` 除非特別註記)

| Run | 情境 | ticketTypeId | validOrderCount | 庫存/備註 |
|---|---|---|---|---|
| run5 | B | 82081943641915392 | 1000 | totalStock 1500,dbRemaining 500 |
| run6 | A | 82087104066093056 | 1000 | totalStock 1000,售完 |
| run6 | B | 82087843052126208 | 1000 | totalStock 1500,dbRemaining 500 |
| run7 | A | 82100567509303296 | 1000 | totalStock 1000,售完 |
| run7 | B | 82101287893598208 | 1000 | totalStock 1500,dbRemaining 500 |
| run8 | A | 82106069031059456 | 1000 | totalStock 1000,售完 |
| run8 | B | 82106787750215680 | 1000 | totalStock 1500,dbRemaining 500 |
| run9 | A | 82114610370445312 | 1000 | totalStock 1000,售完 |
| run9 | B | 82115327940362240 | 1000 | totalStock 1500,dbRemaining 500 |
| run10(bypass) | A | 82320684600000512 | 1000 | totalStock 1000,售完 |
| run10(bypass) | B | 82321595263090688 | 1000 | totalStock 1500,dbRemaining 500 |
| run11 | A | 82341270168535040 | 1000 | totalStock 1000,售完 |
| run11(3000VU) | B | 82341976577409024 | 2851(補齊後) | totalStock 3500;**一度 `consistent:false`,修復後重新對帳已變回 `true`,見上方旁支章節** |

## 已知限制

- 壓測皆為本機 dev 環境單機測試,未打正式站(OCI A1,4C/24G)
- `redis_exporter` 沒有開逐指令延遲監控(`LATENCY` 需另外開)
