import { onMounted, reactive } from 'vue'
import { useArtistsStore } from '@/stores/artists'

/** 決定海報時需要的最小活動形狀(EventSummary 與 EventDetail 都相容)。 */
export interface PosterSubject {
  title: string
  coverImageUrl: string | null
}

/**
 * 活動 → 海報 URL 的三層解析(M8)。
 *
 * 1. `coverImageUrl` —— 活動自己指定的封面,最權威
 * 2. 標題比對到的藝人海報(`posterRegistry`)
 * 3. `null` → 呼叫端渲染 `GenerativePoster` 生成式 SVG
 *
 * ## 載入失敗以 **URL** 為鍵而不是活動 id
 *
 * 舊寫法記的是「這個活動的封面壞了」,於是封面一壞就直接跳到最後一層。改記 URL 之後,
 * 第 1 層壞掉會**往下掉到第 2 層**而不是掉到底 —— 一個 404 的 `coverImageUrl` 仍然
 * 看得到藝人海報。同一張藝人海報被多個活動共用時,也只需要壞一次就整批跳過。
 *
 * ## 為什麼在這裡 `ensureLoaded` 而不是在 App.vue
 *
 * 放在 App.vue 表示「不管去哪一頁都先打這支 API」,而訂單頁、登入頁根本不需要藝人清單。
 * 放在這個 composable 則是「誰要畫海報誰才載入」,而 store 的單一航班保證同一次進站
 * 三個 view 一起掛載時仍然只打一次。
 */
export function useEventPoster() {
  const artists = useArtistsStore()
  /** 載入失敗的圖片 URL;下一次解析時直接跳過這一層。 */
  const failedUrls = reactive(new Set<string>())

  onMounted(() => {
    // 不 await:海報是裝飾,清單不該等它。載完之後 store 的 index 是 computed,畫面自己更新。
    void artists.ensureLoaded()
  })

  function posterUrlFor(subject: PosterSubject | null | undefined): string | null {
    if (!subject) return null
    const candidates = [subject.coverImageUrl, artists.posterUrlFor(subject.title)]
    return candidates.find((url): url is string => !!url && !failedUrls.has(url)) ?? null
  }

  function markFailed(url: string | null | undefined): void {
    if (url) failedUrls.add(url)
  }

  return { posterUrlFor, markFailed }
}
