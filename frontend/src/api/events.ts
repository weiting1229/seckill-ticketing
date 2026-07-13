import { request } from './http'
import type { EventDetail, EventSummary, PageResponse } from './types'

export function listEvents(page: number, size: number): Promise<PageResponse<EventSummary>> {
  return request<PageResponse<EventSummary>>({
    method: 'GET',
    url: '/events',
    params: { page, size },
  })
}

export function getEvent(id: string): Promise<EventDetail> {
  return request<EventDetail>({ method: 'GET', url: `/events/${id}`, silent: true })
}
