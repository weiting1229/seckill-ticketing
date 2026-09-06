package com.seckill.artist.service;

import com.seckill.common.exception.BizCode;
import com.seckill.common.exception.BusinessException;
import com.seckill.config.PosterStorageProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 海報檔案落地(計畫 §4.4 的「檔名完全由伺服器決定 / 落點是固定的單一目錄常數」)。
 *
 * <p><b>檔名的信任模型</b>:檔名來自 manifest,但它<b>不是</b>被當成路徑使用的。
 * 兩道關卡:(1) 先過 {@link #FILE_NAME} 白名單,任何 {@code /}、{@code \}、{@code ..}
 * 或大寫都不符;(2) 解析後再斷言父目錄就是根目錄。少了第二道,
 * 白名單被放寬(例如日後想允許子目錄)的那一天,路徑穿越會安靜地成立。
 */
@Component
public class PosterStorage {

    private static final Logger log = LoggerFactory.getLogger(PosterStorage.class);

    /** 與 V4 migration 的 chk_artist_posters_file_name 同一份規則,兩邊都要擋。 */
    private static final Pattern FILE_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]*\\.webp$");

    private static final String TMP_SUFFIX = ".tmp-";

    private final Path root;

    public PosterStorage(PosterStorageProperties properties) {
        this.root = Path.of(properties.dir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            // 啟動就失敗優於「上傳當下才發現目錄不存在」—— 後者要等到有人真的匯入才會知道。
            throw new UncheckedIOException("海報目錄無法建立:" + root, e);
        }
        log.info("海報目錄 dir={}", root);
    }

    public Path root() {
        return root;
    }

    /** 檔名是否通過白名單;呼叫端負責把 false 轉成帶檔名的 5006。 */
    public static boolean isValidFileName(String fileName) {
        return fileName != null && FILE_NAME.matcher(fileName).matches();
    }

    /**
     * 一次寫入整批海報。
     *
     * <p>每個檔案都是「寫暫存檔 → 原子改名蓋上去」。直接對正式檔名開串流寫入的話,
     * 寫到一半失敗會留下<b>半張圖</b>覆蓋掉原本正常的那張,而半張 WebP 在瀏覽器上是破圖、
     * 不是錯誤。同目錄的 rename 在同一個檔案系統上是原子的,讀者永遠看到完整的舊檔或完整的新檔。
     *
     * <p>批次中途失敗<b>不</b>回滾已寫好的檔案:呼叫端的順序是「先落檔、後寫 DB」,
     * 留下的檔案在 DB 沒有對應列時就是孤兒檔(無害、可由孤兒清單端點列出),
     * 而反過來讓 DB 指向不存在的檔案就是破圖。
     */
    public void writeAll(Map<String, byte[]> files) {
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            write(entry.getKey(), entry.getValue());
        }
    }

    private void write(String fileName, byte[] content) {
        Path target = resolve(fileName);
        Path tmp = root.resolve(fileName + TMP_SUFFIX + UUID.randomUUID());
        try {
            Files.write(tmp, content);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 極少見(跨檔案系統);退回非原子改名,並留下記錄 —— 這種環境下上面那個保證不成立。
                log.warn("檔案系統不支援原子改名,退回一般覆蓋 file={}", fileName);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new BusinessException(BizCode.POSTER_STORAGE_FAILED,
                    "海報檔案寫入失敗:" + fileName);
        }
    }

    /** 目錄下的實際檔案名稱(排除寫入中的暫存檔),依字典序。 */
    public List<String> listFileNames() {
        try (Stream<Path> stream = Files.list(root)) {
            List<String> names = new ArrayList<>();
            stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.contains(TMP_SUFFIX))
                    .forEach(names::add);
            names.sort(Comparator.naturalOrder());
            return names;
        } catch (IOException e) {
            throw new BusinessException(BizCode.POSTER_STORAGE_FAILED, "海報目錄無法讀取:" + root);
        }
    }

    public boolean exists(String fileName) {
        return Files.isRegularFile(resolve(fileName));
    }

    /**
     * 檔名 → 絕對路徑。白名單 + 父目錄斷言,兩道都過才回傳。
     * 這是整個模組唯一由檔名產生路徑的地方。
     */
    Path resolve(String fileName) {
        if (!isValidFileName(fileName)) {
            throw new BusinessException(BizCode.IMPORT_PAYLOAD_MISMATCH,
                    "檔名不符格式(僅小寫英數、底線、連字號 + .webp):" + fileName);
        }
        Path resolved = root.resolve(fileName).normalize();
        if (!root.equals(resolved.getParent())) {
            // 白名單已經擋掉已知的路徑字元;這一道是防白名單日後被放寬。
            throw new BusinessException(BizCode.IMPORT_PAYLOAD_MISMATCH, "檔名解析後落在目錄之外");
        }
        return resolved;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("暫存檔清理失敗 path={}", path, e);
        }
    }
}
