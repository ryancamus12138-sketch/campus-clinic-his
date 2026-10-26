import type { Component } from 'vue'
import { EditPen, FirstAidKit, Money, Setting, View, Wallet } from '@element-plus/icons-vue'

/**
 * 登录身份的说明与排列顺序。可选身份本身来自后端（系统中启用的角色），这里只补充说明文字和图标；
 * 说明文字对应各角色在系统中实际拥有的菜单权限。未在此列出的角色按名称显示。
 */
export const LOGIN_ROLE_INFO: Record<string, { duty: string; icon: Component; order: number }> = {
  CASHIER: { duty: '窗口挂号、划价收费、退费申请、日结交班', icon: Money, order: 1 },
  FINANCE: { duty: '退费审批、收款异常处理、交班复核、报表、对账与票据', icon: Wallet, order: 2 },
  DOCTOR: { duty: '接诊开方', icon: EditPen, order: 3 },
  PHARMACIST: { duty: '发药退药、批次库存', icon: FirstAidKit, order: 4 },
  ADMIN: { duty: '账号与角色、校区与收费点、收费项目与药品目录', icon: Setting, order: 5 },
  AUDITOR: { duty: '查看操作日志', icon: View, order: 6 },
}

export function sortLoginRoles<T extends { code: string }>(roles: T[]): T[] {
  return [...roles].sort(
    (a, b) => (LOGIN_ROLE_INFO[a.code]?.order ?? 99) - (LOGIN_ROLE_INFO[b.code]?.order ?? 99),
  )
}
