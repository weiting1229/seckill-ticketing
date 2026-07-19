<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

/** 搶購結果卡資訊(非成功情境;成功會直接導向訂單頁,不經此卡)。 */
export interface WaitingResult {
  kind: 'timeout' | 'soldout' | 'duplicate' | 'fail'
  title: string
  message: string
  /** 是否提供「去我的訂單」引導 */
  viewOrders: boolean
}

const props = withDefaults(
  defineProps<{
    /** queuing:排隊處理中(不可關閉);result:顯示結果卡 */
    mode: 'queuing' | 'result'
    /** 進入排隊的時間戳(ms epoch),用於顯示已等待秒數 */
    startedAt?: number | null
    result?: WaitingResult | null
  }>(),
  { startedAt: null, result: null },
)

const emit = defineEmits<{ close: []; viewOrders: [] }>()

// ---- 已等待秒數(自帶 1s ticker,不依賴外部時鐘)----
const elapsed = ref(0)
let timer: number | undefined

function tick() {
  elapsed.value = props.startedAt ? Math.max(0, Math.floor((Date.now() - props.startedAt) / 1000)) : 0
}

// ---- 輪流提示文案 ----
const tips = [
  '正在為你搶票,請勿重新整理或返回',
  '系統依序處理排隊請求,通常幾秒內完成',
  '若久候,結果也會出現在「我的訂單」',
]
const tipIndex = ref(0)

watch(
  () => props.mode,
  (mode) => {
    if (mode === 'queuing') {
      tick()
      if (timer === undefined) {
        timer = window.setInterval(() => {
          tick()
          if (elapsed.value % 3 === 0) tipIndex.value = (tipIndex.value + 1) % tips.length
        }, 1000)
      }
    } else if (timer !== undefined) {
      window.clearInterval(timer)
      timer = undefined
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  if (timer !== undefined) window.clearInterval(timer)
})

const resultIcon = computed(() => {
  switch (props.result?.kind) {
    case 'soldout':
      return '🎫'
    case 'duplicate':
      return '✅'
    case 'timeout':
      return '⏳'
    default:
      return '⚠️'
  }
})
</script>

<template>
  <Teleport to="body">
    <div class="wr" role="dialog" aria-modal="true" :aria-label="mode === 'queuing' ? '排隊處理中' : '搶購結果'">
      <!-- 排隊中:品牌動畫 + 已等待 + 輪流提示(不可關閉) -->
      <div v-if="mode === 'queuing'" class="wr__panel">
        <div class="wr__viz" aria-hidden="true">
          <span class="wr__ring"></span>
          <div class="wr__eq">
            <span v-for="n in 5" :key="n" :style="{ animationDelay: `${n * 0.12}s` }"></span>
          </div>
        </div>
        <h2 class="wr__title">正在為你搶票</h2>
        <p class="wr__elapsed tabular-nums">已等待 {{ elapsed }} 秒</p>
        <p class="wr__tip">{{ tips[tipIndex] }}</p>
      </div>

      <!-- 結果卡 -->
      <div v-else-if="result" class="wr__panel wr__result">
        <div class="wr__icon" aria-hidden="true">{{ resultIcon }}</div>
        <h2 class="wr__title">{{ result.title }}</h2>
        <p class="wr__message">{{ result.message }}</p>
        <div class="wr__actions">
          <el-button v-if="result.viewOrders" type="primary" size="large" @click="emit('viewOrders')">
            去我的訂單
          </el-button>
          <el-button size="large" @click="emit('close')">
            {{ result.viewOrders ? '留在此頁' : '知道了' }}
          </el-button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.wr {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(5, 6, 12, 0.82);
  backdrop-filter: blur(8px);
}

.wr__panel {
  width: 100%;
  max-width: 360px;
  padding: 36px 28px;
  border-radius: var(--radius-card);
  background: var(--bg-surface);
  border: 1px solid var(--hairline);
  text-align: center;
  box-shadow: var(--shadow-elevate);
}

/* ---- 排隊視覺:脈衝圓環 + 等化器 ---- */
.wr__viz {
  position: relative;
  height: 72px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 20px;
}

.wr__ring {
  position: absolute;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  border: 2px solid var(--brand-accent);
  animation: wr-ring 1.6s ease-out infinite;
}

.wr__eq {
  display: flex;
  align-items: flex-end;
  gap: 5px;
  height: 34px;
}

.wr__eq span {
  width: 5px;
  height: 100%;
  border-radius: 3px;
  background: linear-gradient(var(--brand-primary), var(--brand-accent));
  transform-origin: bottom;
  animation: wr-eq 0.9s ease-in-out infinite;
}

.wr__title {
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 800;
}

.wr__elapsed {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 600;
  color: var(--brand-accent);
}

.wr__tip {
  margin: 0;
  min-height: 2.8em;
  font-size: 14px;
  line-height: 1.5;
  color: var(--text-secondary);
}

/* ---- 結果卡 ---- */
.wr__icon {
  font-size: 44px;
  margin-bottom: 10px;
}

.wr__message {
  margin: 0 0 22px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.wr__actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.wr__actions :deep(.el-button) {
  margin-left: 0;
}

@keyframes wr-ring {
  0% {
    transform: scale(0.7);
    opacity: 0.9;
  }
  100% {
    transform: scale(1.5);
    opacity: 0;
  }
}

@keyframes wr-eq {
  0%,
  100% {
    transform: scaleY(0.3);
  }
  50% {
    transform: scaleY(1);
  }
}

/* 尊重減少動態偏好:改為靜態呈現 */
@media (prefers-reduced-motion: reduce) {
  .wr__ring {
    animation: none;
    opacity: 0.4;
  }

  .wr__eq span {
    animation: none;
    transform: scaleY(0.6);
  }
}
</style>
