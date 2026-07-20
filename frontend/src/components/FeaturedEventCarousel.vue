<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { EventSummary } from '@/api/types'
import { nextCarouselIndex, previousCarouselIndex } from '@/utils/carousel'
import GenerativePoster from './GenerativePoster.vue'

const props = defineProps<{ items: EventSummary[] }>()
const current = ref(0)
const paused = ref(false)
const failed = ref(new Set<string>())
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
let timer: ReturnType<typeof setInterval> | undefined

const active = computed(() => props.items[current.value])
const hasControls = computed(() => props.items.length > 1)

function go(index: number) {
  current.value = Math.max(0, Math.min(index, props.items.length - 1))
}

function next() {
  current.value = nextCarouselIndex(current.value, props.items.length)
}

function previous() {
  current.value = previousCarouselIndex(current.value, props.items.length)
}

function restartTimer() {
  clearInterval(timer)
  timer = undefined
  if (!hasControls.value || paused.value || reducedMotion.matches) return
  timer = setInterval(next, 5000)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'ArrowLeft') previous()
  if (event.key === 'ArrowRight') next()
}

function markFailed(id: string) {
  failed.value = new Set(failed.value).add(id)
}

watch([() => props.items.length, paused], () => {
  if (current.value >= props.items.length) current.value = 0
  restartTimer()
})

onMounted(restartTimer)
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <section
    v-if="active"
    class="featured"
    aria-roledescription="carousel"
    aria-label="精選活動"
    tabindex="0"
    @mouseenter="paused = true"
    @mouseleave="paused = false"
    @focusin="paused = true"
    @focusout="paused = false"
    @pointerdown="paused = true"
    @pointerup="paused = false"
    @pointercancel="paused = false"
    @keydown="onKeydown"
  >
    <div class="featured__viewport">
      <RouterLink
        v-for="(event, index) in items"
        :key="event.id"
        :to="`/events/${event.id}`"
        class="featured__slide"
        :class="{
          'is-active': index === current,
          'is-before': hasControls && index !== current && index === previousCarouselIndex(current, items.length),
          'is-after': hasControls && index !== current && index === nextCarouselIndex(current, items.length),
        }"
        :aria-hidden="index !== current"
        :tabindex="index === current ? 0 : -1"
      >
        <img
          v-if="event.coverImageUrl && !failed.has(event.id)"
          :src="event.coverImageUrl"
          :alt="`${event.title} 活動圖片`"
          class="featured__image"
          @error="markFailed(event.id)"
        />
        <GenerativePoster
          v-else
          :title="event.title"
          :venue="event.venue ?? ''"
          :event-time="event.eventTime"
          variant="landscape"
        />
        <span class="featured__caption">{{ event.title }}</span>
      </RouterLink>

      <button
        v-if="hasControls"
        type="button"
        class="featured__arrow featured__arrow--previous"
        aria-label="上一個活動"
        @click="previous"
      >
        ‹
      </button>
      <button
        v-if="hasControls"
        type="button"
        class="featured__arrow featured__arrow--next"
        aria-label="下一個活動"
        @click="next"
      >
        ›
      </button>
    </div>

    <div v-if="hasControls" class="featured__dots" aria-label="選擇輪播活動">
      <button
        v-for="(event, index) in items"
        :key="event.id"
        type="button"
        :class="{ 'is-active': index === current }"
        :aria-label="`前往第 ${index + 1} 個活動：${event.title}`"
        :aria-current="index === current ? 'true' : undefined"
        @click="go(index)"
      ></button>
    </div>
  </section>
</template>

<style scoped>
.featured {
  margin: -20px -20px 0;
  padding: 20px 0 12px;
  overflow: hidden;
  background: radial-gradient(70% 100% at 50% 0%, color-mix(in srgb, var(--brand-primary) 18%, transparent), transparent);
}

.featured__viewport {
  position: relative;
  width: min(72vw, 840px);
  aspect-ratio: 3 / 2;
  margin: 0 auto;
}

.featured__slide {
  position: absolute;
  inset: 0;
  display: block;
  overflow: hidden;
  border: 1px solid var(--hairline);
  border-radius: var(--radius-card);
  background: var(--bg-surface);
  color: #fff;
  opacity: 0;
  pointer-events: none;
  transform: translateX(0) scale(0.88);
  transition: transform 280ms ease, opacity 280ms ease;
}

.featured__slide.is-active {
  z-index: 3;
  opacity: 1;
  pointer-events: auto;
  transform: translateX(0) scale(1);
  box-shadow: var(--shadow-elevate);
}

.featured__slide.is-before,
.featured__slide.is-after {
  z-index: 1;
  opacity: 0.36;
}

.featured__slide.is-before {
  transform: translateX(-82%) scale(0.82);
}

.featured__slide.is-after {
  transform: translateX(82%) scale(0.82);
}

.featured__image {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: contain;
  background: #090d17;
}

.featured__caption {
  position: absolute;
  inset: auto 0 0;
  padding: 32px 18px 14px;
  background: linear-gradient(transparent, rgba(5, 6, 12, 0.88));
  font-weight: 700;
  font-size: 18px;
}

.featured__arrow {
  position: absolute;
  z-index: 5;
  top: 50%;
  width: 44px;
  height: 44px;
  border: 1px solid rgba(255, 255, 255, 0.4);
  border-radius: 50%;
  background: rgba(5, 6, 12, 0.72);
  color: white;
  font-size: 32px;
  line-height: 1;
  cursor: pointer;
  transform: translateY(-50%);
}

.featured__arrow--previous { left: 14px; }
.featured__arrow--next { right: 14px; }

.featured__dots {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-top: 12px;
}

.featured__dots button {
  width: 10px;
  height: 10px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: var(--text-secondary);
  opacity: 0.45;
  cursor: pointer;
}

.featured__dots button.is-active {
  background: var(--brand-primary);
  opacity: 1;
}

@media (max-width: 767px) {
  .featured { padding-top: 12px; }
  .featured__viewport { width: calc(100vw - 32px); }
  .featured__slide.is-before,
  .featured__slide.is-after { opacity: 0; }
  .featured__caption { font-size: 15px; padding: 28px 14px 10px; }
}

@media (prefers-reduced-motion: reduce) {
  .featured__slide { transition: none; }
}
</style>
