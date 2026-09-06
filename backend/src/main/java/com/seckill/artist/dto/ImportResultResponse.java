package com.seckill.artist.dto;

import java.util.List;

/**
 * 匯入結果。數字全部是實際發生的動作,不是請求裡的筆數。
 *
 * @param staleArtists DB 有、但這份 manifest 沒有的藝人 slug。<b>刻意不自動刪除也不自動停用</b>:
 *                     內容包少一個藝人的成因可能是核可被撤下,也可能是匯出時跳過,
 *                     兩者的正確處置不同。列出來讓人決定,而不是替人決定
 * @param orphanFiles  目錄裡沒有被任何一列引用的檔案。同樣只列不刪(計畫 §4.4)
 */
public record ImportResultResponse(
        int artistsCreated,
        int artistsUpdated,
        int postersCreated,
        int postersUpdated,
        int postersRemoved,
        int filesWritten,
        List<String> staleArtists,
        List<String> orphanFiles
) {
}
