# demo-seed:把 poster-forge 的成品變成站上看得到的活動

**這是權宜工具,不是產品功能。** 它做的事本來是 seeder
([`docs/plans/2026-08-17-demo-event-seeder.md`](../../docs/plans/2026-08-17-demo-event-seeder.md))的職責,
而 seeder **完全未實作**。seeder 做出來之後這整個資料夾應該刪掉。

## 為什麼需要它

seckill 的首頁列的是**活動(events)**,不是藝人。46 張海報掛在 `artists` 表上,
一個藝人要出現在前台,必須存在一個**標題含該團名的活動** —— 因為前端的海報比對是
`utils/posterRegistry.ts` 用標題去比對團名(見 ADR 0009 §19)。

所以只匯入內容包不會讓首頁有任何變化。順序是:

```
poster-forge  pnpm publish:seckill   →  artists / artist_posters + /posters/*.webp
     ↓
scripts/demo-seed/seed_artists_events.py  →  46 個活動(標題含團名)
     ↓
scripts/demo-seed/seed_ticket_types.py    →  115 個票種 + warmup
```

## 憑證與環境

**沒有任何預設帳密**(CLAUDE.md:祕密禁止寫進會 commit 的檔案)。

```bash
export SECKILL_BASE_URL=http://localhost:8080          # 預設值,dev 可省略
export SECKILL_ADMIN_USERNAME=... SECKILL_ADMIN_PASSWORD=...
# 或 export SECKILL_ADMIN_TOKEN=<access token>(15 分鐘就過期,長流程建議用帳密)
```

⚠️ **打非 localhost 時必須另外設 `SECKILL_CONFIRM_PROD=yes`。** 這些腳本會**寫入**資料,
而 HANDOFF.md 記著一條實際存在的風險:PowerShell 的 `$env:` 設定會留在同一個視窗裡、
不隨指令結束清除,所以「以為在打本機其實在打正式站」是會發生的。每支腳本執行時都會
先把目標環境印出來。

## 四支腳本

| 腳本 | 做什麼 | 冪等? |
|---|---|---|
| `seed_artists_events.py` | 讀 `GET /artists`,為**每個藝人建一個活動**並發佈。場地與日期依 slug 排序決定,可重跑結果一致;FEATURED 藝人的活動標為精選 | ❌ **會重複建立**,重跑前先清 |
| `seed_ticket_types.py` | 為每個活動建票種(大型場館 3 檔 / live house 與戶外 2 檔)並逐一 `warmup` | ❌ 同上 |
| `fix_empty_theme_titles.py` | 修正標題含空主題的活動(見下方「已知資料坑」) | ✅ 找不到就不動 |
| `verify_purchase.py` | 端到端驗證:註冊 → 領 token → 搶購 → 輪詢 → 對帳 | ⚠️ **會產生一筆真實訂單並扣一張庫存** |

`cleanup-loadtest-residue.sql` 是清 k6 壓測殘骸用的,直接餵給 psql:

```bash
docker exec -i seckill-postgres psql -U seckill -d seckill -v ON_ERROR_STOP=1 < cleanup-loadtest-residue.sql
```

⚠️ **`docker exec` 少了 `-i` 的話 stdin 不會被轉發,SQL 根本不會執行,而指令會安靜地成功。**
這在 2026-09-07 踩過一次:回報「已刪除」但實際一筆都沒動。跑完務必用 `SELECT count(*)` 複查。

## 兩個必須知道的機制

**一、建票種之後一定要 `warmup`。**
`POST /admin/ticket-types` 建出來的票種是 `OFFLINE` 且 Redis 沒有庫存 key。
不預熱的話,前台看得到票種、按下去回 `3003`(未上線)或 `3008`(尚未就緒)——
**畫面上有票卻買不到,而那個錯誤訊息跟「賣完了」長得不一樣**。
`seed_ticket_types.py` 已經包含這一步;手動建票種時別忘了。

**二、庫存不要造假。**
前台的「充足 / 熱賣中 / 剩餘少量」是依 `remaining/totalStock` 分桶的,滿庫存時全部顯示
「充足」。要讓畫面好看而去改 `stock_remaining`,會讓 `ReconcileService` 的
DB / Redis / 有效訂單 / stock_logs **四方對帳當場不一致** —— 而偵測那種不一致正是這個
專案花力氣做的事。要有銷售數字就真的去買(`verify_purchase.py`)。

## 已知資料坑

內容包裡有 **6 個藝人的 `tourThemes` 陣列非空、但其中一個語言是空字串**
(`cinder` / `ethan-lin` / `lazy-siesta` / `ophelia` / `scarlet-engines` /
`stars-fell-into-the-forest`,例 `ethan-lin` = `[{"en":"First Run","zh":""}]`)。

`seed_artists_events.py` 只檢查陣列非空就套用該語言的主題,所以會產出
「林予安 「」巡迴演唱會」這種空引號標題。跑完用 `fix_empty_theme_titles.py` 修,
或直接在 `compose()` 裡改成檢查該語言的字串非空。

這也是「有主題卻沒有 `themeIndex`」的成因 —— poster-forge 烤圖時該語言沒有文字可放。

## 清掉這批 demo 活動

活動與藝人之間**沒有外鍵**,靠標題比對關聯,所以刪除也用同一個條件:

```sql
-- 順序不可調換:ticket_types → events 有外鍵,先刪 events 會被擋;
-- orders / stock_logs 對 ticket_types 沒有外鍵,不先刪會留下孤兒列而不報錯
BEGIN;
CREATE TEMP TABLE tgt AS
  SELECT e.id FROM events e WHERE EXISTS (
    SELECT 1 FROM artists a
    WHERE e.title LIKE '%' || COALESCE(NULLIF(a.name_zh, ''), a.name_en) || '%');
CREATE TEMP TABLE tgt_tt AS SELECT id FROM ticket_types WHERE event_id IN (SELECT id FROM tgt);
DELETE FROM orders       WHERE ticket_type_id IN (SELECT id FROM tgt_tt);
DELETE FROM stock_logs   WHERE ticket_type_id IN (SELECT id FROM tgt_tt);
DELETE FROM ticket_types WHERE id IN (SELECT id FROM tgt_tt);
DELETE FROM events       WHERE id IN (SELECT id FROM tgt);
COMMIT;
```

刪完 Redis 會留下孤兒的 `seckill:stock:*` key(對 DB 沒有外鍵約束,不會連帶清掉)。
無害但會累積,要清的話比對 `ticket_types.id` 之後 `redis-cli DEL`。
