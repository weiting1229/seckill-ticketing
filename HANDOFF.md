# HANDOFF:交接事項

> 最後更新:2026-09-20。**本文件只放需要交接給下一個 session 的待辦事項**,不放已結案的
> 排查過程或原始數據——那些在
> [`load-test/long-tail-latency-investigation.md`](load-test/long-tail-latency-investigation.md)
> (長尾延遲根因排查,run5–11)、
> [`docs/adr/0008-CD上線與k6壓測(M7).md`](docs/adr/0008-CD上線與k6壓測(M7).md)
> (M7 全部決策)與
> [`docs/adr/0009-海報內容包匯入(M8).md`](docs/adr/0009-海報內容包匯入(M8).md)
> (M8 全部決策,20 個決策點 + 實測驗收)。

---

# ✅ M8 已上正式站(2026-09-14)

**正式站現況(實測)**:藝人 46 / 海報 46(`/opt/seckill/posters`,屬主 1001)/ 活動 46(精選 20)/
票種 115(全 ONLINE + 已預熱)/ 1 筆端到端驗證訂單(`第七象限` 搖滾區,對帳 `consistent: true`)。
`/posters/*.webp` 回 `200 + image/webp + max-age=3600`,`/posters/` 回 404。
首頁 18 張海報全部載入、0 張壞圖。LoadTest 殘骸(1 活動 / 20 訂單 / 40 流水)已清除。
上線前 DB 備份:`/opt/seckill/backups/seckill-20260914-140327.dump`。

上線過程踩到、下次會再遇到的:

- **CD 暫停一段時間後 Trivy 幾乎一定會擋。** 這次停 6 天就冒出 7 個 HIGH/CRITICAL
  (tomcat / netty / amqp-client),Spring Boot 3.5.16 已是 3.5.x 最新,只能在 `backend/pom.xml`
  的 `<properties>` 覆寫版本(commit `4f2df61`,比照 PR #3)。兩個細節:
  - **Trivy 表格的 Fixed Version 是合併儲存格**,同一套件的多個 CVE 可能要求不同版本
    (amqp-client 5.33.0 還剩兩個,要 5.33.1)。**push 前先在本機用同版 Trivy 掃 jar**,省一輪 CD:
    `docker run --rm -v "${PWD}\target:/scan:ro" aquasec/trivy:0.65.0 rootfs --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 /scan/seckill-backend-0.1.0-SNAPSHOT.jar`
  - **Trivy 建議的版本不一定有發布**(tomcat 10.1.58 不在 Maven Central,直接跳 10.1.59)。
- **`setup-server.sh` 重跑後要實際 `stat /opt/seckill/posters` 確認**,不要只看「跑過了」。
  第一次回報跑完但目錄不存在(主機上 `~/` 與 `/opt/seckill/` 各有一份不同版本的腳本)。
- 覆寫的依賴版本 **Spring Boot 出新的 3.5.x 時要回頭拿掉**,否則會把 parent 管理的更新版本壓回舊版。

## 需要注意

- ✅ 海報備份已確認(2026-09-15 03:30 首次執行):`posters-20260915-033001.tar.gz` 2.9 MB、46 張 webp。
- **正式站 admin 密碼曾於 2026-09-14 出現在對話紀錄中**,使用者評估後決定不更換。
  若日後要換:`AdminBootstrap` 帳號已存在就略過,改 GitHub Secret 不會生效,要直接 UPDATE `users.password_hash`。
- `scripts/demo-seed/` 兩支 seed 腳本**不冪等**,正式站已跑過一次,**不要再跑**。
- 首頁 console 有一條 CSP 擋 `index.html` 內嵌主題腳本的錯誤(2026-07-19 起就存在,與 M8 無關),已另開任務。

## M8 沒做、留給之後的

| 項目 | 說明 |
|---|---|
| **seeder** | `docs/plans/2026-08-17-demo-event-seeder.md` **完全未實作**,正式站與本機的 46 個活動都是 `scripts/demo-seed/` 權宜產生。⚠️ 動工前必讀:`CreateEventRequest` / `UpdateEventRequest` 的 `coverImageUrl` 是 `@Pattern("^(https?://.+)?$")`,**存不了 `/posters/x.webp` 這種同源相對路徑** —— 計畫 S5 規劃的「seeder 直接寫 `coverImageUrl`」走不通,要先放寬這個 pattern;改成寫絕對 URL 則會讓 CSP 的 `img-src 'self'` 開始擋圖 |
| **前端裁掉海報的字** | 詳情頁 hero 是 1080×420 顯示 1200×675 走 `object-fit: cover`,上下各裁 15.4%。實測 **30/46 張海報有字被裁在下緣、23/46 在上緣**。這是既有行為不是新 bug,但它是資訊遺失且不會報錯 |
| **詳情頁團名重複** | 海報上烤著團名,HTML 又疊一次 `events.title`(標題含團名)。ADR 0009 §18 有三種收法 |
| **`AdminArtistsView`** | 依 S7 判定**不做**:內容的唯一可寫入口是匯入,後台再開一套增刪改會讓同一份資料有兩個可寫入口 |
| **`copy`(活動文案池)** | 內容包裡一律是空陣列,精簡 manifest 刻意不送。等有來源再兩邊一起加 |

## 已知會咬人的地方

- **`docker exec` 少 `-i` 會安靜地什麼都不做。** 跑完 SQL 一律用 `SELECT count(*)` 複查。
- **`loading="lazy"` 在自動化瀏覽器不觸發**,驗證海報時先把 `img.loading` 改成 `eager` 再量 `naturalWidth`。
- **驗收海報一律看 `Content-Type`,不看狀態碼**:Caddy 路由失效時 SPA fallback 會回 200 + HTML(ADR 0009 §14)。
- **海報快取是 1 小時**(刻意不用 `immutable`,檔名是識別不是內容雜湊,ADR 0009 §14)。換圖後最多等一小時才全面生效。

## ❌ 已放棄的方向(2026-09-08 使用者拍板)

**「把地點與時間也烤進海報」不做了。** 量測結論:31/46(67%)的底部已被第二輪烤字佔住、
活動改期就要重烤重傳、產出量會等於活動數而非藝人數。**地點與時間維持走 HTML 疊字**
(`EventDetailView` 的 `.hero__content`)。若日後要社群卡 / 紙本 DM,做成獨立產出目標,
用「擴畫布加專用頁腳」而不是往剩下的空地擠。

---

## ✅ k6 腳本環境切換(2026-09-15 完成)

`load-test/lib/config.js` 改由 `TARGET_ENV=dev|prod` 決定目標,prod 需 `CONFIRM_PROD=yes` + admin 帳密、
開跑前印目標並倒數 10 秒、殘留的正式站 `BASE_URL` 在 dev 模式會被擋下。用法與防呆清單見
[`load-test/README.md`](load-test/README.md)「切換目標環境」。

---

## 待辦:找下游真實承載上限(OCI 正式站壓測,分階段進行中)

**目標**:在繞過限流(`SECKILL_RL_BYPASS=true`)的乾淨狀態下,對 OCI 正式站逐步加大流量,量出 DB/MQ/建單流程真正的承載上限,回答「`global-capacity=3000` 這個門檻夠不夠」。根因(`seckill:rl:global` 熱 key 排隊)已在本機坐實,不用重查,詳見上方連結;本機測試拓樸撞過 Windows 埠耗盡的牆,已定案改在 OCI 正式站測。

**各階段詳細做法、判定標準、喊停條件**:[`docs/plans/2026-09-15-oci-load-test-stages.md`](docs/plans/2026-09-15-oci-load-test-stages.md)(本表只記進度)。

**進度**:

| 階段 | 內容 | 狀態 |
|---|---|---|
| 0 | 小規模探路(~20 VU、~30 秒、庫存 20,不覆寫限流),純粹確認打正式站不會有預期外的計費、app 對真實網域/TLS/Caddy 路徑正常運作 | ✅ 已完成,對帳與計費皆確認正常 |
| 1 | 正式規模(情境 A 2000 VU / 情境 B 1000 VU),**不開 bypass**,在正式站真實限流設定下驗證根因是否在真實硬體重現 | ⚠️ **卡住**。1-0/1-1/1-2 已跑完,但 1-2 證明現行流量產生方式壓不到門檻量級,見下方 |
| 2 | 拿掉限流重新做下游承載測試。⚠️ **`SECKILL_RL_BYPASS` 在 prod 無效**(key 只在 dev profile),開工前要先做設定決策,見計畫 §3.2 | ⏳ 未開始 |
| 3 | 獨立小型壓力測試腳本,量出單一 Redis 節點面對瞬間爆量的限流 CAS 檢查實際能撐多少併發(在 OCI 上測) | 🔧 **工具已寫好待執行**(因 §2.8 從階段 1 之後提前)。`load-test/ratelimit-bench/`(見其 README)+ `infra/docker-compose.loadtest-redis.yml`。本機 smoke test 跑通,尚未在 OCI 上跑 |
| 4 | 合併步驟 2、3 的數字(目標流量 ÷ 單節點上限 ≈ 至少要幾個分片),判斷 sharded counter 該拆幾片 | ⏳ 未開始 |

### ⚠️ 階段 1 卡在「產不出 3000/s 的 global key 流量」(2026-09-20,待使用者拍板)

1-2(2000 VU)跑完的結論是**本輪無效**,不是「根因不存在」:

- 情境 A 每個 VU 只搶一次就 `settled`,2000 VU 在 30 秒 ramp 內對 `seckill:rl:global`
  只產生約 **65 次/秒**,是要驗證的門檻(`global-capacity=3000`/s)的 **2%**;
  ramp 一結束全部定局,hold 的 120 秒整段 idle。
- 所以計畫 §2.6 判定表不適用。再跑幾輪 2000 VU 只會重複得到同一組毫秒級數字。
- 詳細算式、已知限制與候選方向:計畫 **§2.8**;逐輪數據:`docs/load-test-report.md` §14.3。

**下一步要先決定怎麼產生流量**(會改變情境腳本核心行為與可比性,屬不可逆決策):
改情境 A 為迴圈重試 / 改用情境 B 當階段 1 載具 / 提前做原本階段 3 的最小腳本。

### ✅ 1-2 順帶確立的事(可沿用)

- **集中預登入有效**:量測窗內零 BCrypt,CPU 峰值從預期的 100% 降到 42%
  (預登入本身 90%,冷卻 15 秒後回 7%)。預設已開啟,對照組設 `SCENARIO_A_PRELOGIN=false`。
- **正式站在這個規模下完全健康**:purchase p99 13.7ms、global p99 3.5ms、零 5xx、對帳一致。
- **逾時取消正確**:壓測後 309 筆訂單全數 EXPIRED、庫存與 `stock_logs` 正確歸還。
- ⚠️ **`scripts/demo-seed/cleanup-loadtest-residue.sql` 不要直接往正式站丟**:它還留著
  `%ZUTOMAYO%` / `%落日飛車%` / `%sunset rollercoaster%` 這些 demo-seed 時代的條件,
  哪天海報內容包匯入同名藝人的活動就會誤刪真實資料。這次是改用指定 id 的精準刪除。
- ⚠️ **PowerShell 5.1 呼叫 ssh 的引號**:外層雙引號、內層單引號(反過來會被 PowerShell 吃掉,
  遠端 bash 會在 `;` 上斷開);`<` 輸入重導向在 PowerShell 不存在;要讓容器內展開的變數
  寫成 `` `$VAR ``(反引號擋 PowerShell、單引號擋遠端 bash)。

**OCI 計費風險已確認安全**(使用者已於 2026-08-16 親自查證):Cost Analysis 當月花費 $0、運算實例有 Always Free 標籤、已設定 OCI Budgets(超過 0.01 SGD 即 mail 告警)。之後每次拉高壓測規模,建議還是回 Cost Analysis 確認一次沒有異常變化,不要假設一定安全。

**執行方式(下一步進階段 1 時沿用)**:對正式站送流量的指令,一律由**使用者自己在自己的終端機執行**,不透過我的 Bash 工具代跑——admin 帳密是部署 secret,不應貼進對話;且是對有計費風險的正式環境送流量,使用者需要親自在旁邊看著 Cost Analysis 確認。

**階段 1 指令模板**(供使用者在自己的 PowerShell 執行;`TARGET_ENV`/`CONFIRM_PROD` 用 `-e` 傳,不會殘留在視窗裡):

```powershell
$env:ADMIN_USERNAME="<正式站 admin 帳號>"; $env:ADMIN_PASSWORD="<正式站 admin 密碼>"
k6 run -e TARGET_ENV=prod -e CONFIRM_PROD=yes load-test/scenario-a-flash-sale.js
# 情境 B 同理,注意事先要有足夠帳號池(見 load-test/README.md)
Remove-Item Env:ADMIN_USERNAME, Env:ADMIN_PASSWORD
```

開跑前確認橫幅寫的是 `PROD  https://tixco.kozow.com` 與預期的 VU 數,倒數 10 秒內可 Ctrl+C。

跑完立刻呼叫對帳 API 存證,並回 OCI Cost Analysis 確認花費無異常。

⚠️ **2026-09-08 補充**:正式站上線 M8 之後再壓測的話,資料量與活動數都變了
(1 筆 → 46 筆活動、0 → 115 個票種;LoadTest 殘骸已清),階段 0 的基準數字不能直接沿用當對照組。

## 環境現況(如果要接續本機開發)

- 專案路徑:`C:\Users\USER\Documents\seckill-ticketing`
- **海報上游**:`C:\Users\USER\Documents\poster-forge`,分支 `layout-architecture`
  (最新 commit `f9af932`,N6 匯出 + 發佈端)
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
- ⚠️ **停 dev backend 時,殺 mvnw 沒有用。** `spring-boot:run` 會分叉一個獨立 JVM,
  殺掉 Maven 外殼只殺得到外殼,那個 JVM 會繼續佔著 8080,下次啟動報
  `Port 8080 was already in use`。正解:
  ```powershell
  Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
  ```
- ⚠️ **dev 的海報落點是 `frontend/public/posters/`**(dev profile 的
  `seckill.posters.dir=../frontend/public/posters`,相對於 `backend/`,即上面的啟動目錄)。
  從 repo 根目錄啟動 backend 會讓它落在 repo 外面 —— 症狀是「匯入成功但前端看不到圖」,
  不會報錯。要從別處啟動就用 `SECKILL_POSTERS_DIR` 指絕對路徑。
- 本機監控/GUI 工具:Grafana `http://localhost:3000`、Prometheus `http://localhost:9090`、RabbitMQ management `http://localhost:15672`、RedisInsight(`docker compose -f infra/docker-compose.tools.yml up -d` 另外啟動)`http://localhost:5540`
- OCI 正式站的對應工具需先開 SSH tunnel(帳密皆在 `/opt/seckill/.env` 或對應 GitHub Secret 裡查):
  ```bash
  ssh -L 13000:localhost:3000 -L 19090:localhost:9090 -L 15540:localhost:5540 -L 25672:localhost:15672 -L 5432:localhost:5432 oci-seckill
  ```
- **本機 dev DB 現況**(2026-09-07 重建):46 活動(精選 20)/ 115 票種(全 ONLINE + 已預熱)/
  46 藝人 / 46 海報 / 1 筆端到端驗證訂單。壓測殘骸已全數清除。
- **git 狀態**(2026-09-14):M8 全部已 push 並部署。M8 的 commit 是
  `fac1c68`(backend)、`cd6c229`(infra)、`cce2680`(frontend)、`200ef77`(docs),
  上線過程另有 `4f2df61`(CVE 版本覆寫)、`bd6bb3c`(verify_purchase 修正)。
