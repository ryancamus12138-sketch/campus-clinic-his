<script setup lang="ts">
import { yuan, yuanColumn } from '@/utils/money'
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { PAY_CHANNEL, PATIENT_TYPE } from '@/api/charge'
import {
  financeApi,
  isoDay,
  ITEM_TYPE,
  type CashierWorkload,
  type DeptRevenue,
  type ReportVO,
} from '@/api/finance'

const range = ref<string[]>([isoDay(-29), isoDay()])
const report = ref<ReportVO>()
const depts = ref<DeptRevenue[]>([])
const cashiers = ref<CashierWorkload[]>([])
const loading = ref(false)

const maxDay = computed(() => Math.max(1, ...(report.value?.days ?? []).map((d) => Number(d.totalAmount))))

async function load() {
  loading.value = true
  try {
    const [from, to] = [range.value[0]!, range.value[1]!]
    ;[report.value, depts.value, cashiers.value] = await Promise.all([
      financeApi.report(from, to),
      financeApi.byDept(from, to),
      financeApi.byCashier(from, to),
    ])
  } finally {
    loading.value = false
  }
}

async function rebuild() {
  await financeApi.rebuild(isoDay(-1))
  ElMessage.success('已用流水重算昨日报表')
  load()
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="report">
    <div class="page-card">
      <div class="page-toolbar">
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" style="width: 260px" />
        <el-button type="primary" @click="load">查询</el-button>
        <div style="flex: 1" />
        <el-button @click="rebuild">校准昨日</el-button>
      </div>
      <div v-if="report" class="stats">
        <div class="stat">
          <span>费用合计</span><b>¥{{ yuan(report.total.totalAmount) }}</b>
        </div>
        <div class="stat">
          <span>医保统筹</span><b>¥{{ yuan(report.total.insuranceAmount) }}</b>
        </div>
        <div class="stat">
          <span>个人自付</span><b>¥{{ yuan(report.total.selfAmount) }}</b>
        </div>
        <div class="stat">
          <span>退费</span><b>¥{{ yuan(report.total.refundAmount) }}</b>
        </div>
        <div class="stat strong">
          <span>实收净额</span><b>¥{{ yuan(report.total.netAmount) }}</b>
        </div>
      </div>
      <p class="hint">
        历史日期读取每日预聚合（凌晨用流水校准），今天实时汇总；退费记在退费当天，不改历史日报。
      </p>
    </div>

    <div v-if="report" class="page-card">
      <div class="section-title">每日</div>
      <div v-for="d in report.days" :key="d.date" class="bar-row">
        <span class="bar-date">{{ d.date.slice(5) }}</span>
        <div class="bar-track">
          <div class="bar" :style="{ width: `${(Number(d.totalAmount) / maxDay) * 100}%` }" />
        </div>
        <span class="bar-value">¥{{ yuan(d.totalAmount) }}</span>
      </div>
      <el-empty v-if="report.days.length === 0" description="没有数据" :image-size="60" />
    </div>

    <div v-if="report" class="grid">
      <div class="page-card">
        <div class="section-title">按项目</div>
        <el-table :data="report.byItemType" size="small">
          <el-table-column label="项目">
            <template #default="{ row }">{{ ITEM_TYPE[row.key] }}</template>
          </el-table-column>
          <el-table-column prop="totalAmount" label="合计" align="right" :formatter="yuanColumn" />
          <el-table-column prop="insuranceAmount" label="统筹" align="right" :formatter="yuanColumn" />
          <el-table-column prop="netAmount" label="净收" align="right" :formatter="yuanColumn" />
        </el-table>
      </div>
      <div class="page-card">
        <div class="section-title">按渠道</div>
        <el-table :data="report.byChannel" size="small">
          <el-table-column label="渠道">
            <template #default="{ row }">{{ PAY_CHANNEL[row.key] }}</template>
          </el-table-column>
          <el-table-column prop="selfAmount" label="收款" align="right" :formatter="yuanColumn" />
          <el-table-column prop="refundAmount" label="退款" align="right" :formatter="yuanColumn" />
          <el-table-column prop="netAmount" label="净收" align="right" :formatter="yuanColumn" />
        </el-table>
      </div>
      <div class="page-card">
        <div class="section-title">按身份</div>
        <el-table :data="report.byPatientType" size="small">
          <el-table-column label="身份">
            <template #default="{ row }">{{ PATIENT_TYPE[row.key] }}</template>
          </el-table-column>
          <el-table-column prop="totalAmount" label="合计" align="right" :formatter="yuanColumn" />
          <el-table-column prop="insuranceAmount" label="统筹" align="right" :formatter="yuanColumn" />
          <el-table-column prop="netAmount" label="净收" align="right" :formatter="yuanColumn" />
        </el-table>
      </div>
    </div>

    <div v-if="report" class="grid two">
      <div class="page-card">
        <div class="section-title">科室核算（按开单科室）</div>
        <el-table :data="depts" size="small" empty-text="没有数据">
          <el-table-column label="科室">
            <template #default="{ row }">{{ row.deptName ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="orderCount" label="笔数" align="right" width="70" />
          <el-table-column prop="totalAmount" label="合计" align="right" :formatter="yuanColumn" />
          <el-table-column prop="insuranceAmount" label="统筹" align="right" :formatter="yuanColumn" />
          <el-table-column prop="refundAmount" label="退费" align="right" :formatter="yuanColumn" />
          <el-table-column prop="netAmount" label="净收入" align="right" :formatter="yuanColumn" />
        </el-table>
      </div>
      <div class="page-card">
        <div class="section-title">收费员工作量（窗口）</div>
        <el-table :data="cashiers" size="small" empty-text="没有数据">
          <el-table-column prop="cashierName" label="收费员" />
          <el-table-column prop="chargeCount" label="收费笔数" align="right" />
          <el-table-column prop="chargeAmount" label="收款" align="right" :formatter="yuanColumn" />
          <el-table-column prop="refundCount" label="退费笔数" align="right" />
          <el-table-column prop="refundAmount" label="退款" align="right" :formatter="yuanColumn" />
        </el-table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.report {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.stats {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
  margin-top: 8px;
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 16px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
}

.stat span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.stat b {
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}

.stat.strong b {
  color: var(--el-color-primary);
}

.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.section-title {
  font-weight: 600;
  margin-bottom: 12px;
}

.bar-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  margin-bottom: 4px;
}

.bar-date {
  width: 44px;
  color: var(--el-text-color-secondary);
}

.bar-track {
  flex: 1;
  height: 10px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}

.bar {
  height: 100%;
  background: var(--el-color-primary);
  border-radius: 4px;
}

.bar-value {
  width: 110px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.grid.two {
  grid-template-columns: 3fr 2fr;
}
</style>
