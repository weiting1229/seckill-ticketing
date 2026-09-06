package com.seckill.artist.domain;

/**
 * 巡演主題的中英對照,對應 {@code artists.tour_themes} JSONB 陣列的一筆。
 *
 * <p>兩個欄位都可能是空字串:內容包裡主題是選配的,實測 46 個藝人有 19 個完全沒有主題
 * (整個陣列為空),此時 {@code artist_posters.theme_index} 為 NULL。
 */
public record TourTheme(String zh, String en) {
}
