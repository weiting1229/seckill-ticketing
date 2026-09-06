package com.seckill.artist.service;

import com.seckill.artist.domain.Artist;
import com.seckill.artist.domain.ArtistPoster;
import com.seckill.artist.dto.ArtistAdminResponse;
import com.seckill.artist.dto.ArtistPublicResponse;
import com.seckill.artist.dto.PosterView;
import com.seckill.artist.mapper.ArtistMapper;
import com.seckill.artist.mapper.ArtistPosterMapper;
import com.seckill.common.exception.BizCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.event.dto.PageResponse;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 藝人查詢與營運開關。內容的建立與修改一律走匯入(見 {@link ArtistImportService});
 * 這裡刻意沒有 create / update / delete(計畫 2026-09-06 的 S7)。
 */
@Service
public class ArtistService {

    private static final Logger log = LoggerFactory.getLogger(ArtistService.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final ArtistMapper artistMapper;
    private final ArtistPosterMapper posterMapper;
    private final PosterStorage posterStorage;

    public ArtistService(ArtistMapper artistMapper, ArtistPosterMapper posterMapper,
                         PosterStorage posterStorage) {
        this.artistMapper = artistMapper;
        this.posterMapper = posterMapper;
        this.posterStorage = posterStorage;
    }

    /** 公開清單:僅 enabled,無分頁(§10 已知限制:40~100 筆規模;暴增時要改分頁)。 */
    public List<ArtistPublicResponse> listPublic() {
        List<Artist> artists = artistMapper.findEnabled();
        Map<Long, List<PosterView>> posters = loadPosters(artists);
        return artists.stream()
                .map(a -> ArtistPublicResponse.from(a, posters.getOrDefault(a.getId(), List.of())))
                .toList();
    }

    public PageResponse<ArtistAdminResponse> listAdmin(int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long total = artistMapper.countAll();
        List<Artist> artists = artistMapper.findPage(s, (p - 1) * s);
        Map<Long, List<PosterView>> posters = loadPosters(artists);
        List<ArtistAdminResponse> items = artists.stream()
                .map(a -> ArtistAdminResponse.from(a, posters.getOrDefault(a.getId(), List.of())))
                .toList();
        return PageResponse.of(p, s, total, items);
    }

    /**
     * 切換啟用狀態。停用只影響「之後是否為此藝人產生新活動」,既有活動與海報照常
     * (計畫 §4.3:直接刪除會讓既有活動失去海報對照且無法復原)。
     */
    @Transactional
    public ArtistAdminResponse setEnabled(long id, boolean enabled) {
        if (artistMapper.updateEnabled(id, enabled, Instant.now()) == 0) {
            throw new BusinessException(BizCode.ARTIST_NOT_FOUND);
        }
        Artist artist = artistMapper.findById(id);
        log.info("admin 切換藝人啟用狀態 artistId={} slug={} enabled={}", id, artist.getSlug(), enabled);
        return ArtistAdminResponse.from(artist, loadPosters(List.of(artist))
                .getOrDefault(id, List.of()));
    }

    /**
     * 目錄裡沒有被任何一列引用的檔案。<b>只列出,不刪除</b>(計畫 §4.4):
     * 自動刪檔在這種對照關係上,一次比對失誤就是永久資料損失。
     */
    public List<String> listOrphanFiles() {
        Set<String> referenced = posterMapper.findAll().stream()
                .map(ArtistPoster::getFileName)
                .collect(Collectors.toCollection(HashSet::new));
        return posterStorage.listFileNames().stream()
                .filter(name -> !referenced.contains(name))
                .toList();
    }

    private Map<Long, List<PosterView>> loadPosters(List<Artist> artists) {
        if (artists.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = artists.stream().map(Artist::getId).toList();
        Map<Long, List<PosterView>> byArtist = new LinkedHashMap<>();
        for (ArtistPoster poster : posterMapper.findByArtistIds(ids)) {
            byArtist.computeIfAbsent(poster.getArtistId(), k -> new java.util.ArrayList<>())
                    .add(PosterView.from(poster));
        }
        return byArtist;
    }
}
