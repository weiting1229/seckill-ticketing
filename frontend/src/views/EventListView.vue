<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { listEvents } from '@/api/events'
import type { EventSummary } from '@/api/types'
import { formatDateTime } from '@/utils/datetime'
import GenerativePoster from '@/components/GenerativePoster.vue'

const loading = ref(false)
const items = ref<EventSummary[]>([])
const page = ref(1)
const size = ref(12)
const total = ref(0)
const keyword = ref('')
// 首次載入前不顯示「查無結果」空狀態,避免閃現
const loadedOnce = ref(false)

// 封面圖載入失敗的活動 id → 該卡改用生成式海報
const failedCovers = reactive(new Set<string>())

async function load() {
  loading.value = true
  try {
    const res = await listEvents(page.value, size.value, keyword.value)
    items.value = res.items
    total.value = res.total
  } catch {
    // 攔截器已提示;列表維持現狀
  } finally {
    loading.value = false
    loadedOnce.value = true
  }
}

onMounted(load)

// 搜尋:debounce 300ms,變更即回到第一頁(不引入 @vueuse)
let debounceTimer: ReturnType<typeof setTimeout> | undefined
watch(keyword, () => {
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    page.value = 1
    load()
  }, 300)
})

function onSearchEnter() {
  clearTimeout(debounceTimer)
  page.value = 1
  load()
}

const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches

function onPageChange(p: number) {
  page.value = p
  load()
  window.scrollTo({ top: 0, behavior: prefersReducedMotion ? 'auto' : 'smooth' })
}

function showCover(event: EventSummary): boolean {
  return !!event.coverImageUrl && !failedCovers.has(event.id)
}

function onCoverError(id: string) {
  failedCovers.add(id)
}

/** 以演出時間相對現在推導卡片徽章(列表僅回 PUBLISHED,故以日期分「即將登場 / 已落幕」)。 */
function statusOf(event: EventSummary): { label: string; kind: 'upcoming' | 'past' } {
  const upcoming = new Date(event.eventTime).getTime() > Date.now()
  return upcoming ? { label: '即將登場', kind: 'upcoming' } : { label: '已落幕', kind: 'past' }
}

function dateBadge(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return d.toLocaleDateString('zh-TW', { month: '2-digit', day: '2-digit' })
}

const isSearching = computed(() => keyword.value.trim() !== '')
const skeletonCount = 8
</script>

<template>
  <div class="events">
    <!-- Hero:一行標語 + 漸層背景 + 搜尋,不搶活動風頭 -->
    <section class="hero">
      <div class="hero__inner">
        <h1 class="hero__title">探索即將開演的現場</h1>
        <p class="hero__sub">精選演唱會與活動,不錯過每一個開賣時刻。</p>
        <el-input
          v-model="keyword"
          class="hero__search"
          size="large"
          placeholder="搜尋活動名稱"
          clearable
          @keyup.enter="onSearchEnter"
        >
          <template #prefix>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="11" cy="11" r="7" stroke="currentColor" stroke-width="2" />
              <path d="m20 20-3-3" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
            </svg>
          </template>
        </el-input>
      </div>
    </section>

    <!-- 載入中:海報形狀 skeleton -->
    <div v-if="loading" class="grid" aria-hidden="true">
      <div v-for="n in skeletonCount" :key="n" class="card card--skeleton">
        <div class="card__poster sk"></div>
        <div class="card__body">
          <div class="sk sk-line sk-line--title"></div>
          <div class="sk sk-line sk-line--sub"></div>
        </div>
      </div>
    </div>

    <!-- 空狀態:生成式風格插畫(純 SVG,無真實圖片) -->
    <div v-else-if="loadedOnce && items.length === 0" class="empty">
      <svg class="empty__art" viewBox="0 0 120 120" fill="none" aria-hidden="true">
        <circle cx="60" cy="52" r="26" stroke="var(--brand-primary)" stroke-width="2" opacity="0.7" />
        <circle cx="60" cy="52" r="16" fill="var(--brand-accent)" opacity="0.18" />
        <circle cx="42" cy="34" r="1.6" fill="var(--brand-accent)" />
        <circle cx="82" cy="40" r="1.6" fill="var(--brand-accent)" />
        <circle cx="86" cy="66" r="1.6" fill="var(--brand-primary)" />
        <circle cx="36" cy="62" r="1.6" fill="var(--brand-primary)" />
        <path d="M20 96h80" stroke="var(--text-secondary)" stroke-width="2" stroke-linecap="round" opacity="0.5" />
      </svg>
      <p class="empty__text">
        {{ isSearching ? `找不到符合「${keyword.trim()}」的活動` : '目前沒有進行中的活動' }}
      </p>
    </div>

    <!-- 海報網格 -->
    <div v-else class="grid">
      <RouterLink
        v-for="event in items"
        :key="event.id"
        :to="`/events/${event.id}`"
        class="card"
        :aria-label="event.title"
      >
        <div class="card__poster">
          <img
            v-if="showCover(event)"
            :src="event.coverImageUrl!"
            :alt="`${event.title} 封面`"
            class="card__img"
            loading="lazy"
            @error="onCoverError(event.id)"
          />
          <GenerativePoster
            v-else
            :title="event.title"
            variant="poster"
            :show-label="false"
          />
          <span v-if="dateBadge(event.eventTime)" class="badge badge--date tabular-nums">
            {{ dateBadge(event.eventTime) }}
          </span>
          <span class="badge badge--status" :data-kind="statusOf(event).kind">
            {{ statusOf(event).label }}
          </span>
        </div>
        <div class="card__body">
          <h3 class="card__title">{{ event.title }}</h3>
          <p v-if="event.venue" class="card__meta">📍 {{ event.venue }}</p>
          <p class="card__meta tabular-nums">🕒 {{ formatDateTime(event.eventTime) }}</p>
        </div>
      </RouterLink>
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
.events {
  padding-bottom: 8px;
}

/* ---------- Hero ---------- */
.hero {
  margin: -20px -20px 24px;
  padding: 40px 20px 32px;
  background:
    radial-gradient(120% 100% at 50% 0%, color-mix(in srgb, var(--brand-primary) 20%, transparent), transparent 70%),
    linear-gradient(180deg, color-mix(in srgb, var(--brand-accent) 8%, transparent), transparent);
  border-bottom: 1px solid var(--hairline);
}

.hero__inner {
  max-width: 640px;
  margin: 0 auto;
  text-align: center;
}

.hero__title {
  margin: 0;
  font-size: clamp(24px, 5vw, 32px);
  font-weight: 800;
  letter-spacing: -0.01em;
}

.hero__sub {
  margin: 8px 0 20px;
  color: var(--text-secondary);
  font-size: 15px;
}

.hero__search {
  max-width: 520px;
}

.hero__search :deep(.el-input__prefix) {
  color: var(--text-secondary);
  display: flex;
  align-items: center;
}

/* ---------- 網格 ---------- */
.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}

@media (max-width: 1023px) {
  .grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 16px;
  }
}

@media (max-width: 639px) {
  .grid {
    grid-template-columns: 1fr;
  }
}

/* ---------- 卡片 ---------- */
.card {
  display: block;
  text-decoration: none;
  color: inherit;
  border-radius: var(--radius-card);
  overflow: hidden;
  background: var(--bg-surface);
  border: 1px solid var(--hairline);
  transition:
    transform var(--transition-base),
    border-color var(--transition-base),
    box-shadow var(--transition-base);
}

.card:hover {
  transform: translateY(-4px);
  border-color: color-mix(in srgb, var(--brand-primary) 50%, transparent);
  box-shadow: var(--shadow-elevate);
}

.card:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
}

.card__poster {
  position: relative;
  aspect-ratio: 3 / 4;
  background: var(--bg-base);
}

.card__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.badge {
  position: absolute;
  top: 10px;
  font-size: 12px;
  font-weight: 700;
  padding: 4px 8px;
  border-radius: var(--radius-pill);
  backdrop-filter: blur(6px);
}

.badge--date {
  left: 10px;
  background: rgba(5, 6, 12, 0.62);
  color: #f8fafc;
  letter-spacing: 0.03em;
}

.badge--status {
  right: 10px;
}

.badge--status[data-kind='upcoming'] {
  background: color-mix(in srgb, var(--color-success) 88%, black);
  color: #fff;
}

.badge--status[data-kind='past'] {
  background: rgba(5, 6, 12, 0.62);
  color: var(--text-secondary);
}

.card__body {
  padding: 12px 14px 14px;
}

.card__title {
  margin: 0 0 6px;
  font-size: 16px;
  font-weight: 700;
  line-height: 1.3;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card__meta {
  margin: 2px 0 0;
  font-size: 13px;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ---------- Skeleton ---------- */
.sk {
  background: linear-gradient(
    100deg,
    var(--el-fill-color) 30%,
    var(--el-fill-color-light) 50%,
    var(--el-fill-color) 70%
  );
  background-size: 200% 100%;
  animation: sk-shimmer 1.3s ease-in-out infinite;
}

.card--skeleton {
  pointer-events: none;
}

.card__poster.sk {
  border-radius: 0;
}

.sk-line {
  height: 12px;
  border-radius: 6px;
  margin-top: 8px;
}

.sk-line--title {
  width: 80%;
}

.sk-line--sub {
  width: 55%;
}

@keyframes sk-shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}

/* ---------- 空狀態 ---------- */
.empty {
  padding: 64px 16px;
  text-align: center;
}

.empty__art {
  width: 120px;
  height: 120px;
}

.empty__text {
  margin: 12px 0 0;
  color: var(--text-secondary);
  font-size: 15px;
}

.pagination {
  margin-top: 24px;
  justify-content: center;
}
</style>
