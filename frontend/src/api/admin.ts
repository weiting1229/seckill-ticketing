import { request } from './http'
import type {
  EventAdmin,
  EventUpsertRequest,
  PageResponse,
  ReconcileResponse,
  TicketTypeAdmin,
  TicketTypeUpsertRequest,
  WarmupResponse,
} from './types'

// ---------- 活動(admin) ----------

export function listAdminEvents(page: number, size: number): Promise<PageResponse<EventAdmin>> {
  return request<PageResponse<EventAdmin>>({
    method: 'GET',
    url: '/admin/events',
    params: { page, size },
  })
}

export function createEvent(body: EventUpsertRequest): Promise<EventAdmin> {
  return request<EventAdmin>({ method: 'POST', url: '/admin/events', data: body })
}

export function updateEvent(id: string, body: EventUpsertRequest): Promise<EventAdmin> {
  return request<EventAdmin>({ method: 'PUT', url: `/admin/events/${id}`, data: body })
}

export function deleteEvent(id: string): Promise<void> {
  return request<void>({ method: 'DELETE', url: `/admin/events/${id}` })
}

// ---------- 票種(admin) ----------

export function listTicketTypesByEvent(eventId: string): Promise<TicketTypeAdmin[]> {
  return request<TicketTypeAdmin[]>({
    method: 'GET',
    url: '/admin/ticket-types',
    params: { eventId },
  })
}

export function createTicketType(body: TicketTypeUpsertRequest): Promise<TicketTypeAdmin> {
  return request<TicketTypeAdmin>({ method: 'POST', url: '/admin/ticket-types', data: body })
}

export function updateTicketType(id: string, body: TicketTypeUpsertRequest): Promise<TicketTypeAdmin> {
  return request<TicketTypeAdmin>({ method: 'PUT', url: `/admin/ticket-types/${id}`, data: body })
}

export function deleteTicketType(id: string): Promise<void> {
  return request<void>({ method: 'DELETE', url: `/admin/ticket-types/${id}` })
}

export function warmupTicketType(id: string): Promise<WarmupResponse> {
  return request<WarmupResponse>({ method: 'POST', url: `/admin/ticket-types/${id}/warmup` })
}

export function reconcileTicketType(id: string): Promise<ReconcileResponse> {
  return request<ReconcileResponse>({ method: 'GET', url: `/admin/ticket-types/${id}/reconcile` })
}
