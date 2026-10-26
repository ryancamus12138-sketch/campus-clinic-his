/** 后端统一返回体 */
export interface ApiResult<T> {
  code: number
  msg: string
  data?: T
  traceId?: string
}

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface PageQuery {
  page?: number
  size?: number
}

/** 雪花 ID 由后端序列化为字符串，避免超出 JS 安全整数范围 */
export type Id = string

export class ApiError extends Error {
  readonly code: number
  readonly status: number
  readonly traceId?: string

  constructor(message: string, code: number, status: number, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.traceId = traceId
  }
}
