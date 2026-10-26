import { http } from './http'
import type { Id, PageQuery, PageResult } from '@/types/api'

export const SLOT_TYPES = [
  { value: 1, label: '门诊', tag: 'primary' },
  { value: 2, label: '新生体检', tag: 'success' },
  { value: 3, label: '疫苗', tag: 'warning' },
] as const

export const SLOT_STATUS = [
  { value: 0, label: '未开放', tag: 'info' },
  { value: 1, label: '开放', tag: 'success' },
  { value: 2, label: '停诊', tag: 'danger' },
] as const

export interface SlotVO {
  id: Id
  campusId: Id
  deptId: Id
  deptName?: string
  doctorId: Id
  doctorName?: string
  slotType: number
  slotDate: string
  startTime: string
  endTime: string
  total: number
  remaining: number
  priceItemCode: string
  price?: string
  status: number
  openTime: string
}

export interface SlotQuery extends PageQuery {
  campusId?: Id
  deptId?: Id
  slotType?: number
  dateFrom?: string
  dateTo?: string
}

export interface SlotPeriod {
  startTime: string
  endTime: string
  total: number
}

export interface SlotBatchCreate {
  campusId: Id
  deptId: Id
  doctorId?: Id
  slotType: number
  dates: string[]
  periods: SlotPeriod[]
  priceItemCode: string
  openTime?: string
}

export interface BookingVO {
  regNo: string
  /** 0 待缴费 1 已挂号 2 已就诊 3 已取消 4 已过期 */
  status: number
  slotType: number
  campusId: Id
  deptName?: string
  doctorName?: string
  slotDate: string
  startTime: string
  endTime: string
  price?: string
  expireTime?: string
  createTime: string
}

export interface RegistrationResultVO {
  registration: BookingVO
  /** 挂号费收费单；免费号为空 */
  chargeOrder?: {
    id: Id
    chargeNo: string
    payableAmount: string
    totalAmount: string
    insuranceAmount: string
  }
}

export const scheduleApi = {
  /** 窗口挂号：可挂号的号源 */
  availableSlots: (query: { slotType: number; date: string; campusId?: Id; deptId?: Id }) =>
    http.get<SlotVO[]>('/api/schedule/registrations/slots', query),
  register: (patientId: Id, slotId: Id) =>
    http.post<RegistrationResultVO>('/api/schedule/registrations', { patientId, slotId }),
  page: (query: SlotQuery) => http.get<PageResult<SlotVO>>('/api/schedule/slots', query),
  batchCreate: (dto: SlotBatchCreate) => http.post<number>('/api/schedule/slots/batch', dto),
  changeStatus: (id: Id, status: number) => http.put<void>(`/api/schedule/slots/${id}/status`, { status }),
}
