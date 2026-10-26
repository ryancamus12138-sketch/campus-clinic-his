import type { RouteRecordRaw } from 'vue-router'
import AppLayout from '@/layout/AppLayout.vue'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** 访问所需权限码，满足其一即可；不填表示登录即可访问 */
    permission?: string | string[]
    /** 侧边栏分组 */
    group?: string
    icon?: string
    public?: boolean
  }
}

/** 业务页面。侧边栏按 meta.group 分组，并按当前用户权限过滤 */
export const appRoutes: RouteRecordRaw[] = [
  {
    path: '',
    name: 'dashboard',
    component: () => import('@/views/dashboard/DashboardView.vue'),
    meta: { title: '工作台', icon: 'HomeFilled' },
  },
  {
    path: 'schedule/slots',
    name: 'schedule-slots',
    component: () => import('@/views/schedule/SlotListView.vue'),
    meta: { title: '号源与排班', group: '挂号', icon: 'Calendar', permission: 'schedule:slot:list' },
  },
  {
    path: 'clinic/prescribe',
    name: 'clinic-prescribe',
    component: () => import('@/views/clinic/PrescribeView.vue'),
    meta: { title: '接诊开方', group: '门诊', icon: 'EditPen', permission: 'clinic:prescribe' },
  },
  {
    path: 'patient/archive',
    name: 'patient-archive',
    component: () => import('@/views/patient/ArchiveView.vue'),
    meta: { title: '患者建档', group: '门诊', icon: 'Postcard', permission: 'patient:create' },
  },
  {
    path: 'charge/settle',
    name: 'charge-settle',
    component: () => import('@/views/charge/SettleView.vue'),
    meta: { title: '收费结算', group: '收费', icon: 'Money', permission: 'charge:settle' },
  },
  {
    path: 'charge/orders',
    name: 'charge-orders',
    component: () => import('@/views/charge/OrderListView.vue'),
    meta: { title: '收费单查询', group: '收费', icon: 'Tickets', permission: 'charge:order:list' },
  },
  {
    path: 'charge/refunds',
    name: 'charge-refunds',
    component: () => import('@/views/charge/RefundListView.vue'),
    meta: {
      title: '退费',
      group: '收费',
      icon: 'RefreshLeft',
      permission: ['charge:refund:approve', 'charge:refund:apply'],
    },
  },
  {
    path: 'charge/checkout-exceptions',
    name: 'charge-checkout-exceptions',
    component: () => import('@/views/charge/CheckoutExceptionView.vue'),
    meta: { title: '收款异常', group: '收费', icon: 'Warning', permission: 'charge:exception:handle' },
  },
  {
    path: 'pharmacy/dispense',
    name: 'pharmacy-dispense',
    component: () => import('@/views/pharmacy/DispenseView.vue'),
    meta: { title: '发药退药', group: '药房', icon: 'FirstAidKit', permission: 'pharmacy:dispense' },
  },
  {
    path: 'pharmacy/stock',
    name: 'pharmacy-stock',
    component: () => import('@/views/pharmacy/StockView.vue'),
    meta: { title: '批次库存', group: '药房', icon: 'Box', permission: 'pharmacy:stock:list' },
  },
  {
    path: 'dict/drugs',
    name: 'dict-drugs',
    component: () => import('@/views/dict/DrugDictView.vue'),
    meta: { title: '药品目录', group: '字典', icon: 'Collection', permission: 'dict:drug:manage' },
  },
  {
    path: 'dict/prices',
    name: 'dict-prices',
    component: () => import('@/views/dict/PriceDictView.vue'),
    meta: { title: '收费项目', group: '字典', icon: 'PriceTag', permission: 'dict:price:manage' },
  },
  {
    path: 'finance/shifts',
    name: 'finance-shifts',
    component: () => import('@/views/finance/ShiftView.vue'),
    meta: { title: '日结交班', group: '财务', icon: 'Wallet', permission: 'finance:shift' },
  },
  {
    path: 'finance/reports',
    name: 'finance-reports',
    component: () => import('@/views/finance/ReportView.vue'),
    meta: { title: '统计报表', group: '财务', icon: 'DataLine', permission: 'finance:report' },
  },
  {
    path: 'finance/recon',
    name: 'finance-recon',
    component: () => import('@/views/finance/ReconView.vue'),
    meta: { title: '渠道对账', group: '财务', icon: 'Finished', permission: 'finance:recon' },
  },
  {
    path: 'finance/invoices',
    name: 'finance-invoices',
    component: () => import('@/views/finance/InvoiceView.vue'),
    meta: { title: '电子票据', group: '财务', icon: 'Document', permission: 'finance:invoice' },
  },
  {
    path: 'system/users',
    name: 'system-users',
    component: () => import('@/views/system/UserListView.vue'),
    meta: { title: '用户管理', group: '系统', icon: 'User', permission: 'system:user:list' },
  },
  {
    path: 'system/charge-points',
    name: 'system-charge-points',
    component: () => import('@/views/system/ChargePointView.vue'),
    meta: { title: '校区与收费点', group: '系统', icon: 'OfficeBuilding', permission: 'system:charge-point' },
  },
  {
    path: 'audit/logs',
    name: 'audit-logs',
    component: () => import('@/views/audit/AuditLogView.vue'),
    meta: { title: '操作日志', group: '审计', icon: 'View', permission: 'audit:log:view' },
  },
]

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { title: '登录', public: true },
  },
  { path: '/', component: AppLayout, children: appRoutes },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/error/ForbiddenView.vue'),
    meta: { title: '无权限' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/error/NotFoundView.vue'),
    meta: { title: '页面不存在', public: true },
  },
]
