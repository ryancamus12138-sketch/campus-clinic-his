import { http } from './http'
import type { Id, PageQuery, PageResult } from '@/types/api'

export interface AuditLogVO {
  id: Id
  eventTime: string
  userId?: Id
  username?: string
  clientIp?: string
  userAgent?: string
  action: string
  actionName?: string
  httpMethod?: string
  uri?: string
  target?: string
  success: boolean
  httpStatus?: number
  errorCode?: number
  detail?: string
  traceId?: string
}

export interface AuditQuery extends PageQuery {
  username?: string
  action?: string
  success?: boolean
  from?: string
  to?: string
}

export const auditApi = {
  page: (query: AuditQuery) => http.get<PageResult<AuditLogVO>>('/api/audit/logs', query),
}
