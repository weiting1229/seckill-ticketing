import { request } from './http'
import type { EventDetail, EventSummary, PageResponse } from './types'

export function listEvents(
  page: number,
  size: number,
  keyword?: string,
): Promise<PageResponse<EventSummary>> {
  const kw = keyword?.trim()
  return request<PageResponse<EventSummary>>({
    method: 'GET',
    url: '/events',
    // 空字串不帶,交由後端「不過濾」語意
    params: { page, size, ...(kw ? { keyword: kw } : {}) },
  })
}

export function getEvent(id: string): Promise<EventDetail> {
  return request<EventDetail>({ method: 'GET', url: `/events/${id}`, silent: true })
}
