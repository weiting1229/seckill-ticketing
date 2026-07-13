import { onBeforeUnmount, ref } from 'vue'
import { fetchResult, fetchSeckillToken, purchase } from '@/api/seckill'

/**
 * 搶購流程:領一次性 token → 下單 → 以 requestId 輪詢排隊結果。
 *
 * 輪詢策略(設計文件第 2 節):1 秒起步、1.5 倍指數退避、單次間隔上限 5 秒、
 * 最多 12 次(總計約 50 秒,遠小於後端 result key 的 10 分鐘 TTL)。
 * 只在 QUEUING 時續輪;SUCCESS / FAIL 立即停止;次數用盡回 TIMEOUT 由呼叫端
 * 引導使用者到「我的訂單」確認,絕不無限輪詢。
 */

const POLL_INITIAL_MS = 1000
const POLL_BACKOFF_FACTOR = 1.5
const POLL_MAX_INTERVAL_MS = 5000
const POLL_MAX_ATTEMPTS = 12

export type SeckillPhase = 'idle' | 'submitting' | 'queuing'

export interface SeckillOutcome {
  status: 'SUCCESS' | 'FAIL' | 'TIMEOUT' | 'CANCELLED'
  orderId?: string
  reason?: string
}

export function useSeckillFlow() {
  const phase = ref<SeckillPhase>('idle')
  /** 進行中的票種 id(供按鈕 loading 判斷);null 表示無進行中的搶購。 */
  const buyingTicketTypeId = ref<string | null>(null)

  // 元件卸載後停止輪詢(結果留給「我的訂單」頁查)
  let cancelled = false
  onBeforeUnmount(() => {
    cancelled = true
  })

  const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

  /**
   * 執行完整搶購流程。token / purchase 階段的業務錯誤(BizError,如 3004 限流、
   * 3005 售罄、3006 重複)原樣拋出,由呼叫端做客製提示。
   */
  async function buy(ticketTypeId: string): Promise<SeckillOutcome> {
    phase.value = 'submitting'
    buyingTicketTypeId.value = ticketTypeId
    try {
      // token 60s TTL、用後即焚:領完立刻下單
      const { token } = await fetchSeckillToken(ticketTypeId)
      const { requestId } = await purchase(ticketTypeId, token)

      phase.value = 'queuing'
      let interval = POLL_INITIAL_MS
      for (let attempt = 0; attempt < POLL_MAX_ATTEMPTS; attempt++) {
        await sleep(interval)
        if (cancelled) return { status: 'CANCELLED' }
        try {
          const res = await fetchResult(requestId)
          if (res.status === 'SUCCESS' && res.orderId) {
            return { status: 'SUCCESS', orderId: res.orderId }
          }
          if (res.status === 'FAIL') {
            return { status: 'FAIL', reason: res.reason ?? '搶購失敗' }
          }
          // QUEUING:續輪
        } catch {
          // 單次輪詢失敗(網路抖動等)視同 QUEUING,計入次數後續輪
        }
        interval = Math.min(interval * POLL_BACKOFF_FACTOR, POLL_MAX_INTERVAL_MS)
      }
      return { status: 'TIMEOUT' }
    } finally {
      phase.value = 'idle'
      buyingTicketTypeId.value = null
    }
  }

  return { phase, buyingTicketTypeId, buy }
}
