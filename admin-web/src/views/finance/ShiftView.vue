<script setup lang="ts">
import { yuan } from '@/utils/money'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { financeApi, isoDay, SHIFT_STATUS, type ShiftVO } from '@/api/finance'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const current = ref<ShiftVO | null>(null)
const counted = ref('')
const posCounted = ref('')
/** 本班有银行卡收款或退款：交班须录入 POS 机结算单金额 */
const hasPos = () => !!current.value && (current.value.posCount ?? 0) > 0
const rows = ref<ShiftVO[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 20, date: isoDay() as string | undefined })

async function load() {
  current.value = await financeApi.currentShift()
  const result = await financeApi.shifts(query)
  rows.value = result.list
  total.value = result.total
}

async function handOver() {
  if (!current.value || counted.value === '') return
  if (hasPos() && posCounted.value === '') {
    ElMessage.warning('本班有银行卡业务，请先打印 POS 机结算单并录入结算单金额')
    return
  }
  const diff = Number(counted.value) - Number(current.value.expectedCash)
  const posDiff = hasPos() ? Number(posCounted.value) - Number(current.value.posExpected) : 0
  let note: string | undefined
  if (diff !== 0 || posDiff !== 0) {
    // 长短款、POS 差额必须当场说明，记录在交班单上，财务复核
    const parts = [
      diff !== 0 ? `实点现金与应有相差 ¥${diff.toFixed(2)}` : '',
      posDiff !== 0 ? `POS 结算单与系统相差 ¥${posDiff.toFixed(2)}` : '',
    ].filter(Boolean)
    const { value } = await ElMessageBox.prompt(
      `${parts.join('；')}，请写明原因（如：找零差错、退费由备用金支付、POS 交易未结算）`,
      '差额说明',
      { type: 'warning', inputPattern: /\S/, inputErrorMessage: '请填写差额说明' },
    )
    note = value.trim()
  }
  await financeApi.handOver(
    current.value.id,
    Number(counted.value).toFixed(2),
    hasPos() ? Number(posCounted.value).toFixed(2) : undefined,
    note,
  )
  ElMessage.success('已交班')
  counted.value = ''
  posCounted.value = ''
  load()
}

async function review(row: ShiftVO) {
  const { value } = await ElMessageBox.prompt('复核意见（可不填）', '复核交班', { inputValue: '' })
  await financeApi.reviewShift(row.id, value?.trim() || undefined)
  ElMessage.success('已复核')
  load()
}

onMounted(load)
</script>

<template>
  <div class="shift">
    <div class="page-card">
      <div class="section-title">我的当前班次</div>
      <el-empty v-if="!current" description="当前没有当班记录，首笔窗口收费时自动开班" :image-size="60" />
      <template v-else>
        <el-descriptions :column="4" border>
          <el-descriptions-item label="班次号">{{ current.shiftNo }}</el-descriptions-item>
          <el-descriptions-item label="开班时间">{{ current.startTime }}</el-descriptions-item>
          <el-descriptions-item label="收费笔数">{{ current.orderCount }}</el-descriptions-item>
          <el-descriptions-item label="退费合计">¥{{ yuan(current.refundAmount) }}</el-descriptions-item>
          <el-descriptions-item label="现金收款">¥{{ yuan(current.cashAmount) }}</el-descriptions-item>
          <el-descriptions-item label="刷卡收款（POS）">¥{{ yuan(current.cardAmount) }}</el-descriptions-item>
          <el-descriptions-item label="钱箱应有现金">
            <b>¥{{ yuan(current.expectedCash) }}</b>
          </el-descriptions-item>
          <el-descriptions-item label="POS 应有（银行卡收 − 退）">
            <b>¥{{ yuan(current.posExpected) }}</b>
          </el-descriptions-item>
        </el-descriptions>
        <div class="handover">
          <span>实点现金</span>
          <el-input v-model="counted" style="width: 160px" placeholder="清点钱箱后填写" />
          <template v-if="hasPos()">
            <span>POS 结算单</span>
            <el-input v-model="posCounted" style="width: 160px" placeholder="POS 机结算单金额" />
          </template>
          <el-button type="primary" :disabled="counted === ''" @click="handOver">交班</el-button>
          <span class="hint"
            >交班金额由本班资金流水汇总得出；之后的收费自动进入新班次，已交班的数字不会再变。</span
          >
        </div>
      </template>
    </div>

    <div class="page-card">
      <div class="page-toolbar">
        <el-date-picker v-model="query.date" type="date" value-format="YYYY-MM-DD" clearable @change="load" />
      </div>
      <el-table :data="rows" border>
        <el-table-column label="班次 / 收费员" min-width="170">
          <template #default="{ row }">
            <div>
              {{ row.cashierName }}<span class="hint"> · {{ row.chargePointName }}</span>
            </div>
            <div class="hint">{{ row.shiftNo }}</div>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="130">
          <template #default="{ row }">
            <div>{{ row.startTime?.slice(5, 16) }}</div>
            <div class="hint">至 {{ row.endTime ? row.endTime.slice(5, 16) : '当班中' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="现金收 / 退费" width="130" align="right">
          <template #default="{ row }">{{ yuan(row.cashAmount) }} / {{ yuan(row.refundAmount) }}</template>
        </el-table-column>
        <el-table-column label="应有 / 实点" width="150" align="right">
          <template #default="{ row }">{{ yuan(row.expectedCash) }} / {{ yuan(row.countedCash) }}</template>
        </el-table-column>
        <el-table-column label="差额" width="80" align="right">
          <template #default="{ row }">
            <span :class="{ diff: row.cashDiff && Number(row.cashDiff) !== 0 }">{{
              yuan(row.cashDiff)
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="POS 应有 / 结算单" width="150" align="right">
          <template #default="{ row }">
            <span :class="{ diff: row.posDiff && Number(row.posDiff) !== 0 }"
              >{{ yuan(row.posExpected) }} / {{ yuan(row.posCounted) }}</span
            >
          </template>
        </el-table-column>
        <el-table-column label="说明 / 复核" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.diffNote ?? '' }}</span>
            <span v-if="row.reviewTime" class="hint">
              复核：{{ row.reviewNote ?? '无意见' }}（{{ row.reviewTime }}）</span
            >
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="SHIFT_STATUS[row.status].tag">{{ SHIFT_STATUS[row.status].label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="auth.hasPermission('finance:report')" label="" width="80">
          <template #default="{ row }">
            <!-- 收费员不能复核自己的班次（后端同样校验） -->
            <el-button
              v-if="row.status === 1 && String(row.cashierId) !== String(auth.user?.userId)"
              link
              type="primary"
              @click="review(row as ShiftVO)"
              >复核</el-button
            >
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="query.page"
        :page-size="query.size"
        :total="total"
        layout="total, prev, pager, next"
        class="pager"
        @current-change="load"
      />
    </div>
  </div>
</template>

<style scoped>
.shift {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-title {
  font-weight: 600;
  margin-bottom: 12px;
}

.handover {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
}

.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.diff {
  color: var(--el-color-danger);
  font-weight: 600;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
