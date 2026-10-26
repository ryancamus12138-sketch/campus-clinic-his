import { ref } from 'vue'
import { http } from './http'
import type { Id } from '@/types/api'

export interface OptionVO {
  id: Id
  name: string
  groupId?: Id
}

export interface PriceItemVO {
  itemCode: string
  itemName: string
  itemType: number
  unit: string
  unitPrice: string
  insurable: boolean
}

export interface CampusVO {
  id: Id
  name: string
  address?: string
}

export type CounterMethod = 'CASH' | 'BANK_CARD' | 'CAMPUS_CARD' | 'AUTH_CODE'

export interface ChargePointVO {
  id: Id
  campusId: Id
  code: string
  name: string
  counterMethods: CounterMethod[]
  status: number
}

const campusList = ref<CampusVO[]>([])
let campusLoading: Promise<void> | null = null

/** 校区列表（数据库维护，不写死在页面里），全局只加载一次 */
export function useCampuses() {
  if (!campusLoading) {
    campusLoading = http
      .get<CampusVO[]>('/api/system/campuses', undefined, { showError: false })
      .then((list) => {
        campusList.value = list.map((c) => ({ ...c, id: String(c.id) }))
      })
      .catch(() => {
        campusLoading = null
      })
  }
  return campusList
}

/** 校区修改后刷新全局缓存 */
export function reloadCampuses() {
  campusLoading = null
  return useCampuses()
}

export const orgApi = {
  updateCampus: (id: Id, body: { name: string; address?: string }) =>
    http.put<CampusVO>(`/api/system/campuses/${id}`, body),
  chargePoints: () => http.get<ChargePointVO[]>('/api/system/charge-points'),
  updateChargePoint: (id: Id, body: { name: string; counterMethods: CounterMethod[]; status: number }) =>
    http.put<ChargePointVO>(`/api/system/charge-points/${id}`, body),
  depts: (campusId?: Id) => http.get<OptionVO[]>('/api/system/depts', { campusId }),
  doctors: (deptId?: Id) => http.get<OptionVO[]>('/api/system/doctors', { deptId }),
  priceItems: (itemType?: number) => http.get<PriceItemVO[]>('/api/charge/price-items', { itemType }),
}
