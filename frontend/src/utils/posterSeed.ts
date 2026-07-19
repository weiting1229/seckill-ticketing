/**
 * 生成式海報的 seed 與構圖參數推導(方案 C)。
 *
 * 全部 deterministic:同一 title 永遠產出同一組參數(同圖),供 <GenerativePoster>
 * 在 coverImageUrl 為空時即時渲染。無網路請求、無外部依賴。
 *
 * 流程:title → hashTitle() 32-bit seed → mulberry32 PRNG → 調色盤 + 構圖參數。
 */

/** FNV-1a 32-bit 雜湊,回傳無號 32-bit 整數。純函式、與平台無關。 */
export function hashTitle(title: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < title.length; i++) {
    h ^= title.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return h >>> 0
}

/** mulberry32:由 32-bit seed 產生 [0,1) 的 deterministic 亂數序列。 */
function mulberry32(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** 一組與品牌色相容的深色調色盤(舞台氛圍)。 */
export interface PosterPalette {
  name: string
  /** 背景漸層上緣 */
  bgTop: string
  /** 背景漸層下緣(接近頁面底色) */
  bgBottom: string
  /** 月亮/光暈色 */
  glow: string
  /** 月亮本體 */
  orb: string
  /** 同心圓環 */
  ring: string
  /** 光束 */
  beam: string
  /** 天際線剪影 */
  skyline: string
  /** 星點 */
  star: string
}

/** 4–6 組深色調色盤;seed 決定選用哪一組。紫羅蘭為品牌主色相。
 *  以 tuple(as const)宣告,使 [0] 的型別恆為已定義,供索引取值時的保底。 */
export const POSTER_PALETTES = [
  { name: 'violet', bgTop: '#1a1440', bgBottom: '#0b0e16', glow: '#7063ff', orb: '#b7adff', ring: '#7063ff', beam: '#8b7dff', skyline: '#05060c', star: '#c9c2ff' },
  { name: 'cyan', bgTop: '#062a33', bgBottom: '#07121a', glow: '#22d3ee', orb: '#a7f0fb', ring: '#22d3ee', beam: '#38e0f5', skyline: '#02080c', star: '#c5f6ff' },
  { name: 'coral', bgTop: '#3a1526', bgBottom: '#140a12', glow: '#fb7185', orb: '#ffc2cb', ring: '#fb7185', beam: '#ff8fa0', skyline: '#0c0509', star: '#ffd6dc' },
  { name: 'indigo', bgTop: '#101a44', bgBottom: '#080b18', glow: '#6366f1', orb: '#b3b6ff', ring: '#6366f1', beam: '#818cf8', skyline: '#04060f', star: '#c7c9ff' },
  { name: 'teal', bgTop: '#06302a', bgBottom: '#071613', glow: '#2dd4bf', orb: '#a8f0e6', ring: '#2dd4bf', beam: '#4fe3d2', skyline: '#02100c', star: '#c3f7ef' },
  { name: 'amber', bgTop: '#3a2408', bgBottom: '#160f06', glow: '#f59e0b', orb: '#ffd894', ring: '#f59e0b', beam: '#ffb733', skyline: '#0e0a04', star: '#ffe9c2' },
] as const satisfies readonly PosterPalette[]

export interface Point {
  /** 0..1,相對海報寬 */
  x: number
  /** 0..1,相對海報高 */
  y: number
  /** 星點半徑(px @ 基準座標) */
  r: number
}

/** 由 title 推導出的完整構圖參數。座標一律正規化為 0..1,由元件依實際 viewBox 縮放。 */
export interface PosterParams {
  seed: number
  paletteIndex: number
  palette: PosterPalette
  /** 月亮(大圓):圓心與半徑皆為 0..1 相對值 */
  moon: { cx: number; cy: number; r: number }
  /** 同心圓環數(2..5) */
  rings: number
  /** 光束傾角(度,-30..30,相對垂直) */
  beamAngle: number
  /** 光束數(3..6) */
  beamCount: number
  /** 天際線各棟高度序列(0..1 相對海報高),固定 12 棟 */
  skyline: number[]
  /** 星點分布 */
  stars: Point[]
}

/** 天際線棟數(固定,使不同 seed 的剪影可比較)。 */
export const SKYLINE_BUILDINGS = 12

/**
 * 由 title 推導構圖參數。deterministic:相同 title → 相同輸出。
 * 亂數抽取順序固定(調色盤 → 月亮 → 圓環 → 光束 → 天際線 → 星點),
 * 不可調換,否則既有 title 的圖會改變。
 */
export function derivePosterParams(title: string): PosterParams {
  const seed = hashTitle(title)
  const rand = mulberry32(seed)

  const paletteIndex = Math.floor(rand() * POSTER_PALETTES.length)
  const palette: PosterPalette = POSTER_PALETTES[paletteIndex] ?? POSTER_PALETTES[0]

  const moon = {
    cx: 0.3 + rand() * 0.45, // 0.30..0.75
    cy: 0.2 + rand() * 0.28, // 0.20..0.48
    r: 0.12 + rand() * 0.1, // 0.12..0.22
  }

  const rings = 2 + Math.floor(rand() * 4) // 2..5
  const beamAngle = -30 + rand() * 60 // -30..30
  const beamCount = 3 + Math.floor(rand() * 4) // 3..6

  const skyline: number[] = []
  for (let i = 0; i < SKYLINE_BUILDINGS; i++) {
    skyline.push(0.15 + rand() * 0.45) // 0.15..0.60
  }

  const starCount = 24 + Math.floor(rand() * 24) // 24..47
  const stars: Point[] = []
  for (let i = 0; i < starCount; i++) {
    stars.push({
      x: rand(),
      y: rand() * 0.6, // 星點集中在上 60%
      r: 0.5 + rand() * 1.2,
    })
  }

  return { seed, paletteIndex, palette, moon, rings, beamAngle, beamCount, skyline, stars }
}
