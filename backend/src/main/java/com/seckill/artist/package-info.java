/**
 * 藝人與海報模組:poster-forge 內容包的落地與查詢。
 *
 * <p>資料流向是**單向**的:poster-forge 編寫 → admin 匯入端點 → {@code artists} /
 * {@code artist_posters} 表 → 公開唯讀端點。後台刻意<b>不</b>提供藝人的建立 / 改名 / 刪除
 * (計畫 2026-09-06 的 S7):名字、tier、巡演主題全部在 poster-forge 編寫,兩邊都能改同一份
 * 資料時,不一致不會有任何一步報錯,只會在下一次匯入被安靜覆蓋掉。後台只保留唯讀檢視與
 * {@code enabled} 切換 —— 後者是「停止為此藝人產生新活動」的營運開關,不是內容編輯。
 */
package com.seckill.artist;
