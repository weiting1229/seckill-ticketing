package com.seckill.artist.domain;

import java.time.Instant;
import java.util.List;
import lombok.Data;

/**
 * 藝人(對應 artists 表)。主鍵為 Snowflake ID,時間一律 {@link Instant}(UTC)。
 *
 * <p>{@code nameZh} 允許為 {@code null}:實測 poster-forge 內容包 46 筆裡有 23 筆沒有中文名。
 * 顯示名的推導與 poster-forge 一致 —— 見 {@link #displayName()}。
 */
@Data
public class Artist {
    private Long id;
    private String slug;
    /** 中文團名;null = 沒有中文名(不是空字串,DB 有 CHECK 擋空字串)。 */
    private String nameZh;
    private String nameEn;
    private ArtistTier tier;
    /** 巡演主題;空陣列合法,不會是 null(DB NOT NULL DEFAULT '[]')。 */
    private List<TourTheme> tourThemes;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * 顯示與比對用的名字。與 poster-forge {@code src/identity/artists.ts} 的
     * {@code name = nameZh || nameEn} 推導方式一致 —— 兩邊若各自推導出不同的名字,
     * 前端的 {@code title.includes(artist)} 比對會安靜地全數落空,退回生成式 SVG 而不報錯。
     */
    public String displayName() {
        return nameZh != null && !nameZh.isBlank() ? nameZh : nameEn;
    }
}
