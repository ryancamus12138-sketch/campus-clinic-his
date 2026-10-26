<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CHARGE_STATUS } from '@/api/charge'
import { clinicApi, type PrescriptionLine, type PrescriptionVO, type VisitVO } from '@/api/clinic'
import { orgApi, type PriceItemVO } from '@/api/org'
import { pharmacyApi, type DrugOptionVO } from '@/api/pharmacy'

function today() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

interface Row extends PrescriptionLine {
  name: string
  unit: string
  price: string
  available?: number
}

const date = ref(today())
const visits = ref<VisitVO[]>([])
const visit = ref<VisitVO>()
const history = ref<PrescriptionVO[]>([])
const rows = ref<Row[]>([])
const drugs = ref<DrugOptionVO[]>([])
const treatments = ref<PriceItemVO[]>([])
const submitting = ref(false)
const lastResult = ref<PrescriptionVO>()

const estimate = computed(() => rows.value.reduce((sum, r) => sum + Number(r.price) * r.qty, 0).toFixed(2))

async function loadVisits() {
  visits.value = await clinicApi.visits(date.value)
}

async function pick(v: VisitVO | null | undefined) {
  if (!v) return
  visit.value = v
  rows.value = []
  lastResult.value = undefined
  history.value = await clinicApi.prescriptions(v.registrationId)
}

async function searchDrugs(keyword: string) {
  drugs.value = await pharmacyApi.drugs(keyword)
}

function addDrug(id: string) {
  const d = drugs.value.find((x) => x.id === id)
  if (!d) return
  rows.value.push({
    kind: 1,
    drugId: d.id,
    qty: 1,
    usageNote: '',
    name: `${d.name} ${d.spec}`,
    unit: d.unit,
    price: d.retailPrice,
    available: d.available,
  })
}

function addTreatment(code: string) {
  const t = treatments.value.find((x) => x.itemCode === code)
  if (!t) return
  rows.value.push({
    kind: 2,
    itemCode: t.itemCode,
    qty: 1,
    name: t.itemName,
    unit: t.unit,
    price: t.unitPrice,
  })
}

async function submit() {
  if (!visit.value || rows.value.length === 0) return
  submitting.value = true
  try {
    lastResult.value = await clinicApi.prescribe(
      visit.value.registrationId,
      rows.value.map(({ kind, drugId, itemCode, qty, usageNote }) => ({
        kind,
        drugId,
        itemCode,
        qty,
        usageNote,
      })),
    )
    ElMessage.success('处方已提交，请患者到收费窗口缴费')
    rows.value = []
    history.value = await clinicApi.prescriptions(visit.value.registrationId)
    loadVisits()
  } finally {
    submitting.value = false
  }
}

const statusOf = (v?: number) => CHARGE_STATUS.find((s) => s.value === v)

watch(date, loadVisits)
onMounted(async () => {
  loadVisits()
  searchDrugs('')
  const all = await Promise.all([3, 4].map((t) => orgApi.priceItems(t)))
  treatments.value = all.flat()
})
</script>

<template>
  <div class="prescribe">
    <div class="page-card visits">
      <div class="page-toolbar">
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
        <el-button @click="loadVisits">刷新</el-button>
      </div>
      <el-table :data="visits" highlight-current-row empty-text="当天没有门诊挂号" @current-change="pick">
        <el-table-column prop="startTime" label="时段" width="70" />
        <el-table-column prop="patientName" label="患者" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'warning' : 'success'" size="small">
              {{ row.status === 1 ? '候诊' : '已就诊' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="page-card editor">
      <el-empty v-if="!visit" description="从左侧选择一位患者接诊" />
      <template v-else>
        <div class="section-title">{{ visit.patientName }} · 挂号 {{ visit.regNo }}</div>
        <div class="page-toolbar">
          <el-select
            placeholder="添加药品（输入名称或编码）"
            filterable
            remote
            :remote-method="searchDrugs"
            :model-value="''"
            style="width: 320px"
            @change="addDrug"
          >
            <el-option
              v-for="d in drugs"
              :key="d.id"
              :value="d.id"
              :label="`${d.name} ${d.spec}  ¥${d.retailPrice}`"
              :disabled="d.available === 0"
            >
              <span>{{ d.name }} {{ d.spec }}</span>
              <span class="option-meta">¥{{ d.retailPrice }} · 可用 {{ d.available }}{{ d.unit }}</span>
            </el-option>
          </el-select>
          <el-select
            placeholder="添加诊疗/检查"
            filterable
            :model-value="''"
            style="width: 240px"
            @change="addTreatment"
          >
            <el-option
              v-for="t in treatments"
              :key="t.itemCode"
              :value="t.itemCode"
              :label="`${t.itemName}  ¥${t.unitPrice}`"
            />
          </el-select>
        </div>
        <el-table :data="rows" border empty-text="尚未添加">
          <el-table-column prop="name" label="名称" min-width="180" />
          <el-table-column label="数量" width="150">
            <template #default="{ row }">
              <el-input-number
                v-model="row.qty"
                :min="1"
                :max="Math.min(99, row.available ?? 99)"
                size="small"
              />
            </template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="60" />
          <el-table-column label="用法用量" min-width="180">
            <template #default="{ row }">
              <el-input
                v-if="row.kind === 1"
                v-model="row.usageNote"
                size="small"
                placeholder="每日 2 次，每次 1 粒"
              />
            </template>
          </el-table-column>
          <el-table-column label="" width="60">
            <template #default="{ $index }">
              <el-button link type="danger" @click="rows.splice($index, 1)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="actions">
          <span>原价合计约 ¥{{ estimate }}（医保报销后自付以收费单为准）</span>
          <el-button type="primary" :loading="submitting" :disabled="rows.length === 0" @click="submit">
            提交处方
          </el-button>
        </div>
        <el-alert
          v-if="lastResult"
          type="success"
          :closable="false"
          :title="`收费单 ${lastResult.chargeNo}：合计 ¥${lastResult.totalAmount}，医保 ¥${lastResult.insuranceAmount}，自付 ¥${lastResult.payableAmount}`"
        />

        <div class="section-title history">本次就诊的处方</div>
        <el-table :data="history" size="small" empty-text="暂无">
          <el-table-column prop="rxNo" label="处方号" />
          <el-table-column prop="chargeNo" label="收费单" />
          <el-table-column label="自付" width="90">
            <template #default="{ row }">¥{{ row.payableAmount }}</template>
          </el-table-column>
          <el-table-column label="缴费" width="90">
            <template #default="{ row }">
              <el-tag :type="statusOf(row.chargeStatus)?.tag" size="small">
                {{ statusOf(row.chargeStatus)?.label }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </div>
  </div>
</template>

<style scoped>
.prescribe {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 16px;
  align-items: start;
}

.section-title {
  font-weight: 600;
  margin-bottom: 12px;
}

.history {
  margin-top: 24px;
}

.option-meta {
  float: right;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 16px;
  margin: 16px 0;
  color: var(--el-text-color-regular);
}
</style>
