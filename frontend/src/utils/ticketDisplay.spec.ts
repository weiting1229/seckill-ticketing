import { describe, it, expect } from 'vitest'
import { availabilityOf, zoneColor, ZONE_COLORS } from './ticketDisplay'

describe('availabilityOf', () => {
  it('remaining 為 null(未預熱)回 null', () => {
    expect(availabilityOf(null, 100)).toBeNull()
  })

  it('0 售罄', () => {
    expect(availabilityOf(0, 100)).toEqual({ kind: 'sold', label: '售罄' })
    // 防禦:負數也視為售罄
    expect(availabilityOf(-1, 100)?.kind).toBe('sold')
  })

  it('>50% 充足', () => {
    expect(availabilityOf(51, 100)?.kind).toBe('plenty')
    expect(availabilityOf(100, 100)?.kind).toBe('plenty')
  })

  it('10–50% 熱賣中(含 50%、含 10% 邊界)', () => {
    expect(availabilityOf(50, 100)?.kind).toBe('hot')
    expect(availabilityOf(30, 100)?.kind).toBe('hot')
    expect(availabilityOf(10, 100)?.kind).toBe('hot')
  })

  it('1–10%(不含 10%)剩餘少量', () => {
    expect(availabilityOf(9, 100)?.kind).toBe('low')
    expect(availabilityOf(1, 100)?.kind).toBe('low')
  })

  it('totalStock 為 0 時不除以零(視為售罄以外的最低桶)', () => {
    // remaining>0 但 total=0 屬異常資料;ratio=0 → 剩餘少量,不應丟例外
    expect(availabilityOf(5, 0)?.kind).toBe('low')
  })

  it('永遠不外洩精確剩餘數(只回 kind/label)', () => {
    const a = availabilityOf(37, 100)
    expect(Object.keys(a ?? {})).toEqual(['kind', 'label'])
  })
})

describe('zoneColor', () => {
  it('deterministic:同輸入同色', () => {
    expect(zoneColor('搖滾區')).toBe(zoneColor('搖滾區'))
  })

  it('永遠回傳候選色之一', () => {
    for (const s of ['A', '搖滾區', '看台 B', '', '12345']) {
      expect(ZONE_COLORS).toContain(zoneColor(s))
    }
  })

  it('不同名稱大多得到不同色', () => {
    const set = new Set(['搖滾區', '看台A', '看台B', 'VIP', '身障席'].map(zoneColor))
    expect(set.size).toBeGreaterThanOrEqual(3)
  })
})
