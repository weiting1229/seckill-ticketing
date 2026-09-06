package com.seckill.artist.dto;

import com.seckill.artist.domain.Artist;
import com.seckill.artist.domain.ArtistTier;
import com.seckill.artist.domain.TourTheme;
import java.time.Instant;
import java.util.List;

/** admin 檢視用的完整藝人欄位。 */
public record ArtistAdminResponse(
        long id,
        String slug,
        String name,
        String nameZh,
        String nameEn,
        ArtistTier tier,
        List<TourTheme> tourThemes,
        boolean enabled,
        List<PosterView> posters,
        Instant createdAt,
        Instant updatedAt
) {

    public static ArtistAdminResponse from(Artist artist, List<PosterView> posters) {
        return new ArtistAdminResponse(artist.getId(), artist.getSlug(), artist.displayName(),
                artist.getNameZh(), artist.getNameEn(), artist.getTier(), artist.getTourThemes(),
                artist.isEnabled(), posters, artist.getCreatedAt(), artist.getUpdatedAt());
    }
}
