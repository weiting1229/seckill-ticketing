<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { BizError } from '@/api/http'
import { getOrder, payOrder } from '@/api/orders'
import type { OrderResponse, OrderStatus } from '@/api/types'
import { formatDateTime, formatDuration } from '@/utils/datetime'
import { resolveOrderNames } from '@/utils/eventCache'
import { serverNow } from '@/utils/serverClock'

const route = useRoute()
const router = useRouter()

const orderId = String(route.params.id)
const loading = ref(false)
const notFound = ref(false)
const order = ref<OrderResponse | null>(null)
const names = ref<{ eventTitle: string; ticketName: string } | null>(null)

const now = ref(serverNow())
let timer: number | undefined
/** 付款期限跨越 0 之後,輪詢幾次等後端(延遲訊息/兜底排程)把狀態翻成 EXPIRED。 */
let expiryReloadsLeft = 6

async function load() {
  loading.value = true
  try {
    const res = await getOrder(orderId)
    order.value = res
    notFound.value = false
    resolveOrderNames(res.eventId, res.ticketTypeId).then((n) => (names.value = n))
  } catch {
    // 4001:不存在或非本人(silent),以空狀態呈現
    notFound.value = true
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  timer = window.setInterval(() => {
    now.value = serverNow()
    // 逾期後每 5 秒重載一次(至多 6 次),等後端取消生效
    if (
      order.value?.status === 'PENDING_PAYMENT' &&
      payRemainMs.value <= 0 &&
      expiryReloadsLeft > 0 &&
      now.value % 5000 < 1000
    ) {
      expiryReloadsLeft--
      load()
    }
  }, 1000)
})

onBeforeUnmount(() => {
  if (timer !== undefined) window.clearInterval(timer)
})

const payRemainMs = computed(() =>
  order.value ? Date.parse(order.value.expireAt) - now.value : 0,
)

const statusMeta: Record<OrderStatus, { label: string; tag: 'warning' | 'success' | 'info' | 'danger' }> = {
  PENDING_PAYMENT: { label: '待付款', tag: 'warning' },
  PAID: { label: '已付款', tag: 'success' },
  CANCELLED: { label: '已取消', tag: 'info' },
  EXPIRED: { label: '已逾期', tag: 'danger' },
}

const paying = ref(false)

async function onPay() {
  try {
    await ElMessageBox.confirm('確認要模擬支付這筆訂單嗎?', '模擬支付', {
      confirmButtonText: '確認付款',
      cancelButtonText: '取消',
      type: 'info',
    })
  } catch {
    return // 使用者取消
  }
  paying.value = true
  try {
    order.value = await payOrder(orderId)
    ElMessage.success('付款成功!')
  } catch (e) {
    // 4001/4002 攔截器已提示;4002(狀態已變,如剛被逾時取消)重載最新狀態
    if (e instanceof BizError && e.code === 4002) load()
  } finally {
    paying.value = false
  }
}
</script>

<template>
  <div v-loading="loading" class="order-detail">
    <el-result v-if="notFound" icon="warning" title="訂單不存在">
      <template #extra>
        <el-button type="primary" @click="router.push('/orders')">回我的訂單</el-button>
      </template>
    </el-result>

    <template v-else-if="order">
      <el-page-header content="訂單詳情" @back="router.push('/orders')" />

      <el-card class="detail-card">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="訂單號">{{ order.id }}</el-descriptions-item>
          <el-descriptions-item label="狀態">
            <el-tag :type="statusMeta[order.status].tag">{{ statusMeta[order.status].label }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="活動">{{ names?.eventTitle ?? '…' }}</el-descriptions-item>
          <el-descriptions-item label="票種">{{ names?.ticketName ?? '…' }}</el-descriptions-item>
          <el-descriptions-item label="金額">
            NT$ {{ Number(order.price).toLocaleString() }}
          </el-descriptions-item>
          <el-descriptions-item label="建立時間">{{ formatDateTime(order.createdAt) }}</el-descriptions-item>
          <el-descriptions-item v-if="order.status === 'PAID'" label="付款時間">
            {{ formatDateTime(order.paidAt) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="order.status === 'PENDING_PAYMENT'" label="付款期限">
            {{ formatDateTime(order.expireAt) }}
            <span v-if="payRemainMs > 0" class="pay-countdown">
              (剩餘 {{ formatDuration(payRemainMs) }})
            </span>
            <span v-else class="pay-expired">(已逾期,系統取消處理中…)</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="actions">
          <el-button
            v-if="order.status === 'PENDING_PAYMENT' && payRemainMs > 0"
            type="primary"
            size="large"
            :loading="paying"
            @click="onPay"
          >
            模擬支付
          </el-button>
          <el-button size="large" @click="router.push('/orders')">回我的訂單</el-button>
        </div>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.detail-card {
  margin-top: 16px;
}

.actions {
  margin-top: 20px;
  display: flex;
  gap: 12px;
}

.pay-countdown {
  color: var(--el-color-warning);
  font-variant-numeric: tabular-nums;
}

.pay-expired {
  color: var(--el-color-danger);
}
</style>
