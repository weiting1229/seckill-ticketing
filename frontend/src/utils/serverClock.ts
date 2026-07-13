import { ref } from 'vue'

/**
 * 全站共用的「後端時鐘」:以 GET /events/{id} 回傳的 serverTime 校準,
 * 之後任何倒數(搶購、訂單付款期限)都用 serverNow() 而非瀏覽器時鐘。
 * 未校準前 offset 為 0(退化為本機時間)。
 */
const offsetMs = ref(0)

export function calibrate(serverTimeIso: string): void {
  offsetMs.value = Date.parse(serverTimeIso) - Date.now()
}

export function serverNow(): number {
  return Date.now() + offsetMs.value
}
