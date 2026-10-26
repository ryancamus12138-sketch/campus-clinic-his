import { http } from './http'
import type { Id, PageQuery, PageResult } from '@/types/api'

export const CHARGE_STATUS = [
  { value: 0, label: '待支付', tag: 'warning' },
  { value: 2, label: '已支付', tag: 'success' },
  { value: 3, label: '部分退费', tag: 'info' },
  { value: 4, label: '已全退', tag: 'info' },
  { value: 5, label: '已关闭', tag: 'info' },
] as const

export const CHARGE_SOURCE: Record<number, string> = { 1: '挂号', 2: '门诊', 3: '体检', 4: '疫苗' }
export const PAY_CHANNEL: Record<number, string> = {
  1: '现金',
  2: '校园卡',
  3: '支付宝',
  4: '微信',
  5: '医保个账',
  6: '银行卡',
  9: '医保基金',
}
export const PAY_STATUS: Record<number, string> = { 0: '待支付', 1: '成功', 2: '失败', 3: '已关闭' }
export const PATIENT_TYPE: Record<number, string> = { 1: '学生', 2: '教职工', 3: '其他' }
export const INSURANCE_TYPE: Record<number, string> = {
  0: '自费',
  1: '大学生医保',
  2: '职工医保',
  3: '公费医疗',
}

export interface PatientBriefVO {
  id: Id
  patientNo: string
  name: string
  schoolNo?: string
  patientType: number
  insuranceType: number
  campusId: Id
}

export interface ChargeItemVO {
  id?: Id
  itemCode: string
  itemName: string
  itemType: number
  unitPrice: string
  qty: number
  amount: string
  insuranceRatio: string
  insuranceAmount: string
  selfAmount: string
  refundedQty: number
  dispensedQty?: number
}

export interface PayOrderVO {
  payNo: string
  channel: number
  amount: string
  status: number
  channelTradeNo?: string
  successTime?: string
  createTime: string
}

export const REFUND_STATUS = [
  { value: 0, label: '待审核', tag: 'warning' },
  { value: 1, label: '退款中', tag: 'primary' },
  { value: 2, label: '成功', tag: 'success' },
  { value: 3, label: '失败', tag: 'danger' },
  { value: 4, label: '已驳回', tag: 'info' },
] as const

/** 退款按原收款方式拆分；现金、银行卡需收费员确认 */
export interface RefundTenderVO {
  id: Id
  channel: number
  amount: string
  /** 0 待退 1 已退 2 失败 3 待收费员确认 */
  status: number
  channelRefundNo?: string
  confirmBy?: Id
  confirmTime?: string
  confirmNote?: string
}

export const REFUND_TENDER_STATUS: Record<
  number,
  { label: string; tag: 'warning' | 'info' | 'success' | 'danger' }
> = {
  0: { label: '待退', tag: 'info' },
  1: { label: '已退', tag: 'success' },
  2: { label: '失败', tag: 'danger' },
  3: { label: '待收费员确认', tag: 'warning' },
}

export interface RefundVO {
  id: Id
  refundNo: string
  chargeOrderId: Id
  chargeNo?: string
  amount: string
  insuranceAmount: string
  status: number
  reason: string
  createTime: string
  successTime?: string
  items: { chargeItemId: Id; qty: number; amount: string; insuranceAmount: string; selfAmount: string }[]
  tenders: RefundTenderVO[]
  /** 医保平台未接入：需在医保终端撤销原结算并重结后登记 */
  awaitingInsuranceRegistration: boolean
  applicantId?: Id
  applicantName?: string
  /** 审批人；自动通过的为空 */
  approverId?: Id
  approverName?: string
  approveTime?: string
  /** 审批意见；驳回时为驳回理由 */
  approveNote?: string
}

/** 医保结算平台未接入时，收费员在医保结算终端办理后登记的结果 */
export interface InsuranceRegistration {
  setlId: string
  fundPay: string
}

/** 外部平台接入状态 */
export interface PlatformStatus {
  insurance: boolean
  campusCard: boolean
  authCode: boolean
}

export interface ChargeOrderVO {
  id: Id
  chargeNo: string
  patientId: Id
  patientName?: string
  patientType: number
  sourceType: number
  status: number
  totalAmount: string
  insuranceAmount: string
  payableAmount: string
  paidAmount: string
  refundedAmount: string
  feePolicyCode: string
  expireTime: string
  paidTime?: string
  createTime: string
  items?: ChargeItemVO[]
  pays?: PayOrderVO[]
  refunds?: RefundVO[]
}

export interface ChargePreviewVO {
  feePolicyCode: string
  feePolicyName: string
  items: ChargeItemVO[]
  totalAmount: string
  insuranceAmount: string
  payableAmount: string
}

/** 窗口收款方式 */
export type TenderMethod = 'CASH' | 'CAMPUS_CARD' | 'BANK_CARD' | 'AUTH_CODE'

export const TENDER_METHOD: Record<TenderMethod, string> = {
  CASH: '现金',
  CAMPUS_CARD: '校园卡',
  BANK_CARD: '银行卡（POS）',
  AUTH_CODE: '付款码',
}

/** 就诊凭证类型 */
export const CERT_TYPE: Record<string, string> = {
  '01': '医保电子凭证',
  '02': '居民身份证',
  '03': '社会保障卡',
}

export interface CheckoutTender {
  method: TenderMethod
  amount: string
  received?: string
  cardNo?: string
  reference?: string
  authCode?: string
}

export interface CheckoutResultVO {
  chargeNo: string
  totalAmount: string
  fundPay: string
  selfAmount: string
  setlId?: string
  change: string
  tenders: { payNo: string; channel: number; amount: string; channelTradeNo?: string; tenderRef?: string }[]
}

export interface CheckoutExceptionVO {
  id: Id
  chargeNo: string
  cashierId: Id
  cashierName?: string
  /** 3 待处理 4 已处理 */
  status: number
  failReason?: string
  createTime: string
  resolverId?: Id
  resolverName?: string
  resolveTime?: string
  resolveNote?: string
  /** source：1 平台结算 3 医保终端办理后登记；status：0 结算中（结果未知） 1 已结算（未撤销） 2 已撤销 */
  insurance: {
    bizNo: string
    setlId?: string
    source: number
    status: number
    fundPay?: string
    cancelNote?: string
  }[]
  /** status：0 收取中（结果未知） 1 已收 2 已撤回 3 撤回失败 4 已核实退还 5 已核实未扣款 */
  tenders: CheckoutExceptionTender[]
}

export interface CheckoutExceptionTender {
  id: Id
  payNo: string
  channel: number
  amount: string
  status: number
  channelTradeNo?: string
  tenderRef?: string
  /** 可能已扣款，需要财务逐笔核实 */
  needsVerification: boolean
  verifyBy?: Id
  verifyByName?: string
  verifyTime?: string
  verifyRef?: string
  verifyNote?: string
}

export type TenderVerifyResult = 'RETURNED' | 'NOT_CHARGED'

export const CHECKOUT_TENDER_STATUS: Record<
  number,
  { label: string; tag: 'warning' | 'info' | 'success' | 'danger' }
> = {
  0: { label: '结果未知', tag: 'warning' },
  1: { label: '已收', tag: 'info' },
  2: { label: '已撤回', tag: 'success' },
  3: { label: '撤回失败', tag: 'danger' },
  4: { label: '已核实退还', tag: 'success' },
  5: { label: '已核实未扣款', tag: 'success' },
  6: { label: '现金待确认退还', tag: 'danger' },
}

export interface ChargeCreate {
  patientId: Id
  items: { itemCode: string; qty: number }[]
}

export interface ChargeOrderQuery extends PageQuery {
  chargeNo?: string
  patientId?: Id
  status?: number
  dateFrom?: string
  dateTo?: string
}

/** 每次结算生成一个幂等键；网络重试沿用同一个，服务端保证只收一次 */
export function newIdempotencyKey() {
  return crypto.randomUUID()
}

export const chargeApi = {
  searchPatients: (keyword: string) => http.get<PatientBriefVO[]>('/api/patients', { keyword }),
  preview: (dto: ChargeCreate) => http.post<ChargePreviewVO>('/api/charge/orders/preview', dto),
  create: (dto: ChargeCreate) => http.post<ChargeOrderVO>('/api/charge/orders', dto),
  page: (query: ChargeOrderQuery) => http.get<PageResult<ChargeOrderVO>>('/api/charge/orders', query),
  detail: (id: Id) => http.get<ChargeOrderVO>(`/api/charge/orders/${id}`),
  /** 收款异常（撤回失败、医保撤销失败、中途中断）；resolved=true 为已处理记录 */
  checkoutExceptions: (resolved = false) =>
    http.get<CheckoutExceptionVO[]>('/api/charge/checkouts/exceptions', { resolved }),
  retryInsuranceCancel: (id: Id) => http.post<boolean>(`/api/charge/checkouts/${id}/retry-insurance-cancel`),
  /** 逐笔核实一笔可能已扣款的收款 */
  verifyCheckoutTender: (
    id: Id,
    tenderId: Id,
    body: { result: TenderVerifyResult; reference?: string; note?: string },
  ) => http.post<void>(`/api/charge/checkouts/${id}/tenders/${tenderId}/verify`, body),
  resolveCheckout: (id: Id, note: string, insuranceCancelledManually: boolean) =>
    http.post<void>(`/api/charge/checkouts/${id}/resolve`, { note, insuranceCancelledManually }),
  /** 收费点开放、且对应平台已接入的窗口收款方式 */
  tenderMethods: (campusId?: Id) =>
    http.get<TenderMethod[]>('/api/charge/orders/tender-methods', { campusId }),
  platforms: () => http.get<PlatformStatus>('/api/charge/orders/platforms'),
  /** 窗口结算：登记医保结算结果 + 一种或多种方式收取自付 */
  checkout: (
    id: Id,
    body: {
      tenders: CheckoutTender[]
      certType?: string
      certNo?: string
      insurance?: InsuranceRegistration
    },
    idempotencyKey: string,
  ) =>
    http.post<CheckoutResultVO>(`/api/charge/orders/${id}/checkout`, body, {
      headers: { 'Idempotency-Key': idempotencyKey },
    }),
  cancel: (id: Id) => http.post<void>(`/api/charge/orders/${id}/cancel`),
  applyRefund: (
    id: Id,
    dto: { items: { chargeItemId: Id; qty: number }[]; reason: string },
    idempotencyKey: string,
  ) =>
    http.post<RefundVO>(`/api/charge/orders/${id}/refunds`, dto, {
      headers: { 'Idempotency-Key': idempotencyKey },
    }),
  refunds: (query: PageQuery & { status?: number }) =>
    http.get<PageResult<RefundVO>>('/api/charge/refunds', query),
  approveRefund: (id: Id, note?: string) =>
    http.post<RefundVO>(`/api/charge/refunds/${id}/approve`, { note }),
  /** 驳回必须写明理由 */
  rejectRefund: (id: Id, note: string) => http.post<void>(`/api/charge/refunds/${id}/reject`, { note }),
  /** 收费员确认已当面退还现金，或已在 POS 机上退货（填退货参考号） */
  confirmRefundTender: (id: Id, tenderId: Id, body: { reference?: string; note?: string }) =>
    http.post<RefundVO>(`/api/charge/refunds/${id}/tenders/${tenderId}/confirm`, body),
  /** 医保平台未接入：登记医保终端上的撤销与重结 */
  registerRefundInsurance: (id: Id, resettlement?: InsuranceRegistration) =>
    http.post<RefundVO>(`/api/charge/refunds/${id}/insurance-registration`, { resettlement }),
}
