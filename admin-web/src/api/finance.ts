import { http } from './http'
import type { Id, PageQuery, PageResult } from '@/types/api'

export interface ShiftVO {
  id: Id
  shiftNo: string
  cashierId: Id
  cashierName?: string
  chargePointId?: Id
  chargePointName?: string
  status: number
  startTime: string
  endTime?: string
  cashAmount: string
  cardAmount: string
  onlineAmount: string
  refundAmount: string
  expectedCash?: string
  countedCash?: string
  cashDiff?: string
  orderCount: number
  /** POS 应有：银行卡收款 − 银行卡退款 */
  posExpected?: string
  /** 收费员录入的 POS 机结算单金额 */
  posCounted?: string
  posDiff?: string
  /** 当班的银行卡收款、退款笔数；大于 0 时交班须录入 POS 结算单 */
  posCount?: number
  /** 长短款说明 */
  diffNote?: string
  reviewerId?: Id
  reviewTime?: string
  reviewNote?: string
}

export interface ReportLine {
  key?: number
  orderCount: number
  totalAmount: string
  insuranceAmount: string
  selfAmount: string
  refundAmount: string
  netAmount: string
}

export interface ReportVO {
  from: string
  to: string
  source: string
  total: ReportLine
  days: (Omit<ReportLine, 'key' | 'orderCount'> & { date: string })[]
  byItemType: ReportLine[]
  byChannel: ReportLine[]
  byPatientType: ReportLine[]
}

export interface ReconResultVO {
  date: string
  ourCount: number
  channelCount: number
  matched: number
  longCount: number
  shortCount: number
  mismatchCount: number
  /** 参与对账的已接入渠道；为空表示没有已接入的平台，本次没有对账 */
  channels: number[]
}

export interface ReconDiffVO {
  id: Id
  reconDate: string
  channel: number
  diffType: number
  payNo?: string
  channelTradeNo?: string
  ourAmount?: string
  channelAmount?: string
  status: number
  remark?: string
  createTime: string
}

export interface InvoiceVO {
  id: Id
  invoiceNo?: string
  chargeNo: string
  invoiceType: number
  amount: string
  selfAmount: string
  insuranceAmount: string
  status: number
  pdfUrl?: string
  issueTime?: string
  /** 1 平台开具 2 人工开具后登记 */
  issueMode?: number
  issuedBy?: Id
}

export const SHIFT_STATUS: Record<number, { label: string; tag: 'primary' | 'warning' | 'success' }> = {
  0: { label: '当班', tag: 'primary' },
  1: { label: '已交班', tag: 'warning' },
  2: { label: '已复核', tag: 'success' },
}
export const DIFF_TYPE: Record<number, string> = { 1: '长款', 2: '短款', 3: '金额不符' }
export const ITEM_TYPE: Record<number, string> = {
  1: '挂号费',
  2: '药品',
  3: '诊疗',
  4: '检查',
  5: '卫生材料',
  6: '体检',
  7: '疫苗服务',
}

export interface DeptRevenue {
  deptId?: Id
  deptName?: string
  orderCount: number
  totalAmount: string
  insuranceAmount: string
  selfAmount: string
  refundAmount: string
  netAmount: string
}

export interface CashierWorkload {
  cashierId: Id
  cashierName?: string
  chargeCount: number
  chargeAmount: string
  refundCount: number
  refundAmount: string
}

export const financeApi = {
  currentShift: () => http.get<ShiftVO | null>('/api/finance/shifts/current'),
  /** posCounted：POS 机结算单金额，本班有银行卡业务时必填；现金或 POS 不一致须写说明 */
  handOver: (id: Id, countedCash: string, posCounted?: string, diffNote?: string) =>
    http.post<ShiftVO>(`/api/finance/shifts/${id}/hand-over`, { countedCash, posCounted, diffNote }),
  reviewShift: (id: Id, note?: string) => http.post<ShiftVO>(`/api/finance/shifts/${id}/review`, { note }),
  shifts: (query: PageQuery & { date?: string }) =>
    http.get<PageResult<ShiftVO>>('/api/finance/shifts', query),
  report: (from: string, to: string, campusId?: Id) =>
    http.get<ReportVO>('/api/finance/reports/summary', { from, to, campusId }),
  byDept: (from: string, to: string, campusId?: Id) =>
    http.get<DeptRevenue[]>('/api/finance/reports/departments', { from, to, campusId }),
  byCashier: (from: string, to: string, campusId?: Id) =>
    http.get<CashierWorkload[]>('/api/finance/reports/cashiers', { from, to, campusId }),
  rebuild: (date: string) => http.post<number>(`/api/finance/reports/rebuild?date=${date}`),
  reconcile: (date: string) => http.post<ReconResultVO>(`/api/finance/recon/run?date=${date}`),
  diffs: (query: PageQuery & { date?: string; status?: number }) =>
    http.get<PageResult<ReconDiffVO>>('/api/finance/recon/diffs', query),
  resolveDiff: (id: Id, remark: string) =>
    http.post<void>(`/api/finance/recon/diffs/${id}/resolve?remark=${encodeURIComponent(remark)}`),
  invoices: (query: PageQuery & { chargeNo?: string }) =>
    http.get<PageResult<InvoiceVO>>('/api/finance/invoices', query),
  invoiceConnected: () => http.get<boolean>('/api/finance/invoices/connected'),
  registerManualInvoice: (id: Id, invoiceNo: string) =>
    http.post<InvoiceVO>(`/api/finance/invoices/${id}/manual-issue`, { invoiceNo }),
}

export function isoDay(offset = 0) {
  const d = new Date()
  d.setDate(d.getDate() + offset)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
