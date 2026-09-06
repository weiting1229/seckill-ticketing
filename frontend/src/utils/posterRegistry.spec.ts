import { describe, expect, it } from 'vitest'
import type { ArtistPublic } from '@/api/types'
import { EMPTY_POSTER_INDEX, buildPosterIndex, matchPosterUrl } from './posterRegistry'

function artist(partial: Partial<ArtistPublic> & { slug: string }): ArtistPublic {
  return {
    name: partial.nameZh ?? partial.nameEn ?? partial.slug,
    nameZh: null,
    nameEn: partial.slug,
    tier: 'ROTATING',
    tourThemes: [],
    posters: [{ imageUrl: `/posters/${partial.slug}__bottom-heavy.webp`, archetype: 'bottom-heavy', themeIndex: null }],
    ...partial,
  }
}

describe('posterRegistry', () => {
  it('比對中文團名(無詞邊界可用,純子字串)', () => {
    const index = buildPosterIndex([artist({ slug: '7th-quadrant', nameZh: '第七象限', nameEn: '7th Quadrant' })])

    expect(matchPosterUrl('第七象限 2026 巡迴演唱會', index)).toBe(
      '/posters/7th-quadrant__bottom-heavy.webp',
    )
  })

  it('比對英文團名,大小寫不敏感', () => {
    const index = buildPosterIndex([artist({ slug: 'astra', nameEn: 'Astra' })])

    expect(matchPosterUrl('ASTRA Live in Taipei', index)).toBe('/posters/astra__bottom-heavy.webp')
    expect(matchPosterUrl('astra 台北場', index)).toBe('/posters/astra__bottom-heavy.webp')
  })

  it('短英文名不會被其他單字的一部分誤觸發(這一條沒有的話會掛上完全無關的海報且不報錯)', () => {
    const index = buildPosterIndex([artist({ slug: 'mist', nameEn: 'Mist' })])

    // Mistake / Mister 都含有 mist,但它們不是這個團
    expect(matchPosterUrl('Mistake Tour 2026', index)).toBeNull()
    expect(matchPosterUrl('Mister Big Night', index)).toBeNull()
    // 真的獨立成詞時要命中
    expect(matchPosterUrl('Mist 冬季巡演', index)).toBe('/posters/mist__bottom-heavy.webp')
  })

  it('詞邊界允許常見標點緊鄰', () => {
    const index = buildPosterIndex([artist({ slug: 'ripple', nameEn: 'Ripple' })])

    expect(matchPosterUrl('「Ripple」台北站', index)).toBe('/posters/ripple__bottom-heavy.webp')
    expect(matchPosterUrl('Ripple: The Final Show', index)).toBe(
      '/posters/ripple__bottom-heavy.webp',
    )
    expect(matchPosterUrl('(Ripple)', index)).toBe('/posters/ripple__bottom-heavy.webp')
  })

  it('標題同時含兩個團名時取較長的那個,且與輸入順序無關', () => {
    const short = artist({ slug: 'mint', nameEn: 'Mint' })
    const long = artist({ slug: 'mint-condition', nameEn: 'Mint Condition' })

    const a = buildPosterIndex([short, long])
    const b = buildPosterIndex([long, short])

    expect(matchPosterUrl('Mint Condition 巡演', a)).toBe(
      '/posters/mint-condition__bottom-heavy.webp',
    )
    expect(matchPosterUrl('Mint Condition 巡演', b)).toBe(matchPosterUrl('Mint Condition 巡演', a))
  })

  it('沒有海報的藝人不進索引(空 URL 進 img src 會變成重新請求當前頁,看起來像圖破了)', () => {
    const index = buildPosterIndex([artist({ slug: 'nobody', nameEn: 'Nobody', posters: [] })])

    expect(index.entries).toHaveLength(0)
    expect(matchPosterUrl('Nobody 演唱會', index)).toBeNull()
  })

  it('沒命中、空標題、空索引一律回 null,不拋例外', () => {
    const index = buildPosterIndex([artist({ slug: 'astra', nameEn: 'Astra' })])

    expect(matchPosterUrl('LoadTest Scenario A 1786639603621', index)).toBeNull()
    expect(matchPosterUrl('', index)).toBeNull()
    expect(matchPosterUrl('Astra', EMPTY_POSTER_INDEX)).toBeNull()
  })

  it('中英兩個名字都能各自命中同一張海報', () => {
    const index = buildPosterIndex([
      artist({ slug: 'crimson-gravity', nameZh: '赤色引力', nameEn: 'Crimson Gravity' }),
    ])

    const url = '/posters/crimson-gravity__bottom-heavy.webp'
    expect(matchPosterUrl('赤色引力 台北小巨蛋', index)).toBe(url)
    expect(matchPosterUrl('Crimson Gravity Live', index)).toBe(url)
  })
})
