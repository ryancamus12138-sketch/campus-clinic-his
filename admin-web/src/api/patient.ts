import { http } from './http'
import type { Id } from '@/types/api'

export interface PatientBriefVO {
  id: Id
  patientNo: string
  name: string
  schoolNo?: string
  patientType: number
  insuranceType: number
  campusId: Id
}

export interface PatientDetailVO extends PatientBriefVO {
  gender: number
  birthDate: string
  idType: number
  idNoMasked: string
  phoneMasked: string
}

export interface PatientSensitiveVO {
  id: Id
  idType: number
  idNo: string
  phone: string
}

export interface PatientCreateDTO {
  campusId: Id
  name: string
  gender: number
  birthDate: string
  patientType: number
  schoolNo?: string
  insuranceType: number
  idType: number
  idNo: string
  phone: string
}

export const ID_TYPES = [
  { value: 1, label: '居民身份证' },
  { value: 2, label: '护照' },
  { value: 3, label: '港澳台居民居住证' },
  { value: 4, label: '港澳台通行证' },
  { value: 5, label: '外国人永久居留身份证' },
]

export const PATIENT_TYPES: Record<number, string> = { 1: '学生', 2: '教职工', 3: '其他' }
export const INSURANCE_TYPES: Record<number, string> = {
  0: '自费',
  1: '大学生医保',
  2: '职工医保',
  3: '公费医疗',
}

export const patientApi = {
  search: (keyword: string) => http.get<PatientBriefVO[]>('/api/patients', { keyword }),
  create: (dto: PatientCreateDTO) => http.post<PatientDetailVO>('/api/patients', dto),
  detail: (id: Id) => http.get<PatientDetailVO>(`/api/patients/${id}`),
  reveal: (id: Id, reason: string) =>
    http.get<PatientSensitiveVO>(`/api/patients/${id}/sensitive`, { reason }),
}
