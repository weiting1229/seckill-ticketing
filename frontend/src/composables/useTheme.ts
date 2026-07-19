import { computed, readonly, ref } from 'vue'

/**
 * 全站主題(深色為預設,可切換淺色)。
 * - localStorage 持久化,key = 'theme'
 * - 切換時對 <html> 掛 'dark' / 'light' class,並同步 color-scheme
 * - 為避免首次載入閃爍,index.html 內另有 inline script 會在 Vue 掛載前
 *   先讀 localStorage 並套上 class;本 composable 以該結果為初始值,兩者需一致。
 * - 不引入 @vueuse,狀態為 module 層單例。
 */

export type ThemeName = 'dark' | 'light'

const STORAGE_KEY = 'theme'
const DEFAULT_THEME: ThemeName = 'dark'

function readStoredTheme(): ThemeName {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved === 'dark' || saved === 'light') return saved
  } catch {
    // localStorage 不可用(隱私模式等)時退回預設
  }
  // 尚未持久化過時,沿用 index.html inline script 已套上的 class
  if (document.documentElement.classList.contains('light')) return 'light'
  return DEFAULT_THEME
}

// module 層單例,確保 header 切換鈕與其他頁面共享同一狀態
const theme = ref<ThemeName>(readStoredTheme())

function applyTheme(next: ThemeName) {
  const root = document.documentElement
  root.classList.toggle('dark', next === 'dark')
  root.classList.toggle('light', next === 'light')
  root.style.colorScheme = next
  try {
    localStorage.setItem(STORAGE_KEY, next)
  } catch {
    // 無法持久化時仍套用當前 session 的主題
  }
}

// 初始化:確保 DOM class 與狀態一致(涵蓋 localStorage 為空的情況)
applyTheme(theme.value)

export function useTheme() {
  const isDark = computed(() => theme.value === 'dark')

  function setTheme(next: ThemeName) {
    theme.value = next
    applyTheme(next)
  }

  function toggleTheme() {
    setTheme(theme.value === 'dark' ? 'light' : 'dark')
  }

  return {
    theme: readonly(theme),
    isDark,
    setTheme,
    toggleTheme,
  }
}
