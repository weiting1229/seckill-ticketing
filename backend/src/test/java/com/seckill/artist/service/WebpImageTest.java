package com.seckill.artist.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seckill.support.WebpFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** WebP 檔頭解析(計畫 §4.4 的 magic bytes 與尺寸兩道防護)。 */
class WebpImageTest {

    @Test
    @DisplayName("VP8(有損):讀出畫布尺寸")
    void parsesLossy() {
        WebpImage image = WebpImage.parse(WebpFixtures.lossy(1200, 675));

        assertThat(image).isNotNull();
        assertThat(image.width()).isEqualTo(1200);
        assertThat(image.height()).isEqualTo(675);
    }

    @Test
    @DisplayName("VP8L(無失真):讀出畫布尺寸")
    void parsesLossless() {
        WebpImage image = WebpImage.parse(WebpFixtures.lossless(800, 600));

        assertThat(image).isNotNull();
        assertThat(image.width()).isEqualTo(800);
        assertThat(image.height()).isEqualTo(600);
    }

    @Test
    @DisplayName("VP8X(延伸):讀出畫布尺寸")
    void parsesExtended() {
        WebpImage image = WebpImage.parse(WebpFixtures.extended(4096, 2160));

        assertThat(image).isNotNull();
        assertThat(image.width()).isEqualTo(4096);
        assertThat(image.height()).isEqualTo(2160);
    }

    @Test
    @DisplayName("PNG 改名成 .webp:magic bytes 不符,回 null")
    void rejectsNonWebp() {
        assertThat(WebpImage.parse(WebpFixtures.notWebp())).isNull();
    }

    @Test
    @DisplayName("RIFF 宣告長度與實際位元組不符(檔案被截斷):回 null")
    void rejectsTruncated() {
        // 截斷的圖在瀏覽器上是「載到一半的破圖」,不會有任何錯誤 —— 所以要在這一層擋。
        assertThat(WebpImage.parse(WebpFixtures.truncated())).isNull();
    }

    @Test
    @DisplayName("空的 / 過短的輸入:回 null 而不是拋例外")
    void rejectsTooShort() {
        assertThat(WebpImage.parse(null)).isNull();
        assertThat(WebpImage.parse(new byte[0])).isNull();
        assertThat(WebpImage.parse(new byte[12])).isNull();
    }

    @Test
    @DisplayName("認得的 RIFF/WEBP 但 chunk 不是三種之一:回 null")
    void rejectsUnknownChunk() {
        byte[] data = WebpFixtures.lossy(1200, 675);
        data[12] = 'X';
        data[13] = 'X';
        data[14] = 'X';
        data[15] = 'X';

        assertThat(WebpImage.parse(data)).isNull();
    }
}
