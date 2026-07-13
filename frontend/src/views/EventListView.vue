<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listEvents } from '@/api/events'
import type { EventSummary } from '@/api/types'
import { formatDateTime } from '@/utils/datetime'

const router = useRouter()

const loading = ref(false)
const items = ref<EventSummary[]>([])
const page = ref(1)
const size = ref(10)
const total = ref(0)

async function load() {
  loading.value = true
  try {
    const res = await listEvents(page.value, size.value)
    items.value = res.items
    total.value = res.total
  } catch {
    // 攔截器已提示;列表維持現狀
  } finally {
    loading.value = false
  }
}

onMounted(load)

function onPageChange(p: number) {
  page.value = p
  load()
}
</script>

<template>
  <div v-loading="loading" class="event-list">
    <h2>活動列表</h2>

    <el-empty v-if="!loading && items.length === 0" description="目前沒有進行中的活動" />

    <el-card
      v-for="event in items"
      :key="event.id"
      class="event-card"
      shadow="hover"
      @click="router.push(`/events/${event.id}`)"
    >
      <div class="event-row">
        <div>
          <div class="event-title">{{ event.title }}</div>
          <div class="event-meta">
            <span v-if="event.venue">📍 {{ event.venue }}</span>
            <span>🕒 {{ formatDateTime(event.eventTime) }}</span>
          </div>
        </div>
        <el-button type="primary" plain>查看詳情</el-button>
      </div>
    </el-card>

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
.event-list h2 {
  margin: 8px 0 16px;
}

.event-card {
  margin-bottom: 12px;
  cursor: pointer;
}

.event-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.event-title {
  font-size: 17px;
  font-weight: 600;
  margin-bottom: 6px;
}

.event-meta {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.pagination {
  margin-top: 16px;
  justify-content: center;
}
</style>
