package com.seckill.artist.service;

/**
 * WebP 檔頭解析:magic bytes 判定與畫布尺寸讀取(計畫 §4.4)。
 *
 * <p><b>刻意不引入影像函式庫</b>:Java 標準 {@code ImageIO} 不支援 WebP,要讀尺寸得加
 * TwelveMonkeys 或 webp-imageio(CLAUDE.md 要求新依賴先說明用途與替代方案)。而這裡需要的
 * 只是「前 30 個位元組是不是一張合法 WebP、畫布多大」,不需要解碼任何一個像素 ——
 * 解碼反而把解壓縮炸彈的攻擊面搬進來,而擋炸彈正是讀尺寸的目的。
 *
 * <p>三種 chunk 都支援:{@code VP8 }(有損)、{@code VP8L}(無失真)、{@code VP8X}(延伸)。
 * poster-forge 目前 46 張全部是 VP8,但編碼參數改一次就會變 —— 只認 VP8 的話,
 * 失敗的樣子是「換了壓縮設定之後整批匯入被拒,而錯誤訊息說格式不合法」。
 */
public final class WebpImage {

    /** RIFF 容器最小長度:RIFF(4) + size(4) + WEBP(4) + fourcc(4) + chunkSize(4) + 尺寸欄位。 */
    private static final int MIN_LENGTH = 30;

    private final int width;
    private final int height;

    private WebpImage(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * 解析並回傳畫布尺寸;不是合法 WebP 則回 {@code null}。
     *
     * <p>回 null 而不是拋例外:呼叫端要把它轉成 5004 業務錯誤並附上檔名,
     * 在這一層拋例外會讓那個檔名資訊掉在半路。
     */
    public static WebpImage parse(byte[] data) {
        if (data == null || data.length < MIN_LENGTH) {
            return null;
        }
        if (!matches(data, 0, "RIFF") || !matches(data, 8, "WEBP")) {
            return null;
        }
        // RIFF 宣告的長度必須與實際位元組數一致 —— 對不上代表檔案被截斷或被塞了尾巴,
        // 而截斷的圖在瀏覽器上是「載到一半的破圖」,不會有任何錯誤。
        long declared = u32(data, 4) + 8L;
        if (declared != data.length) {
            return null;
        }
        return switch (fourcc(data)) {
            case "VP8 " -> parseLossy(data);
            case "VP8L" -> parseLossless(data);
            case "VP8X" -> parseExtended(data);
            default -> null;
        };
    }

    /** 有損:chunk 內容自 20 起,frame tag(3)+ sync code 9D 01 2A(3),尺寸為 14 bit。 */
    private static WebpImage parseLossy(byte[] d) {
        if (d.length < 30) {
            return null;
        }
        if ((d[23] & 0xFF) != 0x9D || (d[24] & 0xFF) != 0x01 || (d[25] & 0xFF) != 0x2A) {
            return null;
        }
        return new WebpImage(u16(d, 26) & 0x3FFF, u16(d, 28) & 0x3FFF);
    }

    /** 無失真:20 為簽章 0x2F,其後 4 位元組打包 (width-1):14bit + (height-1):14bit。 */
    private static WebpImage parseLossless(byte[] d) {
        if (d.length < 25 || (d[20] & 0xFF) != 0x2F) {
            return null;
        }
        long bits = u32(d, 21);
        return new WebpImage((int) (bits & 0x3FFF) + 1, (int) ((bits >> 14) & 0x3FFF) + 1);
    }

    /** 延伸:20 為 flags(1)+reserved(3),24 起為 (canvasWidth-1)、(canvasHeight-1),各 3 位元組 LE。 */
    private static WebpImage parseExtended(byte[] d) {
        if (d.length < 30) {
            return null;
        }
        return new WebpImage(u24(d, 24) + 1, u24(d, 27) + 1);
    }

    private static String fourcc(byte[] d) {
        return new String(d, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static boolean matches(byte[] d, int offset, String ascii) {
        for (int i = 0; i < ascii.length(); i++) {
            if (d[offset + i] != (byte) ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int u16(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
    }

    private static int u24(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8) | ((d[i + 2] & 0xFF) << 16);
    }

    private static long u32(byte[] d, int i) {
        return (d[i] & 0xFFL) | ((d[i + 1] & 0xFFL) << 8) | ((d[i + 2] & 0xFFL) << 16)
                | ((d[i + 3] & 0xFFL) << 24);
    }
}
