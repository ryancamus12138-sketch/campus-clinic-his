import { http } from './http'
import type { Id, PageQuery, PageResult } from '@/types/api'

export interface UserVO {
  id: Id
  username: string
  nickname: string
  userType: number
  campusId?: Id
  deptId?: Id
  status: number
  lastLoginTime?: string
  createTime: string
  mustChangePassword: number
  roleIds: Id[]
}

export interface UserQuery extends PageQuery {
  keyword?: string
  userType?: number
}

export interface RoleVO {
  id: Id
  code: string
  name: string
}

export interface UserSecretVO {
  id: Id
  username: string
  temporaryPassword: string
}

export interface UserForm {
  username?: string
  nickname: string
  campusId: Id
  deptId?: Id
  roleIds: Id[]
  status?: number
}

export const userApi = {
  page: (query: UserQuery) => http.get<PageResult<UserVO>>('/api/system/users', query),
  roles: () => http.get<RoleVO[]>('/api/system/roles'),
  create: (form: UserForm) => http.post<UserSecretVO>('/api/system/users', form),
  update: (id: Id, form: UserForm) => http.put<void>(`/api/system/users/${id}`, form),
  resetPassword: (id: Id) => http.post<UserSecretVO>(`/api/system/users/${id}/reset-password`),
  unlock: (id: Id) => http.post<void>(`/api/system/users/${id}/unlock`),
}
