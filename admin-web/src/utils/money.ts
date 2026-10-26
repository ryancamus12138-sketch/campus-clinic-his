/** 金额统一显示两位小数；空值显示 “-” */
export function yuan(v: string | number | null | undefined): string {
  if (v === null || v === undefined || v === '') return '-'
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : String(v)
}

/** el-table-column 的 formatter：金额列两位小数 */
export const yuanColumn = (_row: unknown, _col: unknown, v: string | number | null | undefined) => yuan(v)
