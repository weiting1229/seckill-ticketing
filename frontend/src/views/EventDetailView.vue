<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getEvent } from '@/api/events'
import type { EventDetail, TicketTypeView } from '@/api/types'
import { formatDateTime, formatDuration } from '@/utils/datetime'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const notFound = ref(false)
const detail = ref<EventDetail | null>(null)

/**
 * 倒數一律用「後端 server time 校準後的現在」:
 * offset = serverTime - 本機時間(每次載入詳情時重新校準),
 * now = 本機時間 + offset,避免使用者本機時鐘偏差。
 */
const serverOffset = ref(0)
const now = ref(Date.now())
let timer: number | undefined

async function load() {
  loading.value = true
  try {
    const res = await getEvent(String(route.params.id))
    detail.value = res
    serverOffset.value = new Date(res.serverTime).getTime() - Date.now()
    now.value = Date.now() + serverOffset.value
    notFound.value = false
  } catch {
    // 2001 活動不存在(getEvent 為 silent,由本頁呈現空狀態)
    notFound.value = true
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  timer = window.setInterval(() => {
    now.value = Date.now() + serverOffset.value
  }, 1000)
})

onBeforeUnmount(() => {
  if (timer !== undefined) window.clearInterval(timer)
})

type Phase = 'offline' | 'upcoming' | 'live' | 'ended'

function phaseOf(t: TicketTypeView): Phase {
  if (t.status !== 'ONLINE') return 'offline'
  if (now.value < Date.parse(t.seckillStart)) return 'upcoming'
  if (now.value < Date.parse(t.seckillEnd)) return 'live'
  return 'ended'
}

/** 任一票種跨越開賣/結束邊界時重新載入,更新即時剩餘並重新校準時鐘。 */
const phaseSignature = computed(() =>
  (detail.value?.ticketTypes ?? []).map((t) => phaseOf(t)).join(','),
)
watch(phaseSignature, (next, prev) => {
  if (prev && next !== prev) load()
})

function countdownText(t: TicketTypeView): string {
  const phase = phaseOf(t)
  if (phase === 'upcoming') return `距開賣 ${formatDuration(Date.parse(t.seckillStart) - now.value)}`
  if (phase === 'live') return `距結束 ${formatDuration(Date.parse(t.seckillEnd) - now.value)}`
  return ''
}

const phaseLabel: Record<Phase, string> = {
  offline: '未開放',
  upcoming: '即將開賣',
  live: '開賣中',
  ended: '已結束',
}

const phaseTagType: Record<Phase, 'info' | 'warning' | 'success' | 'danger'> = {
  offline: 'info',
  upcoming: 'warning',
  live: 'success',
  ended: 'danger',
}

function formatPrice(price: string | number): string {
  return `NT$ ${Number(price).toLocaleString()}`
}

function onBuy(t: TicketTypeView) {
  // 搶購流程於 M5-4 接上(領 token → purchase → 輪詢結果)
  ElMessage.info(`搶購流程尚未接上(票種:${t.name})`)
}
</script>

<template>
  <div v-loading="loading" class="event-detail">
    <el-result v-if="notFound" icon="warning" title="活動不存在或未發布">
      <template #extra>
        <el-button type="primary" @click="router.push('/events')">回活動列表</el-button>
      </template>
    </el-result>

    <template v-else-if="detail">
      <el-page-header content="活動詳情" @back="router.push('/events')" />

      <el-card class="info-card">
        <h2 class="event-title">{{ detail.title }}</h2>
        <p v-if="detail.description" class="event-desc">{{ detail.description }}</p>
        <div class="event-meta">
          <span v-if="detail.venue">📍 {{ detail.venue }}</span>
          <span>🕒 演出時間:{{ formatDateTime(detail.eventTime) }}</span>
        </div>
      </el-card>

      <h3>票種</h3>
      <el-empty v-if="detail.ticketTypes.length === 0" description="尚未設定票種" />

      <el-card v-for="t in detail.ticketTypes" :key="t.id" class="ticket-card">
        <div class="ticket-row">
          <div class="ticket-info">
            <div class="ticket-name">
              {{ t.name }}
              <el-tag :type="phaseTagType[phaseOf(t)]" size="small">{{ phaseLabel[phaseOf(t)] }}</el-tag>
            </div>
            <div class="ticket-meta">
              <span class="ticket-price">{{ formatPrice(t.price) }}</span>
              <span>剩餘:{{ t.remaining ?? '—' }} / {{ t.totalStock }}</span>
            </div>
            <div class="ticket-meta">
              <span>開賣:{{ formatDateTime(t.seckillStart) }} ~ {{ formatDateTime(t.seckillEnd) }}</span>
            </div>
            <div v-if="countdownText(t)" class="countdown" :class="{ live: phaseOf(t) === 'live' }">
              ⏱ {{ countdownText(t) }}
            </div>
          </div>
          <el-button
            type="danger"
            size="large"
            :disabled="phaseOf(t) !== 'live' || t.remaining === 0"
            @click="onBuy(t)"
          >
            {{ phaseOf(t) === 'live' && t.remaining === 0 ? '已售罄' : '立即搶購' }}
          </el-button>
        </div>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.info-card {
  margin: 16px 0;
}

.event-title {
  margin: 0 0 8px;
}

.event-desc {
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
}

.event-meta {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.ticket-card {
  margin-bottom: 12px;
}

.ticket-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.ticket-name {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.ticket-meta {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  display: flex;
  gap: 16px;
  margin-bottom: 4px;
}

.ticket-price {
  color: var(--el-color-danger);
  font-weight: 600;
}

.countdown {
  font-variant-numeric: tabular-nums;
  color: var(--el-color-warning);
  font-size: 14px;
}

.countdown.live {
  color: var(--el-color-success);
}
</style>
