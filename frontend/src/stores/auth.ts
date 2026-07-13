import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import { accessToken, clearTokens, setTokens } from '@/api/tokenStorage'
import type { MeResponse } from '@/api/types'

/**
 * 登入狀態。token 的事實來源在 api/tokenStorage(http 攔截器共用);
 * 本 store 負責使用者資訊(/auth/me)與登入/登出動作。
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<MeResponse | null>(null)

  const isLoggedIn = computed(() => accessToken.value !== null)
  const isAdmin = computed(() => user.value?.role === 'ADMIN')

  async function login(username: string, password: string): Promise<void> {
    const res = await authApi.login(username, password)
    setTokens(res.accessToken, res.refreshToken)
    user.value = await authApi.me()
  }

  async function register(username: string, password: string): Promise<void> {
    await authApi.register(username, password)
  }

  /** 帶著 token 但尚未載入使用者資訊時(重新整理後),補打 /auth/me;失敗交由攔截器處理。 */
  async function ensureUser(): Promise<void> {
    if (!isLoggedIn.value || user.value) return
    try {
      user.value = await authApi.me()
    } catch {
      // 憑證失效時攔截器已 refresh 或導向登入頁;此處靜默即可
    }
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 後端撤銷失敗不阻擋本地登出
    }
    clearTokens()
    user.value = null
  }

  return { user, isLoggedIn, isAdmin, login, register, ensureUser, logout }
})
