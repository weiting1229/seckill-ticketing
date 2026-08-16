# 壓測長尾延遲根因排查記錄(run5–11)

> 排查時間:2026-08-15 ~ 2026-08-16。前情提要(run1–4,情境 A/B 首次驗收)見
> [`docs/load-test-report.md`](../docs/load-test-report.md)。
>
> **這是已結案的歷史記錄**,結論已收錄進
> [`docs/adr/0008-CD上線與k6壓測(M7).md`](../docs/adr/0008-CD上線與k6壓測(M7).md) §10–12,
> 那邊只有精簡的決策摘要;這裡保留完整原始數據與逐一排除的假設,供之後想重新驗證、
> 深入研究,或修復限流器演算法時查對照數字用。**目前的待辦事項見專案根目錄的
> [`HANDOFF.md`](../HANDOFF.md)**,不在這裡。

## 症狀

情境 B(1000 VU 恆定 10 分鐘)壓測時,`/api/v1/seckill/purchase` 出現 p99 飆到 8–30 秒、少數請求 max 到 17–60 秒的長尾延遲,穩態 p99 只有個位數毫秒。每次壓測穩定重現。**不影響業務正確性**——零超賣零重複購買,每次都有對帳 API 驗證通過。

## 根因

`seckill:rl:global`(全站唯一共用的限流 key)在 Redis 單執行緒佇列裡因熱 key 爭用而排隊,瞬間並發峰值越高,佇列就堆得越深。

## 證據(兩階段,由弱到強)

**1. Timer 相關性**(run9,幫 `RateLimiterService` 四個 `try*` 方法加 Micrometer Timer 量測端到端耗時,含排隊等待):`seckill_ratelimit_check_duration_seconds{layer="global"}` 的 p99 時序幾乎逐點貼著 HTTP p99:

| 時間點 | HTTP p99(秒) | 限流 global 層 p99(秒) | 限流 ip 層 p99(秒) |
|---|---|---|---|
| 22:17:50 | 30.00 | 27.13 | 4.79 |
| 22:18:20 | 27.82 | 26.47 | 6.22 |
| 22:19:50 | 23.70 | 21.61 | 11.17 |
| 22:25:50 | 22.07 | 18.26 | 12.82 |
| 22:27:20 | 21.95 | 16.80 | 14.28 |

`user`/`token_user` 兩層全程 p99 都是 30–40 毫秒,完全正常(這兩層 key 按 userId 分散,不會撞)。低併發對照組(情境 A)同時段 `global` 層 p99 只有 2 毫秒。

**2. 消融測試(因果坐實)**(run10,dev-only 開關讓 `tryGlobal`/`tryUser`/`tryIp`/`tryTokenUser` 整段跳過、直接 `return true`,不呼叫 Redis):

| 狀態碼 | bypass 開啟後 p99 | bypass 關閉基準(run5–9) |
|---|---|---|
| 409(重複/售罄,佔絕大多數) | 開頭 170ms,之後穩定 3.0–3.4ms,整整 10 分鐘 | 8–30 秒,max 17–60 秒 |
| 200(成功) | 唯一有效窗口 443ms | 同上量級 |

量級差三到四個數量級,異常實質消失。開關程式碼保留在 `RateLimiterService.java`(`bypassEnabled`)+ `application.yml` dev profile(`bypass-enabled`),預設 `false`、prod 沒有這個 key,不影響正常環境。

## 已排除的假設(逐一測過,不用重查)

| 假設 | 排除方式 |
|---|---|
| DB(Hikari)連線池排隊 | `hikaricp_connections_pending` 全程 0 |
| 元件掛掉/例外中斷 | 查過 41 萬行 log,無相關例外 |
| 限流誤觸發(429) | 伺服器只有 200/409,無 429 |
| Redis 連線數/連線池不夠 | 開了連線池(`RedisConfig.java`)並驗證生效,300 併發實測連線數仍只有 2,長尾延遲無改善 |
| RabbitMQ publisher-confirm 同步等待 | counter 顯示 confirm 幾乎即時完成,且 `OrderMessagePublisher.publish()` 硬 timeout 5 秒、無重試,理論上限遠低於實測 p99(8–15秒),矛盾到足以排除 |
| Virtual thread pinning | 加 `-Djdk.tracePinnedThreads=full` 跑完整場 10 分鐘,pinning log 零筆記錄 |
| JIT 暖機不足 | 先跑情境 A 熱身再接情境 B,異常模式完全沒有改善(甚至更差) |

## 方法論教訓(以後每次壓測都要遵守)

- **每次壓測前完全重啟 docker + backend**,不要連續多輪沿用同一行程——同一 JVM/容器連續跑多輪會放大異常(run5/6 連續跑三輪 vs run7 單輪乾淨對照,差異明顯,已用對照實驗確認)。但**乾淨重啟不保證只重現「僅開頭異常」的模式**,run7/8/9 都是乾淨重啟卻分別出現「僅開頭」跟「持續整場」兩種模式——這個變異性不影響根因結論,但解讀單次測試結果時不要只憑一次乾淨對照就下定論。
- **每次情境跑完立刻呼叫對帳 API**,不要拖到後面——訂單 15 分鐘未付款會自動 `EXPIRED`,拖久了對帳會混進超時取消的雜訊。
- 排查新假設優先找**直接量測**的方法(Timer/slowlog),不要只靠間接指標(連線數、CPU%)去猜——這次排查曾用間接推理誤判過 Redis 連線數是瓶頸,後來被直接量測推翻。

## run11:本機測試拓樸撞牆(埠耗盡),後續改對 OCI 壓測

**背景**:找下游真實承載上限時,發現情境 B 每個 VU 全程固定綁定一個帳號,「每人限購一張」是 DB 條件 UPDATE 強制的業務規則——**成功購買數的天花板是 VU 數,不是庫存數**。單純調高 `SCENARIO_B_STOCK` 沒用,必須同步調高 `SCENARIO_B_VUS`。改用 `SCENARIO_B_VUS=3000`(用滿帳號池 2000–4999 區段)、`SCENARIO_B_STOCK=3500`。

**結果:數據作廢**——k6 client、Spring Boot backend、Redis 全部擠在同一台 Windows 機器上共用同一個 TCP 臨時連接埠池(預設約 16384 個,連線關閉後要 `TIME_WAIT` 約 120 秒才能回收)。3000 VU 在 10 分鐘內開的連線數遠超這個池子,一旦「已用埠數 + TIME_WAIT 卡住的埠數」超過總量,新連線被作業系統直接拒絕且連鎖不會自己恢復。實測:87.8% 請求(785,816/895,160)失敗於 `connectex: Only one usage of each socket address...`,不只 k6→backend,連 backend→Redis 也中箭(`RedisConnectionFailureException: ...Address already in use`)。1000→3000 VU 不是線性變差,是斷崖式(從幾乎 0 直接跳到 87.8%),證實是測試機資源耗盡,不是後端真的撐不住。

**這次意外挖出真實 bug**:埠耗盡連帶讓 backend→Redis 連線失敗,踩到 `OrderCreateListener.onOrderCreate()` 的冪等分支卡死問題,見下一節。

**結論(已定案)**:三個本機修復選項(調 Windows 動態連接埠範圍、換機器/WSL2 分離 k6、本機分段找安全 VU 上限)全部沒有 OCI 參考價值——這些解的是本機特有拓樸的自爆問題,量到的數字不會反映 OCI(原生 Linux、4C/24G ARM、k6 天生從外部機器打入)的真實承載能力。**已改為直接對 OCI 正式站壓測**,進度見 `HANDOFF.md`。

## 已解決:OrderCreateListener 冪等分支卡死 bug

`onOrderCreate()` 的 `catch (DuplicateKeyException e)` 冪等分支呼叫 `resultCache.writeSuccess(...)` 補寫結果快取,但這個呼叫本身若失敗(Redis 暫時連不上),例外會直接從這個 catch 區塊冒泡出去,完全繞過 `handleUnexpected()` 的 `x-retry-count` 重試上限 + 死信保護機制——這正是為什麼 DLQ 是 0(沒有死信)但佇列又消化不掉(不受 MAX_RETRY 保護)的原因。

**實測影響**:`seckill.order.queue` 卡了 2465 筆訊息、consumer 靜止 18+ 分鐘不會自己恢復,對帳一度 `consistent:false`(Redis 顯示扣減 2851 筆,DB 只有 1815 筆訂單)。業務資料本身沒有遺失:`orderCreateService.createOrder(message)` 是這個方法的第一步,在 `writeSuccess` 之前執行,訂單早已落庫,只是結果快取沒寫成功、後續處理卡住。

**修復**:把該分支的 `writeSuccess` 呼叫包一層 try-catch,失敗時導去既有的 `handleUnexpected()` 走統一重試/DLQ 路徑(commit `66f11d1`)。**已用真實卡住的資料驗證**:重啟 backend 後佇列歸零,對帳恢復 `consistent:true`(`validOrderCount` 從 1815 補齊到 2851,無資料遺失)。詳見 [ADR 0008 §12](../docs/adr/0008-CD上線與k6壓測(M7).md)。

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
| run11(3000VU) | B | 82341976577409024 | 2851(補齊後) | totalStock 3500;**一度 `consistent:false`,修復後重新對帳已變回 `true`,見上方章節** |

## 操作備忘

- **多 session 平行開發**:`spawn_task` 這類「開新 session」的機制不會自動建立隔離 git worktree(用 `git worktree list` 可確認),預設會在同一份工作目錄同一分支上直接改。要真隔離需手動 `git worktree add ../<新目錄> -b <新分支>`,且 `.env`(gitignore 排除)要手動複製過去;`infra/docker-compose.dev.yml` 的 container 名稱與 port 是寫死的,兩個 worktree 不能同時各跑一份 dev compose。
- 壓測皆為本機 dev 環境單機測試,未打正式站;`redis_exporter` 沒有開逐指令延遲監控(`LATENCY` 需另外開)。
