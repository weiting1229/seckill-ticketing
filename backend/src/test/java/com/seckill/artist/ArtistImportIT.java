package com.seckill.artist;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.seckill.support.AbstractAdminIntegrationTest;
import com.seckill.support.WebpFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 內容包匯入端點的整合測試。
 *
 * <p>每個測試用自己的 slug 前綴,因為 {@code artists} 表跨測試共用同一個容器 ——
 * 匯入端點回傳的 {@code staleArtists} / {@code orphanFiles} 是<b>全域</b>的,
 * 斷言只能用 contains / doesNotContain,不能斷言筆數。
 */
class ArtistImportIT extends AbstractAdminIntegrationTest {

    private static final String IMPORT_PATH = "/api/v1/admin/artists/import";
    private static final AtomicInteger SEQ = new AtomicInteger();

    // ---- 正常路徑 ----

    @Test
    @DisplayName("匯入一份內容包:藝人與海報入庫、檔案落地、公開端點看得到")
    void importsPack() {
        String prefix = uniquePrefix();
        String withTheme = prefix + "-alpha";
        String withoutTheme = prefix + "-beta";

        String manifest = manifestJson(
                artistJson("阿爾法", "Alpha " + prefix, withTheme, "FEATURED",
                        List.of(theme("巡演主題", "Tour Theme")),
                        List.of(posterJson(withTheme + "__bottom-heavy.webp", 0, "bottom-heavy", 1))),
                // 沒有中文名、沒有主題、themeIndex 整個不存在 —— 實測 46 筆裡最常見的形狀
                artistJson(null, "Beta " + prefix, withoutTheme, "ROTATING",
                        List.of(),
                        List.of(posterJson(withoutTheme + "__split-grid.webp", null, "split-grid", 2))));

        Map<String, byte[]> files = Map.of(
                withTheme + "__bottom-heavy.webp", WebpFixtures.lossy(1200, 675),
                withoutTheme + "__split-grid.webp", WebpFixtures.lossy(1200, 675));

        JsonNode data = json(importPack(createAdminToken(), manifest, files)).path("data");

        assertThat(data.path("artistsCreated").asInt()).isEqualTo(2);
        assertThat(data.path("artistsUpdated").asInt()).isZero();
        assertThat(data.path("postersCreated").asInt()).isEqualTo(2);
        assertThat(data.path("filesWritten").asInt()).isEqualTo(2);

        // 檔案真的落地了,而且是完整的位元組(不是零長度的殼)
        assertThat(posterFile(withTheme + "__bottom-heavy.webp")).exists();
        assertThat(readPoster(withTheme + "__bottom-heavy.webp"))
                .isEqualTo(WebpFixtures.lossy(1200, 675));

        JsonNode alpha = findBySlug(publicArtists(), withTheme);
        assertThat(alpha.path("name").asText()).isEqualTo("阿爾法");
        assertThat(alpha.path("tier").asText()).isEqualTo("FEATURED");
        assertThat(alpha.path("tourThemes")).hasSize(1);
        assertThat(alpha.path("tourThemes").get(0).path("zh").asText()).isEqualTo("巡演主題");
        assertThat(alpha.path("posters").get(0).path("imageUrl").asText())
                .isEqualTo("/posters/" + withTheme + "__bottom-heavy.webp");
        assertThat(alpha.path("posters").get(0).path("themeIndex").asInt()).isZero();

        JsonNode beta = findBySlug(publicArtists(), withoutTheme);
        // 沒有中文名時顯示名退回英文名(與 poster-forge 的 name = nameZh || nameEn 一致)
        assertThat(beta.path("name").asText()).isEqualTo("Beta " + prefix);
        assertThat(beta.path("nameZh").isNull()).isTrue();
        assertThat(beta.path("tourThemes")).isEmpty();
        // themeIndex 必須是 null,不能被填成 0 —— 0 會指向不存在的 themes[0]
        assertThat(beta.path("posters").get(0).path("themeIndex").isNull()).isTrue();
    }

    @Test
    @DisplayName("同一份內容包重跑:冪等,不會重複建立")
    void importIsIdempotent() {
        String prefix = uniquePrefix();
        String slug = prefix + "-repeat";
        String file = slug + "__corner-heavy.webp";
        String manifest = manifestJson(artistJson("重跑", "Repeat " + prefix, slug, "ROTATING",
                List.of(), List.of(posterJson(file, null, "corner-heavy", 7))));
        Map<String, byte[]> files = Map.of(file, WebpFixtures.lossy(1200, 675));
        String token = createAdminToken();

        importPack(token, manifest, files);
        JsonNode second = json(importPack(token, manifest, files)).path("data");

        assertThat(second.path("artistsCreated").asInt()).isZero();
        assertThat(second.path("artistsUpdated").asInt()).isEqualTo(1);
        assertThat(second.path("postersCreated").asInt()).isZero();
        assertThat(second.path("postersUpdated").asInt()).isEqualTo(1);
        assertThat(publicArtists().stream().filter(a -> a.path("slug").asText().equals(slug)))
                .hasSize(1);
    }

    @Test
    @DisplayName("同一藝人改掉海報:舊列被移除,舊檔留下成為孤兒檔(不自動刪)")
    void replacingPosterLeavesOrphanFile() {
        String prefix = uniquePrefix();
        String slug = prefix + "-swap";
        String oldFile = slug + "__repetition.webp";
        String newFile = slug + "__vertical-axis.webp";
        String token = createAdminToken();

        importPack(token, manifestJson(artistJson("換圖", "Swap " + prefix, slug, "ROTATING",
                        List.of(), List.of(posterJson(oldFile, null, "repetition", 1)))),
                Map.of(oldFile, WebpFixtures.lossy(1200, 675)));

        JsonNode data = json(importPack(token,
                manifestJson(artistJson("換圖", "Swap " + prefix, slug, "ROTATING",
                        List.of(), List.of(posterJson(newFile, null, "vertical-axis", 1)))),
                Map.of(newFile, WebpFixtures.lossy(1200, 675)))).path("data");

        assertThat(data.path("postersRemoved").asInt()).isEqualTo(1);
        assertThat(toStrings(data.path("orphanFiles"))).contains(oldFile);
        // 檔案本身仍在:自動刪檔在這種對照關係上,一次比對失誤就是永久資料損失
        assertThat(posterFile(oldFile)).exists();
        assertThat(toStrings(json(get("/api/v1/admin/artists/orphan-files", token))
                .path("data"))).contains(oldFile);
    }

    @Test
    @DisplayName("停用藝人:公開清單不再出現,但海報檔與資料列都還在")
    void disabledArtistDisappearsFromPublicList() {
        String prefix = uniquePrefix();
        String slug = prefix + "-off";
        String file = slug + "__circular-flow.webp";
        String token = createAdminToken();
        importPack(token, manifestJson(artistJson("停用", "Off " + prefix, slug, "ROTATING",
                        List.of(), List.of(posterJson(file, null, "circular-flow", 3)))),
                Map.of(file, WebpFixtures.lossy(1200, 675)));
        long id = adminArtistId(token, slug);

        ResponseEntity<String> response = exchange("/api/v1/admin/artists/" + id + "/enabled",
                HttpMethod.PATCH, token, Map.of("enabled", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicArtists().stream().filter(a -> a.path("slug").asText().equals(slug)))
                .isEmpty();
        assertThat(posterFile(file)).exists();

        // 再匯入同一份內容包,不可把管理員停用過的藝人重新打開
        importPack(token, manifestJson(artistJson("停用", "Off " + prefix, slug, "ROTATING",
                        List.of(), List.of(posterJson(file, null, "circular-flow", 3)))),
                Map.of(file, WebpFixtures.lossy(1200, 675)));
        assertThat(publicArtists().stream().filter(a -> a.path("slug").asText().equals(slug)))
                .isEmpty();
    }

    // ---- 權限 ----

    @Test
    @DisplayName("非 ADMIN 打匯入端點:403;匿名:401")
    void importRequiresAdmin() {
        String prefix = uniquePrefix();
        String file = prefix + "-guard__tiny-metadata.webp";
        String manifest = manifestJson(artistJson("守衛", "Guard " + prefix, prefix + "-guard",
                "ROTATING", List.of(), List.of(posterJson(file, null, "tiny-metadata", 1))));
        Map<String, byte[]> files = Map.of(file, WebpFixtures.lossy(1200, 675));

        assertThat(importPack(createUserToken(), manifest, files).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(importPack(null, manifest, files).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(posterFile(file)).doesNotExist();
    }

    @Test
    @DisplayName("公開清單匿名可讀")
    void publicListIsAnonymous() {
        assertThat(get("/api/v1/artists", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- 校驗:每一條對應一種「不擋就不會報錯」的不一致 ----

    @Test
    @DisplayName("PNG 改名成 .webp:回 5004,且沒有任何檔案落地")
    void rejectsNonWebp() {
        String prefix = uniquePrefix();
        String slug = prefix + "-fake";
        String file = slug + "__oversized-crop.webp";

        JsonNode body = json(importPack(createAdminToken(),
                manifestJson(artistJson("偽裝", "Fake " + prefix, slug, "ROTATING",
                        List.of(), List.of(posterJson(file, null, "oversized-crop", 1)))),
                Map.of(file, WebpFixtures.notWebp())));

        assertThat(body.path("code").asInt()).isEqualTo(5004);
        assertThat(posterFile(file)).doesNotExist();
        assertThat(publicArtists().stream().filter(a -> a.path("slug").asText().equals(slug)))
                .isEmpty();
    }

    @Test
    @DisplayName("尺寸低於下限:回 5005")
    void rejectsTooSmall() {
        String prefix = uniquePrefix();
        String slug = prefix + "-small";
        String file = slug + "__axis-conflict.webp";

        JsonNode body = json(importPack(createAdminToken(),
                manifestJson(artistJson("過小", "Small " + prefix, slug, "ROTATING",
                        List.of(), List.of(posterJson(file, null, "axis-conflict", 1)))),
                Map.of(file, WebpFixtures.lossy(320, 180))));

        assertThat(body.path("code").asInt()).isEqualTo(5005);
        assertThat(posterFile(file)).doesNotExist();
    }

    @Test
    @DisplayName("上傳了 manifest 沒宣告的檔案:回 5006,兩個方向都要報出來")
    void rejectsFileSetMismatch() {
        String prefix = uniquePrefix();
        String slug = prefix + "-mismatch";
        String declared = slug + "__diagonal-hero.webp";
        String stray = slug + "__not-declared.webp";
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(declared, WebpFixtures.lossy(1200, 675));
        files.put(stray, WebpFixtures.lossy(1200, 675));

        JsonNode body = json(importPack(createAdminToken(),
                manifestJson(artistJson("對不上", "Mismatch " + prefix, slug, "ROTATING",
                        List.of(), List.of(posterJson(declared, null, "diagonal-hero", 1)))),
                files));

        assertThat(body.path("code").asInt()).isEqualTo(5006);
        assertThat(body.path("message").asText()).contains(stray);
        assertThat(posterFile(declared)).doesNotExist();
        assertThat(posterFile(stray)).doesNotExist();
    }

    @Test
    @DisplayName("part 檔名帶路徑穿越:被當成不在 manifest 而整批拒絕,目錄外不會有任何檔案")
    void rejectsPathTraversalFileName() throws IOException {
        String prefix = uniquePrefix();
        String slug = prefix + "-traversal";
        String declared = slug + "__bottom-heavy.webp";
        Path outside = postersRoot().getParent().resolve("evil.webp");
        Files.deleteIfExists(outside);

        // part 的檔名宣告成 ../evil.webp:伺服器先剝掉目錄成分,剩下的 evil.webp
        // 不在 manifest 裡,於是整批被拒 —— 檔名從頭到尾沒有被當成路徑用過
        JsonNode body = json(importPackRaw(createAdminToken(),
                manifestJson(artistJson("穿越", "Traversal " + prefix, slug, "ROTATING",
                        List.of(), List.of(posterJson(declared, null, "bottom-heavy", 1)))),
                List.of(Map.entry("../evil.webp", WebpFixtures.lossy(1200, 675)))));

        assertThat(body.path("code").asInt()).isEqualTo(5006);
        assertThat(outside).doesNotExist();
        assertThat(postersRoot().resolve("evil.webp")).doesNotExist();
    }

    @Test
    @DisplayName("themeIndex 指到不存在的主題:回 5006")
    void rejectsThemeIndexOutOfRange() {
        String prefix = uniquePrefix();
        String slug = prefix + "-theme";
        String file = slug + "__repetition.webp";

        JsonNode body = json(importPack(createAdminToken(),
                // themes 只有 1 筆,themeIndex=1 指向不存在的第二筆
                manifestJson(artistJson("主題", "Theme " + prefix, slug, "ROTATING",
                        List.of(theme("唯一主題", "Only Theme")),
                        List.of(posterJson(file, 1, "repetition", 1)))),
                Map.of(file, WebpFixtures.lossy(1200, 675))));

        assertThat(body.path("code").asInt()).isEqualTo(5006);
        assertThat(body.path("message").asText()).contains("themeIndex");
        assertThat(posterFile(file)).doesNotExist();
    }

    @Test
    @DisplayName("兩個 slug 用同一個英文團名:回 5002 而不是 9999")
    void rejectsDuplicateNameEn() {
        String prefix = uniquePrefix();
        String sharedName = "Shared " + prefix;
        String fileA = prefix + "-a__split-grid.webp";
        String token = createAdminToken();
        importPack(token, manifestJson(artistJson("甲", sharedName, prefix + "-a", "ROTATING",
                        List.of(), List.of(posterJson(fileA, null, "split-grid", 1)))),
                Map.of(fileA, WebpFixtures.lossy(1200, 675)));

        String fileB = prefix + "-b__split-grid.webp";
        JsonNode body = json(importPack(token,
                manifestJson(artistJson("乙", sharedName, prefix + "-b", "ROTATING",
                        List.of(), List.of(posterJson(fileB, null, "split-grid", 1)))),
                Map.of(fileB, WebpFixtures.lossy(1200, 675))));

        assertThat(body.path("code").asInt()).isEqualTo(5002);
        assertThat(body.path("message").asText()).contains(prefix + "-a");
    }

    @Test
    @DisplayName("manifest 帶了精簡版沒有的欄位(例如整份 content-pack 的 layout):回 1400")
    void rejectsUnknownManifestFields() {
        String prefix = uniquePrefix();
        String slug = prefix + "-fat";
        String file = slug + "__bottom-heavy.webp";
        String fat = manifestJson(artistJson("肥", "Fat " + prefix, slug, "ROTATING", List.of(),
                        List.of(posterJson(file, null, "bottom-heavy", 1))))
                .replace("\"seed\":1", "\"seed\":1,\"layout\":[]");

        JsonNode body = json(importPack(createAdminToken(), fat,
                Map.of(file, WebpFixtures.lossy(1200, 675))));

        assertThat(body.path("code").asInt()).isEqualTo(1400);
    }

    @Test
    @DisplayName("不支援的內容包版本:回 5006")
    void rejectsUnsupportedVersion() {
        String prefix = uniquePrefix();
        String slug = prefix + "-ver";
        String file = slug + "__bottom-heavy.webp";
        String manifest = manifestJson(artistJson("版本", "Version " + prefix, slug, "ROTATING",
                List.of(), List.of(posterJson(file, null, "bottom-heavy", 1))))
                .replace("\"version\":1", "\"version\":99");

        JsonNode body = json(importPack(createAdminToken(), manifest,
                Map.of(file, WebpFixtures.lossy(1200, 675))));

        assertThat(body.path("code").asInt()).isEqualTo(5006);
    }

    // ---- 輔助 ----

    private static String uniquePrefix() {
        return "it" + SEQ.incrementAndGet() + "x" + (System.nanoTime() % 100000);
    }

    private static String theme(String zh, String en) {
        return "{\"zh\":\"" + zh + "\",\"en\":\"" + en + "\"}";
    }

    private static String posterJson(String file, Integer themeIndex, String archetype, long seed) {
        return "{\"file\":\"" + file + "\",\"themeIndex\":"
                + (themeIndex == null ? "null" : themeIndex)
                + ",\"archetype\":\"" + archetype + "\",\"seed\":" + seed + "}";
    }

    private static String artistJson(String nameZh, String nameEn, String slug, String tier,
                                     List<String> themes, List<String> posters) {
        return "{\"nameZh\":" + (nameZh == null ? "null" : "\"" + nameZh + "\"")
                + ",\"nameEn\":\"" + nameEn + "\",\"slug\":\"" + slug + "\",\"tier\":\"" + tier
                + "\",\"themes\":[" + String.join(",", themes)
                + "],\"posters\":[" + String.join(",", posters) + "]}";
    }

    private static String manifestJson(String... artists) {
        return "{\"version\":1,\"generatedAt\":\"2026-09-06T00:00:00.000Z\",\"artists\":["
                + String.join(",", artists) + "]}";
    }

    private ResponseEntity<String> importPack(String token, String manifest, Map<String, byte[]> files) {
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>(files.entrySet());
        return importPackRaw(token, manifest, entries);
    }

    /** 允許 part 檔名與 manifest 不一致(路徑穿越那一題要的就是這個)。 */
    private ResponseEntity<String> importPackRaw(String token, String manifest,
                                                 List<Map.Entry<String, byte[]>> parts) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("manifest", part("manifest.json", manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                MediaType.APPLICATION_JSON));
        for (Map.Entry<String, byte[]> entry : parts) {
            body.add("files", part(entry.getKey(), entry.getValue(),
                    MediaType.parseMediaType("image/webp")));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(IMPORT_PATH, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private static HttpEntity<ByteArrayResource> part(String fileName, byte[] content, MediaType type) {
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        return new HttpEntity<>(resource, headers);
    }

    private List<JsonNode> publicArtists() {
        JsonNode data = json(get("/api/v1/artists", null)).path("data");
        List<JsonNode> items = new ArrayList<>();
        data.forEach(items::add);
        return items;
    }

    private static JsonNode findBySlug(List<JsonNode> artists, String slug) {
        return artists.stream()
                .filter(a -> a.path("slug").asText().equals(slug))
                .findFirst()
                .orElseThrow(() -> new AssertionError("公開清單找不到 slug=" + slug));
    }

    private long adminArtistId(String token, String slug) {
        JsonNode items = json(get("/api/v1/admin/artists?page=1&size=100", token))
                .path("data").path("items");
        for (JsonNode item : items) {
            if (item.path("slug").asText().equals(slug)) {
                return item.path("id").asLong();
            }
        }
        throw new AssertionError("admin 清單找不到 slug=" + slug);
    }

    private static List<String> toStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static Path postersRoot() {
        return POSTERS_DIR;
    }

    private static Path posterFile(String fileName) {
        return POSTERS_DIR.resolve(fileName);
    }

    private static byte[] readPoster(String fileName) {
        try {
            return Files.readAllBytes(posterFile(fileName));
        } catch (IOException e) {
            throw new AssertionError("海報讀取失敗:" + fileName, e);
        }
    }
}
