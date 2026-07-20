<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * 大型倒數看板(翻牌式大數字)。純呈現:由父層以 serverClock 校準後的 ms 傳入,
 * 本元件不自行計時,避免多個時間源。ms <= 0 時顯示歸零。
 *
 * 無障礙取捨:視覺翻牌數字每秒變動,若直接對整塊套 aria-live 會讓螢幕
 * 閱讀器每秒重複播報而難以使用,因此視覺區塊改為 aria-hidden,另外用
 * 一個畫面外的 aria-live="polite" 播報區,以較粗的粒度更新(平時每 30
 * 秒一次、剩餘 ≤60 秒時每 10 秒一次、歸零時立即播報一次)。
 */
const props = withDefaults(
  defineProps<{
    /** 剩餘毫秒(距開賣或距結束) */
    ms: number
    /** 看板標題,如「距開賣」「距結束」 */
    label?: string
    tone?: 'upcoming' | 'live'
  }>(),
  { label: '', tone: 'upcoming' },
)

const parts = computed(() => {
  const total = Math.max(0, Math.floor(props.ms / 1000))
  const days = Math.floor(total / 86400)
  const hours = Math.floor((total % 86400) / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  const cells = [
    { value: pad(hours), unit: '時' },
    { value: pad(minutes), unit: '分' },
    { value: pad(seconds), unit: '秒' },
  ]
  // 超過一天才顯示「天」欄,避免小時數過大
  if (days > 0) cells.unshift({ value: String(days), unit: '天' })
  return cells
})

const totalSeconds = computed(() => Math.max(0, Math.floor(props.ms / 1000)))

function announceOf(sec: number): string {
  const prefix = props.label ? `${props.label}` : '倒數'
  if (sec <= 0) return `${prefix}已到`
  const days = Math.floor(sec / 86400)
  const hours = Math.floor((sec % 86400) / 3600)
  const minutes = Math.floor((sec % 3600) / 60)
  const seconds = sec % 60
  const bits: string[] = []
  if (days > 0) bits.push(`${days} 天`)
  if (days > 0 || hours > 0) bits.push(`${hours} 時`)
  bits.push(`${minutes} 分`)
  bits.push(`${seconds} 秒`)
  return `${prefix}剩餘 ${bits.join('')}`
}

const announceText = ref('')
let lastAnnouncedSecond = -1

watch(
  totalSeconds,
  (sec) => {
    const dueToInterval =
      lastAnnouncedSecond === -1 ||
      (sec <= 60 ? lastAnnouncedSecond - sec >= 10 : lastAnnouncedSecond - sec >= 30)
    if (!dueToInterval && sec !== 0) return
    lastAnnouncedSecond = sec
    announceText.value = announceOf(sec)
  },
  { immediate: true },
)
</script>

<template>
  <div class="cd" :class="`cd--${tone}`">
    <span v-if="label" class="cd__label">{{ label }}</span>
    <div class="cd__cells tabular-nums" aria-hidden="true">
      <template v-for="(c, i) in parts" :key="c.unit">
        <div class="cd__cell">
          <span class="cd__value">{{ c.value }}</span>
          <span class="cd__unit">{{ c.unit }}</span>
        </div>
        <span v-if="i < parts.length - 1" class="cd__sep" aria-hidden="true">:</span>
      </template>
    </div>
    <span class="sr-only" role="status" aria-live="polite">{{ announceText }}</span>
  </div>
</template>

<style scoped>
.cd {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.cd__label {
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--text-secondary);
}

.cd__cells {
  display: flex;
  align-items: center;
  gap: 6px;
}

.cd__cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  min-width: 52px;
  padding: 8px 6px;
  border-radius: var(--radius-control);
  background: color-mix(in srgb, var(--brand-accent) 12%, var(--bg-surface));
  border: 1px solid color-mix(in srgb, var(--brand-accent) 28%, transparent);
}

.cd__value {
  font-size: 30px;
  font-weight: 800;
  line-height: 1;
  color: var(--brand-accent);
}

.cd__unit {
  font-size: 11px;
  color: var(--text-secondary);
}

.cd__sep {
  font-size: 24px;
  font-weight: 700;
  color: color-mix(in srgb, var(--brand-accent) 60%, transparent);
  transform: translateY(-6px);
}

/* live(距結束)以成功色系,和 upcoming 的 accent 區隔 */
.cd--live .cd__cell {
  background: color-mix(in srgb, var(--color-success) 12%, var(--bg-surface));
  border-color: color-mix(in srgb, var(--color-success) 30%, transparent);
}

.cd--live .cd__value {
  color: var(--color-success);
}

.cd--live .cd__sep {
  color: color-mix(in srgb, var(--color-success) 60%, transparent);
}
</style>
