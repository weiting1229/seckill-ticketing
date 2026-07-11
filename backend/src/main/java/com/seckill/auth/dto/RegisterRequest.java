package com.seckill.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 註冊入參。username 3–50 字(英數底線);密碼強度校驗:8–72 字且至少含一英文字母與一數字。
 * (72 為 BCrypt 上限;ASVS 建議 12 字,此處放寬為 8 供 demo,可再調嚴。)
 */
public record RegisterRequest(

        @NotBlank(message = "不可為空")
        @Pattern(regexp = "^[A-Za-z0-9_]{3,50}$", message = "須為 3–50 字的英數或底線")
        String username,

        @NotBlank(message = "不可為空")
        @Size(min = 8, max = 72, message = "長度須為 8–72 字")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "須至少包含一個英文字母與一個數字")
        String password
) {
}
