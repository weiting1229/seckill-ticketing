import { ref } from 'vue'

/**
 * Token 的單一事實來源(reactive + localStorage 持久化)。
 * 獨立成無依賴的小模組,讓 http.ts(攔截器)與 stores/auth.ts(登入狀態)
 * 都能使用而不形成循環 import。
 */

const ACCESS_KEY = 'seckill.accessToken'
const REFRESH_KEY = 'seckill.refreshToken'

export const accessToken = ref<string | null>(localStorage.getItem(ACCESS_KEY))
export const refreshToken = ref<string | null>(localStorage.getItem(REFRESH_KEY))

export function setTokens(access: string, refresh: string): void {
  accessToken.value = access
  refreshToken.value = refresh
  localStorage.setItem(ACCESS_KEY, access)
  localStorage.setItem(REFRESH_KEY, refresh)
}

export function setAccessToken(access: string): void {
  accessToken.value = access
  localStorage.setItem(ACCESS_KEY, access)
}

export function clearTokens(): void {
  accessToken.value = null
  refreshToken.value = null
  localStorage.removeItem(ACCESS_KEY)
  localStorage.removeItem(REFRESH_KEY)
}
