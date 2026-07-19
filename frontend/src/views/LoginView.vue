<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { BizError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import GenerativePoster from '@/components/GenerativePoster.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const activeTab = ref<'login' | 'register'>('login')

// 與後端 RegisterRequest 的 Jakarta Validation 規則對齊
const USERNAME_PATTERN = /^[A-Za-z0-9_]{3,50}$/
const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d).+$/

/** 僅允許站內路徑,避免 open redirect。 */
function safeRedirect(): string {
  const r = route.query.redirect
  if (typeof r === 'string' && r.startsWith('/') && !r.startsWith('//')) return r
  return '/events'
}

// ---------- 登入 ----------

const loginFormRef = ref<FormInstance>()
const loginForm = reactive({ username: '', password: '' })
const loginRules: FormRules = {
  username: [{ required: true, message: '請輸入帳號', trigger: 'blur' }],
  password: [{ required: true, message: '請輸入密碼', trigger: 'blur' }],
}
const loginError = ref('')
const loggingIn = ref(false)

async function onLogin() {
  const form = loginFormRef.value
  if (!form || !(await form.validate().catch(() => false))) return
  loginError.value = ''
  loggingIn.value = true
  try {
    await auth.login(loginForm.username, loginForm.password)
    ElMessage.success('登入成功')
    router.push(safeRedirect())
  } catch (e) {
    // login API 為 silent,錯誤(1002 帳密錯誤等)在表單內提示
    loginError.value = e instanceof BizError ? e.message : '登入失敗,請稍後再試'
  } finally {
    loggingIn.value = false
  }
}

// ---------- 註冊 ----------

const registerFormRef = ref<FormInstance>()
const registerForm = reactive({ username: '', password: '', confirm: '' })
const registerRules: FormRules = {
  username: [
    { required: true, message: '請輸入帳號', trigger: 'blur' },
    { pattern: USERNAME_PATTERN, message: '須為 3–50 字的英文、數字或底線', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '請輸入密碼', trigger: 'blur' },
    { min: 8, max: 72, message: '長度須為 8–72 字', trigger: 'blur' },
    { pattern: PASSWORD_PATTERN, message: '須至少包含一個英文字母與一個數字', trigger: 'blur' },
  ],
  confirm: [
    { required: true, message: '請再次輸入密碼', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        if (value !== registerForm.password) callback(new Error('兩次輸入的密碼不一致'))
        else callback()
      },
      trigger: 'blur',
    },
  ],
}
const registering = ref(false)

async function onRegister() {
  const form = registerFormRef.value
  if (!form || !(await form.validate().catch(() => false))) return
  registering.value = true
  try {
    await auth.register(registerForm.username, registerForm.password)
  } catch {
    // 註冊錯誤(如 1001 使用者名稱已存在)由 http 攔截器統一提示
    registering.value = false
    return
  }
  try {
    // 註冊成功直接登入,免使用者重打一次帳密
    await auth.login(registerForm.username, registerForm.password)
    ElMessage.success('註冊成功,已為你登入')
    router.push(safeRedirect())
  } catch {
    // 理論上不會發生;退回登入分頁讓使用者手動登入
    ElMessage.success('註冊成功,請登入')
    activeTab.value = 'login'
    loginForm.username = registerForm.username
  } finally {
    registering.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-bg" aria-hidden="true">
      <GenerativePoster title="TIXCO LIVE" variant="banner" :show-label="false" />
      <div class="login-bg__scrim"></div>
    </div>
    <el-card class="login-card">
      <div class="login-brand">
        <span class="login-brand__dot" aria-hidden="true"></span>
        <span class="login-brand__word">TIXCO</span>
      </div>
      <p class="login-sub">登入以搶購你的下一場現場</p>
      <el-tabs v-model="activeTab" stretch>
        <el-tab-pane label="登入" name="login">
          <el-alert
            v-if="loginError"
            :title="loginError"
            type="error"
            show-icon
            :closable="false"
            class="form-alert"
          />
          <el-form
            ref="loginFormRef"
            :model="loginForm"
            :rules="loginRules"
            label-position="top"
            @submit.prevent
          >
            <el-form-item label="帳號" prop="username">
              <el-input
                v-model="loginForm.username"
                autocomplete="username"
                placeholder="請輸入帳號"
                @keyup.enter="onLogin"
              />
            </el-form-item>
            <el-form-item label="密碼" prop="password">
              <el-input
                v-model="loginForm.password"
                type="password"
                show-password
                autocomplete="current-password"
                placeholder="請輸入密碼"
                @keyup.enter="onLogin"
              />
            </el-form-item>
            <el-button type="primary" class="submit-btn" :loading="loggingIn" @click="onLogin">
              登入
            </el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="註冊" name="register">
          <el-form
            ref="registerFormRef"
            :model="registerForm"
            :rules="registerRules"
            label-position="top"
            @submit.prevent
          >
            <el-form-item label="帳號" prop="username">
              <el-input
                v-model="registerForm.username"
                autocomplete="username"
                placeholder="3–50 字的英文、數字或底線"
              />
            </el-form-item>
            <el-form-item label="密碼" prop="password">
              <el-input
                v-model="registerForm.password"
                type="password"
                show-password
                autocomplete="new-password"
                placeholder="8–72 字,至少含一個英文字母與一個數字"
              />
            </el-form-item>
            <el-form-item label="確認密碼" prop="confirm">
              <el-input
                v-model="registerForm.confirm"
                type="password"
                show-password
                autocomplete="new-password"
                placeholder="請再次輸入密碼"
                @keyup.enter="onRegister"
              />
            </el-form-item>
            <el-button
              type="primary"
              class="submit-btn"
              :loading="registering"
              @click="onRegister"
            >
              註冊
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  /* 滿版沉浸:抵銷 app-main 的 padding 讓品牌背景延伸到邊緣 */
  margin: -20px;
  min-height: calc(100vh - var(--header-height));
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
}

.login-bg {
  position: absolute;
  inset: 0;
  overflow: hidden;
}

.login-bg :deep(.poster) {
  width: 100%;
  height: 100%;
  aspect-ratio: auto;
  border-radius: 0;
}

.login-bg__scrim {
  position: absolute;
  inset: 0;
  background: radial-gradient(
    120% 90% at 50% 40%,
    rgba(5, 6, 12, 0.55) 0%,
    rgba(5, 6, 12, 0.85) 70%,
    rgba(5, 6, 12, 0.95) 100%
  );
}

.login-card {
  position: relative;
  z-index: 1;
  width: 400px;
  max-width: 100%;
  background: color-mix(in srgb, var(--bg-surface) 88%, transparent);
  backdrop-filter: blur(12px);
  border: 1px solid var(--hairline);
}

.login-brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  margin-top: 4px;
}

.login-brand__dot {
  width: 10px;
  height: 10px;
  border-radius: var(--radius-pill);
  background: var(--brand-accent);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--brand-accent) 22%, transparent);
}

.login-brand__word {
  font-size: 26px;
  font-weight: 800;
  letter-spacing: 0.14em;
  background: linear-gradient(90deg, var(--brand-primary), var(--brand-accent));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.login-sub {
  margin: 8px 0 12px;
  text-align: center;
  font-size: 14px;
  color: var(--text-secondary);
}

.form-alert {
  margin-bottom: 16px;
}

.submit-btn {
  width: 100%;
  margin-top: 8px;
}
</style>
