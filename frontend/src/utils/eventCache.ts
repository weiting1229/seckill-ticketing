import { getEvent } from '@/api/events'
import type { EventDetail } from '@/api/types'

/**
 * 活動詳情的 session 級快取:訂單頁用它把 eventId / ticketTypeId 轉成
 * 人類可讀的活動標題與票種名(OrderResponse 只有 ID)。
 * 活動不存在或已下架時回 null,呼叫端以 ID 原樣顯示。
 */
const cache = new Map<string, Promise<EventDetail | null>>()

export function getEventCached(eventId: string): Promise<EventDetail | null> {
  let hit = cache.get(eventId)
  if (!hit) {
    hit = getEvent(eventId).catch(() => null)
    cache.set(eventId, hit)
  }
  return hit
}

/** 依訂單的 eventId/ticketTypeId 解析顯示名稱;解析不到時退回 ID。 */
export async function resolveOrderNames(
  eventId: string,
  ticketTypeId: string,
): Promise<{ eventTitle: string; ticketName: string }> {
  const detail = await getEventCached(eventId)
  const ticket = detail?.ticketTypes.find((t) => t.id === ticketTypeId)
  return {
    eventTitle: detail?.title ?? `活動 ${eventId}`,
    ticketName: ticket?.name ?? `票種 ${ticketTypeId}`,
  }
}
