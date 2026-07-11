package com.seckill.event.dto;

import java.util.List;

/**
 * 通用分頁回應。page 為 1-based 頁碼,size 為每頁筆數,total 為符合條件的總筆數。
 */
public record PageResponse<T>(int page, int size, long total, List<T> items) {

    public static <T> PageResponse<T> of(int page, int size, long total, List<T> items) {
        return new PageResponse<>(page, size, total, items);
    }
}
