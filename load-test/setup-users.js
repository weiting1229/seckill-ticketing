// 子項 6 前置作業:批量註冊壓測帳號(冪等,可重跑)。
//
// 執行:
//   k6 run load-test/setup-users.js
//
// 只需要跑一次(帳號會持續存在於 dev DB);之後每次壓測前重跑也安全,已存在的帳號會被
// 略過(見下方 code===1001 判斷)。密碼固定為 lib/config.js 的 USER_PASSWORD。

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, USER_PASSWORD, USER_POOL_SIZE, usernameFor, jsonHeaders } from './lib/config.js';

const SETUP_VUS = Number(__ENV.SETUP_VUS || 50);
// 用 per-vu-iterations(而非 shared-iterations)換取「(VU, ITER) → 帳號序號」的簡單且
// 保證正確的映射,不依賴 k6 執行 API 的全域迭代計數器。
const ITERATIONS_PER_VU = Math.ceil(USER_POOL_SIZE / SETUP_VUS);

export const options = {
  scenarios: {
    register: {
      executor: 'per-vu-iterations',
      vus: SETUP_VUS,
      iterations: ITERATIONS_PER_VU,
      maxDuration: __ENV.SETUP_MAX_DURATION || '10m',
    },
  },
};

export default function () {
  const index = (__VU - 1) * ITERATIONS_PER_VU + __ITER;
  // USER_POOL_SIZE 不是 SETUP_VUS 的整數倍時,最後一輪部分 VU 會算出超出範圍的 index,略過即可。
  if (index >= USER_POOL_SIZE) return;

  const username = usernameFor(index);
  const res = http.post(
    `${BASE_URL}/api/v1/auth/register`,
    JSON.stringify({ username, password: USER_PASSWORD }),
    jsonHeaders(null, 'register'),
  );
  const body = res.json();
  // code 0 = 新註冊成功;1001(USERNAME_ALREADY_EXISTS)= 重跑本腳本時的冪等成功。
  const ok = body && (body.code === 0 || body.code === 1001);
  check(res, { 'register ok or already exists': () => ok });
  if (!ok) {
    console.error(`[register] failed username=${username} status=${res.status} body=${res.body}`);
  }
}
