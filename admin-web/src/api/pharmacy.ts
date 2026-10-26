import { http } from './http'
import type { Id } from '@/types/api'

export interface DrugOptionVO {
  id: Id
  drugCode: string
  name: string
  spec: string
  unit: string
  goodsType: number
  retailPrice: string
  insurable: boolean
  available: number
}

export interface BatchVO {
  id: Id
  batchNo: string
  expireDate: string
  onHand: number
  locked: number
  nearExpiry: boolean
}

export interface StockVO {
  drugId: Id
  drugCode: string
  name: string
  spec: string
  unit: string
  retailPrice: string
  onHand: number
  locked: number
  available: number
  safetyStock: number
  belowSafety: boolean
  batches: BatchVO[]
}

export interface DispenseItemVO {
  chargeItemId: Id
  itemCode: string
  itemName: string
  qty: number
  dispensedQty: number
  refundedQty: number
  dispensable: number
  traceRequired: boolean
  locks: { batchNo: string; expireDate: string; locked: number; dispensed: number; returned: number }[]
}

export interface DispenseOrderVO {
  chargeNo: string
  patientName?: string
  items: DispenseItemVO[]
}

export interface InboundDTO {
  campusId: Id
  drugId: Id
  batchNo: string
  expireDate: string
  purchasePrice: string
  qty: number
}

export const pharmacyApi = {
  drugs: (keyword?: string) => http.get<DrugOptionVO[]>('/api/pharmacy/drugs', { keyword }),
  stock: (campusId: Id, keyword?: string) =>
    http.get<StockVO[]>('/api/pharmacy/stock', { campusId, keyword }),
  inbound: (dto: InboundDTO) => http.post<Id>('/api/pharmacy/inbound', dto),
  dispenseView: (chargeNo: string) =>
    http.get<DispenseOrderVO>(`/api/pharmacy/dispense/${encodeURIComponent(chargeNo)}`),
  dispense: (chargeNo: string, items: { chargeItemId: Id; qty: number; traceCodes: string[] }[]) =>
    http.post<void>('/api/pharmacy/dispense', { chargeNo, items }),
  returnDrug: (dto: { chargeNo: string; chargeItemId: Id; qty: number; traceCodes: string[] }) =>
    http.post<void>('/api/pharmacy/dispense/return', dto),
}

/** 扫码枪每扫一盒输入一行；忽略空行和首尾空格 */
export function parseTraceCodes(text: string) {
  return text
    .split(/\s+/)
    .map((s) => s.trim())
    .filter(Boolean)
}
