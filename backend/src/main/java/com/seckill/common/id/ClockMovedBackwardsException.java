package com.seckill.common.id;

/**
 * 時鐘回撥超過容忍範圍(> 5ms)時拋出,拒絕發號。
 * 由全域例外處理器轉為系統錯誤;同時累加 Micrometer counter 觸發告警。
 */
public class ClockMovedBackwardsException extends RuntimeException {

    public ClockMovedBackwardsException(String message) {
        super(message);
    }
}
