package com.seckill.config;

/**
 * 海報檔案儲存設定(計畫 §4.4 / §4.6)。
 *
 * <p>{@code dir} 是海報唯一的落點,API <b>不接受任何路徑參數</b> —— 檔名由 manifest 提供
 * 但先經格式白名單校驗,寫入前再斷言解析後的父目錄就是這個目錄,兩道都過才落檔。
 *
 * <p>prod 由 compose 把主機的 {@code /opt/seckill/posters} 掛進容器並以環境變數指向它;
 * dev / 整合測試指向本機暫存目錄。兩邊對外 URL 都是 {@code /posters/<檔名>},
 * 前端不需要區分環境。
 *
 * @param dir            海報落地目錄(絕對或相對路徑;啟動時若不存在會建立)
 * @param maxFileBytes   單張上限,預設 2 MB(§4.4:擋磁碟塞爆與記憶體耗盡)
 * @param minDimension   最短邊下限,預設 640(§4.4:擋解壓縮炸彈與異常尺寸)
 * @param maxDimension   最長邊上限,預設 4096
 */
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "seckill.posters")
public record PosterStorageProperties(
        String dir,
        long maxFileBytes,
        int minDimension,
        int maxDimension
) {
}
