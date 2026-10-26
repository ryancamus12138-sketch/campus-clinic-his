<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CHARGE_SOURCE,
  CHARGE_STATUS,
  chargeApi,
  PAY_CHANNEL,
  PAY_STATUS,
  PATIENT_TYPE,
  REFUND_STATUS,
  newIdempotencyKey,
  type ChargeItemVO,
  type ChargeOrderQuery,
  type ChargeOrderVO,
} from '@/api/charge'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

function day(offset = 0) {
  const d = new Date()
  d.setDate(d.getDate() + offset)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const query = reactive<ChargeOrderQuery & { dateRange: string[] }>({
  page: 1,
  size: 20,
  chargeNo: '',
  status: undefined,
  dateRange: [day(-6), day()],
})
const rows = ref<ChargeOrderVO[]>([])
const total = ref(0)
const loading = ref(false)
const detail = ref<ChargeOrderVO>()
const drawer = ref(false)

const statusOf = (v: number) => CHARGE_STATUS.find((s) => s.value === v)

async function load() {
  loading.value = true
  try {
    const { dateRange, ...rest } = query
    const result = await chargeApi.page({ ...rest, dateFrom: dateRange?.[0], dateTo: dateRange?.[1] })
    rows.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

async function open(row: ChargeOrderVO) {
  detail.value = await chargeApi.detail(row.id)
  drawer.value = true
}

async function cancel(row: ChargeOrderVO) {
  await ElMessageBox.confirm(`作废收费单 ${row.chargeNo}？`, '作废', {
    type: 'warning',
  })
  await chargeApi.cancel(row.id)
  ElMessage.success('已作废')
  drawer.value = false
  load()
}

// ------------------------------------------------------------------ 退费
const refundVisible = ref(false)
const refundReason = ref('')
const refundQty = reactive<Record<string, number>>({})
let refundKey = newIdempotencyKey()

/** 可退数量 = 数量 − 已退 − 已发药（已发的需先到药房退药） */
const refundable = (i: ChargeItemVO) => i.qty - i.refundedQty - (i.dispensedQty ?? 0)
const refundStatusOf = (v: number) => REFUND_STATUS.find((s) => s.value === v)

function openRefund() {
  detail.value?.items?.forEach((i) => (refundQty[i.id!] = 0))
  refundReason.value = ''
  refundKey = newIdempotencyKey()
  refundVisible.value = true
}

async function submitRefund() {
  if (!detail.value) return
  const items = Object.entries(refundQty)
    .filter(([, qty]) => qty > 0)
    .map(([chargeItemId, qty]) => ({ chargeItemId, qty }))
  if (items.length === 0 || !refundReason.value.trim()) {
    ElMessage.warning('请选择退费数量并填写原因')
    return
  }
  const result = await chargeApi.applyRefund(
    detail.value.id,
    { items, reason: refundReason.value },
    refundKey,
  )
  ElMessage.success(
    result.status === 0 ? `退费 ¥${result.amount} 已提交，待财务审批` : `已退 ¥${result.amount}`,
  )
  refundVisible.value = false
  detail.value = await chargeApi.detail(detail.value.id)
  load()
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <el-input
        v-model="query.chargeNo"
        placeholder="收费单号"
        clearable
        style="width: 220px"
        @keyup.enter="search"
      />
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 120px">
        <el-option v-for="s in CHARGE_STATUS" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-date-picker
        v-model="query.dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        style="width: 260px"
      />
      <el-button type="primary" @click="search">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border @row-dblclick="open">
      <el-table-column prop="chargeNo" label="收费单号" min-width="190" />
      <el-table-column label="患者" width="140">
        <template #default="{ row }">{{ row.patientName }}（{{ PATIENT_TYPE[row.patientType] }}）</template>
      </el-table-column>
      <el-table-column label="来源" width="80">
        <template #default="{ row }">{{ CHARGE_SOURCE[row.sourceType] }}</template>
      </el-table-column>
      <el-table-column label="合计" width="100" align="right">
        <template #default="{ row }">¥{{ row.totalAmount }}</template>
      </el-table-column>
      <el-table-column label="医保统筹" width="100" align="right">
        <template #default="{ row }">¥{{ row.insuranceAmount }}</template>
      </el-table-column>
      <el-table-column label="个人应付" width="100" align="right">
        <template #default="{ row }">¥{{ row.payableAmount }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusOf(row.status)?.tag">{{ statusOf(row.status)?.label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="开单时间" width="170" />
      <el-table-column label="操作" width="70" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row as ChargeOrderVO)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      :total="total"
      :page-sizes="[20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      class="pager"
      @current-change="load"
      @size-change="search"
    />

    <el-drawer v-model="drawer" :title="detail?.chargeNo" size="640px">
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="患者">{{ detail.patientName }}</el-descriptions-item>
          <el-descriptions-item label="计费策略">{{ detail.feePolicyCode }}</el-descriptions-item>
          <el-descriptions-item label="合计">¥{{ detail.totalAmount }}</el-descriptions-item>
          <el-descriptions-item label="医保统筹">¥{{ detail.insuranceAmount }}</el-descriptions-item>
          <el-descriptions-item label="个人应付">¥{{ detail.payableAmount }}</el-descriptions-item>
          <el-descriptions-item label="已付">¥{{ detail.paidAmount }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusOf(detail.status)?.label }}</el-descriptions-item>
          <el-descriptions-item label="付款期限">{{ detail.expireTime }}</el-descriptions-item>
        </el-descriptions>
        <h4>明细</h4>
        <el-table :data="detail.items" border size="small">
          <el-table-column prop="itemName" label="项目" />
          <el-table-column label="单价×数量" width="110">
            <template #default="{ row }">{{ row.unitPrice }} × {{ row.qty }}</template>
          </el-table-column>
          <el-table-column label="比例" width="70">
            <template #default="{ row }">{{ Math.round(Number(row.insuranceRatio) * 100) }}%</template>
          </el-table-column>
          <el-table-column prop="insuranceAmount" label="统筹" width="80" />
          <el-table-column prop="selfAmount" label="自付" width="80" />
        </el-table>
        <h4>支付记录</h4>
        <el-table :data="detail.pays" border size="small" empty-text="暂无">
          <el-table-column prop="payNo" label="支付单号" min-width="150" />
          <el-table-column label="渠道" width="70">
            <template #default="{ row }">{{ PAY_CHANNEL[row.channel] }}</template>
          </el-table-column>
          <el-table-column prop="amount" label="金额" width="80" />
          <el-table-column label="状态" width="70">
            <template #default="{ row }">{{ PAY_STATUS[row.status] }}</template>
          </el-table-column>
          <el-table-column prop="successTime" label="成功时间" width="160" />
        </el-table>
        <template v-if="detail.refunds?.length">
          <h4>退费记录</h4>
          <el-table :data="detail.refunds" border size="small">
            <el-table-column prop="refundNo" label="退费单号" min-width="150" />
            <el-table-column prop="amount" label="退还患者" width="90" />
            <el-table-column prop="insuranceAmount" label="医保冲销" width="90" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">{{ refundStatusOf(row.status)?.label }}</template>
            </el-table-column>
            <el-table-column prop="reason" label="原因" />
          </el-table>
        </template>
        <div class="actions">
          <el-button
            v-if="detail.status === 0 && auth.hasPermission('charge:settle')"
            type="danger"
            plain
            @click="cancel(detail)"
          >
            作废
          </el-button>
          <el-button
            v-if="(detail.status === 2 || detail.status === 3) && auth.hasPermission('charge:refund:apply')"
            type="warning"
            plain
            @click="openRefund"
          >
            申请退费
          </el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="refundVisible" title="申请退费" width="620px">
      <el-table v-if="detail" :data="detail.items" size="small" border>
        <el-table-column prop="itemName" label="项目" min-width="160" />
        <el-table-column label="数量/已退/已发药" width="140">
          <template #default="{ row }"
            >{{ row.qty }} / {{ row.refundedQty }} / {{ row.dispensedQty ?? 0 }}</template
          >
        </el-table-column>
        <el-table-column label="本次退" width="150">
          <template #default="{ row }">
            <el-input-number
              v-model="refundQty[row.id]"
              :min="0"
              :max="refundable(row as ChargeItemVO)"
              :disabled="refundable(row as ChargeItemVO) === 0"
              size="small"
            />
          </template>
        </el-table-column>
      </el-table>
      <p class="hint">
        已发药的药品需先由药房办理退药；退款按原收款方式退回。有医保结算的，需在医保结算终端撤销并重新结算后，在“退费”中登记。
      </p>
      <el-input v-model="refundReason" placeholder="退费原因" maxlength="200" />
      <template #footer>
        <el-button @click="refundVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRefund">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}

.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

h4 {
  margin: 20px 0 8px;
}

.actions {
  margin-top: 20px;
  text-align: right;
}
</style>
