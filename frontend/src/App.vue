<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

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
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <RouterLink to="/events" class="brand">🎫 搶票系統</RouterLink>
      <el-menu mode="horizontal" :default-active="activeMenu" router :ellipsis="false" class="app-nav">
        <el-menu-item index="/events">活動</el-menu-item>
        <el-menu-item v-if="auth.isLoggedIn" index="/orders">我的訂單</el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/admin/events">活動管理</el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/admin/ticket-types">票種管理</el-menu-item>
      </el-menu>
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
    </el-header>
    <el-main class="app-main">
      <RouterView />
    </el-main>
  </el-container>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
}

.app-header {
  display: flex;
  align-items: center;
  gap: 24px;
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-light);
}

.brand {
  font-size: 18px;
  font-weight: 700;
  color: var(--el-text-color-primary);
  text-decoration: none;
  white-space: nowrap;
}

.app-nav {
  flex: 1;
  border-bottom: none;
}

.user-area {
  white-space: nowrap;
}

.user-name {
  cursor: pointer;
  color: var(--el-text-color-primary);
  outline: none;
}

.app-main {
  max-width: 1080px;
  width: 100%;
  margin: 0 auto;
}
</style>
