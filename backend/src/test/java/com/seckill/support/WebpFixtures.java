package com.seckill.support;

import java.nio.charset.StandardCharsets;

/**
 * 產生測試用的 WebP 檔頭。
 *
 * <p><b>刻意合成而不是塞真檔進 repo</b>:被驗的是檔頭解析(magic bytes、RIFF 宣告長度、
 * 畫布尺寸),一個像素都不會被解碼,所以真檔只會讓測試依賴 poster-forge 那個 repo 的產物,
 * 而那份產物換一次編碼參數,這邊的測試就跟著漂。合成則可以直接指定「1200×675 的 VP8」
 * 或「639×639 的 VP8L」這種正好落在邊界上的輸入。
 */
public final class WebpFixtures {

    private WebpFixtures() {
    }

    /** 有損(VP8):poster-forge 目前 46 張全部是這一種。 */
    public static byte[] lossy(int width, int height) {
        byte[] data = container("VP8 ");
        // frame tag 3 bytes 之後是 sync code 9D 01 2A,再來才是 14 bit 的寬高
        data[23] = (byte) 0x9D;
        data[24] = (byte) 0x01;
        data[25] = (byte) 0x2A;
        putU16(data, 26, width);
        putU16(data, 28, height);
        return data;
    }

    /** 無失真(VP8L):寬高各 -1 後打包進同一個 32 bit。 */
    public static byte[] lossless(int width, int height) {
        byte[] data = container("VP8L");
        data[20] = 0x2F;
        long bits = ((long) (width - 1) & 0x3FFF) | (((long) (height - 1) & 0x3FFF) << 14);
        putU32(data, 21, bits);
        return data;
    }

    /** 延伸(VP8X):畫布寬高各 -1,以 3 位元組小端存放。 */
    public static byte[] extended(int width, int height) {
        byte[] data = container("VP8X");
        putU24(data, 24, width - 1);
        putU24(data, 27, height - 1);
        return data;
    }

    /** 不是 WebP 的東西(這裡用 PNG 的 magic bytes),用來驗「副檔名叫 .webp 也擋得下來」。 */
    public static byte[] notWebp() {
        byte[] data = new byte[40];
        byte[] pngMagic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(pngMagic, 0, data, 0, pngMagic.length);
        return data;
    }

    /** RIFF 宣告的長度比實際位元組多 —— 檔案被截斷的樣子。 */
    public static byte[] truncated() {
        byte[] full = lossy(1200, 675);
        byte[] cut = new byte[full.length - 4];
        System.arraycopy(full, 0, cut, 0, cut.length);
        return cut;
    }

    private static byte[] container(String fourcc) {
        byte[] data = new byte[40];
        write(data, 0, "RIFF");
        putU32(data, 4, data.length - 8L);
        write(data, 8, "WEBP");
        write(data, 12, fourcc);
        putU32(data, 16, data.length - 20L);
        return data;
    }

    private static void write(byte[] data, int offset, String ascii) {
        byte[] bytes = ascii.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, data, offset, bytes.length);
    }

    private static void putU16(byte[] d, int i, int value) {
        d[i] = (byte) (value & 0xFF);
        d[i + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void putU24(byte[] d, int i, int value) {
        d[i] = (byte) (value & 0xFF);
        d[i + 1] = (byte) ((value >> 8) & 0xFF);
        d[i + 2] = (byte) ((value >> 16) & 0xFF);
    }

    private static void putU32(byte[] d, int i, long value) {
        d[i] = (byte) (value & 0xFF);
        d[i + 1] = (byte) ((value >> 8) & 0xFF);
        d[i + 2] = (byte) ((value >> 16) & 0xFF);
        d[i + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
