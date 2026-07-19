/**
 * 票種前台顯示輔助:剩餘票數模糊化 + 票區色點。
 * 前台不顯示精確剩餘數(精確值保留給 admin 頁),避免洩漏搶購情資。
 */

export type AvailabilityKind = 'plenty' | 'hot' | 'low' | 'sold'

export interface Availability {
  kind: AvailabilityKind
  label: string
}

/**
 * 依 remaining/totalStock 比例分桶(門檻定案於 02-UIUX優化計畫 §5 M4):
 *   >50% 充足 / 10–50% 熱賣中 / 1–10% 剩餘少量 / 0 售罄。
 * 邊界歸屬:50% → 熱賣中、10% → 熱賣中(取 >0.5 為充足、>=0.1 為熱賣中)。
 * remaining 為 null(尚未預熱)回 null,由呼叫端決定呈現(通常不顯示)。
 */
export function availabilityOf(remaining: number | null, totalStock: number): Availability | null {
  if (remaining == null) return null
  if (remaining <= 0) return { kind: 'sold', label: '售罄' }
  const ratio = totalStock > 0 ? remaining / totalStock : 0
  if (ratio > 0.5) return { kind: 'plenty', label: '充足' }
  if (ratio >= 0.1) return { kind: 'hot', label: '熱賣中' }
  return { kind: 'low', label: '剩餘少量' }
}

/** 票區色點候選(與品牌色相容),以 tuple 宣告使 [0] 恆為已定義。 */
export const ZONE_COLORS = [
  '#7063ff',
  '#22d3ee',
  '#fb7185',
  '#f59e0b',
  '#22c55e',
  '#a855f7',
  '#38bdf8',
  '#f472b6',
] as const satisfies readonly string[]

/** 由票種名稱/ID 推導一個穩定的票區色點(同輸入同色)。 */
export function zoneColor(seed: string): string {
  let h = 0
  for (let i = 0; i < seed.length; i++) {
    h = (Math.imul(h, 31) + seed.charCodeAt(i)) >>> 0
  }
  return ZONE_COLORS[h % ZONE_COLORS.length] ?? ZONE_COLORS[0]
}
