# 海報動態管理(藝人表 + 後台 CRUD + 圖片上傳)實作計畫

> ## ⚠️ 2026-08-22:啟動條件已滿足,且 schema 需要新增一個欄位
>
> 本文開頭的「**啟動時機**:等生圖路線(seeder 計畫 §6.6)實測定案後再開工」——
> **該路線已於 2026-08-22 定案**(模型產無文字底圖 + 程式合成標準字),條件解除。
>
> 兩項需要調整,詳見 §4.3 與 §5 的標註:
> - `title_style` 的形狀有答案了,且**只在 fallback 情境生效**
> - `artists` 表**需要新增 `tier` 欄位**
>
> 海報由 poster-forge 專案(`C:/Users/USER/Documents/poster-forge`,決策紀錄見其 `CLAUDE.md` §2) 產出。

> ## ⚠️ 2026-09-06:內容包已經產出,本計畫的契約有 9 項修訂(S1–S9)
>
> poster-forge 的 N6 匯出已完成,產出在
> `C:/Users/USER/Documents/poster-forge/curated/`:
>
> | 產出 | 實際內容 |
> |---|---|
> | `content-pack.json` | 154 KB,**46 個藝人 / 46 張海報**(FEATURED 20 / ROTATING 26) |
> | `posters/*.webp` | 46 張,**1200×675**,每張 15–147 KB,合計 2.81 MB |
> | 檔名格式 | `<slug>__<版式>.webp`,例 `7th-quadrant__bottom-heavy.webp` |
>
> 修訂來源:`poster-forge/docs/plans/2026-08-23-layout-architecture.md` §11.2。
> **每一項都在對應章節有標註**,這裡只列索引:
>
> | # | 修訂 | 影響章節 |
> |---|---|---|
> | S1 | **一藝人多張海報** → `image_path` 單欄不夠 | §5 |
> | S2 | `tour_themes JSONB` **升為必要** | §5 |
> | S3 | 新增 `name_en VARCHAR(100)` | §5 |
> | S4 | 新增 `tier VARCHAR(16)`(2026-08-22 已標,不變) | §5 |
> | S5 | seeder 建活動時**直接寫 `coverImageUrl`** | §5、seeder 計畫 Task 6 |
> | S6 | `title_style` **建議刪除** | §4.3、§5 |
> | S7 | Task 2 / Task 7 的 **admin CRUD 與前端管理頁降級** | §8 |
> | S8 | Task 3(種子 bootstrap)**刪除**,改為匯入內容包 | §8 |
> | S9 | Task 5(圖片上傳端點)**保留**,但形狀要改 | §6、§8 |
>
> ⚠️ **這些修訂不影響資料庫現況**:實查 Flyway 只到
> `V3__add_event_featured_fields.sql`,`artists` 表不存在,後端也沒有任何一個
> 檔案提到 artist。**沒有 migration 要補、沒有資料要搬**,改的全部是還沒動工的計畫。
>
> ⚠️ **poster-forge 那邊還沒有「送出去」這一步**。§10.2 原本規劃的 `pnpm publish`
> 只做到匯出;HTTP 客戶端刻意沒寫,因為對著一個不存在的端點寫的程式**不會報錯**
> (它從來沒被執行過),等這邊真的做出來、路徑或欄位跟猜的不一樣時,失敗的樣子是
> 「送出去了但資料沒進去」。**端點定案後再回頭補那一端。**

> ## ✅ 2026-09-07:後端段已實作,S1–S9 中的 7 項落地,待決 #6 / #7 結案
>
> 決策全文見 [ADR 0009](../adr/0009-海報內容包匯入(M8).md)(**不是 §8 Task 8 寫的 0010** ——
> 該編號假設 seeder 計畫先佔掉 0009,而 seeder 至今未實作;實查 `docs/adr/` 最大號是 0008)。
>
> **本輪範圍(使用者拍板)**:後端 API + 圖片落地 + 測試。
> **不含** Task 4(Caddy `/posters/*` 路由、主機目錄、備份)與 Task 7(前端)。
> 因此圖片寫得下來、API 讀得到,但**訪客還看不到** —— 那需要 Task 4。
>
> | 項目 | 結果 |
> |---|---|
> | Flyway | **`V4__create_artists.sql`**,不是 §5 寫的 V6(理由見下) |
> | 表 | `artists` + `artist_posters`(待決 #7 → **獨立子表**) |
> | 匯入 | `POST /api/v1/admin/artists/import`,單一 multipart(待決 #6 → **批次**) |
> | 公開 | `GET /api/v1/artists`(匿名),欄位見 §6 標註 |
> | admin | 唯讀檢視 + `enabled` 切換 + 孤兒檔清單;**沒有 CRUD**(S7) |
> | 錯誤碼 | 5001–5007(§4.7 的 5001–5005 + 新增 5006 / 5007) |
> | 測試 | `ArtistImportIT` 14 + `WebpImageTest` 7;全庫 138 個測試 0 失敗 |
> | 實測匯入 | 46 藝人 / 46 海報 / 2.88 MB;重跑冪等;46 張逐位元相同 |
>
> ⚠️ **§5 的 S3 標註被實測推翻,DDL 與它寫的相反。** S3 寫「新增 `name_en`,**可為 NULL**:
> 不是每個團都有英文名」。實查內容包 46 筆:**`nameEn` 46/46 都有且互不重複,
> `nameZh` 有 23 筆是空的**。所以實作是 `name_en NOT NULL UNIQUE`、`name_zh` 可為 NULL
> 且走部分唯一索引。照抄原文的 `name NOT NULL UNIQUE` 會在第 2 個沒有中文名的藝人
> 撞唯一鍵、整批匯入失敗。
>
> ⚠️ **Flyway 版號改用 V4 而非 §5 寫的 V6。** seeder 計畫預定的 V4 / V5 尚未實作、沒有佔號。
> 若這裡跳號用 V6,日後補 V4 時 Flyway 預設 `outOfOrder=false` 會**讓 backend 啟動失敗**,
> 而那是部署當下才炸。**seeder 計畫的版號規劃因此作廢**,那份計畫要從當時的最大號往後接。

> 狀態:**待 review,尚未動工**。日期:2026-08-18。
> **前置依賴**:[`2026-08-17-demo-event-seeder.md`](2026-08-17-demo-event-seeder.md) 的
> **Task 4(`DemoContentPool`)與 Task 8(`posterRegistry`)必須先完成**。
> 本計畫是那份計畫 §6.8 評估後拆出的獨立里程碑。<br/>
> 生圖操作見 [`docs/poster-generation-runbook.md`](../poster-generation-runbook.md)。
> **啟動時機**:等生圖路線(該計畫 §6.6)實測定案後再開工——路線決定了 `title_style` 欄位
> 到底要不要用,提早做會白工。

---

## 1. 目標與非目標

**目標**

- 在 admin 後台新增一個樂團(名稱 + 海報圖 + 標題樣式),**不需要改程式碼、不需要重新部署**。
- 新增的樂團**自動進入輪替桶**——seeder 下一個 tick 就會開始為它產生活動。
- 海報圖片可從後台上傳,同源提供,CSP 不需放寬。
- 消除目前「後端藝人池」與「前端 registry」兩份清單必須人工對齊的負擔。

**非目標**

- 不做圖片編輯/裁切(上傳什麼就是什麼,壓縮在本機生圖流程完成)。
- 不做多圖輪播、不做每個活動各自指定圖(那是既有 `coverImageUrl` 的職責)。
- 不做藝人的公開頁面或搜尋(這是 demo 資料管理,不是產品功能)。
- 不動 seeder 的池子架構(常駐/輪替/靜態三桶維持不變)。

---

## 2. 這個里程碑要解決的問題

seeder 計畫做完之後,狀態會是:

| 資料 | 存在哪裡 | 加一個樂團要做什麼 |
|---|---|---|
| 藝人清單(產標題用) | 後端 `DemoContentPool` 硬編碼 | 改 Java、commit、跑 CD |
| 藝人 → 海報對照 | 前端 `posterRegistry.ts` 硬編碼 | 改 TS、commit、跑 CD |
| 海報圖檔 | 打包進 frontend 映像 | 加檔案、commit、跑 CD |

三份東西、三個地方、必須同步,而且每次都要走完整的建置部署。seeder 計畫甚至為此各寫了一條
「兩邊清單要對齊」的測試——**那條測試的存在本身就是這個設計有問題的訊號**。

本里程碑把這三份合併成一份 DB 資料 + 一個主機目錄,後端與前端都從它讀。

---

## 3. 架構總覽

### 3.1 完成後的完整流程(含使用者操作介入點)

紅色節點是**人工操作**,其餘全部自動。整個設計的重點是:人只負責「決定有哪些樂團、長什麼樣」,
資料的產生與呈現完全自動。

```mermaid
flowchart TD
    subgraph U["👤 使用者操作(admin,瀏覽器)"]
        U0["① 登入取得 JWT<br/>role = ADMIN"] --> U1["② 進 /admin/artists"]
        U1 --> U2["③ 新增藝人<br/>name / slug / titleStyle"]
        U2 --> U3["④ 上傳海報 WebP<br/>(本機生圖流程產出)"]
        U1 --> U5["⑥ 隨時可停用藝人<br/>enabled = false"]
    end

    subgraph API["後端 API(雙層防護:URL + @PreAuthorize)"]
        A1["POST /api/v1/admin/artists"] --> A2{"name / slug<br/>是否重複?"}
        A2 -->|是| E1["回 5002 / 5003"]
        A2 -->|否| A3["寫入 artists 表<br/>Snowflake id、enabled=true"]
        A4["POST /admin/artists/:id/poster"] --> S1{"magic bytes<br/>是 RIFF....WEBP?"}
        S1 -->|否| E2["回 5004"]
        S1 -->|是| S2{"≤ 2MB 且<br/>尺寸 640–4096?"}
        S2 -->|否| E3["回 1400 / 5005"]
        S2 -->|是| S3["檔名由伺服器決定<br/>slug-snowflakeId.webp<br/>⚠ 完全忽略上傳者檔名"]
        S3 --> S4["① 寫新檔 → ② 更新 DB<br/>→ ③ 刪舊檔(順序不可換)"]
    end

    U2 --> A1
    U3 --> A4
    U5 --> A5["PUT /admin/artists/:id"]

    subgraph ST["儲存"]
        DB[("PostgreSQL<br/><b>artists 表</b><br/>單一事實來源")]
        FSD[("主機目錄<br/>/opt/seckill/posters<br/>每日 tar 備份")]
    end

    A3 --> DB
    A5 --> DB
    S4 --> FSD
    S4 --> DB

    subgraph AUTO["自動流程(無人介入)"]
        SD["DemoEventSeeder tick<br/>每 10 分鐘"] --> SD1["讀 artists<br/>WHERE enabled = true"]
        SD1 --> SD2{"有 enabled 藝人?"}
        SD2 -->|否| SDW["記 WARN 並跳過<br/>不拋例外中斷 tick"]
        SD2 -->|是| SD3["DemoContentPool 組標題<br/>→ 輪替桶產生新活動"]
        BK["每日 03:30 cron"] --> BK1["pg_dump + tar posters"]
    end

    DB --> SD1
    SD3 --> DB
    FSD --> BK1

    subgraph V["🌐 訪客瀏覽(匿名)"]
        V1["App 啟動<br/>GET /api/v1/artists"] --> V2["Pinia store 快取<br/>失敗回空陣列,不阻塞渲染"]
        V2 --> V3["GenerativePoster<br/>比對 title 是否含藝人名"]
        V3 -->|命中| V4["&lt;img src='/posters/xxx.webp'&gt;"]
        V3 -->|未命中| V5["生成式 SVG(第 3 層)"]
        V4 --> CD["Caddy handle_path /posters/*<br/>→ file_server(同源,CSP 不需放寬)"]
    end

    DB --> V1
    CD --> FSD

    classDef human fill:#fde8e8,stroke:#c53030,stroke-width:2px
    classDef guard fill:#fff5e6,stroke:#d69e2e
    class U0,U1,U2,U3,U5 human
    class S1,S2,S3,A2,SDW guard
```

### 3.2 使用者操作與自動流程的交界

| 使用者做什麼 | 系統自動接手什麼 | 多久生效 |
|---|---|---|
| 新增一個藝人(填名稱 + slug) | seeder 下個 tick 開始為它產生輪替桶活動 | **≤ 10 分鐘** |
| 上傳該藝人的海報 WebP | 檔案落到主機目錄,Caddy 立即可提供;訪客重整即看到 | **立即**(訪客需重整) |
| 把藝人設為 `enabled=false` | seeder 不再為它產生新活動;**既有活動與海報照常** | 下個 tick |
| 刪除藝人 | 連同圖檔刪除;既有活動退回生成式 SVG | 立即 |
| **什麼都不做** | seeder 持續維持 820 筆活動、汰換舊的、補新的 | 持續 |

**沒有任何一步需要重新部署或改程式碼**——這是本里程碑的唯一目的。

### 3.3 元件對照

| # | 元件 | 內容 |
|---|---|---|
| 1 | **圖片路由** | Caddy 加 `handle_path /posters/*` + `file_server`,volume 掛 `/opt/seckill/posters` |
| 2 | **藝人表** | Flyway `V6__create_artists.sql` |
| 3 | **後端 API** | admin CRUD + 公開唯讀清單 + 上傳端點 |
| 4 | **seeder 改讀表** | `DemoContentPool` 的藝人維度改從 `artists` 讀 |
| 5 | **前端改 fetch** | `posterRegistry` 從 `import` 常數改成啟動時抓 API(Pinia store 快取) |
| 6 | **admin 頁面** | `AdminArtistsView.vue` + router + 上傳 UI |

**關鍵性質:1 和 2~6 必須一起做才有意義。** 只做圖片路由,registry 那行仍在原始碼裡;
只做藝人表,圖仍在映像裡。任一邊沒做,「不用重新部署」就不成立。

---

## 4. 關鍵設計決策

### 4.1 圖片改由 Caddy 提供,不再打包進 frontend 映像

**決策**:圖片放主機 `/opt/seckill/posters/`,由 Caddy 直接 `file_server`。

**理由**:CSP `img-src 'self'` 要求的是**同源**,不是「必須在 frontend 映像裡」。Caddy 本來就是
唯一入口,由它提供 `/posters/*` 一樣是 `https://tixco.kozow.com` 這個 origin,CSP 完全不用動。
而圖片脫離映像之後,新增圖片不再需要重新建置與部署——這正是本里程碑的目的。

**取捨**(這些是真實損失,不是形式上的):

- **圖片不再受版控**:沒有歷史、沒有 rollback、誤刪就沒了。
  必須配套備份(見 4.5),否則主機掛掉圖全沒。
- **本機開發預設看不到圖**:dev 跑 Vite `:5173`,沒有 Caddy。解法見 4.6。
- **「目前部署了什麼」變成兩個來源**:程式碼看 git,圖片看主機目錄。

### 4.2 藝人清單升格為 DB 表:一份資料,前後端共用

**決策**:新增 `artists` 表,後端 `DemoContentPool` 讀它產標題,前端讀它對照海報。

**理由**:這是本里程碑真正的架構價值,比「後台能點按鈕」更重要。目前兩份清單分屬前後端、
必須人工對齊,seeder 計畫還為此各寫了一條檢查測試。合併之後那個同步問題**在結構上消失**,
而不是靠測試去抓。附帶效果就是使用者要的「新增藝人自動進輪替桶」。

**取捨**:seeder 從此依賴一張 admin 可改的表。若 admin 把表清空,輪替桶就沒有藝人可用
→ 必須有防呆(見 4.3 的 `enabled` 與 §10 已知限制)。

### 4.3 表設計上的三個決定

> ✅ **2026-08-22:`title_style` 的形狀有答案了,但角色改變了。**
>
> 原文說「形狀還沒定案(取決於 seeder 計畫 §6.6 生圖路線的結果),用 JSONB 可以先上線」。
> 現在路線已定:**團名由 poster-forge 離線合成、烤進 WebP**,所以正常情況下
> 前端根本不需要渲染標題。
>
> `title_style` 因此降級為 **fallback 專用**:只在「藝人有名字但還沒上傳合成好的海報」時,
> 讓前端用 CSS 疊字。形狀 = 合成器排版模板的參數:
>
> ```ts
> { fontFamily, weight, tracking, size, color, position, blend?, stroke?, shadow?, uppercase? }
> ```
>
> 見 `poster-forge/src/compose/template.ts` 的 `TitleStyle`——**那份 CSS 模板就是前端
> fallback 該用的樣式**,兩邊共用同一份實作,不要再寫第二套。
>
> **維持 JSONB 的決定不變**(理由從「形狀未定」改成「這是 fallback,不值得為它開具名欄位」)。

> ⚠️ **2026-09-06(S6):上面那條標註引用的實作正在消失,`title_style` 建議整欄刪除。**
>
> 該標註寫著「見 `poster-forge/src/compose/template.ts` 的 `TitleStyle`——那份 CSS 模板
> 就是前端 fallback 該用的樣式,兩邊共用同一份實作」。但 poster-forge 2026-08-23 的
> layout 架構計畫 §15 已把 `compose/template.ts` 標記為**待刪**,取代它的 Layout Grammar
> 產出的是**烤好的點陣圖**,沒有任何可以給前端套用的 CSS 參數。
> 照抄那份參數的下場是:前端疊出來的字跟海報上的字是兩套視覺,而不會有任何錯誤。
>
> **fallback 情境本身也已經不存在**:內容包的匯出**跳過沒有核可海報的藝人**
> (2026-09-06 使用者拍板;這一輪跳過 3 個,全是 ROTATING)。表裡不會出現
> 「有藝人但沒有海報」的列,`title_style` 唯一的存在理由就沒了。
>
> ⚠️ 刪除的前提是**匯入流程必須維持這個不變量**:任何寫進 `artists` 表的藝人都帶著
> 至少一張海報。哪天匯入端改成「先建藝人、之後再補圖」,這個前提就破了——
> 破的樣子是首頁出現沒有圖的卡片,而不是錯誤訊息。

- **`slug` 與 `name` 分離**:`name` 是顯示與比對用的樂團名(可含中文),`slug` 是檔名與
  URL 用的小寫英數(`mayday`)。分離是因為檔名不能含中文與空白,而比對必須用原名。
- **`enabled` 旗標而非直接刪除**:停用的藝人不再產生新活動,但既有活動與海報照常運作。
  直接 DELETE 會讓已存在的活動失去海報對照(退回 SVG),而且無法復原。
- **`title_style` 用 `JSONB`**:欄位形狀還沒定案(取決於 seeder 計畫 §6.6 生圖路線的結果),
  用 JSONB 可以先上線、之後演進而不必再開 migration。**代價是失去型別檢查**,
  前端要對缺欄位有預設值。

### 4.4 檔案上傳的安全設計(本專案第一個上傳端點)

目前全站**零檔案上傳**,所以這是全新的攻擊面。設計如下,每一條都有對應的攻擊:

| 措施 | 擋掉什麼 |
|---|---|
| **檔名完全由伺服器決定**(`{slug}-{snowflakeId}.webp`),**絕不使用上傳者提供的檔名** | 路徑穿越(`../../etc/...`)、覆蓋既有檔案 |
| 落點是固定的單一目錄常數,API **不接受任何路徑參數** | 同上 |
| 只接受 **WebP**,以 **magic bytes** 判定(`RIFF....WEBP`),**不信 Content-Type、不信副檔名** | 偽裝成圖片的可執行檔或腳本 |
| 檔案大小上限 **2 MB**(`spring.servlet.multipart.max-file-size`) | 磁碟塞爆、記憶體耗盡 |
| 解析 WebP header 驗證寬高在合理範圍(如 640–4096) | 解壓縮炸彈、異常尺寸 |
| Caddy `file_server` **不開 `browse`**(預設關閉,但要明確確認) | 目錄列表洩漏 |
| 該目錄不在任何會被執行的路徑,Caddy 只做靜態提供 | 上傳後執行 |
| 端點走 `/api/v1/admin/**` + `@PreAuthorize("hasRole('ADMIN')")` 雙層 | 未授權上傳 |

**為什麼只收 WebP 而不在伺服器端轉檔**:轉檔(PNG/JPEG → WebP)需要新依賴,因為 Java 標準
`ImageIO` **不支援 WebP 寫入**,得引入 TwelveMonkeys 或 webp-imageio。依 CLAUDE.md
「新增任何第三方依賴前先說明用途與替代方案」,而這裡有零依賴的替代方案:**要求上傳前先壓好**。
使用者的生圖工作流本來就包含 ImageMagick 轉檔那一步(見 seeder 計畫 §6.3),所以這個要求
不增加任何實際負擔。**若日後真的想收 PNG/JPEG 再單獨評估依賴。**

**替換舊圖的順序**:寫新檔 → 更新 DB `image_path` → 刪舊檔。順序不可調換——若中途失敗,
寧可留下孤兒檔(無害,可清理),也不要出現 DB 指向不存在檔案的破圖狀態。

**孤兒檔清理**:提供一個 admin 端點比對 DB 與目錄,列出沒有被任何藝人引用的檔案供人工確認後刪除。
**不做自動刪除**——自動刪檔在這種對照關係上風險大於效益。

### 4.5 備份必須一併處理(容易被跳過的一項)

現況:`setup-server.sh` 安裝的每日 03:30 cron **只跑 `pg_dump`,不含任何檔案目錄**
(`infra/backup-db.sh`)。圖片一旦離開 git,就變成**完全沒有備份的資料**。

因此本里程碑**必須**擴充備份:在既有備份腳本加一段 `tar czf` 打包 `/opt/seckill/posters`,
與 DB dump 一起留在 `/opt/seckill/backups`,套用相同的保留天數。這不是加分項,是先決條件——
沒有它,這個里程碑等於把資料從「有版控」降級成「沒有任何保護」。

### 4.6 本機開發如何看到圖

**決策**:dev 把圖放 `frontend/public/posters/`(**並加進 `.gitignore`**),prod 走 Caddy 主機目錄。

兩邊的 URL 路徑都是 `/posters/{slug}.webp`,dev 由 Vite 的 `publicDir` 提供、prod 由 Caddy 提供,
**前端程式碼完全不需要區分環境**。零設定、零外掛、零依賴。

⚠️ `.gitignore` 那條**一定要記得加**,否則圖片又悄悄進了 git,本里程碑的前提就破了。

### 4.7 錯誤碼新增 5xxx 分段

設計文件第 9 節目前定義 `1xxx 認證、2xxx 活動、3xxx 搶購、4xxx 訂單`。藝人管理是新領域,
**新增 5xxx 分段**(`5001` 藝人不存在、`5002` 名稱重複、`5003` slug 重複、
`5004` 圖片格式不合法、`5005` 圖片尺寸不合法)。這是對設計文件的擴充,ADR 要記。

---

## 5. Schema(Flyway `V6__create_artists.sql`)

> ⚠️ **2026-08-22:本表需要新增 `tier` 欄位。**
>
> poster-forge 的篩選流程會把每個藝人分成兩級(A 精選 / B 備用),直接對應 seeder 的兩個桶。
> 沒有這個欄位,「人工精選的藝人才進常駐桶」這件事做不到。
>
> ```sql
> tier VARCHAR(16) NOT NULL DEFAULT 'ROTATING'
>     CHECK (tier IN ('FEATURED', 'ROTATING')),
> ```
>
> 配套的 seeder 改動(屬 seeder 計畫 Task 6 的範圍):
> - 補**常駐桶**時只取 `enabled = TRUE AND tier = 'FEATURED'`
> - 補**輪替桶**時取所有 `enabled = TRUE`
> - `FEATURED` 藝人數 < 常駐桶目標數(20)時記 WARN,**不可拋例外中斷 tick**
>
> 另可考慮加 `tour_themes JSONB`——每個藝人 2~3 個專屬巡演主題,讓 `DemoContentPool`
> 組標題時優先使用。這是使用者對活動名稱保留控制權的方式。

> ⚠️ **2026-09-06:下面這份 DDL 有四項要改(S1 / S2 / S3 / S6)。**
>
> **S1 — 一藝人多張海報。** `image_path VARCHAR(200)` 單欄裝不下。
> content-pack 的 `posters` **型別上已經是陣列**(這一輪每人剛好 1 張,是核可的結果
> 不是型別的限制);poster-forge 那邊 `recipe.ts` 只吃 `posters[0]` 也已標為待辦。
> 兩個做法:獨立的 `artist_posters` 表,或 `posters JSONB` 陣列。
> **現在就決定形狀比較省**:欄位改成陣列要搬資料,而海報的 URL 一旦發出去就有人存了。
> (poster-forge 的檔名也是為此才帶版式:`<slug>__<版式>.webp`,第二張進來不必改任何已存在的 URL。)
>
> **S2 — `tour_themes JSONB` 升為必要。** 原文只寫「另可考慮加」。內容包裡它是
> `[{zh, en}]`,而海報上烤進去的就是其中一筆(`posters[].themeIndex` 指哪一筆)。
> ⚠️ **欄位必須允許空陣列**:實測 46 張裡只有 26 張帶主題,另外 20 個藝人沒有巡演主題,
> 那些海報的 `themeIndex` 欄位**整個不存在**(不是 0、不是 -1——填任何數字都會指向
> `themes` 裡不存在的一筆,而 JS 取到 `undefined` 不會報錯)。
>
> **S3 — 新增 `name_en VARCHAR(100)`。** 內容包每個藝人都有 `nameZh` / `nameEn` 兩個名字,
> 目前 `name` 只裝得下一個。
> ⚠️ 唯一性與**子字串互斥**(poster-forge `CLAUDE.md` 硬規則 #3)要**中英各驗一次**:
> 前端 `title.includes(artist)` 對兩種語言都會發生,中文名互不衝突不代表英文名也不衝突。
> poster-forge 匯出前已經驗過(`src/identity/artists.ts` 的 `validateArtists`),
> 這邊要不要再驗一次是「信不信上游」的取捨,不是必須。
>
> **S6 — `title_style` 建議整欄刪除**,理由見 §4.3 的 2026-09-06 標註。
> 連帶 §4.7 的錯誤碼、§6 的公開端點欄位、§7 的前端 fallback 都少一塊。
>
> 併進去之後的形狀大致是(**未定案,只是把上面四項畫出來**):
>
> ```sql
> name_en      VARCHAR(100),                       -- S3。可為 NULL:不是每個團都有英文名
> tour_themes  JSONB NOT NULL DEFAULT '[]'::jsonb, -- S2。空陣列合法
> tier         VARCHAR(16) NOT NULL DEFAULT 'ROTATING'
>     CHECK (tier IN ('FEATURED', 'ROTATING')),    -- S4(2026-08-22 已提)
> -- image_path / title_style 移除,海報改走 artist_posters 或 posters JSONB(S1 / S6)
> ```
>
> **S5 — seeder 建活動時直接寫 `events.coverImageUrl`**(該欄 V2 就存在)。
> 原規劃走前端 `title.includes(artist)` 字串比對挑海報,那條路徑降級成 fallback。
> 附帶效果:硬規則 #3 從「錯了會靜靜挑錯圖」降級成「只影響 fallback」,規則保留但風險下降。

> Flyway 版本號承接 seeder 計畫的 V4(`events.source`)與 V5(列表索引),**本計畫從 V6 起算**。

> ## ✅ 2026-09-07:實際落地的 DDL 在 `V4__create_artists.sql`,與下面這份有五處不同
>
> 下面的 DDL **保留原文供對照**,實際以 migration 檔為準。差異與理由:
>
> | # | 差異 | 理由 |
> |---|---|---|
> | 1 | 版號 **V4** 不是 V6 | seeder 的 V4/V5 未佔號;跳號會讓日後補 V4 時 Flyway 擋住啟動 |
> | 2 | `name` → **`name_zh`(可 NULL)+ `name_en`(NOT NULL UNIQUE)** | S3 說反了,見檔頭標註。`name_zh` 唯一性走部分索引 `WHERE name_zh IS NOT NULL`,並以 CHECK 擋空字串(空字串與 NULL 是兩種「沒有中文名」,只留一種) |
> | 3 | `image_path` 移除,改 **`artist_posters` 子表** | 待決 #7 結案。`file_name UNIQUE` 是匯入的冪等鍵,JSONB 陣列給不了唯一約束 —— 檔名重複只會在覆蓋掉舊資料時被發現,而那不報錯 |
> | 4 | `title_style` **整欄不建** | S6 採納,見 §4.3 標註 |
> | 5 | 新增 `tier` / `tour_themes` | S4 / S2 採納。⚠ `tour_themes` 必須允許空陣列:實測 19/46 沒有主題 |
>
> **子表不存路徑只存檔名**:對外 URL 由 `/posters/` 前綴在回應層組出來。存成兩份事實的話,
> 改前綴時漏掉一邊不會報錯,只會整站破圖。
>
> **`artist_posters.theme_index` 可為 NULL** = 這張海報沒有烤主題文字。不可用 0 或 -1 代替
> —— 兩者都指向不存在的一筆,而消費端取到 `undefined` 不會報錯。實測 46 張裡 20 張為 NULL。

```sql
CREATE TABLE artists (
    id           BIGINT PRIMARY KEY,                 -- Snowflake,不用自增
    name         VARCHAR(100) NOT NULL UNIQUE,       -- 顯示與比對用(可含中文)
    slug         VARCHAR(50)  NOT NULL UNIQUE,       -- 檔名/URL 用,小寫英數與連字號
    image_path   VARCHAR(200),                       -- 例 /posters/mayday-123456.webp;NULL = 尚未上傳
    title_style  JSONB,                              -- 字體/字重/字距/顏色/擺位;NULL = 用預設
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE, -- FALSE = 不再產生新活動,既有不受影響
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_artists_slug_format CHECK (slug ~ '^[a-z0-9-]+$')
);

CREATE INDEX idx_artists_enabled ON artists (enabled) WHERE enabled = TRUE;

COMMENT ON TABLE  artists           IS 'Demo 用藝人清單:seeder 產標題與前端海報對照的單一事實來源';
COMMENT ON COLUMN artists.title_style IS '前端標題樣式覆寫(JSONB,形狀依生圖路線定案)';
```

**種子資料**:表若為空,輪替桶就沒有藝人可用。因此需要一支 bootstrap
(比照 `AdminBootstrap` 的模式,`CommandLineRunner` + 冪等檢查),在表為空時寫入
seeder 計畫 `DemoContentPool` 原本硬編碼的那份藝人清單。**不用 Flyway 塞種子資料**——
那會讓 demo 資料混進 schema 版本歷史,且無法在不同環境給不同內容。

---

## 6. API 契約

| 方法 | 路徑 | 權限 | 說明 |
|---|---|---|---|
| `GET` | `/api/v1/artists` | 匿名 | 公開唯讀清單,只回 `name` / `imagePath` / `titleStyle`(**不回 id、enabled、時間戳**) |
| `GET` | `/api/v1/admin/artists` | ADMIN | 完整欄位 + 分頁 |
| `POST` | `/api/v1/admin/artists` | ADMIN | 建立(`name` / `slug` / `titleStyle`) |
| `PUT` | `/api/v1/admin/artists/{id}` | ADMIN | 更新 |
| `DELETE` | `/api/v1/admin/artists/{id}` | ADMIN | 刪除(**同時刪除其圖片檔**) |
| `POST` | `/api/v1/admin/artists/{id}/poster` | ADMIN | 上傳/替換海報(multipart) |
| `GET` | `/api/v1/admin/artists/orphan-files` | ADMIN | 列出沒有被引用的孤兒檔(**只列出,不刪**) |

> ⚠️ **2026-09-06(S9):上傳端點保留,但這張表的形狀對不上「匯入一份內容包」。**
>
> S9 說「Task 5 是內容包進去的唯一通道」,而現行契約是
> `POST /api/v1/admin/artists/{id}/poster`:**一次一張、而且要先有 artist id**。
> 用它匯入這一輪的東西,是先打 46 次 CRUD 建藝人、再打 46 次上傳,中間任何一步失敗
> 就停在半套狀態——而「半套」在這裡的樣子是首頁部分藝人有圖、部分沒有。
>
> 兩個方向,**這邊決定**:
>
> 1. **加一個匯入端點**(例 `POST /api/v1/admin/artists/import`,吃 content-pack.json +
>    一包圖):一次交易、要嘛全進要嘛全不進,冪等以 `slug` 為鍵。
> 2. **維持逐張端點**,由 poster-forge 那側的客戶端負責排序與重試,
>    並接受中途失敗會留下半套狀態。
>
> 決定之前 poster-forge 不會寫送出去那一端(見本檔頂部標註)。
>
> 兩個現成的數字,擋掉不必要的擔心:單張最大 **147 KB**(4.4 的 2 MB 上限綽綽有餘),
> 46 張合計 **2.81 MB**;檔名 `<slug>__<版式>.webp` 最長 40 餘字元,`VARCHAR(200)` 夠。

- 回應一律走既有的 `ApiResponse{code, message, data}`。
- 公開端點刻意精簡欄位:前端只需要比對與渲染,不需要知道內部 id 與啟用狀態。
- 公開端點要加進 `SecurityConfig` 的匿名 GET 白名單(比照 `/api/v1/events`)。

> ## ✅ 2026-09-07:實際實作的端點(待決 #6 結案 → 批次)
>
> | 方法 | 路徑 | 權限 | 說明 |
> |---|---|---|---|
> | `GET` | `/api/v1/artists` | 匿名 | `slug` / `name`(= `nameZh \|\| nameEn`)/ `nameZh` / `nameEn` / `tier` / `tourThemes` / `posters[{imageUrl, archetype, themeIndex}]`。僅 `enabled`,無分頁 |
> | `POST` | `/api/v1/admin/artists/import` | ADMIN | **批次匯入**,multipart:`manifest`(JSON)+ N 個 `files`(WebP) |
> | `GET` | `/api/v1/admin/artists` | ADMIN | 完整欄位 + 分頁 |
> | `PATCH` | `/api/v1/admin/artists/{id}/enabled` | ADMIN | 營運開關 |
> | `GET` | `/api/v1/admin/artists/orphan-files` | ADMIN | 孤兒檔(只列不刪) |
>
> **原契約的 `POST` / `PUT` / `DELETE` 與 `/{id}/poster` 不實作**(S7 / S9)。
>
> `SecurityConfig` 的白名單寫成 `HttpMethod.GET, "/api/v1/artists"` 而**不是** `/api/v1/artists/**`
> —— 全放行會把日後新增的子路徑一起放出去,而那不會有任何一步報錯。
>
> ⚠️ **匯入端點的 `ObjectMapper` 開了 `FAIL_ON_UNKNOWN_PROPERTIES`**:收的是**精簡 manifest**
> (content-pack 去掉 `posters[].layout`,106 KB → 10.7 KB)。多送欄位當場回 1400 而不是
> 安靜丟掉。實測請求總量 2.88 MB,`spring.servlet.multipart.max-request-size` 設 32MB、
> `max-file-size` 設 2MB。

---

## 7. 前端改動

- 新增 `stores/artists.ts`(Pinia):App 啟動時抓一次 `/api/v1/artists`,**快取在記憶體**,
  失敗時回空陣列(**不可阻塞頁面渲染**——海報只是裝飾,抓不到就全站退回 SVG 第 3 層)。
- `posterRegistry.ts` 的比對函式**保留不動**,只把資料來源從 `import` 常數換成 store。
  seeder 計畫 Task 8 已經要求資料形狀比照 API 回傳,所以這裡是**換來源不是重寫**。
- `GenerativePoster.vue` **完全不用改**——它只呼叫比對函式。
- 新增 `views/admin/AdminArtistsView.vue` + router `/admin/artists`,比照
  `AdminEventsView.vue` 的既有版式(表格 + 對話框表單 + 即時預覽)。
- 上傳 UI 用 Element Plus `el-upload`,限制 `.webp`、2 MB,上傳後即時預覽。

---

## 8. Task 拆解

每個 Task 結束就 commit(Conventional Commits + `Co-Authored-By: Claude Fable 5`)。
後端測試一律 `rtk mvn test` / `rtk mvn verify`。

### Task 1:`artists` 表與唯讀查詢

**檔案**:`V6__create_artists.sql`、`domain/Artist.java`、`mapper/ArtistMapper.java` + XML、
`ArtistMapperIT.java`

先寫失敗的 mapper 整合測試(insert / findAll / findEnabled / findByName / slug 唯一衝突),
再實作。SQL 手寫在 XML、參數一律 `#{}`、主鍵用 Snowflake `IdGenerator`。
commit:`feat(backend): artists 表與 mapper`

### Task 2:藝人 CRUD 與公開查詢

> ⚠️ **2026-09-06(S7):admin CRUD 降級,公開唯讀端點不變。**
> 藝人的名字、tier、巡演主題全部在 poster-forge 編寫並隨內容包進來,後台再開一套
> 增刪改等於**兩個地方都能改同一份資料**——而兩邊不一致時沒有任何一步會報錯,
> 只會下一次匯入時被覆蓋掉。建議只留 `GET`(公開唯讀 + admin 檢視)與 `enabled` 切換,
> 建立/改名/刪除交給匯入。`GET /api/v1/artists` 仍然必要(前端要讀)。


> ✅ **2026-09-07:已實作,形狀依 S7 降級。** 實際檔案:
> `artist/{ArtistController, package-info}`、`artist/domain/{Artist, ArtistPoster, ArtistTier, TourTheme}`、
> `artist/mapper/{ArtistMapper, ArtistPosterMapper, TourThemeListTypeHandler}` + 兩份 XML、
> `artist/dto/{ImportManifest, ImportResultResponse, ArtistAdminResponse, ArtistPublicResponse, PosterView}`、
> `artist/service/{ArtistService, ArtistImportService, ImportRequestReader, PosterStorage, WebpImage}`、
> `admin/AdminArtistController`、`config/PosterStorageProperties`、`BizCode`(5001–5007)、`SecurityConfig`。
> 測試:`ArtistImportIT`(14)、`WebpImageTest`(7)、`support/WebpFixtures`。


**檔案**:`ArtistService.java`、`AdminArtistController.java`、`ArtistController.java`、
DTO 群、`BizCode.java`(加 5xxx)、`SecurityConfig`(公開 GET 白名單)、`ArtistFlowIT.java`

1. 先寫失敗的整合測試:匿名可讀公開清單、非 ADMIN 打 admin 端點得 403、
   名稱/slug 重複回 5002/5003、slug 格式非法回 1400。
2. 實作 service 與兩個 controller,admin 端 URL 層 + `@PreAuthorize` 雙層防護。
3. 入參全部 Jakarta Validation。
commit:`feat(backend): 藝人管理 API(admin CRUD + 公開唯讀)`

### Task 3:種子 bootstrap

> ❌ **2026-09-06(S8):本 Task 刪除。**
> 原本要在表為空時寫入「`DemoContentPool` 硬編碼的那份藝人清單」,而那份清單已經
> 被內容包取代(46 個藝人,含中英名、tier、巡演主題、海報)。留著的話,空表啟動時會
> 先塞一批硬編碼藝人,再被匯入覆蓋——中間那段時間前端顯示的是不存在的團,而且不報錯。


**檔案**:`ArtistBootstrap.java`、`ArtistBootstrapTest.java`

比照 `AdminBootstrap` 的 `CommandLineRunner` + 冪等模式:表為空時寫入預設藝人清單,
非空則跳過並記 log。單元測試涵蓋「已有資料 → 不寫入」。
commit:`feat(backend): 藝人清單啟動種子`

### Task 4:圖片路由與備份(基礎設施)

> ## ✅ 2026-09-07:已實作,七個子項全做,另外多了三件原文沒寫的
>
> 決策見 [ADR 0009](../adr/0009-海報內容包匯入(M8).md) §14–§17。原文七項對照:
> 1 ✅ Caddyfile / 2 ✅ caddy 唯讀掛載 / 3 ✅ backend 可寫掛載 + `SECKILL_POSTERS_DIR`
> / 4 ✅ setup-server.sh / 5 ✅ backup-db.sh 加 tar / 6 ✅ `frontend/.gitignore`
> / 7 ⏸ **prod `curl` 驗收未做**(需要真的部署一次;本機已用真 Caddy 容器 + 真 46 張圖驗過)
>
> **原文沒寫、但不做會安靜壞掉的三件:**
>
> **① 主機目錄的屬主必須是 uid 1001,而且 `chown` 順序會咬人。** bind mount 沿用主機屬主,
> 而 backend 容器以 `backend/Dockerfile` 的 `app`(uid 1001)執行。屬主不對**啟動不會失敗**
> (目錄已存在,`createDirectories` 是 no-op),而是等到有人真的匯入才 permission denied。
> 另外 `mkdir -p $APP_DIR/posters` 必須放在既有的 `chown -R "$APP_USER" "$APP_DIR"` **之後** ——
> 順序反過來遞迴 chown 會把屬主改回去,而腳本會成功結束。
> ⚠️ **改 Dockerfile 的 uid 就必須同步改 `setup-server.sh`。**
>
> **② dev 的落點要指向 `frontend/public/posters`,不是 `backend/data/posters`。**
> §4.6 說 dev 由 Vite 的 publicDir 提供,但匯入端點若寫進 backend 底下,**匯入會成功而前端
> 看不到圖,且不會報錯**。已在 dev profile 設 `seckill.posters.dir=../frontend/public/posters`
> (相對於 `backend/`,即 runbook 的 dev 啟動目錄)。
>
> **③ `Cache-Control` 不可標 `immutable`。** 檔名 `<slug>__<版式>.webp` 是**識別**不是內容雜湊:
> 同一個藝人的同一個版式換底圖重新核可,匯入會就地覆蓋同一個檔名 —— 內容變了、URL 沒變。
> 標 immutable 的訪客會一整年拿到舊圖,而瀏覽器根本不會回來問。用 `max-age=3600` +
> Caddy 自動帶的 ETag,實測過期後一次 304 就結束。
>
> **本機實測**(真 `caddy:2.9-alpine` 容器載入真的 Caddyfile + 真的 46 張圖):
>
> | 檢查 | 結果 |
> |---|---|
> | `GET /posters/<檔名>.webp` | 200、`Content-Type: image/webp`、63052 bytes、帶 ETag / Last-Modified |
> | 內容 | 與 `curated/posters/` **逐位元相同** |
> | `GET /posters/` | **404**,不列出目錄 |
> | `GET /posters/no-such-file.webp` | **404**(不是 SPA fallback 的 200+HTML —— 這是本項最重要的一條) |
> | `GET /` 與 `/event/123` | 502(frontend 容器不存在)→ 證明 catch-all 仍吃得到 |
> | `GET /actuator/health` | 404,不受影響 |
> | 安全 header | CSP / HSTS / nosniff 仍套用在海報回應上;`img-src 'self'` **未放寬** |
> | `If-None-Match` | **304** |
>
> **dev 端到端**:`pnpm publish:seckill` → 46 張落進 `frontend/public/posters` → Vite 提供 →
> 瀏覽器實際渲染出海報;程式化抓公開端點回傳的 46 個 `imageUrl`,**46/46 皆 200 + image/webp**,
> 合計 2,943,210 bytes。`git check-ignore` 確認圖檔被 `frontend/.gitignore:45` 擋住。

**檔案**:`infra/caddy/Caddyfile`、`infra/docker-compose.prod.yml`、`infra/backup-db.sh`、
`infra/setup-server.sh`、`frontend/.gitignore`

1. Caddyfile 在 `handle /api/*` 之後、catch-all 之前插入:

   ```
   handle_path /posters/* {
       root * /srv/posters
       file_server
   }
   ```

   **順序不可放到 catch-all 之後**,否則會被前端 SPA fallback 吃掉(與 `/actuator` 同一個坑)。
2. prod compose 的 caddy 服務掛 `- /opt/seckill/posters:/srv/posters:ro`(**唯讀掛載**——
   Caddy 只需要讀,寫入是 backend 的事)。
3. backend 服務掛同一目錄為**可寫**,供上傳端點寫入。
4. `setup-server.sh` 加 `mkdir -p $APP_DIR/posters` 與屬主設定。
5. `backup-db.sh` 加一段 `tar czf` 打包 posters 目錄,套用相同保留天數。
6. `frontend/.gitignore` 加 `public/posters/`。
7. 部署後實測 `curl -I https://.../posters/test.webp` 回 200 且 `Content-Type: image/webp`。
commit:`feat(infra): 海報靜態路由、儲存目錄與備份`

### Task 5:圖片上傳端點

> ⚠️ **2026-09-06(S9):保留,但先決定 §6 標註的那兩個方向再實作。**
> 4.4 的七條防護全部照舊(它們擋的是攻擊面,跟內容從哪來無關)。

> ## ✅ 2026-09-07:方向選了批次,所以**逐張端點不做**,防護併進匯入端點
>
> 4.4 的七條全部落地,只有「檔名完全由伺服器決定」那條換了形狀 —— 批次匯入必須靠檔名把
> multipart part 對應回 manifest 條目,檔名不能丟掉。改成**「檔名只當查表的鍵,不當路徑用」**,
> 三道關卡在安全性上等價:
>
> 1. 剝掉 part 檔名的任何目錄成分(`/` 與 `\` 兩種分隔字元)
> 2. 剝完的名字**必須出現在 manifest 裡**,而 manifest 的每個檔名先過白名單
>    `^[a-z0-9][a-z0-9_-]*\.webp$`(DB 亦有同一份規則的 CHECK)
> 3. 實際落檔時再斷言解析後的父目錄就是根目錄 —— 這一道是防白名單日後被放寬
>
> 送 `../../evil.webp` 會在第二道因為不在 manifest 而讓**整批**被拒(有整合測試驗證,
> 且斷言目錄外沒有產生任何檔案)。
>
> **尺寸驗證不引入影像函式庫**:`WebpImage` 手寫解析 RIFF 容器與 `VP8 ` / `VP8L` / `VP8X`
> 三種 chunk,只讀畫布尺寸、一個像素都不解碼 —— 解碼反而把解壓縮炸彈的攻擊面搬進來,
> 而擋炸彈正是讀尺寸的目的。解析公式在寫 Java 之前先以實際 46 張驗證過。
>
> **寫檔順序**:校驗全部跑完 → 寫所有檔案 → DB 交易。每個檔案是「寫暫存檔 → 原子改名蓋上去」,
> 直接對正式檔名寫入的話,寫到一半失敗會留下**半張圖覆蓋掉原本正常的那張**,
> 而半張 WebP 在瀏覽器上是破圖不是錯誤。


**檔案**:`ArtistPosterService.java`、`AdminArtistController.java`、`application.yml`
(multipart 限制)、`ArtistPosterUploadIT.java`

1. 先寫失敗測試,**每一條對應 4.4 的一項防護**:
   - 非 WebP(magic bytes 不符)→ 5004
   - 超過 2 MB → 1400
   - 尺寸超出範圍 → 5005
   - 上傳的檔名含 `../` → **落點仍在固定目錄**(驗證檔名完全被忽略)
   - 非 ADMIN → 403
   - 替換舊圖 → 新檔存在、DB 已更新、舊檔已刪
2. 實作,檔名一律 `{slug}-{snowflakeId}.webp`,落點為設定常數。
3. `application.yml` 設 `spring.servlet.multipart.max-file-size: 2MB` / `max-request-size: 3MB`。
commit:`feat(backend): 海報圖片上傳(WebP、伺服器決定檔名)`

### Task 6:seeder 改讀藝人表

**檔案**:`DemoContentPool.java`、`DemoSeedMapper`、`DemoEventSeederIT.java`

1. 先寫失敗測試:新增一個 `enabled=true` 的藝人 → 下一次 `seedOnce()` 產出的活動中
   出現該藝人;設為 `enabled=false` → 不再出現在新活動(**既有活動不受影響**)。
2. `DemoContentPool` 的藝人維度改從 `artists` 表讀(只取 `enabled=true`),
   其餘維度(曲風/城市/場館/巡演主題)仍留在程式碼——那些不需要動態管理。
3. **防呆**:表中沒有任何 enabled 藝人時,seeder 記 `WARN` 並跳過建立,**不可拋例外中斷 tick**。
4. 移除 seeder 計畫 Task 4 / Task 8 那兩條「前後端清單要對齊」的測試——
   **合併之後它們已無意義**,留著會誤導。
commit:`feat(backend): seeder 藝人清單改讀 artists 表`

### Task 7:前端改 fetch 與 admin 頁面

> ⚠️ **2026-09-06(S7):`AdminArtistsView` 降級或刪除**(跟 Task 2 同一個理由),
> Pinia store 與 `posterRegistry` 換來源那兩項**不變**。
> 若 S6 拍板刪掉 `title_style`,前端的疊字 fallback 也一併移除——
> 留著會在「海報還沒載入」的瞬間閃一次跟成品不同的字體。


> ## ✅ 2026-09-07:已實作。`posterRegistry` 是**新寫的**,不是「換來源」
>
> 決策見 [ADR 0009](../adr/0009-海報內容包匯入(M8).md) §18–§20。
>
> ⚠️ **原文「`posterRegistry` 的比對函式保留不動,只把資料來源換掉」的前提不成立**:
> 那個檔案來自 seeder 計畫 Task 8,而 seeder **完全未實作**,`frontend/src/utils/posterRegistry.ts`
> 從來沒有存在過。所以這一輪是從零寫比對函式,不是換來源。
>
> 實際檔案:`api/artists.ts`(新)、`api/types.ts`(加 `ArtistPublic` / `ArtistPoster` / `TourTheme`)、
> `utils/posterRegistry.ts` + `.spec.ts`(新,8 個測試)、`stores/artists.ts`(新)、
> `composables/useEventPoster.ts`(新)、`views/EventListView.vue`、
> `components/FeaturedEventCarousel.vue`、`views/EventDetailView.vue`。
> **`AdminArtistsView` 與 router 依 S7 不做。**
>
> ### 三層解析,以及失敗時往下掉一層而不是掉到底
>
> 1. `events.coverImageUrl` → 2. 標題比對到的藝人海報 → 3. `GenerativePoster` 生成式 SVG
>
> 舊寫法把載入失敗記成「這個**活動 id** 的封面壞了」,於是第 1 層一壞就直接跳到第 3 層。
> 改記 **URL** 之後,壞掉的 `coverImageUrl` 會往下掉到第 2 層 —— 一個 404 的封面仍然
> 看得到藝人海報。同一張藝人海報被多個活動共用時也只需要壞一次。
>
> ### 拉丁團名要求詞邊界,中日韓不要(原文沒提,但漏掉會掛錯海報)
>
> 內容包裡有 `Mint` / `Dodo` / `Mist` / `Astra` 這種短英文名。純 `includes` 的話,
> 一個叫「Mistake Tour」的活動會命中藝人 `Mist`,**掛上完全無關的海報而且不會報錯**
> ——那條路徑上每一步都成功了。所以拉丁名要求左右不得緊鄰英數字。
> 中文沒有詞邊界可用,靠的是上游保證(poster-forge 硬規則 #3 的子字串互斥);
> 實測 46 個藝人共 **69 個中英名字,互斥違反 0 筆**。
>
> ### ⚠️ 給 seeder(S5)的前置:`coverImageUrl` 的驗證擋掉了相對路徑
>
> `CreateEventRequest` / `UpdateEventRequest` 的 `coverImageUrl` 是
> `@Pattern(regexp = "^(https?://.+)?$")` ——**存不了 `/posters/x.webp` 這種同源相對路徑**。
> S5 規劃「seeder 建活動時直接寫 `coverImageUrl`」那條路目前走不通,實作 seeder 時
> 必須先放寬這個 pattern。不放寬的話 seeder 寫入會被擋成 1400,或者更糟:有人改成寫絕對
> URL,而那會讓 CSP 的 `img-src 'self'` 開始擋圖。**本輪的比對路徑不受影響。**
>
> ### 實測(dev 全棧,本機)
>
> seeder 未實作,而現有 26 個活動裡 24 個是 k6 壓測留下的 `LoadTest Scenario A/B <timestamp>`,
> 沒有任何一個標題對得上藝人 —— 因此建了 6 個 demo 活動才有東西可驗
> (描述帶 `[DEMO-M8]` 標記,可安全刪除)。標題刻意做成真實活動的樣子
> (如「第七象限 「座標之外」巡迴演唱會」),證明比對不是靠「標題剛好等於團名」。
>
> | 檢查 | 結果 |
> |---|---|
> | 6 個 demo 活動的卡片 | 6/6 渲染 `<img>`,URL 對應到**正確**的藝人,解碼後 1200×675 |
> | 首頁精選輪播 | 真海報(截圖確認,非生成式 SVG) |
> | 活動詳情頁 hero | 真海報,`naturalWidth` 1200 |
> | 第 1 層(ZUTOMAYO,自帶 `coverImageUrl`) | 仍走自己的封面,不被藝人海報蓋掉 |
> | 第 3 層(LoadTest 活動) | 11/11 退回生成式 SVG |
> | 單一航班 | 兩個元件各自呼叫 `ensureLoaded`,單次頁面載入**只打 1 次** `/api/v1/artists` |
> | `pnpm type-check` / `pnpm test` / `pnpm ci:lint` | 全綠;測試 29 個(新增 8 個) |
>
> ⚠️ store 載入失敗會退回空陣列、全站走生成式 SVG —— 這條由 `EMPTY_POSTER_INDEX` 單元測試
> 與 store 的 catch 覆蓋,**沒有做線上故障注入**。

**檔案**:`stores/artists.ts`、`posterRegistry.ts`、`views/admin/AdminArtistsView.vue`、
`router/index.ts`、`api/types.ts`、對應 spec

1. Pinia store:啟動抓一次、記憶體快取、**失敗回空陣列不阻塞渲染**。
2. `posterRegistry` 比對函式保留,資料來源換成 store。
3. `AdminArtistsView`:表格 + 新增/編輯對話框 + `el-upload`(限 `.webp` / 2 MB)+ 即時預覽。
4. router 加 `/admin/artists`,比照既有 admin 路由的守衛設定。
5. `pnpm test` / `vue-tsc` 全綠。
commit:`feat(frontend): 藝人管理後台與海報動態載入`

### Task 8:ADR 0010 與收尾

**檔案**:`docs/adr/0010-海報動態管理.md`

> ADR 編號承接 seeder 計畫的 0009,**本計畫是 0010**。

> ✅ **2026-09-07:實際寫的是 [ADR 0009](../adr/0009-海報內容包匯入(M8).md),不是 0010。**
> 上面那句假設 seeder 計畫先佔掉 0009,而 seeder 至今未實作。實查 `docs/adr/` 最大號是
> **0008**(CD 上線與 k6 壓測 M7),依 CLAUDE.md「掃描取最大號 +1」就是 0009。
> ⚠️ 本 ADR **不取代任何既有 ADR**:0009 涵蓋的是全新領域,ADR 0009 §8 記錄的
> 「registry 前端硬編碼」問題在 ADR 0006 裡也還沒發生(前端 `posterRegistry.ts` 從未存在)。
>
> ⚠️ **Task 8 只完成了 ADR 那一半**:`rtk mvn verify` 全綠(138 個測試 0 失敗)已跑,
> 但**尚未 commit、尚未 push、CI 未驗**。

至少涵蓋:圖片改由 Caddy 提供的取捨(4.1)、藝人清單升格為單一事實來源(4.2)、
表設計三決定(4.3)、上傳安全設計逐條理由(4.4)、備份為何是先決條件(4.5)、
dev/prod 路徑一致的做法(4.6)、5xxx 錯誤碼分段擴充(4.7)、以及
**本 ADR 取代 ADR 0009 中關於「registry 前端硬編碼」的部分**(依 CLAUDE.md 的舊 ADR 不修改原則,
要在 0009 狀態行補「部分被 ADR 0010 取代」)。

跑完整 `rtk mvn verify` + `pnpm test`,push,確認 CI 綠。
commit:`docs: ADR 0010(海報動態管理)`

---

## 9. 部署與驗收

**部署順序有依賴**:Task 4 的主機目錄必須先存在,Task 5 的上傳才有落點。
若 CD 先部署了 backend 才建目錄,上傳會失敗。建議 Task 4 單獨部署一次確認無誤後,再進 Task 5。

**驗收清單**:

- [ ] `curl -I https://tixco.kozow.com/posters/{檔名}.webp` → 200 + `Content-Type: image/webp`
- [ ] `curl https://tixco.kozow.com/posters/` → **不列出目錄內容**
- [ ] 瀏覽器 console **無 CSP 違規**(img-src 未放寬仍正常顯示)
- [ ] 後台新增一個藝人 + 上傳圖 → **不重新部署**,重整前台即可看到該藝人的海報
- [ ] 該藝人在下一個 seeder tick(≤10 分鐘)後出現在輪替桶的新活動裡
- [ ] 把該藝人設為 `enabled=false` → 不再產生新活動,**既有活動的海報仍正常**
- [ ] 上傳一個改名為 `.webp` 的 PNG → 回 5004 被拒
- [ ] 上傳檔名為 `../../evil.webp` → 檔案落在固定目錄、名稱為 `{slug}-{id}.webp`
- [ ] 隔日確認 `/opt/seckill/backups` 有 posters 的 tar 檔
- [ ] 本機 dev 把圖放 `frontend/public/posters/` → Vite 也看得到,且 `git status` **不顯示那些圖**

---

## 10. 已知限制

- **藝人表為空 = 輪替桶停止產生新活動**。有 bootstrap 種子與 WARN 防呆,但 admin 若手動把所有
  藝人停用,seeder 會安靜地什麼都不做(只有 log)。這是刻意的——不該讓 demo 資料產生器強制
  覆寫管理員的決定。
- **圖片沒有版控與 rollback**。備份是每日一次的 tar,粒度就是一天;當天上傳後誤刪,救不回來。
- **`title_style` 是 JSONB,沒有型別檢查**。欄位打錯不會在後端被擋下,前端必須對缺欄位有預設值。
- **公開藝人清單是全量回傳**,沒有分頁。40~100 個藝人沒問題,若日後暴增需改成分頁或加快取 header。
- **孤兒檔只列出不自動刪**。長期會累積,需要人工定期清。
- **前端 store 只在啟動時抓一次**。後台改完藝人後,已開著的分頁要重整才會看到——demo 場景可接受。
- **dev 與 prod 的圖片內容可能不同步**(一邊在 repo 外的本機目錄、一邊在主機)。這是脫離版控的
  必然後果,不是 bug。

---

## 11. 待決事項

> ✅ **2026-08-22:待決 #2(`title_style` 用 JSONB 還是具名欄位)已結案 → 維持 JSONB**,
> 理由見 §4.3 標註(角色已改為 fallback 專用)。

| # | 問題 | 我的建議 |
|---|---|---|
| 1 | 只收 WebP(要求上傳前自己壓)還是收 PNG/JPEG 由伺服器轉檔? | **只收 WebP**。轉檔要新依賴(Java `ImageIO` 不支援寫 WebP),而生圖流程本來就有 ImageMagick 那一步 |
| 2 | `title_style` 用 JSONB 還是拆成具名欄位? | **先 JSONB**。形狀取決於生圖路線(seeder 計畫 §6.6)還沒定案;定案後若穩定,再開 migration 拆欄位 |
| 3 | 刪除藝人時是否連同其既有活動一起刪? | **不要**。只刪藝人與圖檔,既有活動退回 SVG 海報。連動刪活動的破壞力太大且不可逆 |
| 4 | 公開端點 `/api/v1/artists` 要不要加快取 header? | 可以加 `Cache-Control: max-age=300`;但後台改完要等 5 分鐘才生效,**demo 時容易讓你以為沒存到**。建議先不加 |
| 5 | 要不要順便把 `venue` / 曲風也做成可管理? | **不要**。那些不需要對應圖片,硬編碼即可,擴大範圍只會拖長里程碑 |
| 6 | ~~**(2026-09-06)** 匯入走批次端點還是逐張上傳?~~ **✅ 2026-09-07 結案:批次** | 見 §6 標註與 ADR 0009 §4 |
| 7 | ~~**(2026-09-06)** 海報存 `artist_posters` 表還是 `posters JSONB`?~~ **✅ 2026-09-07 結案:獨立子表** | 見 §5 標註與 ADR 0009 §3 |
| 8 | **(2026-09-06)** 內容包裡 `copy`(活動文案池)目前是**空陣列** | poster-forge 那邊還沒有來源。`DemoContentPool` 組活動標題時不能假設它有東西 |

> ## ✅ 2026-09-07:#6 / #7 結案,#8 維持未決且**欄位沒有落地**
>
> `copy` 一律是空陣列,而 seckill 沒有對應欄位 —— 精簡 manifest **刻意不送它**。
> 送一個永遠是空陣列的欄位過去,只會讓這一端多一個「這要拿來做什麼」的問號。
> 文案有來源、也決定怎麼存的時候再一起加(兩邊要同時改,因為匯入端開了
> `FAIL_ON_UNKNOWN_PROPERTIES`)。
>
> **新增待決 #9**:`staleArtists`(DB 有而內容包沒有的藝人)目前**只列出、不自動處理**。
> 成因可能是核可被撤下,也可能是匯出時跳過(這一輪 poster-forge 跳過 3 個藝人),
> 兩者的正確處置不同 —— 是該停用、該刪除、還是該留著,由人決定。
> 匯入回應與 WARN 日誌都會列出,但沒有任何一步會強迫人去看。

---

## 12. 風險與回復路徑

| 風險 | 防護 | 回復方式 |
|---|---|---|
| 圖片目錄遺失(主機重建、誤刪) | 每日 tar 備份(Task 4) | 從 `/opt/seckill/backups` 解壓;最差情況是重跑本機生圖流程 |
| 上傳端點被濫用 | ADMIN 雙層防護 + 2 MB 上限 + magic bytes + 伺服器決定檔名 | 停用端點(移除 controller 方法重新部署);目錄可直接檢視異常檔案 |
| Caddy 路由順序寫錯導致 `/posters/*` 落到 SPA fallback | 部署後 `curl -I` 驗 Content-Type | 改 Caddyfile 重新部署;與 `/actuator` 是同一個已知坑 |
| seeder 因藝人表空掉而停擺 | bootstrap 種子 + WARN 日誌 | 後台重新啟用任一藝人,下個 tick 即恢復 |
| JSONB 欄位形狀混亂 | 前端對缺欄位有預設值 | 直接 `UPDATE artists SET title_style = NULL`,退回預設樣式 |
| migration 影響既有資料 | 純新增資料表,**不動任何既有表** | Flyway 失敗會擋住 backend 啟動,舊容器仍在;必要時 rollback 映像 tag |
