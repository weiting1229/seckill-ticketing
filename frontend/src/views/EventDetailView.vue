<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getEvent } from '@/api/events'
import { BizError } from '@/api/http'
import type { EventDetail, TicketTypeView } from '@/api/types'
import { useSeckillFlow } from '@/composables/useSeckillFlow'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime, formatDuration } from '@/utils/datetime'
import { calibrate, serverNow } from '@/utils/serverClock'
import { availabilityOf } from '@/utils/ticketDisplay'
import GenerativePoster from '@/components/GenerativePoster.vue'
import CountdownBoard from '@/components/CountdownBoard.vue'
import TicketTypeCard from '@/components/TicketTypeCard.vue'
import WaitingRoom, { type WaitingResult } from '@/components/WaitingRoom.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const notFound = ref(false)
const detail = ref<EventDetail | null>(null)

/**
 * 倒數一律用「後端 server time 校準後的現在」(utils/serverClock):
 * 每次載入詳情以 serverTime 重新校準,避免使用者本機時鐘偏差。
 */
const now = ref(serverNow())
let timer: number | undefined

async function load() {
  loading.value = true
  try {
    const res = await getEvent(String(route.params.id))
    detail.value = res
    calibrate(res.serverTime)
    now.value = serverNow()
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
    now.value = serverNow()
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

/** 剛由 upcoming 跨入 live 的票種 id,用於按鈕一次性脈衝(短暫) */
const justOpenedId = ref<string | null>(null)
let pulseTimer: number | undefined

/** 任一票種跨越開賣/結束邊界時重新載入,更新即時剩餘並重新校準時鐘。 */
const phaseSignature = computed(() =>
  (detail.value?.ticketTypes ?? []).map((t) => phaseOf(t)).join(','),
)
watch(phaseSignature, (next, prev) => {
  if (!prev || next === prev) return
  // 在重載前偵測 upcoming → live 的票種,觸發按鈕點亮脈衝
  const prevPhases = prev.split(',')
  const nextPhases = next.split(',')
  const tts = detail.value?.ticketTypes ?? []
  const opened = tts.find((t, i) => prevPhases[i] === 'upcoming' && nextPhases[i] === 'live')
  if (opened) {
    justOpenedId.value = opened.id
    if (pulseTimer !== undefined) window.clearTimeout(pulseTimer)
    pulseTimer = window.setTimeout(() => {
      justOpenedId.value = null
    }, 1400)
  }
  // 現有重載邏輯不動:跨邊界即重新拉取最新剩餘與 serverTime
  load()
})

onBeforeUnmount(() => {
  if (pulseTimer !== undefined) window.clearTimeout(pulseTimer)
})

function countdownText(t: TicketTypeView): string {
  const phase = phaseOf(t)
  if (phase === 'upcoming') return `距開賣 ${formatDuration(Date.parse(t.seckillStart) - now.value)}`
  if (phase === 'live') return `距結束 ${formatDuration(Date.parse(t.seckillEnd) - now.value)}`
  return ''
}

function buyDisabled(t: TicketTypeView): boolean {
  return phaseOf(t) !== 'live' || t.remaining === 0 || buyingTicketTypeId.value !== null
}

/** 看板 / sticky 面板聚焦的「主要票種」:優先進行中(最快結束),否則最快開賣者。 */
const primary = computed<{ ticket: TicketTypeView; phase: Phase } | null>(() => {
  const tts = detail.value?.ticketTypes ?? []
  const live = tts
    .filter((t) => phaseOf(t) === 'live')
    .sort((a, b) => Date.parse(a.seckillEnd) - Date.parse(b.seckillEnd))
  if (live[0]) return { ticket: live[0], phase: 'live' }
  const upcoming = tts
    .filter((t) => phaseOf(t) === 'upcoming')
    .sort((a, b) => Date.parse(a.seckillStart) - Date.parse(b.seckillStart))
  if (upcoming[0]) return { ticket: upcoming[0], phase: 'upcoming' }
  return null
})

const boardMs = computed(() => {
  if (!primary.value) return 0
  const t = primary.value.ticket
  return primary.value.phase === 'live'
    ? Date.parse(t.seckillEnd) - now.value
    : Date.parse(t.seckillStart) - now.value
})

const hasCover = computed(() => !!detail.value?.coverImageUrl)
const coverFailed = ref(false)
watch(
  () => detail.value?.coverImageUrl,
  () => {
    coverFailed.value = false
  },
)
const showCover = computed(() => hasCover.value && !coverFailed.value)

// ---------- 搶購流程(領 token → purchase → 輪詢結果) ----------

const { phase: buyPhase, buyingTicketTypeId, buy, queueStartedAt } = useSeckillFlow()

/** 搶購結果卡(非成功情境);成功直接導向訂單頁,不經此卡。 */
const waitingResult = ref<WaitingResult | null>(null)
const waitingOpen = computed(() => buyPhase.value === 'queuing' || waitingResult.value !== null)
const waitingMode = computed<'queuing' | 'result'>(() =>
  buyPhase.value === 'queuing' ? 'queuing' : 'result',
)

function goOrdersFromResult() {
  waitingResult.value = null
  router.push('/orders')
}

async function onBuy(t: TicketTypeView) {
  if (!auth.isLoggedIn) {
    ElMessage.info('請先登入才能搶購')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  waitingResult.value = null
  try {
    const outcome = await buy(t.id)
    switch (outcome.status) {
      case 'SUCCESS':
        ElMessage.success('搶購成功!請於 15 分鐘內完成付款')
        router.push(`/orders/${outcome.orderId}`)
        break
      case 'FAIL':
        waitingResult.value = {
          kind: 'fail',
          title: '搶購失敗',
          message: outcome.reason ?? '這次沒能搶到,別灰心,再看看其他票種。',
          viewOrders: false,
        }
        load()
        break
      case 'TIMEOUT':
        waitingResult.value = {
          kind: 'timeout',
          title: '仍在處理中',
          message: '系統還在處理你的請求。結果會出現在「我的訂單」,請前往查看,不需重新搶購。',
          viewOrders: true,
        }
        break
      case 'CANCELLED':
        break
    }
  } catch (e) {
    handleBuyError(e)
  }
}

/** token / purchase 階段的業務錯誤客製提示(API 皆 silent)。 */
function handleBuyError(e: unknown) {
  if (!(e instanceof BizError)) {
    ElMessage.error('網路異常,請稍後再試')
    return
  }
  switch (e.code) {
    case 3004: // 限流(429):暫時性、可重試,維持輕量提示
      ElMessage.warning('請求過於頻繁,請稍候幾秒再試')
      break
    case 3005: // 售罄:終局結果 → 結果卡
      waitingResult.value = {
        kind: 'soldout',
        title: '此票種已售罄',
        message: '手速再快一點!看看這場的其他票種,或探索別的活動。',
        viewOrders: false,
      }
      load()
      break
    case 3006: // 重複購買:終局結果 → 結果卡並引導至訂單
      waitingResult.value = {
        kind: 'duplicate',
        title: '你已購買過此票種',
        message: '每人限購一張。可到「我的訂單」查看並完成付款。',
        viewOrders: true,
      }
      break
    case 3007: // token 無效/已用:暫時性、可重試,維持輕量提示
      ElMessage.warning('搶購憑證已失效,請再點一次「立即搶購」')
      break
    default:
      // 3001/3002/3003/3008/3009 等:顯示後端訊息並重載最新狀態
      ElMessage.error(e.message)
      load()
  }
}

const primarySold = computed(() =>
  primary.value ? availabilityOf(primary.value.ticket.remaining, primary.value.ticket.totalStock)?.kind === 'sold' : false,
)
</script>

<template>
  <div v-loading="loading && !detail" class="detail">
    <el-result v-if="notFound" icon="warning" title="活動不存在或未發布">
      <template #extra>
        <el-button type="primary" @click="router.push('/events')">回活動列表</el-button>
      </template>
    </el-result>

    <template v-else-if="detail">
      <!-- Hero 沉浸區 -->
      <section class="hero">
        <div class="hero__bg">
          <img
            v-if="showCover"
            :src="detail.coverImageUrl!"
            :alt="`${detail.title} 主視覺`"
            class="hero__img"
            @error="coverFailed = true"
          />
          <GenerativePoster v-else :title="detail.title" variant="banner" :show-label="false" />
        </div>
        <div class="hero__scrim"></div>
        <div class="hero__content">
          <button class="hero__back" type="button" @click="router.push('/events')">← 活動列表</button>
          <h1 class="hero__title">{{ detail.title }}</h1>
          <div class="hero__meta">
            <span v-if="detail.venue">📍 {{ detail.venue }}</span>
            <span class="tabular-nums">🕒 {{ formatDateTime(detail.eventTime) }}</span>
          </div>
        </div>
      </section>

      <div class="detail__body">
        <div class="detail__main">
          <section v-if="detail.description" class="about">
            <h2 class="section-title">關於活動</h2>
            <p class="about__text">{{ detail.description }}</p>
          </section>

          <section class="tickets">
            <h2 class="section-title">票種</h2>
            <el-empty v-if="detail.ticketTypes.length === 0" description="尚未設定票種" />
            <div v-else class="tickets__list">
              <TicketTypeCard
                v-for="t in detail.ticketTypes"
                :key="t.id"
                :ticket="t"
                :phase="phaseOf(t)"
                :disabled="buyDisabled(t)"
                :loading="buyingTicketTypeId === t.id"
                :pulse="justOpenedId === t.id"
                :countdown="countdownText(t)"
                @buy="onBuy(t)"
              />
            </div>
          </section>
        </div>

        <!-- Sticky 購票面板(桌面右側 / 手機底部固定) -->
        <aside class="detail__aside">
          <div class="buy-panel">
            <template v-if="primary">
              <CountdownBoard
                class="buy-panel__board"
                :ms="boardMs"
                :label="primary.phase === 'live' ? '距結束' : '距開賣'"
                :tone="primary.phase === 'live' ? 'live' : 'upcoming'"
              />
              <div class="buy-panel__row">
                <div class="buy-panel__ticket">
                  <span class="buy-panel__ticket-name">{{ primary.ticket.name }}</span>
                  <span class="buy-panel__ticket-price tabular-nums">
                    NT$ {{ Number(primary.ticket.price).toLocaleString() }}
                  </span>
                </div>
                <el-button
                  class="buy-panel__cta"
                  :class="{ 'is-pulse': justOpenedId === primary.ticket.id }"
                  type="primary"
                  size="large"
                  :disabled="buyDisabled(primary.ticket)"
                  :loading="buyingTicketTypeId === primary.ticket.id"
                  @click="onBuy(primary.ticket)"
                >
                  {{ primary.phase === 'live' ? (primarySold ? '已售罄' : '立即搶購') : '即將開賣' }}
                </el-button>
              </div>
            </template>
            <div v-else class="buy-panel__idle">
              <p class="buy-panel__idle-text">目前沒有可購買的票種</p>
            </div>
          </div>
        </aside>
      </div>

      <WaitingRoom
        v-if="waitingOpen"
        :mode="waitingMode"
        :started-at="queueStartedAt"
        :result="waitingResult"
        @close="waitingResult = null"
        @view-orders="goOrdersFromResult"
      />
    </template>
  </div>
</template>

<style scoped>
.detail {
  /* 手機底部固定購票列的高度預留,避免遮住內容 */
  padding-bottom: 0;
}

/* ---------- Hero ---------- */
.hero {
  position: relative;
  margin: -20px -20px 24px;
  min-height: clamp(220px, 38vw, 420px);
  display: flex;
  align-items: flex-end;
  overflow: hidden;
}

.hero__bg {
  position: absolute;
  inset: 0;
}

.hero__bg :deep(.poster) {
  height: 100%;
  aspect-ratio: auto;
  border-radius: 0;
}

.hero__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.hero__scrim {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    to top,
    rgba(5, 6, 12, 0.94) 0%,
    rgba(5, 6, 12, 0.55) 40%,
    rgba(5, 6, 12, 0.1) 100%
  );
}

.hero__content {
  position: relative;
  width: 100%;
  padding: 24px 20px;
  color: #f8fafc;
}

.hero__back {
  border: none;
  background: transparent;
  color: rgba(248, 250, 252, 0.85);
  font-size: 14px;
  padding: 0;
  margin-bottom: 12px;
  cursor: pointer;
}

.hero__back:hover {
  color: #fff;
}

.hero__title {
  margin: 0 0 10px;
  font-size: clamp(24px, 5vw, 40px);
  font-weight: 800;
  line-height: 1.1;
  letter-spacing: -0.01em;
  text-shadow: 0 2px 16px rgba(0, 0, 0, 0.5);
}

.hero__meta {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  font-size: 14px;
  color: rgba(248, 250, 252, 0.88);
}

/* ---------- 內容雙欄 ---------- */
.detail__body {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 28px;
  align-items: start;
}

.section-title {
  margin: 0 0 14px;
  font-size: 20px;
  font-weight: 700;
}

.about {
  margin-bottom: 28px;
}

.about__text {
  margin: 0;
  color: var(--text-secondary);
  line-height: 1.7;
  white-space: pre-wrap;
}

.tickets__list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* ---------- Sticky 購票面板 ---------- */
.detail__aside {
  position: sticky;
  top: calc(var(--header-height) + 16px);
}

.buy-panel {
  padding: 18px;
  border-radius: var(--radius-card);
  background: var(--bg-surface);
  border: 1px solid var(--hairline);
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.buy-panel__row {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.buy-panel__ticket {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.buy-panel__ticket-name {
  font-size: 14px;
  color: var(--text-secondary);
}

.buy-panel__ticket-price {
  font-size: 22px;
  font-weight: 800;
}

.buy-panel__cta {
  width: 100%;
}

.buy-panel__idle-text {
  margin: 0;
  color: var(--text-secondary);
  font-size: 14px;
  text-align: center;
}

/* 脈衝(與 TicketTypeCard 一致) */
.buy-panel__cta.is-pulse {
  animation: cta-pulse 0.6s ease-out 2;
}

@keyframes cta-pulse {
  0% {
    box-shadow: 0 0 0 0 color-mix(in srgb, var(--brand-primary) 70%, transparent);
  }
  100% {
    box-shadow: 0 0 0 16px color-mix(in srgb, var(--brand-primary) 0%, transparent);
  }
}

/* ---------- 手機:購票面板改底部固定列 ---------- */
@media (max-width: 899px) {
  .detail__body {
    grid-template-columns: 1fr;
    gap: 20px;
  }

  .detail {
    padding-bottom: 92px;
  }

  .detail__aside {
    position: fixed;
    inset: auto 0 0 0;
    top: auto;
    z-index: 50;
  }

  .buy-panel {
    flex-direction: row;
    align-items: center;
    gap: 12px;
    border-radius: 0;
    border-left: none;
    border-right: none;
    border-bottom: none;
    padding: 12px 16px;
    background: var(--header-bg);
    backdrop-filter: blur(14px) saturate(160%);
  }

  /* 底部列空間有限:隱藏大看板,倒數改由票種列各自呈現 */
  .buy-panel__board {
    display: none;
  }

  .buy-panel__row {
    flex: 1;
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
  }

  .buy-panel__ticket {
    flex-direction: column;
    align-items: flex-start;
    gap: 0;
  }

  .buy-panel__cta {
    width: auto;
    min-width: 128px;
  }
}
</style>
