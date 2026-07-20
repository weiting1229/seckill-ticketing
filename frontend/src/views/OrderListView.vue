<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listOrders } from '@/api/orders'
import type { OrderResponse, OrderStatus } from '@/api/types'
import { formatDateTime, formatDuration } from '@/utils/datetime'
import { resolveOrderNames } from '@/utils/eventCache'
import { serverNow } from '@/utils/serverClock'
import GenerativePoster from '@/components/GenerativePoster.vue'

const router = useRouter()

const loading = ref(false)
const rows = ref<OrderResponse[]>([])
const page = ref(1)
const size = ref(10)
const total = ref(0)

/** orderId → 人類可讀名稱(OrderResponse 只有 ID,補打公開活動 API 解析)。 */
const names = reactive(new Map<string, { eventTitle: string; ticketName: string }>())

const now = ref(serverNow())
let timer: number | undefined

async function load() {
  loading.value = true
  try {
    const res = await listOrders(page.value, size.value)
    rows.value = res.items
    total.value = res.total
    for (const order of res.items) {
      resolveOrderNames(order.eventId, order.ticketTypeId).then((n) => names.set(order.id, n))
    }
  } catch {
    // 攔截器已提示
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

function onPageChange(p: number) {
  page.value = p
  load()
}

export type StatusMeta = { label: string; tag: 'warning' | 'success' | 'info' | 'danger' }
const statusMeta: Record<OrderStatus, StatusMeta> = {
  PENDING_PAYMENT: { label: '待付款', tag: 'warning' },
  PAID: { label: '已付款', tag: 'success' },
  CANCELLED: { label: '已取消', tag: 'info' },
  EXPIRED: { label: '已逾期', tag: 'danger' },
}

/** 待付款的剩餘時間;已過期但後端尚未取消時顯示「逾期處理中」。 */
function payCountdown(order: OrderResponse): string {
  const remain = Date.parse(order.expireAt) - now.value
  return remain > 0 ? formatDuration(remain) : '逾期處理中…'
}
</script>

<template>
  <div v-loading="loading" class="orders">
    <h2 class="orders__title">我的訂單</h2>

    <el-empty v-if="!loading && rows.length === 0" description="還沒有訂單,去搶一張吧!">
      <el-button type="primary" @click="router.push('/events')">去看活動</el-button>
    </el-empty>

    <div v-else class="orders__list">
      <div
        v-for="row in rows"
        :key="row.id"
        class="ord"
        role="link"
        tabindex="0"
        @click="router.push(`/orders/${row.id}`)"
        @keydown.enter="router.push(`/orders/${row.id}`)"
      >
        <div class="ord__poster">
          <GenerativePoster
            :title="names.get(row.id)?.eventTitle || '活動'"
            variant="poster"
            :show-label="false"
          />
        </div>
        <div class="ord__body">
          <div class="ord__head">
            <h3 class="ord__event">{{ names.get(row.id)?.eventTitle ?? '…' }}</h3>
            <span class="ord__badge" :data-tag="statusMeta[row.status as OrderStatus].tag">
              {{ statusMeta[row.status as OrderStatus].label }}
            </span>
          </div>
          <p class="ord__ticket">{{ names.get(row.id)?.ticketName ?? '' }}</p>
          <div class="ord__meta">
            <span class="ord__price tabular-nums">NT$ {{ Number(row.price).toLocaleString() }}</span>
            <span class="ord__date tabular-nums">{{ formatDateTime(row.createdAt) }}</span>
          </div>
          <div v-if="row.status === 'PENDING_PAYMENT'" class="ord__pay tabular-nums">
            ⏱ 付款剩餘 {{ payCountdown(row) }}
          </div>
        </div>
        <div class="ord__action">
          <el-button
            v-if="row.status === 'PENDING_PAYMENT'"
            type="primary"
            @click.stop="router.push(`/orders/${row.id}`)"
          >
            前往付款
          </el-button>
          <el-button v-else @click.stop="router.push(`/orders/${row.id}`)">查看</el-button>
        </div>
      </div>
    </div>

    <el-pagination
      v-if="total > size"
      class="pagination"
      layout="prev, pager, next, total"
      :current-page="page"
      :page-size="size"
      :total="total"
      @current-change="onPageChange"
    />
  </div>
</template>

<style scoped>
.orders__title {
  margin: 8px 0 16px;
}

.orders__list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ord {
  display: flex;
  align-items: stretch;
  gap: 14px;
  padding: 12px;
  border-radius: var(--radius-card);
  background: var(--bg-surface);
  border: 1px solid var(--hairline);
  cursor: pointer;
  transition:
    transform var(--transition-base),
    border-color var(--transition-base),
    box-shadow var(--transition-base);
}

.ord:hover {
  transform: translateY(-2px);
  border-color: color-mix(in srgb, var(--brand-primary) 45%, transparent);
  box-shadow: var(--shadow-elevate);
}

.ord:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
}

.ord__poster {
  flex: none;
  width: 66px;
  border-radius: var(--radius-control);
  overflow: hidden;
}

.ord__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ord__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.ord__event {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ord__ticket {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.ord__meta {
  display: flex;
  align-items: baseline;
  gap: 14px;
  margin-top: 2px;
}

.ord__price {
  font-size: 16px;
  font-weight: 800;
}

.ord__date {
  font-size: 12px;
  color: var(--text-secondary);
}

.ord__pay {
  margin-top: 2px;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-warning);
}

.ord__action {
  flex: none;
  display: flex;
  align-items: center;
}

/* 狀態徽章(與 §2.2 對齊) */
.ord__badge {
  flex: none;
  font-size: 12px;
  font-weight: 700;
  padding: 3px 9px;
  border-radius: var(--radius-pill);
  white-space: nowrap;
}

.ord__badge[data-tag='warning'] {
  background: color-mix(in srgb, var(--color-warning) 18%, transparent);
  color: var(--color-warning);
}

.ord__badge[data-tag='success'] {
  background: color-mix(in srgb, var(--color-success) 18%, transparent);
  color: var(--color-success);
}

.ord__badge[data-tag='danger'] {
  background: color-mix(in srgb, var(--color-danger) 18%, transparent);
  color: var(--color-danger);
}

.ord__badge[data-tag='info'] {
  background: var(--el-fill-color);
  color: var(--text-secondary);
}

.pagination {
  margin-top: 20px;
  justify-content: center;
}

@media (max-width: 639px) {
  .ord {
    flex-wrap: wrap;
  }

  .ord__action {
    width: 100%;
  }

  .ord__action :deep(.el-button) {
    width: 100%;
    min-height: 44px;
  }
}
</style>
