import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi, type CurrentUser, type LoginResult } from '@/api/auth'
import { tokenStorage } from '@/utils/token'

const LOGIN_ROLE_KEY = 'clinic.loginRole'

function readLoginRole(): string | null {
  try {
    return sessionStorage.getItem(LOGIN_ROLE_KEY)
  } catch {
    return null
  }
}

function writeLoginRole(role: string | null) {
  try {
    if (role) sessionStorage.setItem(LOGIN_ROLE_KEY, role)
    else sessionStorage.removeItem(LOGIN_ROLE_KEY)
  } catch {
    // 浏览器禁用存储时只影响页头的身份显示
  }
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  // 只关心“是否持有令牌”；令牌被刷新替换时这里仍为非空，无需同步
  const accessToken = ref<string | null>(tokenStorage.getAccess())
  /** 登录时选择的身份，只用于页头显示；权限以 user.permissions（来自后端）为准 */
  const loginRole = ref<string | null>(readLoginRole())

  const isLoggedIn = computed(() => !!accessToken.value)
  const permissionSet = computed(() => new Set(user.value?.permissions ?? []))

  function hasPermission(code?: string | string[]): boolean {
    if (!code) return true
    const codes = Array.isArray(code) ? code : [code]
    return codes.some((c) => permissionSet.value.has(c))
  }

  /** 登录可能分多步：返回 step 时由登录页继续，拿到令牌后调用 finish 完成 */
  async function login(username: string, password: string, role?: string): Promise<LoginResult> {
    return finish(await authApi.login(username, password, role), role)
  }

  async function finish(result: LoginResult, role?: string): Promise<LoginResult> {
    if (result.accessToken && result.refreshToken) {
      tokenStorage.save(result.accessToken, result.refreshToken)
      accessToken.value = result.accessToken
      await loadCurrentUser()
      // 只记录账号确实具备的身份（后端已核对，这里再按 /me 的角色过滤一次）
      const held = role && user.value?.roles.includes(role) ? role : null
      loginRole.value = held
      writeLoginRole(held)
    }
    return result
  }

  async function loadCurrentUser() {
    user.value = await authApi.me()
    return user.value
  }

  async function logout() {
    try {
      await authApi.logout(tokenStorage.getRefresh())
    } finally {
      reset()
    }
  }

  function reset() {
    tokenStorage.clear()
    accessToken.value = null
    user.value = null
    loginRole.value = null
    writeLoginRole(null)
  }

  return { user, loginRole, isLoggedIn, hasPermission, login, finish, loadCurrentUser, logout, reset }
})
