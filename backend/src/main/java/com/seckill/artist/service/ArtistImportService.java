package com.seckill.artist.service;

import com.seckill.artist.domain.Artist;
import com.seckill.artist.domain.ArtistPoster;
import com.seckill.artist.domain.TourTheme;
import com.seckill.artist.dto.ImportManifest;
import com.seckill.artist.dto.ImportResultResponse;
import com.seckill.artist.mapper.ArtistMapper;
import com.seckill.artist.mapper.ArtistPosterMapper;
import com.seckill.common.exception.BizCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.common.id.IdGenerator;
import com.seckill.config.PosterStorageProperties;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 內容包匯入:poster-forge 的 manifest + 一批 WebP → {@code artists} / {@code artist_posters}。
 *
 * <h2>為什麼是批次而不是逐張</h2>
 * 逐張的形狀(先 46 次建藝人、再 46 次上傳)中途失敗會停在半套,而「半套」在這裡的樣子是
 * 首頁部分卡片有圖、部分沒有 —— 沒有任何一步會報錯。批次讓 DB 寫入落在同一個交易內。
 *
 * <h2>檔案與 DB 的順序,以及它為什麼不可調換</h2>
 * 先落檔、後寫 DB(計畫 §4.4)。檔案系統不參與交易,所以兩種失敗必有其一:
 * <ul>
 *   <li>先落檔後寫 DB → DB 失敗時留下<b>孤兒檔</b>:沒有任何一列引用它,無害,
 *       可由孤兒清單端點列出後人工清掉</li>
 *   <li>先寫 DB 後落檔 → 落檔失敗時 DB 指向<b>不存在的檔案</b>:前台整片破圖,
 *       而破圖不會出現在任何日誌裡</li>
 * </ul>
 * 交易涵蓋 DB 那一半就夠了,不需要為檔案再造一套補償邏輯。
 *
 * <h2>校驗一律在寫入任何東西之前跑完</h2>
 * 下面每一種不一致的共同點是:<b>不擋的話不會報錯,只會發佈到錯的東西</b>。
 */
@Service
public class ArtistImportService {

    private static final Logger log = LoggerFactory.getLogger(ArtistImportService.class);

    private static final int SUPPORTED_VERSION = 1;

    private final ArtistMapper artistMapper;
    private final ArtistPosterMapper posterMapper;
    private final PosterStorage posterStorage;
    private final IdGenerator idGenerator;
    private final PosterStorageProperties properties;

    public ArtistImportService(ArtistMapper artistMapper, ArtistPosterMapper posterMapper,
                               PosterStorage posterStorage, IdGenerator idGenerator,
                               PosterStorageProperties properties) {
        this.artistMapper = artistMapper;
        this.posterMapper = posterMapper;
        this.posterStorage = posterStorage;
        this.idGenerator = idGenerator;
        this.properties = properties;
    }

    /**
     * 匯入一份內容包。冪等以 {@code slug}(藝人)與 {@code file}(海報)為鍵:
     * 同一份內容包重跑第二次,結果與第一次相同,只有 {@code updated_at} 會動。
     *
     * <p>{@code @Transactional} 涵蓋的是 DB 那一段;中間的檔案寫入刻意留在交易內但不受它保護 ——
     * 見類別註解對順序的說明。
     */
    @Transactional
    public ImportResultResponse importPack(ImportManifest manifest, Map<String, byte[]> files) {
        checkVersion(manifest);
        checkManifestInternalConsistency(manifest);
        checkFilesMatchManifest(manifest, files);
        checkImagesValid(files);
        checkNameConflictsAgainstDatabase(manifest);

        posterStorage.writeAll(files);

        return persist(manifest);
    }

    // ---- 校驗 ----

    private void checkVersion(ImportManifest manifest) {
        if (manifest.version() != SUPPORTED_VERSION) {
            throw new BusinessException(BizCode.IMPORT_PAYLOAD_MISMATCH,
                    "不支援的內容包版本:" + manifest.version() + "(本端支援 " + SUPPORTED_VERSION + ")");
        }
    }

    /** manifest 自己內部的重複與越界。這些在寫進 DB 之前就能斷定,不必等唯一鍵去撞。 */
    private void checkManifestInternalConsistency(ImportManifest manifest) {
        Set<String> slugs = new HashSet<>();
        Set<String> nameEns = new HashSet<>();
        Set<String> nameZhs = new HashSet<>();
        Set<String> fileNames = new HashSet<>();

        for (ImportManifest.ArtistEntry artist : manifest.artists()) {
            if (!slugs.add(artist.slug())) {
                throw new BusinessException(BizCode.ARTIST_SLUG_DUPLICATED,
                        "manifest 內 slug 重複:" + artist.slug());
            }
            if (!nameEns.add(artist.nameEn())) {
                throw new BusinessException(BizCode.ARTIST_NAME_DUPLICATED,
                        "manifest 內英文團名重複:" + artist.nameEn());
            }
            String nameZh = blankToNull(artist.nameZh());
            if (nameZh != null && !nameZhs.add(nameZh)) {
                throw new BusinessException(BizCode.ARTIST_NAME_DUPLICATED,
                        "manifest 內中文團名重複:" + nameZh);
            }
            for (ImportManifest.PosterEntry poster : artist.posters()) {
                if (!fileNames.add(poster.file())) {
                    throw new BusinessException(BizCode.IMPORT_PAYLOAD_MISMATCH,
                            "manifest 內海報檔名重複:" + poster.file());
                }
                if (!PosterStorage.isValidFileName(poster.file())) {
                    throw new BusinessException(BizCode.IMPORT_PAYLOAD_MISMATCH,
                            "檔名不符格式(僅小寫英數、底線、連字號 + .webp):" + poster.file());
                }
                // themeIndex 指向不存在的主題不會在任何一層報錯 —— 取到 undefined 的前端
                // 只會少渲染一段文字。這是「發佈到錯的東西」而不是「壞掉」,必須在這裡擋。
                Integer themeIndex = poster.themeIndex();
                if (themeIndex != null && themeIndex >= artist.themes().size()) {
                    throw new BusinessException(BizCode.IMPORT_PAYLOAD_MISMATCH,
                            "海報 " + poster.file() + " 的 themeIndex=" + themeIndex
                                    + " 超出該藝人的主題數(" + artist.themes().size() + ")");
                }
            }
        }
    }

    /**
     * manifest 與上傳檔案<b>雙向</b>比對。
     *
     * <p>只檢查單向會漏掉兩種相反的錯:manifest 有而檔案缺 → 該藝人破圖;
     * 檔案有而 manifest 沒有 → 目錄多一個誰也不引用的檔案,而且是使用者以為已經發佈的那張。
     */
    private void checkFilesMatchManifest(ImportManifest manifest, Map<String, byte[]> files) {
        Set<String> declared = new LinkedHashSet<>();
        manifest.artists().forEach(a -> a.posters().forEach(p -> declared.add(p.file())));

        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(files.keySet());
        Set<String> extra = new TreeSet<>(files.keySet());
        extra.removeAll(declared);

        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new BusinessException(BizCode.IMPORT_PAYLOAD_MISMATCH,
                    "manifest 與上傳檔案不一致;manifest 有但沒上傳=" + missing
                            + ",上傳了但 manifest 沒有=" + extra);
        }
    }

    /** 逐檔的格式與尺寸(計畫 §4.4)。不信 Content-Type、不信副檔名,只信 magic bytes。 */
    private void checkImagesValid(Map<String, byte[]> files) {
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String name = entry.getKey();
            byte[] content = entry.getValue();
            if (content.length > properties.maxFileBytes()) {
                throw new BusinessException(BizCode.VALIDATION_FAILED,
                        "海報 " + name + " 超過單張上限 " + properties.maxFileBytes() + " bytes");
            }
            WebpImage image = WebpImage.parse(content);
            if (image == null) {
                throw new BusinessException(BizCode.POSTER_IMAGE_INVALID,
                        "海報 " + name + " 不是合法的 WebP");
            }
            if (image.width() < properties.minDimension() || image.width() > properties.maxDimension()
                    || image.height() < properties.minDimension()
                    || image.height() > properties.maxDimension()) {
                throw new BusinessException(BizCode.POSTER_IMAGE_DIMENSION_INVALID,
                        "海報 " + name + " 尺寸 " + image.width() + "x" + image.height()
                                + " 超出允許範圍 " + properties.minDimension() + "~"
                                + properties.maxDimension());
            }
        }
    }

    /**
     * 團名撞到<b>別的 slug</b> 已經佔用的名字。
     *
     * <p>不先擋的話會撞 DB 唯一鍵,而那是一個 DataIntegrityViolationException:
     * 使用者拿到的是 9999 系統錯誤,訊息裡沒有任何一個團名。
     */
    private void checkNameConflictsAgainstDatabase(ImportManifest manifest) {
        Map<String, String> nameEnOwner = new HashMap<>();
        Map<String, String> nameZhOwner = new HashMap<>();
        for (Artist existing : artistMapper.findAll()) {
            nameEnOwner.put(existing.getNameEn(), existing.getSlug());
            if (existing.getNameZh() != null) {
                nameZhOwner.put(existing.getNameZh(), existing.getSlug());
            }
        }
        for (ImportManifest.ArtistEntry artist : manifest.artists()) {
            String enOwner = nameEnOwner.get(artist.nameEn());
            if (enOwner != null && !enOwner.equals(artist.slug())) {
                throw new BusinessException(BizCode.ARTIST_NAME_DUPLICATED,
                        "英文團名「" + artist.nameEn() + "」已屬於 slug=" + enOwner);
            }
            String nameZh = blankToNull(artist.nameZh());
            String zhOwner = nameZh == null ? null : nameZhOwner.get(nameZh);
            if (zhOwner != null && !zhOwner.equals(artist.slug())) {
                throw new BusinessException(BizCode.ARTIST_NAME_DUPLICATED,
                        "中文團名「" + nameZh + "」已屬於 slug=" + zhOwner);
            }
        }
    }

    // ---- 寫入 ----

    private ImportResultResponse persist(ImportManifest manifest) {
        Instant now = Instant.now();
        Map<String, Artist> existingBySlug = new HashMap<>();
        artistMapper.findAll().forEach(a -> existingBySlug.put(a.getSlug(), a));
        Map<String, ArtistPoster> existingByFile = new HashMap<>();
        posterMapper.findAll().forEach(p -> existingByFile.put(p.getFileName(), p));

        int artistsCreated = 0;
        int artistsUpdated = 0;
        int postersCreated = 0;
        int postersUpdated = 0;
        Set<Long> touchedArtistIds = new HashSet<>();
        Set<String> declaredFiles = new HashSet<>();

        for (ImportManifest.ArtistEntry entry : manifest.artists()) {
            Artist existing = existingBySlug.get(entry.slug());
            Artist artist = toArtist(entry, existing, now);
            if (existing == null) {
                artistMapper.insert(artist);
                artistsCreated++;
            } else {
                artistMapper.updateByImport(artist);
                artistsUpdated++;
            }
            touchedArtistIds.add(artist.getId());

            int sortOrder = 0;
            for (ImportManifest.PosterEntry posterEntry : entry.posters()) {
                declaredFiles.add(posterEntry.file());
                ArtistPoster existingPoster = existingByFile.get(posterEntry.file());
                ArtistPoster poster = toPoster(posterEntry, artist.getId(), sortOrder++,
                        existingPoster, now);
                if (existingPoster == null) {
                    posterMapper.insert(poster);
                    postersCreated++;
                } else {
                    posterMapper.updateByImport(poster);
                    postersUpdated++;
                }
            }
        }

        int postersRemoved = removeDroppedPosters(existingByFile, touchedArtistIds, declaredFiles);

        List<String> staleArtists = existingBySlug.values().stream()
                .filter(a -> !touchedArtistIds.contains(a.getId()))
                .map(Artist::getSlug)
                .sorted()
                .toList();
        List<String> orphanFiles = orphanFiles(declaredFiles, existingByFile, touchedArtistIds);

        log.info("內容包匯入完成 generatedAt={} 藝人 新增={} 更新={} 海報 新增={} 更新={} 移除={} "
                        + "落檔={} 未出現於本次內容包的藝人={} 孤兒檔={}",
                manifest.generatedAt(), artistsCreated, artistsUpdated, postersCreated,
                postersUpdated, postersRemoved, declaredFiles.size(), staleArtists.size(),
                orphanFiles.size());
        if (!staleArtists.isEmpty()) {
            // WARN 而非靜默:DB 有而內容包沒有,代表兩邊已經對不上,但這不足以判定該刪或該停用。
            log.warn("這些藝人在 DB 有但本次內容包沒有,未自動處理:{}", staleArtists);
        }

        return new ImportResultResponse(artistsCreated, artistsUpdated, postersCreated,
                postersUpdated, postersRemoved, declaredFiles.size(), staleArtists, orphanFiles);
    }

    /**
     * 刪掉「本次有匯入的藝人」名下、但這份內容包已經不再包含的海報列。
     *
     * <p>只處理本次動到的藝人:沒出現在內容包裡的藝人(staleArtists)連同其海報一律不動 ——
     * 那是另一個決定,由人來下。列刪掉之後檔案<b>不刪</b>,會出現在孤兒檔清單裡。
     */
    private int removeDroppedPosters(Map<String, ArtistPoster> existingByFile,
                                     Set<Long> touchedArtistIds, Set<String> declaredFiles) {
        List<Long> ids = existingByFile.values().stream()
                .filter(p -> touchedArtistIds.contains(p.getArtistId()))
                .filter(p -> !declaredFiles.contains(p.getFileName()))
                .map(ArtistPoster::getId)
                .toList();
        if (ids.isEmpty()) {
            return 0;
        }
        return posterMapper.deleteByIds(ids);
    }

    /** 目錄裡沒有被任何一列引用的檔案(含本次剛被移除引用的)。 */
    private List<String> orphanFiles(Set<String> declaredFiles,
                                     Map<String, ArtistPoster> existingByFile,
                                     Set<Long> touchedArtistIds) {
        Set<String> referenced = new HashSet<>(declaredFiles);
        existingByFile.values().stream()
                .filter(p -> !touchedArtistIds.contains(p.getArtistId()))
                .forEach(p -> referenced.add(p.getFileName()));
        return posterStorage.listFileNames().stream()
                .filter(name -> !referenced.contains(name))
                .toList();
    }

    private Artist toArtist(ImportManifest.ArtistEntry entry, Artist existing, Instant now) {
        Artist artist = new Artist();
        artist.setId(existing != null ? existing.getId() : idGenerator.nextId());
        artist.setSlug(entry.slug());
        artist.setNameZh(blankToNull(entry.nameZh()));
        artist.setNameEn(entry.nameEn());
        artist.setTier(entry.tier());
        artist.setTourThemes(entry.themes().stream()
                .map(t -> new TourTheme(t.zh(), t.en()))
                .toList());
        // enabled 只在新建時給預設;既有藝人的值不由匯入決定(見 ArtistMapper.xml 的說明)。
        artist.setEnabled(existing == null || existing.isEnabled());
        artist.setCreatedAt(existing != null ? existing.getCreatedAt() : now);
        artist.setUpdatedAt(now);
        return artist;
    }

    private ArtistPoster toPoster(ImportManifest.PosterEntry entry, long artistId, int sortOrder,
                                  ArtistPoster existing, Instant now) {
        ArtistPoster poster = new ArtistPoster();
        poster.setId(existing != null ? existing.getId() : idGenerator.nextId());
        poster.setArtistId(artistId);
        poster.setFileName(entry.file());
        poster.setArchetype(entry.archetype());
        poster.setThemeIndex(entry.themeIndex());
        poster.setSeed(entry.seed());
        poster.setSortOrder(sortOrder);
        poster.setCreatedAt(existing != null ? existing.getCreatedAt() : now);
        poster.setUpdatedAt(now);
        return poster;
    }

    /** 空字串與 NULL 是兩種不同的「沒有中文名」,入庫前收斂成一種(DB 亦有 CHECK 擋空字串)。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
