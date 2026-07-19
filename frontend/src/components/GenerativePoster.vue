<script setup lang="ts">
import { computed, useId } from 'vue'
import { derivePosterParams } from '@/utils/posterSeed'

/**
 * 生成式海報(方案 C):coverImageUrl 為空時即時渲染。
 * 純前端 SVG,無網路請求、無新依賴。同一 title 永遠產出同一張圖(deterministic)。
 * 版權安全:純幾何(月亮/圓環/光束/天際線/星點),不含任何真實人物形象。
 */
const props = withDefaults(
  defineProps<{
    /** 必填,兼作 seed */
    title: string
    subtitle?: string
    venue?: string
    /** ISO-8601(UTC);顯示為日期 */
    eventTime?: string
    /** poster = 3:4 直式;banner = 21:9 橫式 hero */
    variant?: 'poster' | 'banner'
    /** 是否在海報上疊字(團名/副標/日期場地);列表卡片用純藝術時設 false */
    showLabel?: boolean
  }>(),
  { subtitle: '', venue: '', eventTime: '', variant: 'poster', showLabel: true },
)

const uid = useId()

const dims = computed(() =>
  props.variant === 'banner' ? { w: 1260, h: 540 } : { w: 600, h: 800 },
)

/** 依 title 推導的構圖幾何(已縮放到 viewBox 座標)。 */
const art = computed(() => {
  const { w, h } = dims.value
  const min = Math.min(w, h)
  const p = derivePosterParams(props.title)

  const mx = p.moon.cx * w
  const my = p.moon.cy * h
  const mr = p.moon.r * min

  // 同心圓環:由月亮半徑向外擴張
  const rings = Array.from({ length: p.rings }, (_, i) => mr + (i + 1) * mr * 0.55)

  // 光束:自月亮中心朝下扇形展開的細長三角形
  const spreadDeg = 13
  const length = Math.hypot(w, h)
  const beams = Array.from({ length: p.beamCount }, (_, i) => {
    const centered = i - (p.beamCount - 1) / 2
    const angle = ((p.beamAngle + centered * spreadDeg) * Math.PI) / 180
    const dx = Math.sin(angle)
    const dy = Math.cos(angle) // angle 0 → 正下方
    // 垂直於光束方向的單位向量,用來給末端寬度
    const px = -dy
    const py = dx
    const halfW = mr * 0.5
    const ex = mx + dx * length
    const ey = my + dy * length
    return `${mx},${my} ${ex + px * halfW},${ey + py * halfW} ${ex - px * halfW},${ey - py * halfW}`
  })

  // 天際線:等寬棟距,高度序列來自 seed
  const cols = p.skyline.length
  const colW = w / cols
  const buildings = p.skyline.map((frac, i) => {
    const bh = frac * h * 0.28
    return { x: i * colW, y: h - bh, w: colW + 1, h: bh }
  })

  const stars = p.stars.map((s) => ({ cx: s.x * w, cy: s.y * h, r: s.r }))

  return { w, h, mx, my, mr, rings, beams, buildings, stars, palette: p.palette }
})

const formattedDate = computed(() => {
  if (!props.eventTime) return ''
  const d = new Date(props.eventTime)
  if (Number.isNaN(d.getTime())) return ''
  return d.toLocaleDateString('zh-TW', { year: 'numeric', month: '2-digit', day: '2-digit' })
})

const gradId = computed(() => `pg-${uid}`)
const glowId = computed(() => `gl-${uid}`)
</script>

<template>
  <div class="poster" :class="`poster--${variant}`" role="img" :aria-label="`${title} 活動海報`">
    <svg
      class="poster__art"
      :viewBox="`0 0 ${art.w} ${art.h}`"
      preserveAspectRatio="xMidYMid slice"
      xmlns="http://www.w3.org/2000/svg"
    >
      <defs>
        <linearGradient :id="gradId" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" :stop-color="art.palette.bgTop" />
          <stop offset="100%" :stop-color="art.palette.bgBottom" />
        </linearGradient>
        <radialGradient :id="glowId" cx="50%" cy="50%" r="50%">
          <stop offset="0%" :stop-color="art.palette.glow" stop-opacity="0.55" />
          <stop offset="55%" :stop-color="art.palette.glow" stop-opacity="0.14" />
          <stop offset="100%" :stop-color="art.palette.glow" stop-opacity="0" />
        </radialGradient>
      </defs>

      <rect :width="art.w" :height="art.h" :fill="`url(#${gradId})`" />

      <!-- 星點 -->
      <g :fill="art.palette.star">
        <circle
          v-for="(s, i) in art.stars"
          :key="`s${i}`"
          :cx="s.cx"
          :cy="s.cy"
          :r="s.r"
          :opacity="0.25 + (i % 5) * 0.14"
        />
      </g>

      <!-- 光束 -->
      <g :fill="art.palette.beam" opacity="0.14">
        <polygon v-for="(pts, i) in art.beams" :key="`b${i}`" :points="pts" />
      </g>

      <!-- 月亮光暈 -->
      <circle :cx="art.mx" :cy="art.my" :r="art.mr * 3" :fill="`url(#${glowId})`" />

      <!-- 同心圓環 -->
      <g fill="none" :stroke="art.palette.ring">
        <circle
          v-for="(r, i) in art.rings"
          :key="`r${i}`"
          :cx="art.mx"
          :cy="art.my"
          :r="r"
          :stroke-width="1.5"
          :opacity="0.5 - i * 0.08"
        />
      </g>

      <!-- 月亮本體 -->
      <circle :cx="art.mx" :cy="art.my" :r="art.mr" :fill="art.palette.orb" />

      <!-- 天際線剪影 -->
      <g :fill="art.palette.skyline">
        <rect
          v-for="(b, i) in art.buildings"
          :key="`c${i}`"
          :x="b.x"
          :y="b.y"
          :width="b.w"
          :height="b.h"
        />
      </g>
    </svg>

    <div v-if="showLabel" class="poster__scrim"></div>

    <div v-if="showLabel" class="poster__text">
      <p v-if="formattedDate" class="poster__eyebrow tabular-nums">{{ formattedDate }}</p>
      <h3 class="poster__title">{{ title }}</h3>
      <p v-if="subtitle" class="poster__subtitle">{{ subtitle }}</p>
      <p v-if="venue" class="poster__venue">{{ venue }}</p>
    </div>
  </div>
</template>

<style scoped>
.poster {
  position: relative;
  width: 100%;
  overflow: hidden;
  border-radius: inherit;
  container-type: size;
  background: #0b0e16;
  color: #f8fafc;
}

.poster--poster {
  aspect-ratio: 3 / 4;
}

.poster--banner {
  aspect-ratio: 21 / 9;
}

.poster__art {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
}

/* 底部壓暗,確保文字可讀(不依賴隨機藝術的明暗) */
.poster__scrim {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    to top,
    rgba(5, 6, 12, 0.92) 0%,
    rgba(5, 6, 12, 0.55) 28%,
    rgba(5, 6, 12, 0) 55%
  );
}

.poster__text {
  position: absolute;
  inset: auto 0 0 0;
  padding: 8cqmin;
  display: flex;
  flex-direction: column;
  gap: 1.5cqmin;
}

.poster__eyebrow {
  margin: 0;
  font-size: 3.4cqmin;
  font-weight: 600;
  letter-spacing: 0.12em;
  color: #cbd5e1;
}

.poster__title {
  margin: 0;
  font-size: 8.5cqmin;
  font-weight: 800;
  line-height: 1.08;
  letter-spacing: -0.01em;
  /* 最多兩行,超出以省略號截斷 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  text-shadow: 0 2px 12px rgba(0, 0, 0, 0.5);
}

.poster__subtitle {
  margin: 0;
  font-size: 4cqmin;
  font-weight: 500;
  color: #e2e8f0;
}

.poster__venue {
  margin: 0;
  font-size: 3.4cqmin;
  color: #94a3b8;
}

/* banner 橫式:字級改以高度為基準,避免過寬時字太大 */
.poster--banner .poster__text {
  inset: auto 0 0 0;
  padding: 5cqmin 6cqmin;
}

.poster--banner .poster__title {
  font-size: 12cqmin;
  -webkit-line-clamp: 1;
  line-clamp: 1;
}

.poster--banner .poster__eyebrow {
  font-size: 4.5cqmin;
}

.poster--banner .poster__subtitle {
  font-size: 5cqmin;
}

.poster--banner .poster__venue {
  font-size: 4.5cqmin;
}
</style>
