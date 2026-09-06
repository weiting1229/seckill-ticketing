package com.seckill.artist.dto;

import com.seckill.artist.domain.ArtistPoster;

/**
 * 對外的海報視圖。
 *
 * <p>{@code imageUrl} 由固定前綴 + 檔名組出來,<b>不從資料庫讀</b>:URL 前綴與檔案實際掛載點
 * 若各自存成兩份事實,改前綴時漏掉一邊不會報錯,只會整站破圖。
 */
public record PosterView(String imageUrl, String archetype, Integer themeIndex) {

    /** dev 由 Vite publicDir 提供、prod 由 Caddy file_server 提供,路徑一致(計畫 §4.6)。 */
    public static final String URL_PREFIX = "/posters/";

    public static PosterView from(ArtistPoster poster) {
        return new PosterView(URL_PREFIX + poster.getFileName(),
                poster.getArchetype(), poster.getThemeIndex());
    }
}
