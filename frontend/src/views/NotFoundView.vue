<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="nf">
    <svg class="nf__art" viewBox="0 0 160 120" fill="none" aria-hidden="true">
      <defs>
        <radialGradient id="nf-glow" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stop-color="var(--brand-accent)" stop-opacity="0.5" />
          <stop offset="100%" stop-color="var(--brand-accent)" stop-opacity="0" />
        </radialGradient>
      </defs>
      <circle cx="80" cy="50" r="40" fill="url(#nf-glow)" />
      <circle cx="80" cy="50" r="24" stroke="var(--brand-primary)" stroke-width="2" opacity="0.7" />
      <circle cx="80" cy="50" r="14" fill="var(--brand-accent)" opacity="0.22" />
      <circle cx="52" cy="30" r="1.6" fill="var(--brand-accent)" />
      <circle cx="112" cy="34" r="1.6" fill="var(--brand-accent)" />
      <circle cx="120" cy="64" r="1.6" fill="var(--brand-primary)" />
      <circle cx="44" cy="66" r="1.6" fill="var(--brand-primary)" />
      <path d="M20 100h120" stroke="var(--text-secondary)" stroke-width="2" stroke-linecap="round" opacity="0.4" />
    </svg>

    <p class="nf__code">404</p>
    <h1 class="nf__title">這個頁面不在場</h1>
    <p class="nf__sub">你要找的內容可能已下架或不存在。回到活動列表,看看正在開賣的現場。</p>
    <el-button type="primary" size="large" @click="router.push('/events')">回活動列表</el-button>
  </div>
</template>

<style scoped>
.nf {
  min-height: calc(100vh - var(--header-height) - 120px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 48px 20px;
}

.nf__art {
  width: 200px;
  max-width: 60%;
}

.nf__code {
  margin: 8px 0 0;
  font-size: clamp(48px, 12vw, 88px);
  font-weight: 800;
  line-height: 1;
  letter-spacing: 0.04em;
  background: linear-gradient(90deg, var(--brand-primary), var(--brand-accent));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.nf__title {
  margin: 12px 0 8px;
  font-size: clamp(20px, 5vw, 28px);
  font-weight: 800;
}

.nf__sub {
  margin: 0 0 24px;
  max-width: 44ch;
  color: var(--text-secondary);
  line-height: 1.6;
}
</style>
