import { request } from './http'
import type { OrderResponse, PageResponse } from './types'

export function listOrders(page: number, size: number): Promise<PageResponse<OrderResponse>> {
  return request<PageResponse<OrderResponse>>({
    method: 'GET',
    url: '/orders',
    params: { page, size },
  })
}

export function getOrder(id: string): Promise<OrderResponse> {
  // 4001(不存在/非本人)由頁面呈現空狀態
  return request<OrderResponse>({ method: 'GET', url: `/orders/${id}`, silent: true })
}

export function payOrder(id: string): Promise<OrderResponse> {
  // 4001/4002 直接用攔截器的統一提示
  return request<OrderResponse>({ method: 'POST', url: `/orders/${id}/pay` })
}
