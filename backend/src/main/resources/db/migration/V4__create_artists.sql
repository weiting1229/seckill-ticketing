-- V4__create_artists.sql
-- 藝人與海報:poster-forge 內容包(curated/content-pack.json + curated/posters/)的落地表。
-- 計畫:docs/plans/2026-08-18-poster-dynamic-management.md(§5 + 2026-09-06 的 S1~S6 標註)。
--
-- ⚠ 版號用 V4 而非計畫 §5 寫的 V6:seeder 計畫預定的 V4/V5 尚未實作、沒有佔號。
-- 若這裡跳號用 V6,日後補 V4 時 Flyway 預設 outOfOrder=false 會讓 backend 啟動直接失敗,
-- 而那是部署當下才炸。版號依實際已存在的最大號(V3)遞增。

CREATE TABLE artists (
    id          BIGINT       PRIMARY KEY,              -- Snowflake,不用自增
    slug        VARCHAR(50)  NOT NULL UNIQUE,          -- 檔名/URL 用,小寫英數與連字號
    -- 中英團名分離(S3)。⚠ 實測 content-pack 46 筆:name_en 46/46 皆有且互不重複,
    -- name_zh 有 23 筆是空的 —— 與 S3 標註寫的「name_en 可為 NULL」正好相反。
    -- 因此 name_en 設 NOT NULL,name_zh 允許 NULL(匯入時空字串正規化成 NULL)。
    -- name_zh 的唯一性走部分索引:若設欄位層 UNIQUE,23 筆空值以空字串寫入就會互撞。
    name_zh     VARCHAR(100),
    name_en     VARCHAR(100) NOT NULL UNIQUE,
    tier        VARCHAR(16)  NOT NULL DEFAULT 'ROTATING',   -- FEATURED 進常駐桶 / ROTATING 進輪替桶
    -- 巡演主題 [{zh, en}](S2)。⚠ 必須允許空陣列:實測 46 個藝人有 19 個沒有主題。
    tour_themes JSONB        NOT NULL DEFAULT '[]'::jsonb,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,    -- FALSE = 不再產生新活動,既有活動不受影響
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_artists_slug_format  CHECK (slug ~ '^[a-z0-9-]+$'),
    CONSTRAINT chk_artists_tier         CHECK (tier IN ('FEATURED', 'ROTATING')),
    -- 空字串與 NULL 是兩種不同的「沒有中文名」,只留一種:否則部分唯一索引擋不住空字串互撞。
    CONSTRAINT chk_artists_name_zh_not_blank CHECK (name_zh IS NULL OR name_zh <> ''),
    CONSTRAINT chk_artists_tour_themes_array CHECK (jsonb_typeof(tour_themes) = 'array')
);

CREATE UNIQUE INDEX uq_artists_name_zh ON artists (name_zh) WHERE name_zh IS NOT NULL;
CREATE INDEX idx_artists_enabled ON artists (enabled) WHERE enabled = TRUE;

-- 一藝人多張海報(S1)。計畫 §11 待決 #7 於 2026-09-06 拍板為獨立子表而非 posters JSONB:
-- file_name 需要 UNIQUE 當匯入的冪等鍵,而 JSONB 陣列給不了唯一約束 ——
-- 檔名重複只會在覆蓋掉舊資料時才被發現,而那一步不會報錯。
CREATE TABLE artist_posters (
    id          BIGINT       PRIMARY KEY,
    artist_id   BIGINT       NOT NULL REFERENCES artists(id) ON DELETE CASCADE,
    -- poster-forge 的檔名格式 <slug>__<版式>.webp。**只存檔名不存路徑**:
    -- 對外 URL 由 /posters/ 前綴在回應層組出來,前綴要改時不必搬資料,
    -- 也不會出現「DB 存的路徑」與「Caddy 實際掛載點」兩份事實各自漂移。
    file_name   VARCHAR(200) NOT NULL UNIQUE,
    archetype   VARCHAR(32)  NOT NULL,                 -- 版式,例 bottom-heavy
    -- 指向 artists.tour_themes 的第幾筆;⚠ NULL = 這張海報上沒有烤主題文字。
    -- 不可用 0 或 -1 代替:兩者都會指向不存在的一筆,而 JS 取到 undefined 不會報錯。
    theme_index INTEGER,
    seed        BIGINT,                                -- 生成種子,供追溯回 poster-forge
    sort_order  INTEGER      NOT NULL DEFAULT 0,       -- 內容包裡的順序,決定「第一張」是哪張
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_artist_posters_file_name  CHECK (file_name ~ '^[a-z0-9][a-z0-9_-]*\.webp$'),
    CONSTRAINT chk_artist_posters_theme_index CHECK (theme_index IS NULL OR theme_index >= 0),
    CONSTRAINT chk_artist_posters_sort_order  CHECK (sort_order >= 0)
);

CREATE INDEX idx_artist_posters_artist ON artist_posters (artist_id, sort_order, id);

COMMENT ON TABLE  artists                   IS '藝人清單:seeder 產標題與前端海報對照的單一事實來源,由 poster-forge 內容包匯入';
COMMENT ON COLUMN artists.name_zh           IS '中文團名;NULL = 該團沒有中文名(顯示名退回 name_en)';
COMMENT ON COLUMN artists.tour_themes       IS '巡演主題 [{zh, en}];空陣列合法(19/46 沒有主題)';
COMMENT ON TABLE  artist_posters            IS '藝人海報:一藝人可多張,file_name 為匯入冪等鍵';
COMMENT ON COLUMN artist_posters.theme_index IS 'tour_themes 的索引;NULL = 海報上沒有烤主題文字';
