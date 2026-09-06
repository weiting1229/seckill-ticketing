import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { listArtists } from '@/api/artists'
import type { ArtistPublic } from '@/api/types'
import { EMPTY_POSTER_INDEX, buildPosterIndex, matchPosterUrl } from '@/utils/posterRegistry'

/**
 * 藝人海報對照(M8)。App 啟動時抓一次,之後全站共用記憶體快取。
 *
 * ## 失敗一律不阻塞渲染
 *
 * 海報只是裝飾:抓不到就維持空陣列,全站退回 `GenerativePoster` 的生成式 SVG。
 * `ensureLoaded` 因此**不會拋例外**,呼叫端不需要 try/catch,也不該 `await` 它之後
 * 才渲染列表 —— 那會讓一個純視覺的降級變成整頁卡住。
 *
 * ## 單一航班(single-flight)
 *
 * 列表頁、輪播、詳情頁都會呼叫 `ensureLoaded`,而它們在同一次進站幾乎同時掛載。
 * 不共用同一個 in-flight promise 的話,一次進站會打三次同樣的請求 —— 三次都會成功,
 * 所以沒有任何一步會報錯,只是白打兩次。
 *
 * ## 已知限制(計畫 §10)
 *
 * 只在啟動時抓一次。後台匯入新內容包之後,**已經開著的分頁要重整才會看到** ——
 * demo 場景可接受,不值得為它加輪詢。
 */
export const useArtistsStore = defineStore('artists', () => {
  const artists = ref<ArtistPublic[]>([])
  const loaded = ref(false)
  let inflight: Promise<void> | null = null

  /** 比對索引;藝人清單變動時才重算(每次比對重建的話,一頁 12 張卡就重建 12 次)。 */
  const index = computed(() => (loaded.value ? buildPosterIndex(artists.value) : EMPTY_POSTER_INDEX))

  async function ensureLoaded(): Promise<void> {
    if (loaded.value) return
    if (inflight) return inflight
    inflight = listArtists()
      .then((list) => {
        artists.value = list
      })
      .catch(() => {
        // 靜默:api/artists.ts 已設 silent,這裡也不改變任何可見狀態。
        // 空陣列 = 全站退回生成式海報,而那是一個完全可用的畫面。
        artists.value = []
      })
      .finally(() => {
        loaded.value = true
        inflight = null
      })
    return inflight
  }

  /**
   * 活動標題對應到的海報 URL;沒有命中回 null。
   * 清單還沒載入完時也回 null —— 載完之後 `index` 是 computed,畫面會自己更新。
   */
  function posterUrlFor(title: string): string | null {
    return matchPosterUrl(title, index.value)
  }

  return { artists, loaded, ensureLoaded, posterUrlFor }
})
