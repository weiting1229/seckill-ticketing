/** 後端一律回 UTC(ISO-8601);顯示時區交給瀏覽器 locale。 */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-TW', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
}

/** 毫秒差轉倒數文字:「N 天 HH:MM:SS」或「HH:MM:SS」;負值視為 0。 */
export function formatDuration(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000))
  const days = Math.floor(totalSeconds / 86400)
  const hh = String(Math.floor((totalSeconds % 86400) / 3600)).padStart(2, '0')
  const mm = String(Math.floor((totalSeconds % 3600) / 60)).padStart(2, '0')
  const ss = String(totalSeconds % 60).padStart(2, '0')
  return days > 0 ? `${days} 天 ${hh}:${mm}:${ss}` : `${hh}:${mm}:${ss}`
}
