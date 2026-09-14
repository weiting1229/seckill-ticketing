# HANDOFF:交接事項

> 最後更新:2026-09-08。**本文件只放需要交接給下一個 session 的待辦事項**,不放已結案的
> 排查過程或原始數據——那些在
> [`load-test/long-tail-latency-investigation.md`](load-test/long-tail-latency-investigation.md)
> (長尾延遲根因排查,run5–11)、
> [`docs/adr/0008-CD上線與k6壓測(M7).md`](docs/adr/0008-CD上線與k6壓測(M7).md)
> (M7 全部決策)與
> [`docs/adr/0009-海報內容包匯入(M8).md`](docs/adr/0009-海報內容包匯入(M8).md)
> (M8 全部決策,20 個決策點 + 實測驗收)。

---

# 🎯 現在的任務:把 M8 的海報與活動上到 OCI 正式站

**M8 的程式碼全部完成、本機全綠、已 commit,但一行都還沒上正式站。**
`main` 目前 **ahead 7**(其中 4 個是 M8)。

## 一句話說明現況

海報在本機 dev 已經整條打通:poster-forge 匯出 → 匯入 API → Caddy/Vite 提供 → 首頁卡片顯示
→ 可以真的買。**正式站上完全沒有這些東西**,要把同一條路在 OCI 上再走一次。

## 正式站現在長什麼樣(2026-09-08 實測,不是推測)

| 探測 | 結果 | 意思 |
|---|---|---|
| `GET /` | 200 | 站台活著 |
| `GET /api/v1/artists` | **401** | 跑的是 M8 之前的版本(該路徑不在匿名白名單,落到 `anyRequest().authenticated()`) |
| `GET /posters/任何檔名.webp` | **200 + `Content-Type: text/html`** | ⚠️ **不是圖**,是 SPA fallback 回 index.html |
| `GET /api/v1/events` | 200,`total: 1` | 只有 1 筆 `LoadTest Scenario A 1786874039269` 殘骸 |

⚠️ **第三列是這次上線最重要的一件事。** 正式站現在對 `/posters/*` 下的**任何**路徑都回 200,
連 `this-does-not-exist-at-all.webp` 也是 —— 因為 Caddyfile 的 `/posters/*` 路由還沒部署,
請求落到 catch-all 被前端 SPA fallback 吃掉。

**所以驗收絕對不能只看狀態碼。** 破掉的樣子是「圖不出來但 network 面板全綠」,
`<img>` 拿到一份 HTML 解碼失敗。**一律看 `Content-Type` 是不是 `image/webp`。**
(這個坑在 ADR 0009 §14 有完整說明,現在正式站上活生生地成立,可以當對照組。)

## 上線步驟(順序不可調換)

### 步驟 0:主機重跑 `setup-server.sh` —— **必須在部署之前**

```bash
scp infra/setup-server.sh opc@132.145.121.46:~
ssh opc@132.145.121.46 'sudo APP_USER=opc bash ~/setup-server.sh'
```

該腳本這次新增了海報目錄那一節(建 `/opt/seckill/posters`、`chown 1001:1001`、`chmod 0755`)。

⚠️ **為什麼不能等部署完再跑。** `docker-compose.prod.yml` 現在有一條
`/opt/seckill/posters:/srv/posters` 的 bind mount。**Docker 遇到不存在的 bind mount 來源
會自己建一個 root 擁有的目錄**,而 backend 容器以 uid 1001 執行 —— 於是:

- backend **啟動不會失敗**(目錄存在,Java 的 `createDirectories` 是 no-op)
- 一直到有人真的匯入海報,才在寫檔那一步 permission denied

失敗點離原因很遠。先跑腳本就不會發生;真的順序跑反了,補跑 `setup-server.sh` 也能救
(它會 `chown` 回來),但要記得重啟 backend 容器。

⚠️ **`chown 1001:1001` 的 1001 綁死 `backend/Dockerfile` 的 `app` 使用者。**
哪天改了 Dockerfile 的 uid,`setup-server.sh` 必須同步改(兩邊都留了警告註解)。

### 步驟 1:push,讓 CD 部署

```bash
git push origin main
```

CD(`workflow_run`,CI 綠後自動觸發)會做兩件跟這次有關的事:

- 建置並推 backend / frontend 映像 → **Flyway 會在正式站跑 `V4__create_artists.sql`**
  (純新增 `artists` / `artist_posters` 兩張表,不動任何既有表;失敗會擋住 backend 啟動,
  舊容器仍在)
- `tar czf - -C infra .` 把**整個 `infra/`** 同步到 `/opt/seckill` → Caddyfile、
  `docker-compose.prod.yml`、`backup-db.sh` 都會自動過去

⚠️ `setup-server.sh` 也會被同步過去,但 **CD 不會執行它** —— 所以步驟 0 不能省。
⚠️ CD 內含 Trivy 掃描,**HIGH/CRITICAL 即失敗**。這次沒加任何新依賴,理論上不受影響,
但基底映像可能在這段時間爆新 CVE,失敗的話看 ADR 0008 §4 的既有處置。

### 步驟 2:驗證靜態路由(看 Content-Type)

```bash
curl -I https://tixco.kozow.com/posters/7th-quadrant__bottom-heavy.webp
```

- 這時檔案還沒上傳,**預期是 404**(而且是 Caddy 的 404,不是 200+HTML)
- 若仍是 `200 + text/html` → Caddyfile 沒生效,先查 `/opt/seckill/caddy/Caddyfile` 有沒有
  那段 `handle_path /posters/*`,以及 caddy 容器有沒有重載

### 步驟 3:把內容包匯入正式站

在 poster-forge(`C:\Users\USER\Documents\poster-forge`,分支 `layout-architecture`):

```bash
SECKILL_BASE_URL=https://tixco.kozow.com \
SECKILL_ADMIN_USERNAME=<正式站 admin> SECKILL_ADMIN_PASSWORD=<正式站密碼> \
pnpm publish:seckill --dry-run     # 先看一次,不發任何 HTTP
```

確認無誤後拿掉 `--dry-run`。預期回報:藝人新增 46 / 海報新增 46 / 落檔 46,請求約 2.88 MB。

⚠️ **正式站帳密不要貼進對話。** 這一步由使用者自己在自己的終端機執行
(比照下方壓測那條的既有約定)。

驗收:

```bash
curl -s https://tixco.kozow.com/api/v1/artists | head -c 200      # 要 200 且 code:0(不再是 401)
curl -I https://tixco.kozow.com/posters/7th-quadrant__bottom-heavy.webp   # Content-Type: image/webp
curl -I https://tixco.kozow.com/posters/                          # 要 404,不可列出目錄
```

### 步驟 4:建活動與票種

**只匯入內容包,首頁不會有任何變化。** seckill 首頁列的是**活動**不是藝人,
一個藝人要出現必須有一個標題含該團名的活動 —— 那本來是 seeder 的職責,而
**seeder 完全未實作**。權宜工具在 [`scripts/demo-seed/`](scripts/demo-seed/)(本次新增,附 README):

```bash
cd scripts/demo-seed
export SECKILL_BASE_URL=https://tixco.kozow.com
export SECKILL_CONFIRM_PROD=yes          # 非 localhost 的強制確認,少了會直接拒跑
export SECKILL_ADMIN_USERNAME=... SECKILL_ADMIN_PASSWORD=...
python seed_artists_events.py            # 46 個活動(FEATURED 20 標為精選)
python fix_empty_theme_titles.py         # 修 2 筆空主題標題,見下方「已知資料坑」
python seed_ticket_types.py              # 115 個票種 + 逐一 warmup
```

⚠️ 那兩支 seed 腳本**不冪等**,重跑會建出第二份。清法見 `scripts/demo-seed/README.md`。

### 步驟 5:驗收

```bash
python verify_purchase.py                # 端到端:註冊→領token→搶購→輪詢→對帳
```

⚠️ 這支會在正式站產生**一筆真實訂單**並扣一張庫存。對帳要回 `consistent: true`。
本機跑出來的樣子:`dbStockRemaining 799 / redisStockRemaining 799 / validOrderCount 1 /
stockLogNetDelta -1 / consistent: true`。

再用瀏覽器看一次首頁:精選輪播與卡片要出現真海報,不是生成式 SVG。

## 需要你決定的兩件事

1. **正式站那筆 `LoadTest Scenario A 1786874039269` 要不要清掉?**
   本機的 25 筆殘骸已於 2026-09-07 清除(連同 22,862 筆訂單);正式站還留著 1 筆。
   清法見 `scripts/demo-seed/cleanup-loadtest-residue.sql`。
2. **這 46 個活動要不要有票種?** 步驟 4 的第三支腳本會建 115 個票種讓站上真的能買。
   不建的話詳情頁會顯示「目前沒有可購買的票種」。

## 已知會咬人的地方

- **`docker exec` 少 `-i` 會安靜地什麼都不做。** 2026-09-07 踩過:回報「已刪除」但一筆都沒動,
  因為 stdin 沒被轉發。跑完 SQL 一律用 `SELECT count(*)` 複查。
- **`loading="lazy"` 在自動化瀏覽器不觸發**,`naturalWidth` 會是 0 而圖檔其實好好的。
  驗證海報有沒有載入時,先把 `img.loading` 改成 `eager` 再量。
- **已知資料坑**:內容包有 6 個藝人的 `tourThemes` 陣列非空但其中一個語言是空字串
  (`cinder` / `ethan-lin` / `lazy-siesta` / `ophelia` / `scarlet-engines` /
  `stars-fell-into-the-forest`)。seed 腳本只檢查陣列非空,會產出「林予安 「」巡迴演唱會」
  這種空引號標題,所以步驟 4 要跑 `fix_empty_theme_titles.py`。
- **海報快取是 1 小時**(`Cache-Control: public, max-age=3600`)。刻意不用 `immutable`:
  檔名 `<slug>__<版式>.webp` 是識別不是內容雜湊,同版式換底圖會就地覆蓋同一個檔名,
  標 immutable 的訪客會一整年拿到舊圖而瀏覽器根本不會回來問(ADR 0009 §14)。
  換圖後最多等一小時才全面生效。
- **備份**:`backup-db.sh` 這次加了海報目錄的 tar,cron 不變(每日 03:30)。
  上線隔天記得確認 `/opt/seckill/backups` 有 `posters-*.tar.gz`。

## M8 沒做、留給之後的

| 項目 | 說明 |
|---|---|
| **seeder** | `docs/plans/2026-08-17-demo-event-seeder.md` **完全未實作**。⚠️ 動工前必讀:`CreateEventRequest` / `UpdateEventRequest` 的 `coverImageUrl` 是 `@Pattern("^(https?://.+)?$")`,**存不了 `/posters/x.webp` 這種同源相對路徑** —— 計畫 S5 規劃的「seeder 直接寫 `coverImageUrl`」走不通,要先放寬這個 pattern;改成寫絕對 URL 則會讓 CSP 的 `img-src 'self'` 開始擋圖 |
| **前端裁掉海報的字** | 詳情頁 hero 是 1080×420 顯示 1200×675 走 `object-fit: cover`,上下各裁 15.4%。實測 **30/46 張海報有字被裁在下緣、23/46 在上緣**。這是既有行為不是新 bug,但它是資訊遺失且不會報錯 |
| **詳情頁團名重複** | 海報上烤著團名,HTML 又疊一次 `events.title`(標題含團名)。ADR 0009 §18 有三種收法 |
| **`AdminArtistsView`** | 依 S7 判定**不做**:內容的唯一可寫入口是匯入,後台再開一套增刪改會讓同一份資料有兩個可寫入口 |
| **`copy`(活動文案池)** | 內容包裡一律是空陣列,精簡 manifest 刻意不送。等有來源再兩邊一起加 |

## ❌ 已放棄的方向(2026-09-08 使用者拍板)

**「把地點與時間也烤進海報」不做了。** 曾評估過三輪加工的架構
(第一輪原圖 → 第二輪烤團名主題 → 第三輪再烤地點時間),量測結論:

- 對比不是障礙:**0/46** 張的底部帶連純黑/純白都達不到 4.5:1
- **位置才是**:**31/46(67%)** 的底部已經被第二輪的烤字佔住,第二輪的 Layout Grammar
  已經把最好的空地用掉了,第三輪只有 33% 的圖塞得下
- 而且活動會改期換場地,烤進圖等於改期就要重烤重傳;HTML 疊字是即時的
- 產出量會等於**活動數**而不是藝人數(seeder 的輪替桶是 820 筆、每 10 分鐘汰換)

**地點與時間維持走 HTML 疊字**(`EventDetailView` 的 `.hero__content`,`events.venue` /
`events.eventTime`)。若日後真有「海報單獨流出去」的需求(社群卡 / 紙本 DM),
做成**獨立的宣傳卡產出目標**,而且用「擴畫布加一條專用頁腳」而不是往剩下的空地擠。

---

## 待辦(優先,進階段 1 前先做):k6 腳本支援直接切換環境

**目標**:進下方階段 1(對 OCI 正式站壓測)之前,先把 `load-test/` 腳本改成能直接切換 dev/prod 環境,不要每次都手動一個一個設環境變數。

**現況的問題**:
- 現在每次要打正式站,得在終端機手動設好幾個環境變數(`BASE_URL`、`ADMIN_USERNAME`、`ADMIN_PASSWORD`);PowerShell 下 `$env:` 設定的變數會留在同一個視窗裡,不會隨指令執行完自動清除,容易不小心沿用到上一輪殘留的舊值——萬一忘記把 `BASE_URL` 切回 dev 卻以為在打本機,或反過來忘記切到 prod 卻以為在打正式站,都是實際發生過的風險類型(這次階段 0 就是靠使用者自己每次重新設值才避開)。
- 目前沒有任何「執行當下清楚顯示打的是哪個環境」的機制,單靠人工記憶環境變數目前的值。

**設計時要注意的限制**:
- admin 密碼是機密,**不能寫死進任何會 commit 的檔案**(CLAUDE.md 規範)——「切換環境」能簡化的是 `BASE_URL` 之類的非敏感預設值(例如做一個 `ENV=prod`/`ENV=dev` 的單一開關去對應一組預設值),但密碼本身大概率還是得從環境變數或本機不進版控的檔案讀,不能包進切換機制的預設值裡。
- 因為對正式站送流量有計費風險,**切換機制執行時最好清楚印出「目前打的是哪個環境」**(例如 k6 `setup()` 的 console log,或啟動前的確認訊息),降低誤打正式站的風險,這比單純圖方便更重要。

> 💡 **2026-09-08:`scripts/demo-seed/_common.py` 已經實作了一版這個模式**,可以直接抄:
> 非 localhost 一律要求另外設 `SECKILL_CONFIRM_PROD=yes`,而且每次執行都先把目標環境印出來。
> 密碼仍然只從環境變數讀,沒有任何預設值。

---

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

⚠️ **2026-09-08 補充**:正式站上線 M8 之後再壓測的話,資料量與活動數都變了
(1 筆 → 47 筆活動、0 → 115 個票種),階段 0 的基準數字不能直接沿用當對照組。

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
- **git 狀態**:`main` **ahead 7**,尚未 push。M8 的 4 個 commit 是
  `fac1c68`(backend)、`cd6c229`(infra)、`cce2680`(frontend)、`200ef77`(docs)。
  未提交:本文件與 `scripts/demo-seed/`。
