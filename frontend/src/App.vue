<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/useTheme'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { isDark, toggleTheme } = useTheme()

// 讓子頁(如 /events/:id、/orders/:id)也能對應到正確的選單項
const activeMenu = computed(() => {
  if (route.path.startsWith('/admin')) return route.path.startsWith('/admin/ticket-types') ? '/admin/ticket-types' : '/admin/events'
  if (route.path.startsWith('/orders')) return '/orders'
  if (route.path.startsWith('/events')) return '/events'
  return route.path
})

async function onLogout() {
  await auth.logout()
  ElMessage.success('已登出')
  router.push('/events')
}

// ---------- 行動版導覽(<1024px 收合為 hamburger + drawer) ----------
const drawerOpen = ref(false)
// 路由切換後自動收起 drawer(選單項本身也會 @select 關閉,這裡再保底處理瀏覽器返回等情境)
watch(() => route.fullPath, () => {
  drawerOpen.value = false
})

async function onLogoutFromDrawer() {
  drawerOpen.value = false
  await onLogout()
}

function onLoginFromDrawer() {
  drawerOpen.value = false
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <RouterLink to="/events" class="brand" aria-label="TIXCO 首頁">
        <span class="brand-dot" aria-hidden="true"></span>
        <span class="brand-word">TIXCO</span>
      </RouterLink>
      <el-menu mode="horizontal" :default-active="activeMenu" router :ellipsis="false" class="app-nav">
        <el-menu-item index="/events">活動</el-menu-item>
        <el-menu-item v-if="auth.isLoggedIn" index="/orders">我的訂單</el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/admin/events">活動管理</el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/admin/ticket-types">票種管理</el-menu-item>
      </el-menu>
      <div class="header-actions">
        <button
          type="button"
          class="theme-toggle"
          :aria-label="isDark ? '切換為淺色主題' : '切換為深色主題'"
          :title="isDark ? '切換為淺色主題' : '切換為深色主題'"
          @click="toggleTheme"
        >
          <svg v-if="isDark" class="theme-icon" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
            <path
              fill="currentColor"
              d="M12 3a1 1 0 0 1 1 1v1.5a1 1 0 1 1-2 0V4a1 1 0 0 1 1-1Zm0 14a5 5 0 1 1 0-10 5 5 0 0 1 0 10Zm0-2a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm0 2.5a1 1 0 0 1 1 1V20a1 1 0 1 1-2 0v-1.5a1 1 0 0 1 1-1ZM4 12a1 1 0 0 1 1-1h1.5a1 1 0 1 1 0 2H5a1 1 0 0 1-1-1Zm13.5 0a1 1 0 0 1 1-1H20a1 1 0 1 1 0 2h-1.5a1 1 0 0 1-1-1ZM5.64 5.64a1 1 0 0 1 1.42 0l1.06 1.06a1 1 0 0 1-1.42 1.42L5.64 7.06a1 1 0 0 1 0-1.42Zm9.24 9.24a1 1 0 0 1 1.42 0l1.06 1.06a1 1 0 0 1-1.42 1.42l-1.06-1.06a1 1 0 0 1 0-1.42Zm3.48-9.24a1 1 0 0 1 0 1.42l-1.06 1.06a1 1 0 1 1-1.42-1.42l1.06-1.06a1 1 0 0 1 1.42 0ZM8.12 14.88a1 1 0 0 1 0 1.42l-1.06 1.06a1 1 0 0 1-1.42-1.42l1.06-1.06a1 1 0 0 1 1.42 0Z"
            />
          </svg>
          <svg v-else class="theme-icon" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
            <path
              fill="currentColor"
              d="M12.1 3.3a1 1 0 0 1 .2 1.1 6.5 6.5 0 0 0 8 8 1 1 0 0 1 1.3 1.3A8.5 8.5 0 1 1 11 2.8a1 1 0 0 1 1.1.5Zm-1.9 1.9a6.5 6.5 0 1 0 8.6 8.6A8.5 8.5 0 0 1 10.2 5.2Z"
            />
          </svg>
        </button>
        <div class="user-area">
          <el-dropdown v-if="auth.isLoggedIn" @command="(cmd: string) => cmd === 'logout' && onLogout()">
            <span class="user-name">{{ auth.user?.username ?? '使用者' }}</span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">登出</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button v-else text type="primary" @click="router.push({ path: '/login', query: { redirect: route.fullPath } })">
            登入 / 註冊
          </el-button>
        </div>
        <button
          type="button"
          class="hamburger-btn"
          aria-label="開啟導覽選單"
          aria-haspopup="true"
          :aria-expanded="drawerOpen"
          @click="drawerOpen = true"
        >
          <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
            <path
              fill="currentColor"
              d="M3 6h18a1 1 0 1 0 0-2H3a1 1 0 0 0 0 2Zm0 7h18a1 1 0 1 0 0-2H3a1 1 0 0 0 0 2Zm0 7h18a1 1 0 1 0 0-2H3a1 1 0 0 0 0 2Z"
            />
          </svg>
        </button>
      </div>
    </el-header>

    <el-drawer
      v-model="drawerOpen"
      direction="rtl"
      size="min(80vw, 300px)"
      title="導覽選單"
      class="mobile-drawer"
    >
      <el-menu :default-active="activeMenu" router class="drawer-menu" @select="drawerOpen = false">
        <el-menu-item index="/events">活動</el-menu-item>
        <el-menu-item v-if="auth.isLoggedIn" index="/orders">我的訂單</el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/admin/events">活動管理</el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/admin/ticket-types">票種管理</el-menu-item>
      </el-menu>
      <div class="drawer-footer">
        <el-button v-if="auth.isLoggedIn" class="drawer-logout" @click="onLogoutFromDrawer">
          登出({{ auth.user?.username ?? '使用者' }})
        </el-button>
        <el-button v-else type="primary" class="drawer-logout" @click="onLoginFromDrawer">
          登入 / 註冊
        </el-button>
      </div>
    </el-drawer>

    <el-main class="app-main">
      <RouterView />
    </el-main>
    <el-footer class="app-footer" height="auto">
      <div class="footer-inner">
        <p class="disclaimer">
          本網站為個人技術展示作品,所有活動、票價與販售資訊均為虛構,與相關藝人及其經紀公司無關。
        </p>
        <a
          class="footer-link"
          href="https://github.com/weiting1229/seckill-ticketing"
          target="_blank"
          rel="noopener noreferrer"
        >
          GitHub 原始碼 ↗
        </a>
      </div>
    </el-footer>
  </el-container>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
}

/* ---------- 玻璃感 header(sticky) ---------- */
.app-header {
  position: sticky;
  top: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  gap: 24px;
  height: var(--header-height);
  background: var(--header-bg);
  backdrop-filter: blur(14px) saturate(160%);
  -webkit-backdrop-filter: blur(14px) saturate(160%);
  border-bottom: 1px solid var(--header-border);
}

/* ---------- 品牌 wordmark ---------- */
.brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  text-decoration: none;
  white-space: nowrap;
}

.brand-word {
  font-size: 20px;
  font-weight: 800;
  letter-spacing: 0.14em;
  background: linear-gradient(90deg, var(--brand-primary), var(--brand-accent));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.brand-dot {
  width: 10px;
  height: 10px;
  border-radius: var(--radius-pill);
  background: var(--brand-accent);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--brand-accent) 24%, transparent);
}

.app-nav {
  flex: 1;
  border-bottom: none;
  --el-menu-bg-color: transparent;
  --el-menu-hover-bg-color: transparent;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

/* ---------- 主題切換鈕 ---------- */
.theme-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 1px solid var(--el-border-color);
  border-radius: var(--radius-pill);
  background: var(--el-fill-color-light);
  color: var(--text-secondary);
  cursor: pointer;
  transition:
    color var(--transition-base),
    border-color var(--transition-base),
    background-color var(--transition-base);
}

.theme-toggle:hover {
  color: var(--brand-accent);
  border-color: var(--brand-primary);
}

.theme-toggle:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
}

.theme-icon {
  display: block;
}

.user-name {
  cursor: pointer;
  color: var(--el-text-color-primary);
  outline: none;
}

/* ---------- Hamburger(僅 <1024px 顯示) ---------- */
.hamburger-btn {
  display: none;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 1px solid var(--el-border-color);
  border-radius: var(--radius-control);
  background: var(--el-fill-color-light);
  color: var(--text-primary);
  cursor: pointer;
}

.hamburger-btn:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
}

/* ---------- Drawer 選單 ---------- */
.drawer-menu {
  border-right: none;
  --el-menu-bg-color: transparent;
}

.drawer-footer {
  padding: 12px 20px;
  border-top: 1px solid var(--hairline);
}

.drawer-logout {
  width: 100%;
  min-height: 44px;
}

.app-main {
  max-width: var(--content-max);
  width: 100%;
  margin: 0 auto;
}

/* ---------- 全站 footer ---------- */
.app-footer {
  padding: 20px 20px 28px;
  background: var(--footer-bg);
  border-top: 1px solid var(--header-border);
}

.footer-inner {
  max-width: var(--content-max);
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.disclaimer {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-secondary);
  max-width: 72ch;
}

.footer-link {
  font-size: 13px;
  font-weight: 600;
  color: var(--brand-primary);
  text-decoration: none;
  white-space: nowrap;
}

.footer-link:hover {
  color: var(--brand-primary-hover);
}

/* ---------- 平板/手機(<1024px):橫向選單+使用者區收進 drawer,改用 hamburger ---------- */
@media (max-width: 1023px) {
  .app-nav,
  .user-area {
    display: none;
  }

  .hamburger-btn {
    display: inline-flex;
  }

  /* 觸控目標 ≥44×44px */
  .theme-toggle,
  .hamburger-btn {
    width: 44px;
    height: 44px;
  }
}

@media (max-width: 639px) {
  .app-header {
    gap: 12px;
  }

  .footer-inner {
    justify-content: flex-start;
  }
}
</style>
