import type { ArtistPublic } from '@/api/types'

/**
 * 活動標題 → 藝人海報的比對(M8)。
 *
 * ## 這是第 2 層,不是第 1 層
 *
 * 海報有三層,依序:
 * 1. `events.coverImageUrl` —— 活動自己指定的封面,最權威
 * 2. **本模組**:標題裡出現哪個藝人的名字,就用那個藝人的海報
 * 3. `GenerativePoster` 生成式 SVG —— 永遠成立的保底
 *
 * 第 2 層是**降級路徑**:計畫 §5 的 S5 定案由 seeder 建活動時直接寫 `coverImageUrl`,
 * 那條做完之後這裡就只服務「手動建立、標題剛好帶團名」的活動。在 seeder 實作之前,
 * 這是唯一會讓真海報出現在畫面上的路徑。
 *
 * ## 為什麼拉丁字母要求詞邊界,中日韓不要
 *
 * 內容包裡有 `Mint` / `Dodo` / `Mist` / `Astra` 這種短英文名。純 `includes` 的話,
 * 一個叫「Mistake Tour」的活動會命中藝人 `Mist` —— **掛上完全無關的海報,而且不會報錯**,
 * 因為那條路徑上每一步都成功了。所以拉丁名要求左右不得緊鄰英數字。
 *
 * 中文沒有詞邊界可用(「第七象限」前後本來就會直接接其他漢字),只能用純 `includes`。
 * 這條靠的是上游保證:poster-forge 的硬規則 #3 要求團名之間**子字串互斥**,
 * `src/identity/artists.ts` 的 `validateArtists` 會擋。實測 46 個藝人共 69 個中英名字,
 * 互斥違反 0 筆。
 */

/** 預先算好的比對索引;`buildPosterIndex` 產生,`matchPosterUrl` 消費。 */
export interface PosterIndex {
  entries: PosterEntry[]
}

interface PosterEntry {
  /** 用來比對的名字(中文或英文) */
  needle: string
  /** 小寫化的 needle,拉丁比對用 */
  lowered: string
  /** 是否要求詞邊界(名字整串都是拉丁字母/數字/空白/連字號時為 true) */
  wordBoundary: boolean
  slug: string
  imageUrl: string
}

/** 名字整串只有拉丁字母、數字與常見分隔符(空白、點、&、連字號、直/彎撇號)→ 有詞邊界可用。 */
const LATIN_ONLY = /^[A-Za-z0-9][A-Za-z0-9 .&'’-]*$/
/** 詞邊界的定義:不得緊鄰英數字(不用 \b,因為 \b 對「'」「&」的行為不是我們要的)。 */
const WORD_CHAR = /[A-Za-z0-9]/

/**
 * 藝人清單 → 比對索引。
 *
 * 沒有海報的藝人直接跳過:比對到它只會回一個空的 URL,而空 URL 進到 `<img src>`
 * 會變成一次對當前頁面的重新請求(瀏覽器對 `src=""` 的行為),看起來像圖破了。
 * 匯入端已用 `@NotEmpty` 保證每個藝人至少一張,這裡是消費端的獨立防線。
 */
export function buildPosterIndex(artists: ArtistPublic[]): PosterIndex {
  const entries: PosterEntry[] = []
  for (const artist of artists) {
    const imageUrl = artist.posters[0]?.imageUrl
    if (!imageUrl) continue
    for (const raw of [artist.nameZh, artist.nameEn]) {
      const needle = raw?.trim()
      if (!needle) continue
      entries.push({
        needle,
        lowered: needle.toLowerCase(),
        wordBoundary: LATIN_ONLY.test(needle),
        slug: artist.slug,
        imageUrl,
      })
    }
  }
  // 長名字優先。標題同時包含兩個不同藝人的名字時(例如雙主場活動),
  // 依陣列順序挑等於「看 API 回傳順序」—— 那是實作細節不是決定。
  // 長度相同再依 slug,讓結果在任何輸入順序下都一樣。
  entries.sort((a, b) => b.needle.length - a.needle.length || a.slug.localeCompare(b.slug))
  return { entries }
}

/** 空索引;store 還沒載入完或載入失敗時用,讓呼叫端不必處理 null。 */
export const EMPTY_POSTER_INDEX: PosterIndex = { entries: [] }

/** 標題比對到的海報 URL;沒有命中回 null(呼叫端據此退到生成式 SVG)。 */
export function matchPosterUrl(title: string, index: PosterIndex): string | null {
  const text = title?.trim()
  if (!text) return null
  const lowered = text.toLowerCase()
  for (const entry of index.entries) {
    const hit = entry.wordBoundary
      ? containsWholeWord(lowered, entry.lowered)
      : text.includes(entry.needle)
    if (hit) return entry.imageUrl
  }
  return null
}

/** `needle` 出現在 `haystack` 且左右都不是英數字。兩者都必須先小寫化。 */
function containsWholeWord(haystack: string, needle: string): boolean {
  let from = 0
  for (;;) {
    const at = haystack.indexOf(needle, from)
    if (at < 0) return false
    const before = at === 0 ? '' : (haystack[at - 1] ?? '')
    const after = haystack[at + needle.length] ?? ''
    if (!WORD_CHAR.test(before) && !WORD_CHAR.test(after)) return true
    from = at + 1
  }
}
