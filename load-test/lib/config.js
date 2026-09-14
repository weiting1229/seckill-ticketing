// 壓測共用設定與工具函式,供 setup-users.js / scenario-*.js 共用。

import { sleep } from 'k6';

// ---------------------------------------------------------------------------
// 目標環境切換(TARGET_ENV=dev|prod)
//
// 為什麼不再直接吃 BASE_URL:PowerShell 的 $env: 設定會留在同一個視窗裡,不隨指令結束清除,
// 「以為在打本機其實在打正式站」(或反過來)是實際存在的風險。所以:
//   - 目標只由 TARGET_ENV 決定,打錯字直接失敗,不會安靜地落回 dev
//   - dev 的 BASE_URL 覆寫只接受 localhost —— 殘留的正式站 BASE_URL 一定會被擋下
//   - prod 必須另外設 CONFIRM_PROD=yes,且 admin 帳密沒有任何預設值(CLAUDE.md:祕密不進版控)
//   - setup() 一律印出目標,prod 再倒數讓人有機會 Ctrl+C
// 建議 TARGET_ENV / CONFIRM_PROD 用 `k6 run -e` 傳:-e 只對那一次執行有效,不會殘留在視窗裡。
//
// 以下檢查都在 init 階段執行,不通過就在送出任何請求之前失敗。
// ---------------------------------------------------------------------------

const PROD_BASE_URL = 'https://tixco.kozow.com';
const DEV_BASE_URL = 'http://localhost:8080';
const PROD_COUNTDOWN_SECONDS = 10;

export const TARGET_ENV = __ENV.TARGET_ENV || 'dev';

function resolveBaseUrl() {
  const override = (__ENV.BASE_URL || '').replace(/\/+$/, '');
  if (TARGET_ENV === 'dev') {
    if (!override) return DEV_BASE_URL;
    const host = override.replace(/^https?:\/\//, '').split(/[/:]/)[0];
    if (host !== 'localhost' && host !== '127.0.0.1') {
      throw new Error(
        `[config] TARGET_ENV=dev 但 BASE_URL=${override} 不是本機。` +
          `若要打正式站請改用 -e TARGET_ENV=prod;若是視窗殘留的舊值,先 Remove-Item Env:BASE_URL`,
      );
    }
    return override;
  }
  if (TARGET_ENV === 'prod') {
    if (override && override !== PROD_BASE_URL) {
      throw new Error(`[config] TARGET_ENV=prod 但 BASE_URL=${override} 與正式站 ${PROD_BASE_URL} 不符`);
    }
    if (__ENV.CONFIRM_PROD !== 'yes') {
      throw new Error(`[config] 目標是正式站 ${PROD_BASE_URL}。確定的話加 -e CONFIRM_PROD=yes 重跑`);
    }
    if (!__ENV.ADMIN_USERNAME || !__ENV.ADMIN_PASSWORD) {
      throw new Error('[config] TARGET_ENV=prod 必須設 ADMIN_USERNAME / ADMIN_PASSWORD(正式站沒有預設帳密)');
    }
    return PROD_BASE_URL;
  }
  throw new Error(`[config] TARGET_ENV 只能是 dev 或 prod,收到:${TARGET_ENV}`);
}

export const BASE_URL = resolveBaseUrl();

// dev 預設值是本機 dev profile 的測試帳號;prod 走到這裡時已確認兩者都有設。
export const ADMIN_USERNAME = __ENV.ADMIN_USERNAME || 'admin_local';
export const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'AdminLocal123';

// 全部 metric 都帶 target 標籤,事後看報告 / 匯出資料時分得出是哪個環境的數字。
export const TARGET_TAGS = { target: TARGET_ENV };

// 由各腳本的 setup() 呼叫(setup 只跑一次;放在 init 階段的話每個 VU 都會印一次)。
// details 是該腳本想一併印出的規模參數,例如 { vus: 2000, stock: 1000 }。不印密碼。
export function printTarget(scriptName, details) {
  const extra = Object.entries(details || {})
    .map(([k, v]) => `${k}=${v}`)
    .join(' ');
  // 逐行印而不是 join('\n'):輸出不是終端機(被導向檔案 / CI)時 k6 會把換行跳脫成字面上的 \n。
  [
    '============================================================',
    `腳本    ${scriptName}`,
    `目標    ${TARGET_ENV.toUpperCase()}  ${BASE_URL}`,
    `admin   ${ADMIN_USERNAME}`,
    extra ? `規模    ${extra}` : null,
    '============================================================',
  ]
    .filter((line) => line !== null)
    .forEach((line) => console.log(line));

  if (TARGET_ENV === 'prod') {
    for (let s = PROD_COUNTDOWN_SECONDS; s > 0; s--) {
      console.warn(`⚠ 即將對正式站送出流量,${s} 秒後開始(Ctrl+C 中止)`);
      sleep(1);
    }
  }
}

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

// 安全解析回應 JSON。大規模壓測下偶爾會發生連線層級失敗(connection refused/timeout),
// 此時 k6 回應物件的 body 是 null——直接呼叫 res.json() 會丟例外把整個迭代中斷,
// 而不是像一般錯誤回應那樣回傳可判斷的物件。這裡統一防禦,失敗一律回傳 null,
// 呼叫端既有的 `if (!body)` / `body && body.code` 判斷即可正常處理。
export function safeJson(res, path) {
  if (!res || res.status === 0 || res.body === null || res.body === undefined) return null;
  try {
    return path ? res.json(path) : res.json();
  } catch (e) {
    return null;
  }
}
