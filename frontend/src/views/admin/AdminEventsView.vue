<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { createEvent, deleteEvent, listAdminEvents, updateEvent } from '@/api/admin'
import type { EventAdmin, EventStatus, EventUpsertRequest } from '@/api/types'
import { formatDateTime } from '@/utils/datetime'
import GenerativePoster from '@/components/GenerativePoster.vue'

const router = useRouter()

const loading = ref(false)
const rows = ref<EventAdmin[]>([])
const page = ref(1)
const size = ref(10)
const total = ref(0)

const statusMeta: Record<EventStatus, { label: string; tag: 'info' | 'success' | 'warning' }> = {
  DRAFT: { label: '草稿', tag: 'info' },
  PUBLISHED: { label: '已發布', tag: 'success' },
  CLOSED: { label: '已結束', tag: 'warning' },
}

async function load() {
  loading.value = true
  try {
    const res = await listAdminEvents(page.value, size.value)
    rows.value = res.items
    total.value = res.total
  } finally {
    loading.value = false
  }
}

onMounted(load)

function onPageChange(p: number) {
  page.value = p
  load()
}

// ---------- 建立 / 編輯 ----------

const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<string | null>(null)
const submitting = ref(false)
const formRef = ref<FormInstance>()

// eventTime 以 Date 綁定,送出前轉 ISO(UTC)
const form = reactive<{
  title: string
  description: string
  venue: string
  coverImageUrl: string
  eventTime: Date | null
  status: EventStatus
}>({
  title: '',
  description: '',
  venue: '',
  coverImageUrl: '',
  eventTime: null,
  status: 'DRAFT',
})

const rules: FormRules = {
  title: [
    { required: true, message: '請輸入活動標題', trigger: 'blur' },
    { max: 200, message: '長度不可超過 200 字', trigger: 'blur' },
  ],
  eventTime: [{ required: true, message: '請選擇演出時間', trigger: 'change' }],
  coverImageUrl: [
    { max: 500, message: '長度不可超過 500 字', trigger: 'blur' },
    { pattern: /^(https?:\/\/.+)?$/, message: '必須以 http:// 或 https:// 開頭', trigger: 'blur' },
  ],
}

// ---------- 封面即時預覽 ----------
const coverLoadError = ref(false)
// URL 改變時重置載入失敗狀態,讓新網址重新嘗試
watch(
  () => form.coverImageUrl,
  () => {
    coverLoadError.value = false
  },
)
const showCoverImage = computed(() => form.coverImageUrl.trim() !== '' && !coverLoadError.value)
const previewEventTime = computed(() => (form.eventTime ? form.eventTime.toISOString() : ''))

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  Object.assign(form, {
    title: '',
    description: '',
    venue: '',
    coverImageUrl: '',
    eventTime: null,
    status: 'DRAFT',
  })
  dialogVisible.value = true
}

function openEdit(row: EventAdmin) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  Object.assign(form, {
    title: row.title,
    description: row.description ?? '',
    venue: row.venue ?? '',
    coverImageUrl: row.coverImageUrl ?? '',
    eventTime: new Date(row.eventTime),
    status: row.status,
  })
  dialogVisible.value = true
}

async function onSubmit() {
  if (!formRef.value || !(await formRef.value.validate().catch(() => false))) return
  const body: EventUpsertRequest = {
    title: form.title,
    description: form.description || null,
    venue: form.venue || null,
    coverImageUrl: form.coverImageUrl.trim() || null,
    eventTime: form.eventTime!.toISOString(),
  }
  submitting.value = true
  try {
    if (dialogMode.value === 'create') {
      await createEvent(body)
      ElMessage.success('活動已建立(草稿)')
    } else {
      await updateEvent(editingId.value!, { ...body, status: form.status })
      ElMessage.success('活動已更新')
    }
    dialogVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function onDelete(row: EventAdmin) {
  try {
    await ElMessageBox.confirm(`確認刪除活動「${row.title}」嗎?`, '刪除活動', {
      type: 'warning',
      confirmButtonText: '刪除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteEvent(row.id)
    ElMessage.success('已刪除')
    load()
  } catch {
    // 2003 活動含票種不可刪除 → 攔截器已提示
  }
}

function goTicketTypes(row: EventAdmin) {
  router.push({ path: '/admin/ticket-types', query: { eventId: row.id } })
}
</script>

<template>
  <div v-loading="loading" class="admin-events">
    <div class="page-head">
      <h2>活動管理</h2>
      <el-button type="primary" @click="openCreate">＋ 新增活動</el-button>
    </div>

    <el-table :data="rows" row-key="id">
      <el-table-column label="ID" prop="id" min-width="170" />
      <el-table-column label="標題" prop="title" min-width="180" />
      <el-table-column label="場地" prop="venue" min-width="140" />
      <el-table-column label="演出時間" width="180">
        <template #default="{ row }">{{ formatDateTime(row.eventTime) }}</template>
      </el-table-column>
      <el-table-column label="狀態" width="100">
        <template #default="{ row }">
          <el-tag :type="statusMeta[row.status as EventStatus].tag">
            {{ statusMeta[row.status as EventStatus].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="goTicketTypes(row)">票種</el-button>
          <el-button size="small" type="primary" @click="openEdit(row)">編輯</el-button>
          <el-button size="small" type="danger" @click="onDelete(row)">刪除</el-button>
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

    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新增活動' : '編輯活動'"
      width="520"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="標題" prop="title">
          <el-input v-model="form.title" maxlength="200" placeholder="活動標題" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="5000" />
        </el-form-item>
        <el-form-item label="場地">
          <el-input v-model="form.venue" maxlength="200" placeholder="例:台北小巨蛋" />
        </el-form-item>
        <el-form-item label="封面圖 URL" prop="coverImageUrl">
          <el-input
            v-model="form.coverImageUrl"
            maxlength="500"
            placeholder="https://… 留空則自動生成海報"
            clearable
          />
          <div class="field-hint">留空時前台顯示自動生成的海報;填入後即時預覽,所見即所得。</div>
        </el-form-item>
        <el-form-item label="預覽">
          <div class="cover-preview">
            <img
              v-if="showCoverImage"
              :src="form.coverImageUrl"
              class="cover-preview__img"
              alt="封面預覽"
              @error="coverLoadError = true"
            />
            <GenerativePoster
              v-else
              :title="form.title || '活動標題'"
              :venue="form.venue"
              :event-time="previewEventTime"
              variant="poster"
            />
          </div>
          <div v-if="coverLoadError" class="field-hint field-hint--warn">
            圖片載入失敗,前台將改用自動生成海報。
          </div>
        </el-form-item>
        <el-form-item label="演出時間" prop="eventTime">
          <el-date-picker
            v-model="form.eventTime"
            type="datetime"
            placeholder="選擇演出時間"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item v-if="dialogMode === 'edit'" label="狀態">
          <el-select v-model="form.status">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已發布" value="PUBLISHED" />
            <el-option label="已結束" value="CLOSED" />
          </el-select>
          <div class="field-hint">狀態僅可單向:草稿 → 已發布 → 已結束</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">確定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 8px 0 16px;
}

.page-head h2 {
  margin: 0;
}

.pagination {
  margin-top: 16px;
  justify-content: center;
}

.field-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 4px;
}

.field-hint--warn {
  color: var(--el-color-warning);
}

.cover-preview {
  width: 180px;
  border-radius: var(--radius-card);
  overflow: hidden;
  border: 1px solid var(--el-border-color-light);
}

.cover-preview__img {
  display: block;
  width: 100%;
  aspect-ratio: 3 / 4;
  object-fit: cover;
}
</style>
