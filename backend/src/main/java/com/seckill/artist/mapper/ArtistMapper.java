package com.seckill.artist.mapper;

import com.seckill.artist.domain.Artist;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 藝人資料存取(SQL 手寫於 ArtistMapper.xml,參數一律 #{})。 */
@Mapper
public interface ArtistMapper {

    int insert(Artist artist);

    /** 依 slug 更新內容包帶進來的欄位(不動 enabled —— 那是營運開關,不該被匯入覆寫)。 */
    int updateByImport(Artist artist);

    /** 切換啟用狀態;回傳影響行數,0 表示藝人不存在。 */
    int updateEnabled(@Param("id") long id, @Param("enabled") boolean enabled,
                      @Param("updatedAt") java.time.Instant updatedAt);

    Artist findById(@Param("id") long id);

    Artist findBySlug(@Param("slug") String slug);

    /** 全部藝人,依 slug 字典序;匯入時用來一次載入現況做比對。 */
    List<Artist> findAll();

    /** 公開清單:僅 enabled,依 slug 字典序。無分頁(§10 已知限制:40~100 筆規模)。 */
    List<Artist> findEnabled();

    List<Artist> findPage(@Param("limit") int limit, @Param("offset") int offset);

    long countAll();
}
