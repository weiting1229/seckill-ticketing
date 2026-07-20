# 03 — UI/UX 優化計畫 M7 交接文件

> 給**下一個實作 session**。本文件自包含:讀完這份 + `docs/design/02-UIUX優化計畫.md` + 根目錄 `CLAUDE.md` 即可接手 M7。
> 撰寫時間:2026-07-19。前一個 session 完成 M1–M6。
> **注意**:本文件的 M1–M7 是 UI/UX 計畫編號,與後端建置歷程的 M0–M7(見 `docs/handover-M7.md`)無關。

---

## 0. 開場指示(必讀,照做)

1. 先讀根目錄 `CLAUDE.md`(專案硬規範)與 `docs/design/02-UIUX優化計畫.md` 全文,再讀本文件。
2. **動工前載入 `frontend-design` skill**;spec 已定案的部分(色板方向、深色預設、風格定調)不得推翻。
3. M7 是**最後一個里程碑**。一次只做一個里程碑;里程碑內每完成一個可獨立驗證的子項就 commit(Conventional Commits,訊息用繁體中文)。
4. 結束時列出:所有自主決策點、已知限制、建議 review 重點(前幾個里程碑都照這個格式報告)。
5. 技術棧不變:Vue 3 + TS + Vite + Pinia + Element Plus + pnpm。**保留 Element Plus**,透過 CSS 變數換膚。**新增任何依賴前先問 owner**。
6. 驗證:`pnpm type-check`、`pnpm ci:lint`、`pnpm test` 全綠 + 起 dev server 用瀏覽器實測。

---

## 1. ⚠️ 最優先:未完的 git 動作

**M6 已 commit,但尚未合併回 main、尚未 push。**

```
main                 478f142  (= origin/main,M5 為止都已 push)
feat/m6-page-reskin  8f63165  feat(frontend): M6 登入/訂單列表/404 換膚對齊設計系統
```

- 工作區乾淨,無未追蹤的程式碼改動。
- 前幾個里程碑的慣例:**每個里程碑結束 → 報告 → owner 說「合併回 main 並 push」→ 才 `git checkout main && git merge --ff-only <branch> && git push origin main && git branch -d <branch>`**。
- **M6 的合併/push 尚未獲得 owner 授權**(owner 上一輪改問了 DBeaver 的問題,沒回覆合併)。
- 👉 **接手第一件事**:請 owner 確認是否要先把 M6 合併回 main 並 push,再開 M7 分支(建議 `feat/m7-mobile-a11y-qa`)。**不要自己合併**。

---

## 2. 目前完成度(M1–M6)

| 里程碑 | 狀態 | 內容 |
|---|---|---|
| M1 設計系統基座 + 主題切換 | ✅ 已 push | tokens、useTheme、玻璃 header、footer、品牌 TIXCO |
| M2 封面欄位 + 生成式海報 | ✅ 已 push | V2 migration、GenerativePoster、posterSeed、admin 預覽 |
| M3 活動列表改版 | ✅ 已 push | 海報網格、hero、搜尋(後端 keyword) |
| M4 活動詳情改版 | ✅ 已 push | hero、sticky 購票面板、價格表、倒數看板、票數模糊化 |
| M5 搶購與付款體驗 | ✅ 已 push | WaitingRoom 全頁排隊室、結果卡、付款倒數條 |
| M6 剩餘頁面換膚 | ⚠️ **已 commit 未合併** | 登入頁品牌視覺、訂單列表卡片化、404 品牌化 |
| **M7 行動版/無障礙/最終 QA** | ⬜ **待做(本次任務)** | 見第 4 節 |

---

## 3. 已建立的設計系統與元件(**請復用,不要重造**)

### 3.1 Design tokens — `src/assets/tokens.css`
- `html.dark` / `html.light` 兩 scope 定義語意 token,並**映射到 Element Plus 的 `--el-*` 變數**(含 primary 與各語意色的完整 light-N 色階)。深色為預設。
- 常用:`--bg-base`、`--bg-surface`、`--brand-primary`、`--brand-primary-hover`、`--brand-accent`、`--color-success/warning/danger`、`--text-primary`、`--text-secondary`、`--hairline`、`--header-bg`、`--footer-bg`。
- 主題無關:`--radius-card:12px`、`--radius-control:8px`、`--radius-pill`、`--transition-base:160ms`、`--header-height:60px`、`--content-max:1080px`。
- **淺色主題 primary 是 `#5B4FD6`(比深色的 `#7063FF` 深一階)**,為了白底連結對比達 WCAG AA。改色時請保持這個關係。

### 3.2 全域樣式 — `src/assets/main.css`
- `body` 用 token;`.tabular-nums` 工具類(數字等寬,倒數/價格一律加);
- **已有全站 `@media (prefers-reduced-motion: reduce)` 規則**把 animation/transition 壓到 0.001ms。M7 只需「檢查生效 + 個別元件的靜態替代」,不用重寫。

### 3.3 主題 — `src/composables/useTheme.ts` + `index.html` inline script
- module 單例、localStorage key `theme`、切 `<html>` 的 `dark`/`light` class、同步 `color-scheme`。
- `index.html` 有 inline script 在首繪前套 class(防 FOUC)。**改主題邏輯時兩邊要一致**。

### 3.4 元件(`src/components/`)
| 元件 | 用途 | 重點 props |
|---|---|---|
| `GenerativePoster.vue` | 生成式 SVG 海報(零依賴、零版權風險) | `title`(必填,兼 seed)、`variant: 'poster'\|'banner'`、`showLabel`(false = 純藝術,列表卡片用) |
| `CountdownBoard.vue` | 翻牌式大型倒數看板 | `ms`(父層以 serverClock 算好傳入)、`label`、`tone: 'upcoming'\|'live'` |
| `TicketTypeCard.vue` | 票種價格表一列 | `ticket`、`phase`、`disabled`、`loading`、`pulse`、`countdown` |
| `WaitingRoom.vue` | 全頁排隊室 + 結果卡(teleport body) | `mode: 'queuing'\|'result'`、`startedAt`、`result` |

### 3.5 工具(`src/utils/`)
- `posterSeed.ts` — FNV-1a hash → mulberry32 → 6 組調色盤 + 構圖參數。**亂數抽取順序固定不可調換**(調色盤→月亮→圓環→光束→天際線→星點),否則既有活動的海報會整批變樣。有單元測試。
- `ticketDisplay.ts` — 剩餘票數模糊化(`>50%充足 / ≥10%熱賣中 / >0剩餘少量 / 0售罄`)+ 票區色點。有單元測試。
- `datetime.ts`、`serverClock.ts`(server time 校準,倒數一律用它,**不要用瀏覽器時鐘**)、`eventCache.ts`。

### 3.6 測試
- **vitest 已安裝**(唯一經 owner 核准新增的依賴)。`pnpm test` = `vitest run`,`pnpm test:unit` = watch。
- 現有 18 例:`posterSeed.spec.ts`(8)、`ticketDisplay.spec.ts`(10)。
- 目前**沒有** `@vue/test-utils`/jsdom(owner 選擇只加 vitest)。要寫元件測試需先問 owner。

---

## 4. M7 待辦(本次任務)

出處:`docs/design/02-UIUX優化計畫.md` §5 M7。**涉及檔案**:全站微調 + `src/App.vue`(行動版導覽)。

### 4.1 實作要點
1. **斷點統一**:≥1024 桌面 / 640–1023 平板 / <640 手機。
2. **App header 行動版**:手機收合為 hamburger + `el-drawer` 選單;`app-main` padding 隨斷點調整。
3. **觸控目標 ≥ 44×44px**(尤其「立即搶購」在手機為底部固定滿版按鈕)。
4. **無障礙**:focus ring 可見(brand 色)、圖片 alt、**倒數看板加 `aria-live="polite"`**、對比度抽查。
5. **`prefers-reduced-motion` 全站生效檢查**。
6. **最終 QA 清單**:兩主題 × 三斷點 × 全頁面截圖走查;**Lighthouse(手機)Performance ≥ 85、Accessibility ≥ 95**;`pnpm build` 產物大小回報。

### 4.2 ⚠️ 已知需要在 M7 收斂的既有問題

這些是前面里程碑留下、**M7 應該一併處理**的:

- **斷點目前不一致**,M7 要統一成 §5 M7 的規格:
  - `EventListView` 網格:`max-width: 1023px` → 2 欄、`639px` → 1 欄。
  - `EventDetailView`:`max-width: 899px` 時雙欄改單欄 + sticky 面板變底部固定列(**899 不在規格的斷點集合內**,需決定要不要改成 1023)。
  - `TicketTypeCard`、`OrderListView`、`App.vue` footer:各自有 `639px` 斷點。
- **`CountdownBoard.vue` 目前是 `role="timer" aria-live="off"`** → M7 要求改成 `aria-live="polite"`(注意:每秒更新的倒數若直接 polite 會讓螢幕閱讀器狂念,建議只在關鍵節點播報,或用 `aria-live` 搭配較粗的更新粒度——這是個需要判斷的設計點,請在結尾報告說明你的取捨)。
- **手機底部固定購票列**的按鈕高度需確認 ≥44px;`EventDetailView` 的 `.detail` 在 <900px 有 `padding-bottom: 92px` 預留,改斷點時要一起調。
- **focus ring 已有的地方**:`.card`(列表卡)、`.theme-toggle`、`.ord`(訂單卡)有 `:focus-visible`。**尚未全面套用**(EP 元件靠自身樣式),M7 要抽查並補齊。
- **圖片 alt 已有**:列表卡 `${title} 封面`、hero `${title} 主視覺`、admin 預覽「封面預覽」;`GenerativePoster` 用 `role="img"` + `aria-label`。抽查其餘。

### 4.3 驗收(§5 M7)
QA 清單全數通過,並在結尾報告**附截圖與 Lighthouse 分數**。

---

## 5. 開發環境啟動(每次 session 開始都要做)

> 目前**所有服務都已停止**(容器、後端、前端都不在跑)。

### 5.1 本機工具鏈(不在 PATH,需明確路徑)
- JDK 25:`C:\Users\USER\.jdks\temurin-25\jdk-25.0.3+9`(設 `JAVA_HOME`)
- Maven:repo 內有 `mvnw` wrapper
- pnpm:`C:\Users\USER\AppData\Roaming\npm\pnpm`(v10.27)
- Docker Desktop 需先啟動

### 5.2 起中介軟體(PostgreSQL / Redis / RabbitMQ)
```bash
cd /c/Users/USER/Documents/seckill-ticketing
docker compose --env-file .env -f infra/docker-compose.dev.yml up -d
```
⚠️ **compose 指令務必帶 `--env-file .env`**(compose 檔在 `infra/`、`.env` 在 repo 根,不會自動載入;省略會因 `:?` 必填檢查報錯)。

### 5.3 起後端(dev profile)
```bash
cd /c/Users/USER/Documents/seckill-ticketing/backend
export JAVA_HOME="C:/Users/USER/.jdks/temurin-25/jdk-25.0.3+9"
set -a && . ../.env && set +a
export SECKILL_ADMIN_USERNAME=admin_local SECKILL_ADMIN_PASSWORD=AdminLocal123
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=dev
```
背景執行,輪詢 log 等 `Started SeckillApplication` 再繼續。

### 5.4 起前端
`.claude/launch.json` 已有設定:用 preview_start `seckill-frontend`(port 5173,已含 `/api` → `localhost:8080` proxy)。

### 5.5 帳號
- admin:`admin_local` / `AdminLocal123`(env 種子建立)
- 一般使用者:**註冊 API 一律建 USER**,自己註冊即可(密碼規則:8–72 字、至少一英文字母 + 一數字)

### 5.6 資料現況
dev DB 有前一個 session 的驗證殘留(M5「落日飛車 M5 體驗驗證」活動 + 少量訂單 + 約 90+ 測試 users)。
- **要清活動資料**(M4 結束時清過一次):
  ```bash
  docker exec seckill-postgres psql -U seckill -d seckill -c "TRUNCATE orders, stock_logs, ticket_types, events CASCADE;"
  # Redis 對應 key
  for pat in 'seckill:stock:*' 'seckill:bought:*' 'seckill:result:*'; do
    docker exec seckill-redis redis-cli --scan --pattern "$pat" | xargs -r docker exec seckill-redis redis-cli DEL
  done
  ```
  (`users` 表刻意保留,admin 種子在裡面)
- **M7 的 QA 走查需要有資料**:建議先 seed 幾筆已發布活動 + 票種(見第 6 節的踩雷筆記)。

---

## 6. 踩雷筆記(前面 session 累積,**照做可省很多時間**)

### 6.1 驗證/測試相關
- **搶購端點對 ADMIN 回 403**(需 USER role)。測搶購流程**一定要註冊一般 USER**,不能用 admin_local。
- **登入頁有 route guard**:已登入者進 `/login` 會被導回 `/events`。要看登入頁先清 token:
  `localStorage.removeItem('seckill.accessToken'); localStorage.removeItem('seckill.refreshToken')`
- **瀏覽器 UI 登入表單用 form_input + click 有時不生效**。最穩的做法:
  ```js
  const r = await (await fetch('/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({username:'...',password:'...'})})).json();
  localStorage.setItem('seckill.accessToken', r.data.accessToken);
  localStorage.setItem('seckill.refreshToken', r.data.refreshToken);
  ```
  然後 reload 讓 store 水合。
- **screenshot 在 fixed 全螢幕疊層(WaitingRoom)上會 timeout** → 改用 DOM 斷言(`document.querySelector('.wr__title')?.textContent` 等)驗證。偶發 timeout 重試一次通常就好。
- **造票種各 phase**:用近未來 `seckillStart` + warmup 觀察轉換。**lead time 要 ≥40 秒**(18 秒會被 tool round-trip latency 吃掉,還沒截到就已經 live)。
- **造剩餘票數的桶**(充足/熱賣中/剩餘少量/售罄):直接改 Redis,dev redis **無密碼**:
  `docker exec seckill-redis redis-cli SET seckill:stock:{ticketTypeId} 5`
- **造售罄結果卡**:因為 remaining=0 時按鈕本來就 disabled,要用 race——先設 stock=5 讓前端載入時 enable,再 `SET 0`,然後點購買 → 後端 Lua 扣減失敗回 3005。
- **搶購的一次性脈衝只持續 1.4 秒**:要從 upcoming 狀態就開始輪詢才捕捉得到。
- **整合測試的查詢字串不要放中文/空格**(URLEncoder 的 `+` 會被當字面值),M3 的 keyword 測試因此改用純 ASCII。

### 6.2 建置/工具
- `vue-tsc` 開了 `noUncheckedIndexedAccess`:陣列用變數索引取值會是 `T | undefined`。專案內的解法是把常數陣列宣告成 `as const satisfies readonly T[]`(tuple),再 `arr[i] ?? arr[0]`(見 `posterSeed.ts` / `ticketDisplay.ts`)。
- Git Bash 的工作目錄在 tool call 之間**會保留**,但還是建議用絕對路徑。
- `git add` 時會有 `LF will be replaced by CRLF` 警告,**正常**(專案有 `.gitattributes`)。
- commit 訊息:Conventional Commits + 繁體中文本文 + `Co-Authored-By`。

### 6.3 CSS 現代特性(全站已在用,M7 保持一致即可)
`color-mix()`、container query 單位(`cqmin`)、`aspect-ratio`。都需要較新瀏覽器(Chrome 111+)。**這是 owner 已知並接受的取捨**,Lighthouse 跑分時若有相容性提示,照實回報即可,不要為此重寫。

---

## 7. 硬規範速查(`CLAUDE.md` 摘要,M7 會碰到的)

- 回答與註解用**繁體中文**;程式碼識別字用英文。
- **不碰**:搶購核心(token→purchase→輪詢語意)、Redis/Lua、訂單狀態機、既有 API 回應格式(**只允許增欄位**)。
- 新增第三方依賴前先說明用途與替代方案,**經確認才加**。
- 業務邏輯必須有測試才算完成;新邏輯(如 M7 若新增任何判斷函式)要有單元測試。
- 遇到設計文件沒覆蓋的細節:小事自行決定並在總結列出;大事(資料表變更、依賴新增、對外行為改變)**先問再做**。
- 重大架構決策寫 ADR 到 `docs/adr/`(M1–M6 都沒到需要 ADR 的程度;M7 若有重大取捨可考慮)。

---

## 8. 未解事項 / Open items(**請向 owner 確認,不要自行決定**)

1. **M6 合併/push 授權**(見第 1 節)——接手第一件事。
2. **剩餘票數精確值仍在公開 API payload**:M4 的模糊化只做在前端顯示層。要真正對外遮蔽精確庫存需改後端回應格式,而計畫 §6 規定「既有 API 回應格式只允許增欄位」,所以**超出本計畫範圍**。owner 已知,建議另立議題。
3. **搶購端點對 ADMIN 回 403** 是否為刻意設計(既有後端行為,非 UI/UX 計畫造成)——待 owner 確認,可能值得開 issue。
4. **admin 兩頁是否需要更深的「儀表板」重排**:M6 判定 M1 tokens 已提供深色後台外觀,未改一行程式碼。owner 尚未回覆是否滿意。
5. **dev DB 測試資料**是否要保留(見 5.6)。

---

## 9. 附錄:owner 上一輪問的 DBeaver 連線問題(已查明,尚未答覆)

> **問題**:為何本機 DBeaver 連不上 PROD 的 PostgreSQL(`132.145.121.46:5432`)?

**答案:連不上是正常且刻意的,有三層都擋住,而且不該打開。**

1. **`infra/docker-compose.prod.yml` 的 postgres 服務完全沒有 `ports:` 映射** —— 它只存在於 Docker 內部網路,只有 backend 容器能用 `postgres:5432` 連。整份 prod compose 只有 Caddy 對外開 `80:80` / `443:443`。
2. **OCI 雲端 Security List / NSG 只開 80/443**(+22),5432 沒開 ingress。
3. **主機 firewalld 只開 http/https**,5432 沒開。

**不要為了連線去開放 5432 對網際網路**——把資料庫直接暴露在公網是嚴重風險。

**正確做法(專案已有先例)**:prod compose 裡 Prometheus/Grafana 就是**只綁 `127.0.0.1` + 走 SSH tunnel**,照抄這個模式:

- 作法 A(建議,最貼近專案慣例):在 prod compose 的 postgres 服務加
  `ports: ["127.0.0.1:5432:5432"]`(**只綁 loopback,不是 `5432:5432`**),重新部署後在本機開 tunnel:
  ```bash
  ssh -L 5432:localhost:5432 opc@132.145.121.46
  ```
  DBeaver 就連 `localhost:5432`(Host 填 `localhost`,不是 132.145.121.46)。
- 作法 B(不改 compose):postgres 容器沒綁 host port,所以要轉發到**容器 IP**:
  ```bash
  ssh opc@132.145.121.46 "docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' seckill-postgres"
  ssh -L 5432:<那個容器IP>:5432 opc@132.145.121.46
  ```
- 作法 C:DBeaver 內建 SSH tunnel(你截圖上方那個 **「SSH, SSL, ...」** 分頁)直接設定,免自己下 ssh 指令。Host 一樣填 tunnel 另一端看得到的位址。

三者都需要 SSH 私鑰(部署帳號 `opc`)。帳密用 `.env` 裡的 `POSTGRES_USER` / `POSTGRES_PASSWORD`。

---

## 10. 參考文件

- **唯一事實來源(UI/UX)**:`docs/design/02-UIUX優化計畫.md`
- 系統設計:`docs/design/01-系統設計文件-搶票系統MVP.md`
- 專案硬規範:根目錄 `CLAUDE.md`
- 後端 M7(CD/壓測,**與本計畫無關**):`docs/handover-M7.md`、`docs/handover-M7-loadtest.md`
- ADR:`docs/adr/0001`–`0007`
- 正式站:https://tixco.kozow.com
