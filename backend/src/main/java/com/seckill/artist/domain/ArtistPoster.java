package com.seckill.artist.domain;

import java.time.Instant;
import lombok.Data;

/**
 * 藝人海報(對應 artist_posters 表)。
 *
 * <p>只存 {@code fileName},不存路徑或完整 URL:對外 URL 由回應層以固定前綴組出來
 * (見 {@code PosterUrls}),前綴要改時不必搬資料。
 */
@Data
public class ArtistPoster {
    private Long id;
    private Long artistId;
    /** poster-forge 產出的檔名,格式 {@code <slug>__<版式>.webp};全域唯一,是匯入的冪等鍵。 */
    private String fileName;
    private String archetype;
    /** 指向所屬藝人 {@code tourThemes} 的第幾筆;null = 這張海報沒有烤主題文字。 */
    private Integer themeIndex;
    private Long seed;
    private int sortOrder;
    private Instant createdAt;
    private Instant updatedAt;
}
