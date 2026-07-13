<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createTicketType,
  deleteTicketType,
  listAdminEvents,
  listTicketTypesByEvent,
  reconcileTicketType,
  updateTicketType,
  warmupTicketType,
} from '@/api/admin'
import type {
  EventAdmin,
  ReconcileResponse,
  TicketTypeAdmin,
  TicketTypeStatus,
  TicketTypeUpsertRequest,
} from '@/api/types'
import { formatDateTime } from '@/utils/datetime'

const route = useRoute()
const router = useRouter()

const events = ref<EventAdmin[]>([])
const selectedEventId = ref<string | null>(null)

const loading = ref(false)
const rows = ref<TicketTypeAdmin[]>([])

const statusMeta: Record<TicketTypeStatus, { label: string; tag: 'info' | 'success' }> = {
  OFFLINE: { label: '未上線', tag: 'info' },
  ONLINE: { label: '已上線', tag: 'success' },
}

async function loadEvents() {
  const res = await listAdminEvents(1, 50)
  events.value = res.items
  // 初始選定:優先 query.eventId,否則第一筆
  const queryId = typeof route.query.eventId === 'string' ? route.query.eventId : null
  if (queryId && res.items.some((e) => e.id === queryId)) {
    selectedEventId.value = queryId
  } else if (!selectedEventId.value && res.items[0]) {
    selectedEventId.value = res.items[0].id
  }
}

async function loadTicketTypes() {
  if (!selectedEventId.value) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    rows.value = await listTicketTypesByEvent(selectedEventId.value)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadEvents()
  await loadTicketTypes()
})

watch(selectedEventId, (id) => {
  // 同步到 URL 方便分享/重整,並重載票種
  router.replace({ query: id ? { eventId: id } : {} })
  loadTicketTypes()
})

// ---------- 建立 / 編輯 ----------

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<string | null>(null)
const submitting = ref(false)
const formRef = ref<FormInstance>()

const form = reactive<{
  name: string
  price: number
  totalStock: number
  seckillStart: Date | null
  seckillEnd: Date | null
}>({
  name: '',
  price: 0,
  totalStock: 0,
  seckillStart: null,
  seckillEnd: null,
})

const rules: FormRules = {
  name: [
    { required: true, message: '請輸入票種名稱', trigger: 'blur' },
    { max: 100, message: '長度不可超過 100 字', trigger: 'blur' },
  ],
  price: [{ required: true, message: '請輸入價格', trigger: 'blur' }],
  totalStock: [{ required: true, message: '請輸入庫存', trigger: 'blur' }],
  seckillStart: [{ required: true, message: '請選擇開賣時間', trigger: 'change' }],
  seckillEnd: [
    { required: true, message: '請選擇結束時間', trigger: 'change' },
    {
      validator: (_r: unknown, _v: unknown, cb: (e?: Error) => void) => {
        if (form.seckillStart && form.seckillEnd && form.seckillEnd <= form.seckillStart) {
          cb(new Error('結束時間須晚於開賣時間'))
        } else cb()
      },
      trigger: 'change',
    },
  ],
}

function openCreate() {
  if (!selectedEventId.value) {
    ElMessage.warning('請先選擇活動')
    return
  }
  dialogMode.value = 'create'
  editingId.value = null
  Object.assign(form, { name: '', price: 0, totalStock: 0, seckillStart: null, seckillEnd: null })
  dialogVisible.value = true
}

function openEdit(row: TicketTypeAdmin) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    price: Number(row.price),
    totalStock: row.totalStock,
    seckillStart: new Date(row.seckillStart),
    seckillEnd: new Date(row.seckillEnd),
  })
  dialogVisible.value = true
}

async function onSubmit() {
  if (!formRef.value || !(await formRef.value.validate().catch(() => false))) return
  const base: TicketTypeUpsertRequest = {
    name: form.name,
    price: form.price,
    totalStock: form.totalStock,
    seckillStart: form.seckillStart!.toISOString(),
    seckillEnd: form.seckillEnd!.toISOString(),
  }
  submitting.value = true
  try {
    if (dialogMode.value === 'create') {
      await createTicketType({ ...base, eventId: selectedEventId.value! })
      ElMessage.success('票種已建立(未上線)')
    } else {
      await updateTicketType(editingId.value!, base)
      ElMessage.success('票種已更新')
    }
    dialogVisible.value = false
    loadTicketTypes()
  } finally {
    submitting.value = false
  }
}

async function onDelete(row: TicketTypeAdmin) {
  try {
    await ElMessageBox.confirm(`確認刪除票種「${row.name}」嗎?`, '刪除票種', {
      type: 'warning',
      confirmButtonText: '刪除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteTicketType(row.id)
    ElMessage.success('已刪除')
    loadTicketTypes()
  } catch {
    // 2005 已上線不可刪 → 攔截器已提示
  }
}

async function onWarmup(row: TicketTypeAdmin) {
  try {
    await ElMessageBox.confirm(
      `預熱「${row.name}」:上線並將庫存 ${row.stockRemaining} 寫入 Redis。上線後不可再修改。`,
      '庫存預熱',
      { type: 'info', confirmButtonText: '確認預熱', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const res = await warmupTicketType(row.id)
  ElMessage.success(
    res.alreadyWarmed
      ? `已是上線狀態,Redis 現值 ${res.redisStockRemaining}(未覆蓋)`
      : `預熱成功,Redis 庫存 ${res.redisStockRemaining}`,
  )
  loadTicketTypes()
}

// ---------- 對帳 ----------

const reconcileVisible = ref(false)
const reconcileLoading = ref(false)
const reconcileData = ref<ReconcileResponse | null>(null)
const reconcileName = ref('')

async function onReconcile(row: TicketTypeAdmin) {
  reconcileName.value = row.name
  reconcileData.value = null
  reconcileVisible.value = true
  reconcileLoading.value = true
  try {
    reconcileData.value = await reconcileTicketType(row.id)
  } finally {
    reconcileLoading.value = false
  }
}
</script>

<template>
  <div class="admin-ticket-types">
    <div class="page-head">
      <h2>票種管理</h2>
      <div class="head-actions">
        <el-select
          v-model="selectedEventId"
          placeholder="選擇活動"
          filterable
          style="width: 280px"
        >
          <el-option
            v-for="e in events"
            :key="e.id"
            :label="`${e.title}(${e.status}）`"
            :value="e.id"
          />
        </el-select>
        <el-button type="primary" :disabled="!selectedEventId" @click="openCreate">
          ＋ 新增票種
        </el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="rows" row-key="id">
      <el-table-column label="ID" prop="id" min-width="170" />
      <el-table-column label="名稱" prop="name" min-width="140" />
      <el-table-column label="價格" width="110">
        <template #default="{ row }">NT$ {{ Number(row.price).toLocaleString() }}</template>
      </el-table-column>
      <el-table-column label="庫存(剩餘/總)" width="130">
        <template #default="{ row }">{{ row.stockRemaining }} / {{ row.totalStock }}</template>
      </el-table-column>
      <el-table-column label="開賣時間" width="170">
        <template #default="{ row }">{{ formatDateTime(row.seckillStart) }}</template>
      </el-table-column>
      <el-table-column label="結束時間" width="170">
        <template #default="{ row }">{{ formatDateTime(row.seckillEnd) }}</template>
      </el-table-column>
      <el-table-column label="狀態" width="90">
        <template #default="{ row }">
          <el-tag :type="statusMeta[row.status as TicketTypeStatus].tag">
            {{ statusMeta[row.status as TicketTypeStatus].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button
            size="small"
            type="primary"
            :disabled="row.status === 'ONLINE'"
            @click="openEdit(row)"
          >
            編輯
          </el-button>
          <el-button
            size="small"
            type="danger"
            :disabled="row.status === 'ONLINE'"
            @click="onDelete(row)"
          >
            刪除
          </el-button>
          <el-button
            size="small"
            type="success"
            :disabled="row.status === 'ONLINE'"
            @click="onWarmup(row)"
          >
            預熱
          </el-button>
          <el-button size="small" @click="onReconcile(row)">對帳</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && selectedEventId && rows.length === 0" description="此活動尚無票種" />

    <!-- 建立 / 編輯 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新增票種' : '編輯票種'"
      width="520"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="名稱" prop="name">
          <el-input v-model="form.name" maxlength="100" placeholder="例:搖滾區" />
        </el-form-item>
        <el-form-item label="價格" prop="price">
          <el-input-number v-model="form.price" :min="0" :precision="2" :step="100" />
        </el-form-item>
        <el-form-item label="總庫存" prop="totalStock">
          <el-input-number v-model="form.totalStock" :min="0" :step="10" />
        </el-form-item>
        <el-form-item label="開賣時間" prop="seckillStart">
          <el-date-picker
            v-model="form.seckillStart"
            type="datetime"
            placeholder="選擇開賣時間"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="結束時間" prop="seckillEnd">
          <el-date-picker
            v-model="form.seckillEnd"
            type="datetime"
            placeholder="選擇結束時間"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">確定</el-button>
      </template>
    </el-dialog>

    <!-- 對帳結果 -->
    <el-dialog v-model="reconcileVisible" :title="`對帳:${reconcileName}`" width="460">
      <div v-loading="reconcileLoading">
        <el-result
          v-if="reconcileData"
          :icon="reconcileData.consistent ? 'success' : 'warning'"
          :title="reconcileData.consistent ? '三方一致' : '偵測到不一致'"
        />
        <el-descriptions v-if="reconcileData" :column="1" border>
          <el-descriptions-item label="總庫存">{{ reconcileData.totalStock }}</el-descriptions-item>
          <el-descriptions-item label="DB 剩餘">{{ reconcileData.dbStockRemaining }}</el-descriptions-item>
          <el-descriptions-item label="Redis 剩餘">
            {{ reconcileData.redisStockRemaining ?? '(缺值)' }}
          </el-descriptions-item>
          <el-descriptions-item label="有效訂單數">{{ reconcileData.validOrderCount }}</el-descriptions-item>
          <el-descriptions-item label="流水淨值">{{ reconcileData.stockLogNetDelta }}</el-descriptions-item>
          <el-descriptions-item label="DB 售出量">{{ reconcileData.soldByDb }}</el-descriptions-item>
          <el-descriptions-item label="一致性">
            <el-tag :type="reconcileData.consistent ? 'success' : 'danger'">
              {{ reconcileData.consistent ? 'consistent' : 'inconsistent' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 8px 0 16px;
  gap: 16px;
  flex-wrap: wrap;
}

.page-head h2 {
  margin: 0;
}

.head-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}
</style>
