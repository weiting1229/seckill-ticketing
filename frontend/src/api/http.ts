import axios, { AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { AccessTokenResponse, ApiResponse } from './types'
import { accessToken, clearTokens, refreshToken, setAccessToken } from './tokenStorage'

/**
 * 全站共用 axios instance:
 * - 自動帶 Authorization: Bearer {accessToken}
 * - 統一解析 ApiResponse:code ≠ 0 一律轉為 BizError 拋出,並(除非 silent)以 ElMessage 提示
 * - code = 1401(憑證失效)時單一航班(single-flight)refresh 後重放原請求;
 *   refresh 失敗或重放仍 401 → 清 token 整頁導向 /login,防無限迴圈
 */

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** true 時攔截器不彈 ElMessage,由呼叫端自行處理錯誤(如搶購流程的客製提示)。 */
    silent?: boolean
    /** 內部旗標:此請求已因 401 refresh 重放過一次,不再重試。 */
    _retried?: boolean
  }
}

/** 業務錯誤(code ≠ 0)。呼叫端可依 code 做客製處理(如 3004 限流、3005 售罄)。 */
export class BizError extends Error {
  readonly code: number
  readonly httpStatus?: number

  constructor(code: number, message: string, httpStatus?: number) {
    super(message)
    this.name = 'BizError'
    this.code = code
    this.httpStatus = httpStatus
  }
}

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 10_000,
})

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (accessToken.value) {
    config.headers.Authorization = `Bearer ${accessToken.value}`
  }
  return config
})

/** refresh 單一航班:多請求同時 401 只發一次 refresh,其餘等同一個 promise。 */
let refreshPromise: Promise<string> | null = null

function refreshAccessToken(): Promise<string> {
  refreshPromise ??= axios
    // 用原生 axios 而非本 instance,避免 refresh 自身 401 再進攔截器形成迴圈
    .post<ApiResponse<AccessTokenResponse>>(
      '/api/v1/auth/refresh',
      { refreshToken: refreshToken.value },
      { timeout: 10_000 },
    )
    .then((res) => {
      if (res.data.code !== 0) throw new BizError(res.data.code, res.data.message)
      setAccessToken(res.data.data.accessToken)
      return res.data.data.accessToken
    })
    .finally(() => {
      refreshPromise = null
    })
  return refreshPromise
}

/** 清 token 並整頁導向登入頁(整頁導向可同時重置所有前端狀態)。 */
function redirectToLogin(): void {
  clearTokens()
  const current = window.location.pathname + window.location.search
  window.location.assign(`/login?redirect=${encodeURIComponent(current)}`)
}

http.interceptors.response.use(
  // HTTP 2xx:仍可能是業務錯誤(防禦性;後端目前錯誤都帶非 2xx 狀態)
  (response) => {
    const body = response.data as ApiResponse
    if (body && typeof body.code === 'number' && body.code !== 0) {
      if (!response.config.silent) ElMessage.error(body.message)
      return Promise.reject(new BizError(body.code, body.message, response.status))
    }
    return response
  },
  async (error: AxiosError) => {
    const config = error.config
    const body = error.response?.data as ApiResponse | undefined

    // 非 HTTP 錯誤(網路中斷、逾時)
    if (!error.response || !body || typeof body.code !== 'number') {
      if (!config?.silent) ElMessage.error('網路連線異常,請稍後再試')
      return Promise.reject(error)
    }

    // 1401 = 未認證/憑證無效(登入失敗 1002、refresh 失效 1005 不屬此,天然不觸發重放)
    if (body.code === 1401 && config) {
      if (config._retried || !refreshToken.value) {
        redirectToLogin()
        return Promise.reject(new BizError(body.code, body.message, error.response.status))
      }
      try {
        await refreshAccessToken()
      } catch {
        redirectToLogin()
        return Promise.reject(new BizError(body.code, body.message, error.response.status))
      }
      config._retried = true
      return http.request(config)
    }

    if (!config?.silent) ElMessage.error(body.message)
    return Promise.reject(new BizError(body.code, body.message, error.response.status))
  },
)

/** 發送請求並直接取回 ApiResponse.data 內容(泛型即後端 data 型別)。 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const res = await http.request<ApiResponse<T>>(config)
  return res.data.data
}

export default http
