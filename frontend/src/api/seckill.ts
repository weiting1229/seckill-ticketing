import { request } from './http'
import type { PurchaseResponse, SeckillResultResponse, SeckillTokenResponse } from './types'

// 搶購三支 API 皆 silent:錯誤碼(3001–3009、3004 限流)由搶購流程做客製化提示

export function fetchSeckillToken(ticketTypeId: string): Promise<SeckillTokenResponse> {
  return request<SeckillTokenResponse>({
    method: 'POST',
    url: '/seckill/token',
    data: { ticketTypeId },
    silent: true,
  })
}

export function purchase(ticketTypeId: string, token: string): Promise<PurchaseResponse> {
  return request<PurchaseResponse>({
    method: 'POST',
    url: '/seckill/purchase',
    data: { ticketTypeId, token },
    silent: true,
  })
}

export function fetchResult(requestId: string): Promise<SeckillResultResponse> {
  return request<SeckillResultResponse>({
    method: 'GET',
    url: `/seckill/result/${requestId}`,
    silent: true,
  })
}
