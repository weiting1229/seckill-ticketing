<script setup lang="ts">
import { computed } from 'vue'
import type { TicketTypeView } from '@/api/types'
import { availabilityOf, zoneColor } from '@/utils/ticketDisplay'

type Phase = 'offline' | 'upcoming' | 'live' | 'ended'

const props = defineProps<{
  ticket: TicketTypeView
  phase: Phase
  /** 搶購鈕是否停用(由父層依 phase / 售罄 / 進行中的購買計算) */
  disabled: boolean
  /** 此票種是否正在送出搶購 */
  loading: boolean
  /** 剛跨越開賣邊界時的一次性脈衝 */
  pulse: boolean
  /** per-ticket 倒數文字(父層以 serverClock 計算) */
  countdown?: string
}>()

const emit = defineEmits<{ buy: [] }>()

const availability = computed(() => availabilityOf(props.ticket.remaining, props.ticket.totalStock))
const isSold = computed(() => availability.value?.kind === 'sold')
const dotColor = computed(() => zoneColor(props.ticket.name + props.ticket.id))

const phaseMeta: Record<Phase, { label: string; cls: string }> = {
  offline: { label: '未開放', cls: 'is-offline' },
  upcoming: { label: '即將開賣', cls: 'is-upcoming' },
  live: { label: '開賣中', cls: 'is-live' },
  ended: { label: '已結束', cls: 'is-ended' },
}

const availClass: Record<string, string> = {
  plenty: 'av-plenty',
  hot: 'av-hot',
  low: 'av-low',
  sold: 'av-sold',
}

const buttonLabel = computed(() => {
  if (isSold.value) return '已售罄'
  if (props.phase === 'upcoming') return '即將開賣'
  if (props.phase === 'ended') return '已結束'
  if (props.phase === 'offline') return '未開放'
  return '立即搶購'
})

function priceText(price: string | number): string {
  return Number(price).toLocaleString()
}
</script>

<template>
  <div class="tt" :class="{ 'is-sold': isSold }">
    <span v-if="isSold" class="tt__stamp" aria-hidden="true">SOLD OUT</span>

    <div class="tt__main">
      <span class="tt__dot" :style="{ background: dotColor }" aria-hidden="true"></span>
      <div class="tt__info">
        <div class="tt__nameline">
          <span class="tt__name">{{ ticket.name }}</span>
          <span class="tt__phase" :class="phaseMeta[phase].cls">{{ phaseMeta[phase].label }}</span>
        </div>
        <div class="tt__sub">
          <span v-if="availability" class="tt__avail" :class="availClass[availability.kind]">
            {{ availability.label }}
          </span>
          <span v-if="countdown" class="tt__countdown tabular-nums">⏱ {{ countdown }}</span>
        </div>
      </div>
    </div>

    <div class="tt__action">
      <div class="tt__price">
        <span class="tt__currency">NT$</span>
        <span class="tt__amount tabular-nums">{{ priceText(ticket.price) }}</span>
      </div>
      <el-button
        class="tt__buy"
        :class="{ 'is-pulse': pulse }"
        type="primary"
        size="large"
        :disabled="disabled"
        :loading="loading"
        @click="emit('buy')"
      >
        {{ buttonLabel }}
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.tt {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px;
  border-radius: var(--radius-card);
  background: var(--bg-surface);
  border: 1px solid var(--hairline);
  overflow: hidden;
}

.tt__main {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.tt__dot {
  flex: none;
  width: 12px;
  height: 12px;
  border-radius: var(--radius-pill);
  box-shadow: 0 0 0 3px color-mix(in srgb, currentColor 0%, transparent);
}

.tt__info {
  min-width: 0;
}

.tt__nameline {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.tt__name {
  font-size: 17px;
  font-weight: 700;
}

.tt__phase {
  font-size: 12px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
}

.tt__phase.is-live {
  background: color-mix(in srgb, var(--color-success) 90%, black);
  color: #fff;
}

.tt__phase.is-upcoming {
  background: color-mix(in srgb, var(--color-warning) 16%, transparent);
  color: var(--color-warning);
}

.tt__phase.is-offline,
.tt__phase.is-ended {
  background: var(--el-fill-color);
  color: var(--text-secondary);
}

.tt__sub {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.tt__avail {
  font-size: 13px;
  font-weight: 600;
}

.av-plenty {
  color: var(--color-success);
}

.av-hot {
  color: var(--color-warning);
}

.av-low {
  color: var(--color-danger);
}

.av-sold {
  color: var(--text-secondary);
}

.tt__countdown {
  font-size: 13px;
  color: var(--text-secondary);
}

.tt__action {
  display: flex;
  align-items: center;
  gap: 16px;
  flex: none;
}

.tt__price {
  display: flex;
  align-items: baseline;
  gap: 3px;
  color: var(--text-primary);
}

.tt__currency {
  font-size: 13px;
  color: var(--text-secondary);
}

.tt__amount {
  font-size: 24px;
  font-weight: 800;
}

.tt__buy {
  min-width: 116px;
}

/* 售罄:整列灰化 + 斜向印章 */
.tt.is-sold {
  filter: grayscale(0.8);
  opacity: 0.72;
}

.tt__stamp {
  position: absolute;
  top: 14px;
  right: -34px;
  transform: rotate(12deg);
  background: var(--color-danger);
  color: #fff;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.14em;
  padding: 4px 40px;
  z-index: 1;
  pointer-events: none;
}

/* 歸零瞬間按鈕點亮的一次性脈衝 */
.tt__buy.is-pulse {
  animation: tt-pulse 0.6s ease-out 2;
}

@keyframes tt-pulse {
  0% {
    box-shadow: 0 0 0 0 color-mix(in srgb, var(--brand-primary) 70%, transparent);
  }
  100% {
    box-shadow: 0 0 0 14px color-mix(in srgb, var(--brand-primary) 0%, transparent);
  }
}

@media (max-width: 639px) {
  .tt {
    flex-direction: column;
    align-items: stretch;
  }

  .tt__action {
    justify-content: space-between;
  }

  .tt__buy {
    flex: 1;
  }
}
</style>
