// 壓測共用設定與工具函式,供 setup-users.js / scenario-*.js 共用。

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const ADMIN_USERNAME = __ENV.ADMIN_USERNAME || 'admin_local';
export const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'AdminLocal123';

// 壓測帳號固定密碼(僅本機 dev 測試帳號使用,非真實使用者資料,無外洩疑慮)。
export const USER_PASSWORD = 'LoadTest123';

// 壓測帳號池大小,setup-users.js 依此批量註冊;scenario 腳本依同一函式推回帳號名稱。
export const USER_POOL_SIZE = Number(__ENV.USER_POOL_SIZE || 5000);

// 依序號產生壓測帳號名稱(3–50 字英數底線,符合後端 username pattern ^[A-Za-z0-9_]{3,50}$)。
export function usernameFor(index) {
  return `lt_user_${String(index).padStart(5, '0')}`;
}

// 組出 k6 request 的 params(headers + tags),token 為 null/undefined 時省略 Authorization。
export function jsonHeaders(token, tagName) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  const params = { headers };
  if (tagName) params.tags = { name: tagName };
  return params;
}
