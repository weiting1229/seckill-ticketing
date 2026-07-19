-- 活動封面圖 URL(方案 C 混合式):為空時前端渲染生成式海報,有值則顯示該圖。
-- nullable;長度上限與 http(s):// 前綴由 Jakarta Validation 於入參層把關。
ALTER TABLE events ADD COLUMN cover_image_url VARCHAR(500);

COMMENT ON COLUMN events.cover_image_url IS '活動封面圖 URL,可為空(空時前端生成式海報)';
