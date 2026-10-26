<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { authApi, loadOptions, useOrgName, type LoginResult, type LoginRole } from '@/api/auth'
import { ApiError } from '@/types/api'
import { PASSWORD_HINT, passwordRules } from '@/utils/password'
import { LOGIN_ROLE_INFO, sortLoginRoles } from '@/utils/loginRoles'

/**
 * 登录分两步：先选择登录身份，再用账号口令登录。
 * <ul>
 *   <li>所选身份放在地址栏（?role=CASHIER），刷新、后退后保持；无效或缺失时重新选择；</li>
 *   <li>身份只决定登录入口和页面标题：登录时一并提交，后端核对账号是否具备该身份，不具备则不发令牌；
 *       权限始终取自账号的实际角色，前端不据此授予任何权限。</li>
 * </ul>
 */
const ROLE_NOT_GRANTED = 10022
const CHALLENGE_INVALID = 10006

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const orgName = useOrgName()

// ---------------------------------------------------------------- 身份
const roles = ref<LoginRole[]>([])
const optionsState = ref<'loading' | 'ready' | 'failed'>('loading')

async function fetchOptions() {
  optionsState.value = 'loading'
  const options = await loadOptions()
  if (options) {
    roles.value = sortLoginRoles(options.roles ?? [])
    optionsState.value = 'ready'
  } else {
    optionsState.value = 'failed'
  }
}

const requestedRole = computed(() => (typeof route.query.role === 'string' ? route.query.role : ''))
const selectedRole = computed(() => roles.value.find((r) => r.code === requestedRole.value))
/** 地址栏里带了身份，但不是系统中的身份（手工改过地址、角色已停用） */
const invalidRole = computed(
  () => optionsState.value === 'ready' && !!requestedRole.value && !selectedRole.value,
)

const dialogOpen = ref(false)
const choice = ref('')

watch(
  [selectedRole, optionsState],
  () => {
    if (optionsState.value !== 'ready') return
    if (!selectedRole.value) {
      choice.value = ''
      dialogOpen.value = true
    }
  },
  { immediate: true },
)

function openSwitch() {
  choice.value = selectedRole.value?.code ?? ''
  dialogOpen.value = true
}

/** 双击一个身份：选中并继续 */
function pickAndContinue(code: string) {
  choice.value = code
  void confirmRole()
}

async function confirmRole() {
  if (!choice.value) return
  dialogOpen.value = false
  resetForm()
  // replace：切换身份不产生新的历史记录，“后退”不会回到上一个身份的登录页
  await router.replace({ name: 'login', query: { ...route.query, role: choice.value } })
  await nextTick()
  usernameInput.value?.focus()
}

// ---------------------------------------------------------------- 口令登录
const formRef = ref<FormInstance>()
const usernameInput = ref<{ focus: () => void }>()
const form = reactive({ username: '', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入口令', trigger: 'blur' }],
}
const loading = ref(false)
const errorMessage = ref('')
const errorCode = ref<number>()
const capsLock = ref(false)

/** 口令之后的下一步：首次登录修改临时口令 */
const pending = ref<LoginResult | null>(null)
const pwdRef = ref<FormInstance>()
const pwdForm = reactive({ newPassword: '', confirm: '' })
const pwdRules: FormRules = {
  newPassword: passwordRules(),
  confirm: [
    { required: true, message: '请再次输入新口令', trigger: 'blur' },
    {
      validator: (_r, v: string, cb) =>
        v === pwdForm.newPassword ? cb() : cb(new Error('两次输入的口令不一致')),
      trigger: 'blur',
    },
  ],
}

function resetForm() {
  pending.value = null
  errorMessage.value = ''
  errorCode.value = undefined
  form.password = ''
  pwdForm.newPassword = ''
  pwdForm.confirm = ''
  formRef.value?.clearValidate()
}

function detectCapsLock(e: Event | KeyboardEvent) {
  capsLock.value = 'getModifierState' in e && e.getModifierState('CapsLock')
}

async function run(action: () => Promise<LoginResult>) {
  loading.value = true
  errorMessage.value = ''
  errorCode.value = undefined
  try {
    const result = await auth.finish(await action(), selectedRole.value?.code)
    if (result.step) {
      pending.value = result
      return
    }
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    // 只允许站内地址，防止借登录页跳转到外部网站
    await router.replace(redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : '/')
  } catch (e) {
    errorMessage.value = e instanceof ApiError ? e.message : '登录失败，请检查网络后重试'
    errorCode.value = e instanceof ApiError ? e.code : undefined
    if (e instanceof ApiError && e.code === CHALLENGE_INVALID) {
      // 设置新口令的凭证过期：回到第一步重新输入口令
      pending.value = null
    }
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!selectedRole.value || loading.value) return
  if (!(await formRef.value?.validate().catch(() => false))) return
  const role = selectedRole.value.code
  await run(() => authApi.login(form.username.trim(), form.password, role))
}

async function submitPassword() {
  if (loading.value) return
  if (!(await pwdRef.value?.validate().catch(() => false))) return
  await run(() => authApi.changeExpiredPassword(pending.value!.challenge!, pwdForm.newPassword))
}

onMounted(fetchOptions)
</script>

<template>
  <div class="login">
    <header class="login__bar">
      <span class="login__mark" aria-hidden="true">医</span>
      <span class="login__org">{{ orgName }}</span>
      <span class="login__divider" aria-hidden="true" />
      <span class="login__system">收费管理系统</span>
    </header>

    <main class="login__main">
      <section class="login__intro" aria-label="系统说明">
        <p class="login__eyebrow">{{ orgName }}</p>
        <h1>收费管理系统</h1>
        <p class="login__lead">
          供校医院收费、财务、门诊、药房和系统管理人员使用。请按本人岗位选择登录身份，使用本人账号登录。
        </p>
        <ul class="login__notes">
          <li>登录身份须与账号所属岗位一致，系统按账号的实际权限开放功能。</li>
          <li>连续输错口令 5 次，账号锁定 15 分钟。</li>
          <li>新开账号或口令被重置后，首次登录须设置新口令。</li>
        </ul>
      </section>

      <section class="login__card" aria-labelledby="login-title">
        <!-- 身份列表加载中 / 失败 -->
        <div v-if="optionsState === 'loading'" class="login__state" aria-live="polite">
          <el-skeleton :rows="4" animated />
        </div>
        <div v-else-if="optionsState === 'failed'" class="login__state" role="alert">
          <h2 id="login-title">暂时无法登录</h2>
          <p class="login__muted">无法获取登录身份，请检查网络连接后重试。</p>
          <el-button type="primary" @click="fetchOptions">重新加载</el-button>
        </div>

        <!-- 尚未选择身份（关闭了选择窗口时的兜底） -->
        <div v-else-if="!selectedRole" class="login__state">
          <h2 id="login-title">请选择登录身份</h2>
          <p class="login__muted">选择本人岗位对应的身份后，再输入账号和口令。</p>
          <el-button type="primary" @click="openSwitch">选择身份</el-button>
        </div>

        <!-- 第一步：账号口令 -->
        <template v-else-if="!pending">
          <div class="login__head">
            <div>
              <p class="login__role-label">登录身份</p>
              <h2 id="login-title">{{ selectedRole.name }}登录</h2>
            </div>
            <el-button link type="primary" class="login__switch" @click="openSwitch">切换身份</el-button>
          </div>
          <el-form
            ref="formRef"
            :model="form"
            :rules="rules"
            label-position="top"
            size="large"
            :disabled="loading"
            @submit.prevent="submit"
          >
            <el-form-item label="用户名" prop="username">
              <el-input
                ref="usernameInput"
                v-model="form.username"
                autocomplete="username"
                placeholder="请输入用户名"
                autofocus
              />
            </el-form-item>
            <el-form-item label="口令" prop="password">
              <el-input
                v-model="form.password"
                type="password"
                show-password
                autocomplete="current-password"
                placeholder="请输入口令"
                @keyup="detectCapsLock"
                @keydown="detectCapsLock"
              />
            </el-form-item>
            <p v-if="capsLock" class="login__caps" aria-live="polite">大写锁定已开启</p>
            <div v-if="errorMessage" class="login__error" role="alert">
              <el-alert :title="errorMessage" type="error" :closable="false" show-icon />
              <el-button v-if="errorCode === ROLE_NOT_GRANTED" link type="primary" @click="openSwitch">
                切换身份
              </el-button>
            </div>
            <el-button type="primary" native-type="submit" :loading="loading" class="login__submit">
              {{ loading ? '正在登录' : '登录' }}
            </el-button>
          </el-form>
        </template>

        <!-- 第二步：首次登录设置新口令 -->
        <template v-else>
          <div class="login__head">
            <div>
              <p class="login__role-label">{{ selectedRole.name }}</p>
              <h2 id="login-title">设置新口令</h2>
            </div>
          </div>
          <p class="login__muted">当前口令为临时口令，请设置新口令后继续。{{ PASSWORD_HINT }}</p>
          <el-form
            ref="pwdRef"
            :model="pwdForm"
            :rules="pwdRules"
            label-position="top"
            size="large"
            :disabled="loading"
            @submit.prevent="submitPassword"
          >
            <el-form-item label="新口令" prop="newPassword">
              <el-input
                v-model="pwdForm.newPassword"
                type="password"
                show-password
                autocomplete="new-password"
              />
            </el-form-item>
            <el-form-item label="确认新口令" prop="confirm">
              <el-input v-model="pwdForm.confirm" type="password" show-password autocomplete="new-password" />
            </el-form-item>
            <div v-if="errorMessage" class="login__error" role="alert">
              <el-alert :title="errorMessage" type="error" :closable="false" show-icon />
            </div>
            <el-button type="primary" native-type="submit" :loading="loading" class="login__submit">
              确认并登录
            </el-button>
          </el-form>
          <el-button link class="login__back" @click="resetForm">返回重新登录</el-button>
        </template>
      </section>
    </main>

    <footer class="login__footer">{{ orgName }} · 收费管理系统 · 仅限授权工作人员使用</footer>

    <el-dialog
      v-model="dialogOpen"
      title="选择登录身份"
      width="min(600px, calc(100vw - 32px))"
      class="role-dialog"
      :show-close="!!selectedRole"
      :close-on-click-modal="!!selectedRole"
      :close-on-press-escape="!!selectedRole"
      align-center
      append-to-body
    >
      <p v-if="invalidRole" class="role-dialog__notice" role="alert">地址中的登录身份无效，请重新选择。</p>
      <p id="role-dialog-hint" class="role-dialog__hint">
        请选择本人岗位对应的身份。登录时系统会核对账号是否具备该身份。
      </p>
      <div class="role-grid" role="radiogroup" aria-describedby="role-dialog-hint">
        <label
          v-for="r in roles"
          :key="r.code"
          class="role-option"
          :class="{ 'is-selected': choice === r.code }"
          @dblclick="pickAndContinue(r.code)"
        >
          <input v-model="choice" class="role-option__input" type="radio" name="login-role" :value="r.code" />
          <span class="role-option__icon" aria-hidden="true">
            <el-icon v-if="LOGIN_ROLE_INFO[r.code]" :size="20"
              ><component :is="LOGIN_ROLE_INFO[r.code].icon"
            /></el-icon>
          </span>
          <span class="role-option__text">
            <span class="role-option__name">{{ r.name }}</span>
            <span v-if="LOGIN_ROLE_INFO[r.code]" class="role-option__duty">{{
              LOGIN_ROLE_INFO[r.code].duty
            }}</span>
          </span>
        </label>
      </div>
      <template #footer>
        <el-button v-if="selectedRole" @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!choice" @click="confirmRole">继续</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.login {
  --login-border: #dfe3e8;
  --login-text: #1f2933;
  --login-muted: #5f6b7a;

  min-height: 100%;
  display: flex;
  flex-direction: column;
  background: #f4f6f8;
  color: var(--login-text);
}

.login__bar {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 32px;
  background: #fff;
  border-bottom: 1px solid var(--login-border);
}

.login__mark {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--el-color-primary);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
}

.login__org {
  font-weight: 600;
}

.login__divider {
  width: 1px;
  height: 16px;
  background: var(--login-border);
}

.login__system {
  color: var(--login-muted);
}

.login__main {
  flex: 1;
  width: 100%;
  max-width: 1040px;
  margin: 0 auto;
  padding: 56px 32px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 420px;
  gap: 64px;
  align-items: center;
}

.login__eyebrow {
  margin: 0 0 8px;
  color: var(--el-color-primary);
  font-weight: 600;
  font-size: 14px;
}

.login__intro h1 {
  margin: 0 0 16px;
  font-size: 30px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.login__lead {
  margin: 0 0 24px;
  color: var(--login-muted);
  line-height: 1.8;
  max-width: 460px;
}

.login__notes {
  margin: 0;
  padding: 16px 0 0 18px;
  border-top: 1px solid var(--login-border);
  color: var(--login-muted);
  font-size: 13px;
  line-height: 2;
  max-width: 460px;
}

.login__card {
  background: #fff;
  border: 1px solid var(--login-border);
  border-radius: 8px;
  padding: 32px;
  box-shadow: 0 1px 2px rgb(16 24 40 / 4%);
}

.login__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 20px;
}

.login__role-label {
  margin: 0 0 4px;
  font-size: 13px;
  color: var(--login-muted);
}

.login__card h2 {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
}

.login__state h2 {
  margin-bottom: 8px;
}

.login__muted {
  color: var(--login-muted);
  font-size: 14px;
  line-height: 1.7;
  margin: 0 0 20px;
}

.login__caps {
  margin: -8px 0 12px;
  font-size: 13px;
  color: var(--el-color-warning-dark-2);
}

.login__error {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  margin-bottom: 4px;
}

.login__submit {
  width: 100%;
  margin-top: 12px;
}

.login__back {
  margin-top: 12px;
}

.login__footer {
  padding: 16px 32px;
  text-align: center;
  font-size: 12px;
  color: var(--login-muted);
  border-top: 1px solid var(--login-border);
  background: #fff;
}

:deep(.el-form-item__label) {
  font-weight: 500;
  color: var(--login-text);
}

/* ---------------------------------------------------------------- 身份选择 */
.role-dialog__hint {
  margin: 0 0 16px;
  color: var(--login-muted, #5f6b7a);
  font-size: 14px;
}

.role-dialog__notice {
  margin: 0 0 12px;
  padding: 8px 12px;
  border-radius: 4px;
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning-dark-2);
  font-size: 13px;
}

.role-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.role-option {
  position: relative;
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 14px 16px;
  border: 1px solid #dfe3e8;
  border-radius: 8px;
  cursor: pointer;
  transition:
    border-color 0.15s,
    background-color 0.15s;
}

.role-option:hover {
  border-color: var(--el-color-primary-light-5);
}

.role-option.is-selected {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  box-shadow: inset 0 0 0 1px var(--el-color-primary);
}

/* 单选按钮视觉隐藏但保留键盘操作（Tab 进入、方向键切换、空格选中） */
.role-option__input {
  position: absolute;
  opacity: 0;
  width: 1px;
  height: 1px;
}

.role-option:focus-within {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

.role-option__icon {
  flex: none;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #eef2f5;
  color: #44515f;
}

.role-option.is-selected .role-option__icon {
  background: var(--el-color-primary);
  color: #fff;
}

.role-option__text {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.role-option__name {
  font-weight: 600;
}

.role-option__duty {
  font-size: 12px;
  line-height: 1.6;
  color: #5f6b7a;
}

@media (max-width: 900px) {
  .login__main {
    grid-template-columns: 1fr;
    align-content: start;
    gap: 24px;
    padding: 24px 16px;
    max-width: 480px;
  }

  .login__intro h1 {
    font-size: 24px;
  }

  .login__notes {
    display: none;
  }

  .login__bar {
    padding: 0 16px;
  }

  .login__system,
  .login__divider {
    display: none;
  }

  .login__card {
    padding: 24px 20px;
  }
}

@media (max-width: 560px) {
  .role-grid {
    grid-template-columns: 1fr;
  }
}
</style>
