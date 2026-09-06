package com.seckill.artist;

import com.seckill.artist.dto.ArtistPublicResponse;
import com.seckill.artist.service.ArtistService;
import com.seckill.common.web.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公開藝人清單(匿名可讀,計畫 §6)。
 *
 * <p>匿名白名單在 {@code SecurityConfig} 顯式標註,且<b>只放行 GET</b>:
 * 專案預設是 deny by default,寫成 {@code /api/v1/artists/**} 全放行會把日後新增的
 * 子路徑一起放出去,而那不會有任何一步報錯。
 *
 * <p>刻意不加 {@code Cache-Control}(計畫 §11 待決 #4):匯入後要等快取過期才看得到,
 * demo 時最容易讓人以為是沒存進去。
 */
@RestController
@RequestMapping("/api/v1/artists")
public class ArtistController {

    private final ArtistService artistService;

    public ArtistController(ArtistService artistService) {
        this.artistService = artistService;
    }

    @GetMapping
    public ApiResponse<List<ArtistPublicResponse>> list() {
        return ApiResponse.ok(artistService.listPublic());
    }
}
