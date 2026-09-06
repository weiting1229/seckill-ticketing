package com.seckill.artist.domain;

/**
 * 藝人分級,直接對應 seeder 的兩個桶(計畫 §5 的 S4)。
 * 由 poster-forge 的人工挑圖評分(A / B)決定,不由程式推導。
 */
public enum ArtistTier {
    /** 人工精選:可進常駐桶。 */
    FEATURED,
    /** 備用:只進輪替桶。 */
    ROTATING
}
