package com.seckill.artist.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.artist.dto.ImportManifest;
import com.seckill.common.exception.BizCode;
import com.seckill.common.exception.BusinessException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 把 multipart 請求讀成 {@link ImportManifest} 與「檔名 → 位元組」。
 *
 * <h2>為什麼自己解析 JSON 而不用 {@code @RequestPart ImportManifest}</h2>
 * {@code @RequestPart} 綁定非 String 型別時要靠該 part 自己的 {@code Content-Type} 選轉換器,
 * 沒帶就是 415,而 415 沒有訊息本體 —— 用 curl 手動打的人只會看到一個空的 415,
 * 完全看不出「manifest 那個 part 少了 Content-Type」。這裡改成自己讀 bytes 再反序列化,
 * 失敗一律是帶訊息的 1400。
 *
 * <h2>上傳檔名的信任模型</h2>
 * 檔名<b>只當查表的鍵用,不當路徑用</b>。三道:去掉任何目錄成分 → 必須出現在 manifest 裡
 * (manifest 的每個檔名已先過格式白名單)→ 實際落檔時再由 {@link PosterStorage} 斷言父目錄。
 * 因此就算有人送 {@code ../../evil.webp},它在第二道就因為不在 manifest 而整批被拒。
 */
@Component
public class ImportRequestReader {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public ImportRequestReader(Validator validator) {
        this.validator = validator;
        // 用獨立的 ObjectMapper 而非注入 Spring 的:這裡刻意開 FAIL_ON_UNKNOWN_PROPERTIES。
        // 契約是「精簡 manifest」,收到多餘欄位(例如整份沒瘦身的 content-pack)要當場說出來,
        // 否則那些欄位會被安靜丟掉,而送的人以為自己送成功了。
        this.objectMapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public ImportManifest readManifest(MultipartFile part) {
        if (part == null || part.isEmpty()) {
            throw new BusinessException(BizCode.VALIDATION_FAILED, "缺少 manifest part");
        }
        ImportManifest manifest;
        try {
            manifest = objectMapper.readValue(part.getBytes(), ImportManifest.class);
        } catch (IOException e) {
            throw new BusinessException(BizCode.VALIDATION_FAILED,
                    "manifest 解析失敗:" + rootMessage(e));
        }
        Set<ConstraintViolation<ImportManifest>> violations = validator.validate(manifest);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new BusinessException(BizCode.VALIDATION_FAILED, "manifest 校驗失敗:" + detail);
        }
        return manifest;
    }

    /**
     * 檔案 part → 「檔名 → 位元組」。同名檔案出現兩次一律整批拒絕:
     * 後者覆蓋前者不會報錯,而使用者無從知道最後留下的是哪一份。
     */
    public Map<String, byte[]> readFiles(java.util.List<MultipartFile> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new BusinessException(BizCode.VALIDATION_FAILED, "缺少 files part");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (MultipartFile part : parts) {
            String name = stripDirectories(part.getOriginalFilename());
            if (name.isEmpty()) {
                throw new BusinessException(BizCode.IMPORT_PAYLOAD_MISMATCH, "有檔案 part 沒有檔名");
            }
            try {
                if (files.put(name, part.getBytes()) != null) {
                    throw new BusinessException(BizCode.IMPORT_PAYLOAD_MISMATCH,
                            "同一個檔名上傳了兩次:" + name);
                }
            } catch (IOException e) {
                throw new BusinessException(BizCode.POSTER_STORAGE_FAILED,
                        "上傳檔案讀取失敗:" + name);
            }
        }
        return files;
    }

    /** 去掉目錄成分。瀏覽器與部分客戶端會送完整路徑,而路徑分隔字元在兩個平台上不同。 */
    private static String stripDirectories(String original) {
        if (original == null) {
            return "";
        }
        int slash = Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\'));
        return slash < 0 ? original.trim() : original.substring(slash + 1).trim();
    }

    private static String rootMessage(Throwable e) {
        Throwable cursor = e;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        // Jackson 的訊息會帶完整類別名與來源片段,對外只留第一行。
        return message == null ? cursor.getClass().getSimpleName() : message.split("\\R", 2)[0];
    }
}
