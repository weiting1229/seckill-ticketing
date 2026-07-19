import { describe, it, expect } from 'vitest'
import {
  hashTitle,
  derivePosterParams,
  POSTER_PALETTES,
  SKYLINE_BUILDINGS,
} from './posterSeed'

describe('hashTitle', () => {
  it('相同輸入永遠得到相同雜湊', () => {
    expect(hashTitle('五月天 2026')).toBe(hashTitle('五月天 2026'))
  })

  it('不同輸入通常得到不同雜湊', () => {
    expect(hashTitle('A')).not.toBe(hashTitle('B'))
    expect(hashTitle('演唱會')).not.toBe(hashTitle('演唱會 '))
  })

  it('回傳無號 32-bit 整數', () => {
    for (const t of ['', 'x', '很長的活動名稱測試字串 1234567890']) {
      const h = hashTitle(t)
      expect(Number.isInteger(h)).toBe(true)
      expect(h).toBeGreaterThanOrEqual(0)
      expect(h).toBeLessThanOrEqual(0xffffffff)
    }
  })
})

describe('derivePosterParams', () => {
  it('deterministic:相同 title 產出完全相同的參數', () => {
    const a = derivePosterParams('告五人 巡迴')
    const b = derivePosterParams('告五人 巡迴')
    expect(JSON.stringify(a)).toBe(JSON.stringify(b))
  })

  it('seed 與 title 的雜湊一致', () => {
    expect(derivePosterParams('Coldplay').seed).toBe(hashTitle('Coldplay'))
  })

  it('所有參數都落在宣告的範圍內', () => {
    for (let i = 0; i < 200; i++) {
      const p = derivePosterParams('event-' + i)

      expect(p.paletteIndex).toBeGreaterThanOrEqual(0)
      expect(p.paletteIndex).toBeLessThan(POSTER_PALETTES.length)
      expect(p.palette).toBe(POSTER_PALETTES[p.paletteIndex])

      expect(p.moon.cx).toBeGreaterThanOrEqual(0.3)
      expect(p.moon.cx).toBeLessThanOrEqual(0.75)
      expect(p.moon.cy).toBeGreaterThanOrEqual(0.2)
      expect(p.moon.cy).toBeLessThanOrEqual(0.48)
      expect(p.moon.r).toBeGreaterThanOrEqual(0.12)
      expect(p.moon.r).toBeLessThanOrEqual(0.22)

      expect(p.rings).toBeGreaterThanOrEqual(2)
      expect(p.rings).toBeLessThanOrEqual(5)
      expect(p.beamAngle).toBeGreaterThanOrEqual(-30)
      expect(p.beamAngle).toBeLessThanOrEqual(30)
      expect(p.beamCount).toBeGreaterThanOrEqual(3)
      expect(p.beamCount).toBeLessThanOrEqual(6)

      expect(p.skyline).toHaveLength(SKYLINE_BUILDINGS)
      for (const h of p.skyline) {
        expect(h).toBeGreaterThanOrEqual(0.15)
        expect(h).toBeLessThanOrEqual(0.6)
      }

      expect(p.stars.length).toBeGreaterThanOrEqual(24)
      expect(p.stars.length).toBeLessThanOrEqual(47)
      for (const s of p.stars) {
        expect(s.x).toBeGreaterThanOrEqual(0)
        expect(s.x).toBeLessThanOrEqual(1)
        expect(s.y).toBeGreaterThanOrEqual(0)
        expect(s.y).toBeLessThanOrEqual(0.6)
        expect(s.r).toBeGreaterThanOrEqual(0.5)
        expect(s.r).toBeLessThanOrEqual(1.7)
      }
    }
  })

  it('參數分布合理:大量不同 title 會用到多組調色盤', () => {
    const used = new Set<number>()
    for (let i = 0; i < 300; i++) {
      used.add(derivePosterParams('title-' + i).paletteIndex)
    }
    // 6 組調色盤,300 個樣本應涵蓋過半,至少不會全部擠在同一組
    expect(used.size).toBeGreaterThanOrEqual(4)
  })

  it('不同 title 通常產出不同構圖(月亮位置或調色盤有別)', () => {
    const a = derivePosterParams('Taylor Swift')
    const b = derivePosterParams('Bruno Mars')
    const same =
      a.paletteIndex === b.paletteIndex &&
      a.moon.cx === b.moon.cx &&
      a.moon.cy === b.moon.cy
    expect(same).toBe(false)
  })
})
