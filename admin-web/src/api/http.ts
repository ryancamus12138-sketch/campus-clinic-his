import axios, { AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import 'element-plus/theme-chalk/el-message.css'
import { ApiError, type ApiResult } from '@/types/api'
import { tokenStorage } from '@/utils/token'

const instance = axios.create({ baseURL: '/', timeout: 15000 })

instance.interceptors.request.use((config) => {
  const token = tokenStorage.getAccess()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * 访问令牌过期时，用刷新令牌换新令牌并重放原请求。
 * 多个请求同时 401 时只发起一次刷新（共享同一个 Promise）：
 * 刷新令牌是一次性的，并发刷新会让除第一个之外的全部失败。
 */
let refreshing: Promise<string> | null = null

function refreshAccessToken(): Promise<string> {
  if (!refreshing) {
    const refreshToken = tokenStorage.getRefresh()
    refreshing = (
      refreshToken
        ? axios
            .post<
              ApiResult<{ accessToken: string; refreshToken: string }>
            >('/api/auth/refresh', { refreshToken })
            .then(({ data }) => {
              if (data.code !== 0 || !data.data) throw new Error(data.msg)
              tokenStorage.save(data.data.accessToken, data.data.refreshToken)
              return data.data.accessToken
            })
        : Promise.reject(new Error('no refresh token'))
    ).finally(() => {
      refreshing = null
    })
  }
  return refreshing
}

let onSessionExpired: () => void = () => {}

/** 由路由层注册：刷新失败时清理状态并跳转登录页 */
export function setSessionExpiredHandler(handler: () => void) {
  onSessionExpired = handler
}

// 这些接口的 401 表示口令或凭证不对，不是访问令牌过期，不能触发刷新
const AUTH_PATHS = ['/api/auth/login', '/api/auth/refresh', '/api/auth/password/expired']

instance.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiResult<unknown>>) => {
    const config = error.config as (InternalAxiosRequestConfig & { _retried?: boolean }) | undefined
    const status = error.response?.status ?? 0
    const body = error.response?.data

    if (status === 401 && config && !config._retried && !AUTH_PATHS.includes(config.url ?? '')) {
      config._retried = true
      try {
        const token = await refreshAccessToken()
        config.headers.Authorization = `Bearer ${token}`
        return instance(config)
      } catch {
        tokenStorage.clear()
        onSessionExpired()
      }
    }
    const message = body?.msg ?? (status === 0 ? '网络异常，请检查连接' : `请求失败（${status}）`)
    return Promise.reject(new ApiError(message, body?.code ?? status, status, body?.traceId))
  },
)

export interface RequestOptions {
  /** 失败时是否自动弹出提示，默认 true */
  showError?: boolean
  /** 额外请求头，如 Idempotency-Key */
  headers?: Record<string, string>
}

/** 发起请求并解包统一返回体：成功返回 data，失败抛出 ApiError */
export async function request<T>(config: AxiosRequestConfig, options: RequestOptions = {}): Promise<T> {
  const { showError = true, headers } = options
  try {
    const { data } = await instance.request<ApiResult<T>>(headers ? { ...config, headers } : config)
    if (data.code !== 0) {
      throw new ApiError(data.msg, data.code, 200, data.traceId)
    }
    return data.data as T
  } catch (e) {
    if (showError && e instanceof ApiError && e.status !== 401) {
      ElMessage.error(e.message)
    }
    throw e
  }
}

export const http = {
  get: <T>(url: string, params?: object, options?: RequestOptions) =>
    request<T>({ url, method: 'get', params }, options),
  post: <T>(url: string, data?: object, options?: RequestOptions) =>
    request<T>({ url, method: 'post', data }, options),
  put: <T>(url: string, data?: object, options?: RequestOptions) =>
    request<T>({ url, method: 'put', data }, options),
  delete: <T>(url: string, params?: object, options?: RequestOptions) =>
    request<T>({ url, method: 'delete', params }, options),
}
