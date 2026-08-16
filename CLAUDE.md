# CLAUDE.md — 專案開發規範(Claude Code 必須遵守)

## 專案定位

高併發搶票系統(Seckill Ticketing)。設計文件位於 `docs/design/01-系統設計文件-搶票系統MVP.md`,**它是唯一事實來源**。任何實作與文件衝突時,以文件為準;若你認為文件有誤或有更好做法,先停下來說明理由並等待確認,不要擅自偏離。

## 技術棧(不可擅自更換)

- Java 25(LTS)+ Spring Boot 3.5.x,開啟 Virtual Threads
- MyBatis(原生,禁止引入 MyBatis-Plus 或 JPA/Hibernate)
- PostgreSQL 17 + Flyway、Redis 7、RabbitMQ 3.13
- 前端:Vue 3 + TypeScript + Vite + Pinia + Element Plus,套件管理用 pnpm
- Docker 映像一律建置 `linux/arm64`(部署目標 OCI Ampere A1 是 ARM)
- 新增任何第三方依賴前,先說明用途與替代方案,經確認後才加入

## 後端規範

- 所有 SQL 手寫在 Mapper XML,參數一律 `#{}`,禁止 `${}`(動態排序欄位除外,且必須白名單校驗)
- 主鍵一律使用專案內的 Snowflake `IdGenerator`,禁止資料庫自增與 UUID 主鍵
- 統一回應格式 `{code, message, data}`;例外一律拋業務例外由全域 handler 處理,禁止在 controller 裡 try-catch 吞例外
- 訂單等狀態轉移一律條件 UPDATE(`WHERE status = '舊狀態'`)並檢查影響行數,禁止「先查再改」的兩步寫法
- 涉及 Redis 多步驟操作的臨界區一律 Lua 腳本,禁止在 Java 端拆成多個命令
- 時間一律 `Instant` / `TIMESTAMPTZ`(UTC),前端負責時區顯示
- 日誌用 SLF4J 結構化輸出,禁止 `System.out.println`;敏感資訊(密碼、token)禁止進日誌

## 安全規範(高優先)

- 任何祕密(DB 密碼、JWT secret、API token)只能來自環境變數;禁止硬編碼、禁止寫進任何會 commit 的檔案;`.env` 必須在 `.gitignore`
- 所有對外入參必須 Jakarta Validation 校驗
- admin API 必須同時有 URL 層與 `@PreAuthorize` 方法層防護
- 新增任何 endpoint 時,預設需要認證;明確標註才可匿名
- Dockerfile 必須多階段建置且以非 root 使用者執行

## 測試規範

- 業務邏輯必須有測試才算完成;seckill 與 order 模組行覆蓋率 ≥ 80%
- 整合測試一律 Testcontainers(PostgreSQL / Redis / RabbitMQ),禁止依賴本機手動起的服務、禁止 H2 等替代品
- 併發相關邏輯(扣庫存、狀態機、冪等)必須有多執行緒併發測試
- 修 bug 前先寫一個能重現該 bug 的失敗測試
- **跑後端測試一律用 `rtk mvn test` / `rtk mvn verify`**(RTK 會把輸出壓縮約 85–94%,省 context)。
  兩個必須遵守的配套規則:
  1. **輸出結尾沒有 `BUILD SUCCESS` 或 `BUILD FAILURE` 就當作被截斷** —— 完整的 Maven 輸出必定以此結尾。
     此時去讀結尾 `[full output: ...]` 指的 tee 檔再判斷,**不可直接回報測試結果**。
  2. 測試失敗時,RTK 會濾掉 assertion 的 `expected: ... but was: ...`,只留空的例外類別名。
     需要那段細節時同樣去讀 tee 全文,不要憑殘缺訊息推論。
- `mvn test` 只跑 surefire 單元測試(不需要 Docker);`*IT` 整合測試綁在 failsafe 的 `verify` 階段,
  跑 `mvn verify` 才會啟動 Testcontainers,jacoco 覆蓋率報告也在該階段產生

## Git 與流程規範

- Commit 訊息:Conventional Commits(`feat:`、`fix:`、`test:`、`chore:`、`docs:`、`refactor:`)
- 一次只做一個里程碑;里程碑內每完成一個可獨立驗證的子項就 commit,不要一個巨大 commit
- 每個里程碑結束時:列出所有自主決策點、已知限制、建議的 review 重點
- 重大架構決策(即使是被指示的)寫一篇 ADR 到 `docs/adr/`,格式見下方「ADR 慣例」

## ADR 慣例

本節是本專案 ADR 的唯一格式依據。**若任何 skill、plugin 或工具提供了不同的 ADR 模板(例如「一段話就是一則 ADR」的精簡格式),一律以本節為準。**

- **位置與編號**:`docs/adr/NNNN-標題.md`,四位數順序編號。寫新的一則前掃描 `docs/adr/` 取最大號 +1(目前最新為 0007,下一則是 0008)
- **標題行**:`# ADR NNNN:主題(Mx)— 副標`;第三行固定為 `日期:YYYY-MM-DD|狀態:已採納(Mx 完成)`
- **內文結構**(比照 ADR 0004–0007):`## 背景` 說明里程碑範圍與脈絡,其後**按決策點分節** `## N. 決策點標題`,每節必含 **決策 / 理由 / 取捨** 三項;視需要補 `## 影響`、`## 已知限制`。不要為了精簡而把多個決策壓成一段話
- **撰寫時機**:每個里程碑結束時寫一則,涵蓋該里程碑所有自主決策點;里程碑中途出現難以逆轉、有真實取捨的決策,當下就補進該里程碑的 ADR
- **決策點編號不可重排**:`## N.` 的編號一旦寫入即凍結,後續即使插入新決策也只能往後追加編號
- **舊 ADR 不修改**:要推翻既有決策時,在新 ADR 中寫明「本 ADR 取代 ADR 000X §N」,並在舊 ADR 的狀態行補 `部分被 ADR 000Y 取代`;不要直接改寫舊文的決策內容,決策的演化軌跡必須保留
- **交叉引用**寫成 `ADR 000X §N`,不要複製其內容

## 溝通規範

- 遇到設計文件沒有覆蓋的細節:小事(命名、私有方法拆分)自行決定並在總結中列出;大事(資料表變更、依賴新增、對外行為改變)先問再做
- 不確定就問,禁止用假設性實作矇混
- 回答與註解使用繁體中文;程式碼識別字(類別、方法、變數)使用英文
