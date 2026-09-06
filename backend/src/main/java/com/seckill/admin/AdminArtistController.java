package com.seckill.admin;

import com.seckill.artist.dto.ArtistAdminResponse;
import com.seckill.artist.dto.ImportManifest;
import com.seckill.artist.dto.ImportResultResponse;
import com.seckill.artist.service.ArtistImportService;
import com.seckill.artist.service.ArtistService;
import com.seckill.artist.service.ImportRequestReader;
import com.seckill.common.web.ApiResponse;
import com.seckill.event.dto.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 藝人管理 API(admin)。雙層防護:URL 層於 SecurityConfig 限定 {@code /api/v1/admin/**} 需 ADMIN,
 * 方法層再加 {@code @PreAuthorize}(CLAUDE.md 要求)。
 *
 * <p><b>沒有 create / update / delete 是刻意的</b>(計畫 2026-09-06 的 S7):藝人的名字、tier、
 * 巡演主題全部在 poster-forge 編寫並隨內容包進來。後台再開一套增刪改,等於同一份資料有兩個
 * 可寫入口 —— 兩邊不一致時沒有任何一步會報錯,只會在下一次匯入被安靜覆蓋掉。
 * 這裡只保留唯讀檢視、{@code enabled} 營運開關,以及孤兒檔清單。
 */
@RestController
@RequestMapping("/api/v1/admin/artists")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminArtistController {

    private final ArtistService artistService;
    private final ArtistImportService importService;
    private final ImportRequestReader requestReader;

    public AdminArtistController(ArtistService artistService, ArtistImportService importService,
                                 ImportRequestReader requestReader) {
        this.artistService = artistService;
        this.importService = importService;
        this.requestReader = requestReader;
    }

    /**
     * 批次匯入 poster-forge 內容包。
     *
     * <p>multipart 形狀:{@code manifest} 一個 part(精簡版 content-pack JSON)+
     * {@code files} 多個 part(每張海報一個,part 的檔名要與 manifest 的 {@code file} 相同)。
     * 由 poster-forge 的 {@code pnpm publish:seckill} 產生。
     *
     * <p>冪等:以 {@code slug} 與檔名為鍵,同一份內容包重跑結果相同。
     */
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImportResultResponse> importPack(
            @RequestPart("manifest") MultipartFile manifestPart,
            @RequestPart("files") List<MultipartFile> fileParts) {
        ImportManifest manifest = requestReader.readManifest(manifestPart);
        Map<String, byte[]> files = requestReader.readFiles(fileParts);
        return ApiResponse.ok(importService.importPack(manifest, files));
    }

    @GetMapping
    public ApiResponse<PageResponse<ArtistAdminResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(artistService.listAdmin(page, size));
    }

    /**
     * 停用 / 啟用。停用只代表「不再為此藝人產生新活動」,既有活動與海報照常
     * (計畫 §4.3:直接刪除會讓既有活動失去海報對照,而且不可復原)。
     */
    @PatchMapping("/{id}/enabled")
    public ApiResponse<ArtistAdminResponse> setEnabled(
            @PathVariable long id, @Valid @RequestBody EnabledRequest request) {
        return ApiResponse.ok(artistService.setEnabled(id, request.enabled()));
    }

    /** 沒有被任何一列引用的海報檔。<b>只列出,不刪除</b>(計畫 §4.4)。 */
    @GetMapping("/orphan-files")
    public ApiResponse<List<String>> orphanFiles() {
        return ApiResponse.ok(artistService.listOrphanFiles());
    }

    /** {@code enabled} 用包裝物件而非裸 boolean:裸值無法區分「傳 false」與「沒傳」。 */
    public record EnabledRequest(@NotNull Boolean enabled) {
    }
}
