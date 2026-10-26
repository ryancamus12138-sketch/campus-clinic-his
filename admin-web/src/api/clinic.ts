import { http } from './http'
import type { Id } from '@/types/api'

export interface VisitVO {
  registrationId: Id
  regNo: string
  patientId: Id
  patientName?: string
  status: number
  slotDate: string
  startTime: string
}

export interface PrescriptionVO {
  id: Id
  rxNo: string
  status: number
  chargeNo?: string
  chargeStatus?: number
  totalAmount?: string
  insuranceAmount?: string
  payableAmount?: string
  createTime: string
}

export interface PrescriptionLine {
  kind: 1 | 2
  drugId?: Id
  itemCode?: string
  qty: number
  usageNote?: string
}

export const clinicApi = {
  visits: (date?: string) => http.get<VisitVO[]>('/api/clinic/visits', { date }),
  prescriptions: (registrationId: Id) =>
    http.get<PrescriptionVO[]>('/api/clinic/prescriptions', { registrationId }),
  prescribe: (registrationId: Id, items: PrescriptionLine[]) =>
    http.post<PrescriptionVO>('/api/clinic/prescriptions', { registrationId, items }),
}
