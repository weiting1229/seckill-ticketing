import { request } from './http'
import type { LoginResponse, MeResponse, RegisterResponse } from './types'

export function register(username: string, password: string): Promise<RegisterResponse> {
  return request<RegisterResponse>({
    method: 'POST',
    url: '/auth/register',
    data: { username, password },
  })
}

export function login(username: string, password: string): Promise<LoginResponse> {
  return request<LoginResponse>({
    method: 'POST',
    url: '/auth/login',
    data: { username, password },
    // 登入失敗(1002)由登入頁自行在表單上提示
    silent: true,
  })
}

export function me(): Promise<MeResponse> {
  return request<MeResponse>({ method: 'GET', url: '/auth/me' })
}

export function logout(): Promise<void> {
  return request<void>({ method: 'POST', url: '/auth/logout', silent: true })
}
