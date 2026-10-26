import { http } from './http'
import type { Id, PageResult } from '@/types/api'

export interface DrugDetail {
  id?: Id
  drugCode: string
  insuranceCode?: string
  name: string
  spec: string
  unit: string
  goodsType: number
  retailPrice: string
  insuranceClass: number
  traceRequired: number
  manufacturer?: string
  approvalNo?: string
  safetyStock: number
  status: number
}

export interface PriceVersion {
  unitPrice: string
  insuranceClass: number
  effectiveFrom: string
  effectiveTo?: string
}

export interface PriceItemAdmin {
  itemCode: string
  itemName: string
  insuranceCode?: string
  itemType: number
  unit: string
  current: PriceVersion
  pending?: PriceVersion
}

export const ITEM_TYPES: Record<number, string> = {
  1: '挂号',
  3: '诊疗',
  4: '检查',
  6: '体检',
  7: '疫苗服务',
}

export const dictApi = {
  drugs: (keyword: string | undefined, page: number, size: number) =>
    http.get<PageResult<DrugDetail>>('/api/dict/drugs', { keyword, page, size }),
  createDrug: (d: DrugDetail) => http.post<DrugDetail>('/api/dict/drugs', d),
  updateDrug: (id: Id, d: DrugDetail) => http.put<DrugDetail>(`/api/dict/drugs/${id}`, d),
  priceItems: (itemType?: number) => http.get<PriceItemAdmin[]>('/api/dict/price-items', { itemType }),
  createPriceItem: (body: object) => http.post<PriceItemAdmin>('/api/dict/price-items', body),
  schedulePrice: (code: string, body: object) =>
    http.post<PriceItemAdmin>(`/api/dict/price-items/${code}/versions`, body),
  cancelPending: (code: string) =>
    http.delete<PriceItemAdmin>(`/api/dict/price-items/${code}/versions/pending`),
}
