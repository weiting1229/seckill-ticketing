import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    /** 需要登入;未登入導向 /login?redirect=原路徑。 */
    requiresAuth?: boolean
    /** 需要的角色(目前僅 ADMIN);不符者擋下。 */
    role?: 'ADMIN'
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/events' },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
    },
    {
      path: '/events',
      name: 'events',
      component: () => import('@/views/EventListView.vue'),
    },
    {
      path: '/events/:id',
      name: 'event-detail',
      component: () => import('@/views/EventDetailView.vue'),
    },
    {
      path: '/orders',
      name: 'orders',
      component: () => import('@/views/OrderListView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/orders/:id',
      name: 'order-detail',
      component: () => import('@/views/OrderDetailView.vue'),
      meta: { requiresAuth: true },
    },
    { path: '/admin', redirect: '/admin/events' },
    {
      path: '/admin/events',
      name: 'admin-events',
      component: () => import('@/views/admin/AdminEventsView.vue'),
      meta: { requiresAuth: true, role: 'ADMIN' },
    },
    {
      path: '/admin/ticket-types',
      name: 'admin-ticket-types',
      component: () => import('@/views/admin/AdminTicketTypesView.vue'),
      meta: { requiresAuth: true, role: 'ADMIN' },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  // 重新整理後帶著 token 但 user 尚未載入時,先補載以取得角色供守衛判斷
  await auth.ensureUser()

  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.role === 'ADMIN' && !auth.isAdmin) {
    if (!auth.isLoggedIn) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    ElMessage.warning('需要管理員權限')
    return { path: '/events' }
  }
  // 已登入者再訪登入頁:直接回首頁
  if (to.name === 'login' && auth.isLoggedIn) {
    return { path: '/events' }
  }
})

export default router
