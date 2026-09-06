import { request } from './http'
import type { ArtistPublic } from './types'

/**
 * 公開藝人清單(匿名可讀,全量無分頁)。
 *
 * `silent: true`:海報只是裝飾,抓不到就整站退回生成式 SVG。彈一個紅色錯誤訊息
 * 對使用者沒有任何可行動的資訊,只會讓一個純視覺的降級看起來像故障。
 */
export function listArtists(): Promise<ArtistPublic[]> {
  return request<ArtistPublic[]>({ method: 'GET', url: '/artists', silent: true })
}
