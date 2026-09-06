package com.seckill.artist.dto;

import com.seckill.artist.domain.ArtistTier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 匯入用的精簡 manifest —— poster-forge {@code curated/content-pack.json} 去掉
 * {@code posters[].layout} 之後的形狀(`pnpm publish:seckill` 產生)。
 *
 * <p><b>為什麼不直接收原始的 content-pack</b>:{@code layout} 是烘焙用的排版座標、字型授權與
 * 選版理由,序列化後佔整份 106 KB 的 90%(去掉只剩 10.7 KB),而<b>字已經烤進點陣圖</b>,
 * 這一端不會有任何一行程式讀它。收進來的後果不是效能,是契約模糊:
 * 日後有人會以為那些欄位是這個端點的契約的一部分而不敢動。
 *
 * @param version    內容包格式版本;目前只認 1
 * @param generatedAt poster-forge 匯出時間戳(僅供稽核日誌,不入庫)
 * @param artists    藝人清單
 */
public record ImportManifest(
        @NotNull @Min(1) Integer version,
        String generatedAt,
        @NotNull @NotEmpty @Valid List<ArtistEntry> artists
) {

    /**
     * @param nameZh  中文團名;<b>允許空字串或缺欄</b> —— 實測 46 筆有 23 筆沒有中文名,
     *                入庫時正規化成 NULL
     * @param nameEn  英文團名,必填(實測 46/46 皆有,且 slug 就是由它推導)
     * @param themes  巡演主題;<b>允許空陣列</b>(實測 19/46 沒有主題)
     */
    public record ArtistEntry(
            @Size(max = 100) String nameZh,
            @NotBlank @Size(max = 100) String nameEn,
            @NotBlank @Size(max = 50)
            @Pattern(regexp = "^[a-z0-9-]+$", message = "只允許小寫英數與連字號") String slug,
            @NotNull ArtistTier tier,
            @NotNull @Valid List<ThemeEntry> themes,
            @NotNull @NotEmpty @Valid List<PosterEntry> posters
    ) {
    }

    public record ThemeEntry(
            @NotNull @Size(max = 100) String zh,
            @NotNull @Size(max = 100) String en
    ) {
    }

    /**
     * @param themeIndex 指向所屬藝人 {@code themes} 的第幾筆;<b>可為 null</b>,
     *                   代表這張海報上沒有烤主題文字。不可用 0 或 -1 代替 —— 兩者都指向
     *                   不存在的一筆,而取到 undefined 不會報錯
     */
    public record PosterEntry(
            @NotBlank @Size(max = 200) String file,
            Integer themeIndex,
            @NotBlank @Size(max = 32) String archetype,
            Long seed
    ) {
    }
}
