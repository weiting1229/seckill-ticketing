package com.seckill.artist.dto;

import com.seckill.artist.domain.Artist;
import com.seckill.artist.domain.ArtistTier;
import com.seckill.artist.domain.TourTheme;
import java.util.List;

/**
 * 公開唯讀藝人(匿名可讀)。
 *
 * <p>刻意不回 id、enabled 與時間戳(計畫 §6):前端只需要比對名字與渲染海報,
 * 內部識別碼與啟用狀態沒有理由外流。
 *
 * @param name 顯示名 = {@code nameZh || nameEn},與 poster-forge 的推導一致
 */
public record ArtistPublicResponse(
        String slug,
        String name,
        String nameZh,
        String nameEn,
        ArtistTier tier,
        List<TourTheme> tourThemes,
        List<PosterView> posters
) {

    public static ArtistPublicResponse from(Artist artist, List<PosterView> posters) {
        return new ArtistPublicResponse(artist.getSlug(), artist.displayName(),
                artist.getNameZh(), artist.getNameEn(), artist.getTier(),
                artist.getTourThemes(), posters);
    }
}
