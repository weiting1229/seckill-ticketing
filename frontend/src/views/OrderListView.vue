<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listOrders } from '@/api/orders'
import type { OrderResponse, OrderStatus } from '@/api/types'
import { formatDateTime, formatDuration } from '@/utils/datetime'
import { resolveOrderNames } from '@/utils/eventCache'
import { serverNow } from '@/utils/serverClock'

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
  <div v-loading="loading" class="order-list">
    <h2>我的訂單</h2>

    <el-empty v-if="!loading && rows.length === 0" description="還沒有訂單,去搶一張吧!">
      <el-button type="primary" @click="router.push('/events')">去看活動</el-button>
    </el-empty>

    <el-table v-else :data="rows" row-key="id" @row-click="(row: OrderResponse) => router.push(`/orders/${row.id}`)">
      <el-table-column label="訂單號" prop="id" min-width="170" />
      <el-table-column label="活動 / 票種" min-width="200">
        <template #default="{ row }">
          <div>{{ names.get(row.id)?.eventTitle ?? '…' }}</div>
          <div class="ticket-name">{{ names.get(row.id)?.ticketName ?? '' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="金額" width="110">
        <template #default="{ row }">NT$ {{ Number(row.price).toLocaleString() }}</template>
      </el-table-column>
      <el-table-column label="狀態" width="100">
        <template #default="{ row }">
          <el-tag :type="statusMeta[row.status as OrderStatus].tag">
            {{ statusMeta[row.status as OrderStatus].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="建立時間" width="170">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="付款期限" width="150">
        <template #default="{ row }">
          <span v-if="row.status === 'PENDING_PAYMENT'" class="pay-countdown">
            ⏱ {{ payCountdown(row) }}
          </span>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'PENDING_PAYMENT'"
            type="primary"
            size="small"
            @click.stop="router.push(`/orders/${row.id}`)"
          >
            前往付款
          </el-button>
          <el-button v-else size="small" @click.stop="router.push(`/orders/${row.id}`)">
            查看
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="total > 0"
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
.order-list h2 {
  margin: 8px 0 16px;
}

.order-list :deep(.el-table__row) {
  cursor: pointer;
}

.ticket-name {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.pay-countdown {
  color: var(--el-color-warning);
  font-variant-numeric: tabular-nums;
}

.pagination {
  margin-top: 16px;
  justify-content: center;
}
</style>
