import { ref } from 'vue'
import { http } from './http'
import type { Id } from '@/types/api'

export interface TokenVO {
  accessToken: string
  refreshToken: string
  expiresIn: number
  tokenType: string
}

/** 登录下一步：首次登录修改临时口令 */
export type LoginStep = 'PASSWORD_CHANGE'

/** 登录结果：step 为空表示完成，带令牌；否则凭 challenge 继续 */
export interface LoginResult extends Partial<TokenVO> {
  step?: LoginStep
  challenge?: string
}

export interface CurrentUser {
  userId: Id
  username: string
  nickname: string
  /** 1 员工 */
  userType: number
  campusId?: Id
  roles: string[]
  permissions: string[]
}

/** 可选择的登录身份：系统中启用的角色 */
export interface LoginRole {
  code: string
  name: string
}

export interface AuthOptions {
  /** 机构名称（部署配置 CLINIC_ORG_NAME） */
  orgName: string
  roles: LoginRole[]
}

const quiet = { showError: false }

const orgName = ref('校医院')
let optionsLoading: Promise<AuthOptions | null> | null = null

/** 登录页信息，全局只加载一次 */
export function loadOptions(): Promise<AuthOptions | null> {
  if (!optionsLoading) {
    optionsLoading = authApi
      .options()
      .then((o) => {
        orgName.value = o.orgName || orgName.value
        return o
      })
      .catch(() => {
        optionsLoading = null
        return null
      })
  }
  return optionsLoading
}

/** 机构名称，用于页面标题 */
export function useOrgName() {
  void loadOptions()
  return orgName
}

export const authApi = {
  options: () => http.get<AuthOptions>('/api/auth/options', undefined, quiet),
  /** role：登录页选择的身份，后端核对账号是否具备该身份；权限始终以账号实际角色为准 */
  login: (username: string, password: string, role?: string) =>
    http.post<LoginResult>('/api/auth/login', { username, password, role }, quiet),
  changeExpiredPassword: (challenge: string, newPassword: string) =>
    http.post<LoginResult>('/api/auth/password/expired', { challenge, newPassword }, quiet),
  changePassword: (oldPassword: string, newPassword: string) =>
    http.post<void>('/api/auth/password', { oldPassword, newPassword }, quiet),
  me: () => http.get<CurrentUser>('/api/auth/me'),
  logout: (refreshToken: string | null) =>
    http.post<void>('/api/auth/logout', refreshToken ? { refreshToken } : {}, quiet),
}
