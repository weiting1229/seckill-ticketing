# 02 — UI/UX 優化計畫(前端視覺與體驗全面改版)

> 本文件是 UI/UX 優化的**唯一事實來源**,由專案 owner 與規劃 session 討論定案。
> 實作 session 請完整閱讀本文件與根目錄 `CLAUDE.md` 後再動工。
> 里程碑編號 M1–M7 為本計畫獨立編號,與後端建置歷程的 M0–M7 無關。

---

## 0. 給實作 session 的開場指示(必讀)

1. 先讀根目錄 `CLAUDE.md`(專案硬規範)與本文件全文。
2. **動工前載入 `frontend-design` skill**,在本文件的 design spec 框架內執行視覺細節;spec 已定案的部分(色板方向、深色預設、風格定調)不得推翻。
3. 一次只做一個里程碑;里程碑內每完成一個可獨立驗證的子項就 commit(Conventional Commits)。
4. 每個里程碑結束時:列出所有自主決策點、已知限制、建議的 review 重點。
5. 技術棧不變:Vue 3 + TypeScript + Vite + Pinia + Element Plus + pnpm。**保留 Element Plus**,透過 CSS 變數換膚,不引入其他 UI 框架。新增任何依賴(含 @fontsource 類字體包)前先說明用途並等 owner 確認。
6. 驗證方式:`pnpm lint`、`pnpm type-check`、`pnpm test`(若有)全綠,並實際起 dev server 以瀏覽器檢查深/淺兩種主題與行動版斷點。

## 1. 背景與目標

現況:前端為 Element Plus 預設樣式的功能性 MVP——全站無圖片、無品牌色、無深色主題、桌面版型(`max-width: 1080px`)。核心功能(搶購流程、server clock 校準倒數、訂單付款倒數)已完成且穩定,**本計畫只動視覺與體驗層,不改動搶購核心邏輯與 API 語意**(明確列出的後端擴充除外)。

目標:讓介面無限接近市面大型搶票系統(參考 DICE、Ticketmaster 活動頁的沉浸感),成為作品集亮點。

## 2. 設計規範(Design Spec,已定案)

### 2.1 風格定調

- **沉浸演唱會風**:深色舞台氛圍、海報大圖主導、霓虹點綴。
- **深色為預設主題**,提供淺色主題切換(header 上的切換鈕,localStorage 持久化)。
- 資訊層級清楚優先於裝飾;搶購相關元素(倒數、按鈕、狀態)永遠是頁面上最醒目的東西。

### 2.2 色彩 tokens(方向性基準,允許微調但不得偏離色相家族)

深色主題(預設):

| Token | 值 | 用途 |
|---|---|---|
| `--bg-base` | `#0B0E16` | 頁面底色 |
| `--bg-surface` | `#171C29` | 卡片/浮層 |
| `--brand-primary` | `#7063FF` | 主色:按鈕、連結、focus |
| `--brand-primary-hover` | `#8B7DFF` | 主色 hover |
| `--brand-accent` | `#22D3EE` | 點綴:倒數數字、高亮標籤 |
| `--color-success` | `#22C55E` | 開賣中、付款成功 |
| `--color-warning` | `#F59E0B` | 即將開賣、待付款 |
| `--color-danger` | `#EF4444` | 售罄、逾期、錯誤 |
| `--text-primary` | `#F8FAFC` | 主要文字 |
| `--text-secondary` | `#94A3B8` | 次要文字 |

淺色主題:由實作 session 依上表推導(同色相、調亮度),對比度須符合 WCAG AA(正文 ≥ 4.5:1)。

實作方式:`src/assets/tokens.css` 定義上述自訂屬性,並在 `html.dark` / `html.light` 兩個 scope 下**映射到 Element Plus 的 `--el-*` 變數**(`--el-color-primary`、`--el-bg-color`、`--el-fill-color-*`、`--el-text-color-*` 等),同時引入 `element-plus/theme-chalk/dark/css-vars.css` 作為深色基底。

### 2.3 字體與排印

- 維持系統字堆疊(現有 `main.css`),不引入外部字體(若實作中認為必要,先報備)。
- 數字(倒數、價格、剩餘)一律 `font-variant-numeric: tabular-nums`。
- 字級階層:頁面標題 28–32px / 區塊標題 20px / 正文 15px / 輔助 13px。

### 2.4 形狀與動效

- 圓角:卡片 12px、按鈕與輸入 8px、徽章 pill。
- 深色主題下少用陰影,以「表面亮度差 + 1px 邊框(低透明白)」區分層次。
- 動效節制:卡片 hover 抬升 2–4px、頁面切換 fade、倒數歸零時按鈕一次性脈衝;全部尊重 `prefers-reduced-motion`。

## 3. 版權紅線(全站內容規範,不可違反)

1. **禁止**使用任何真實藝人/樂團的官方照片、專輯封面、官方 logo、標準字。
2. **禁止**放任何歌詞文字。
3. 生成式海報與任何示意圖**不得出現可辨識的真實人物形象**(剪影、背影且不可辨識為特定人 = 允許)。
4. 活動名稱允許使用真實樂團名(名稱不受著作權保護),但全站 footer 必須常駐免責聲明:
   > 本網站為個人技術展示作品,所有活動、票價與販售資訊均為虛構,與相關藝人及其經紀公司無關。
5. 海報構圖不得模仿任何一張真實官方海報的具體構圖。

## 4. 生成式海報規格(方案 C:混合式)

### 4.1 行為

- `event.coverImageUrl` **為空** → 前端即時渲染 `<GenerativePoster>`(生成式 SVG)。
- `event.coverImageUrl` **有值** → 直接顯示該圖(`object-fit: cover`),生成式海報作為圖片載入失敗的 fallback。
- admin 可在活動編輯表單填入/清空圖片 URL,即時預覽。

### 4.2 `<GenerativePoster>` 元件規格

- 位置:`src/components/GenerativePoster.vue`,純前端、無網路請求、無新依賴。
- Props:`title`(必填,兼作 seed)、`subtitle?`、`venue?`、`eventTime?`、`variant: 'poster' | 'banner'`(3:4 直式 / 21:9 橫式 hero)。
- 演算法:`hash(title)` 產生 32-bit seed → 決定
  1. 調色盤:從 4–6 組與品牌色相容的深色調色盤中選一組(紫羅蘭/青色/珊瑚/靛藍…);
  2. 構圖參數:月亮(大圓)位置與大小、同心圓環數、光束角度、天際線剪影的高度序列、星點分布。
- 同一 title 永遠產出同一張圖(deterministic);hash 與參數推導寫在 `src/utils/posterSeed.ts` 並附單元測試(同 seed 同輸出、不同 seed 參數分布合理)。
- 文字排版由元件內固定版式負責(團名大字 + 副標 + 日期場地),不參與隨機。

## 5. 里程碑

### M1 — 設計系統基座與主題切換

**目標**:全站換膚的地基;完成後所有頁面自動獲得新配色,深/淺主題可切換。

**涉及檔案**:`src/assets/tokens.css`(新)、`src/assets/main.css`、`src/main.ts`、`src/composables/useTheme.ts`(新)、`src/App.vue`。

**實作要點**:
- 依 §2.2 建立 tokens 與 Element Plus 變數映射;引入 EP dark css-vars。
- `useTheme`:深色預設、localStorage 持久化、切換時對 `<html>` 切 class;不引入 @vueuse(自寫即可)。
- App.vue header 加主題切換鈕(sun/moon icon);header 改深色玻璃感(半透明 + backdrop-filter)。
- 全站 footer(新增):免責聲明(§3.4)+ 專案 GitHub 連結。
- 品牌字樣「🎫 搶票系統」可重新命名為英文品牌名(自主決策,結尾報告)。

**驗收**:兩主題下所有既有頁面無不可讀文字(逐頁檢查);重新整理後主題保持;lint/type-check 綠。

### M2 — 活動封面欄位(後端)+ 生成式海報元件

**目標**:方案 C 的資料層與元件層。

**涉及檔案(後端)**:`db/migration/V2__add_event_cover.sql`(新)、events 相關 model / mapper XML / DTO / admin request 校驗;**涉及檔案(前端)**:`src/components/GenerativePoster.vue`(新)、`src/utils/posterSeed.ts`(新)、`src/api/types.ts`、`src/views/admin/AdminEventsView.vue`。

**實作要點**:
- migration:`ALTER TABLE events ADD COLUMN cover_image_url VARCHAR(500);`(nullable)。此資料表變更**已經 owner 核准**(本文件即核准紀錄)。
- 公開 API 的 `EventSummary` / `EventDetail` 帶出 `coverImageUrl`;admin 建立/更新接受該欄位,Jakarta Validation 限制長度與 `http(s)://` 開頭。
- admin 表單加 URL 輸入與即時預覽(空值時預覽生成式海報,所見即所得)。
- GenerativePoster 依 §4.2 實作,poster/banner 兩 variant;posterSeed 附單元測試。

**驗收**:後端整合測試(Testcontainers)覆蓋新欄位讀寫;前端單測綠;admin 填/清 URL 後前台即時反映。

### M3 — 活動列表頁改版

**目標**:文字直列卡片 → 海報網格,建立門面。

**涉及檔案**:`src/views/EventListView.vue`、`src/api/events.ts`;後端 list API 加選配 `keyword` 參數(title ILIKE,mapper 用 `#{}` 綁定)。

**實作要點**:
- 響應式網格:桌面 3 欄 / 平板 2 欄 / 手機 1 欄;卡片 = 海報(banner variant 或 poster 裁切)+ 日期角標 + 標題 + 場地 + 狀態徽章。
- `v-loading` 換成海報卡片形狀的 skeleton(EP `el-skeleton` 客製)。
- 頂部加搜尋框(debounce 300ms 走 `keyword`)與精簡 hero 區(標語一行 + 漸層背景,不搶活動的風頭)。
- 空狀態插畫化(用生成式風格的簡單 SVG,不放真實圖片)。

**驗收**:三斷點截圖檢查;搜尋後分頁行為正確;後端 keyword 有整合測試。

### M4 — 活動詳情頁改版

**目標**:hero 沉浸區 + 票種價格表 + 大型倒數看板。

**涉及檔案**:`src/views/EventDetailView.vue`(必要時拆出 `src/components/TicketTypeCard.vue`、`CountdownBoard.vue`)。

**實作要點**:
- Hero:banner 海報滿版 + 底部漸層壓暗 + 標題/場地/時間疊字;向下捲動時右側(桌面)或底部(手機)出現 **sticky 購票面板**。
- 票種區改「價格表」視覺:每票種一列,票區色點、價格大字、狀態徽章;售罄票種整列灰化 + 斜向「SOLD OUT」章。
- **剩餘票數模糊化**:前台不顯示精確數字,依 `remaining/totalStock` 比例顯示「充足 / 熱賣中 / 剩餘少量 / 售罄」(門檻:>50% / 10–50% / 1–10% / 0);精確數字保留給 admin 頁。
- **大型倒數看板**:`upcoming` 時顯示 時:分:秒 翻牌式大數字(accent 色、tabular-nums),沿用現有 serverClock,歸零瞬間按鈕點亮 + 一次性脈衝動效(現有 phase watch 重載邏輯不動)。

**驗收**:phase 四態(offline/upcoming/live/ended)各截圖;倒數跨越開賣邊界時按鈕自動可按;兩主題 + 三斷點檢查。

### M5 — 搶購與付款體驗

**目標**:排隊小 dialog → 全頁 waiting room;付款倒數醒目化。

**涉及檔案**:`src/components/WaitingRoom.vue`(新)、`src/views/EventDetailView.vue`、`src/views/OrderDetailView.vue`、`src/composables/useSeckillFlow.ts`(僅暴露資訊,輪詢策略不得更動)。

**實作要點**:
- WaitingRoom:`buyPhase === 'queuing'` 時全螢幕覆蓋(teleport 到 body):品牌動畫(CSS 等化器條/脈衝圓環)、已等待秒數、輪流顯示的提示文案(「請勿重新整理」「結果也會出現在我的訂單」)、TIMEOUT 後引導按鈕。
- useSeckillFlow 增加暴露 `attempt` / 已等待時間(唯讀),**輪詢間隔、次數、退避策略一律不改**。
- OrderDetailView:`PENDING_PAYMENT` 時頁面頂部加常駐倒數條(剩餘時間進度條,<3 分鐘轉 danger 色);逾期自動翻狀態的既有輪詢邏輯不動。
- 搶購失敗/售罄的 ElMessage 提示升級為更明確的結果卡(含下一步引導)。

**驗收**:以 dev 環境實際跑一次完整搶購(成功、售罄、重複購買三情境);WaitingRoom 在慢速結果下不可關閉但 TIMEOUT 後可離開;付款倒數條與訂單 `expireAt` 一致。

### M6 — 訂單、登入與 admin 頁換膚

**目標**:剩餘頁面對齊新設計系統,消滅「素 MVP 殘留」。

**涉及檔案**:`src/views/OrderListView.vue`、`OrderDetailView.vue`、`LoginView.vue`、`NotFoundView.vue`、`src/views/admin/*.vue`。

**實作要點**:
- 訂單列表:卡片化 + 狀態徽章色與 §2.2 對齊 + 每筆帶活動小海報縮圖(GenerativePoster 復用)。
- 登入頁:置中卡片 + 品牌視覺背景(生成式風格漸層/星點),表單體驗(enter 送出、錯誤訊息就地顯示)。
- admin 兩頁:換膚為深色後台風,表格密度與操作按鈕整理;不新增功能。
- 404 頁品牌化。

**驗收**:全站逐頁走查,無任何頁面殘留預設 EP 白底藍鍵風格。

### M7 — 行動版、無障礙與最終 QA

**目標**:手機優先體驗收尾 + 可及性 + 整體品質驗收。

**涉及檔案**:全站微調;`src/App.vue`(行動版導覽)。

**實作要點**:
- 斷點:≥1024 桌面 / 640–1023 平板 / <640 手機。App header 在手機收合為 hamburger + `el-drawer` 選單;`app-main` padding 隨斷點調整。
- 觸控目標 ≥ 44×44px(尤其「立即搶購」在手機為底部固定滿版按鈕)。
- 無障礙:focus ring 可見(brand 色)、圖片 alt、倒數看板加 `aria-live="polite"`、對比度抽查。
- `prefers-reduced-motion` 全站生效檢查。
- 最終 QA 清單:兩主題 × 三斷點 × 全頁面截圖走查;Lighthouse(手機)Performance ≥ 85、Accessibility ≥ 95;`pnpm build` 產物大小回報。

**驗收**:QA 清單全數通過並在結尾報告附截圖與 Lighthouse 分數。

## 6. 驗收總則

- 每個里程碑:lint / type-check / 既有測試全綠;新邏輯(posterSeed、票數模糊化門檻、倒數)有單元測試。
- 任何後端改動遵守 `CLAUDE.md`(Mapper XML、`#{}`、Testcontainers、Conventional Commits)。
- **不碰**:搶購核心(token→purchase→輪詢語意)、Redis/Lua、訂單狀態機、既有 API 回應格式(只允許「增欄位」)。
- 遇到本文件未覆蓋的視覺細節:小事自行決定並在結尾列出;大事(新依賴、API 行為改變、資料表變更)先問 owner。
