package com.seckill.artist.mapper;

import com.seckill.artist.domain.ArtistPoster;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 藝人海報資料存取(SQL 手寫於 ArtistPosterMapper.xml)。 */
@Mapper
public interface ArtistPosterMapper {

    int insert(ArtistPoster poster);

    int updateByImport(ArtistPoster poster);

    ArtistPoster findByFileName(@Param("fileName") String fileName);

    List<ArtistPoster> findByArtistIds(@Param("artistIds") List<Long> artistIds);

    /** 全部海報,依 file_name 字典序;孤兒檔比對用。 */
    List<ArtistPoster> findAll();

    int deleteByIds(@Param("ids") List<Long> ids);
}
