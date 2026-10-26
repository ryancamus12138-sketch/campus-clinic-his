<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  CERT_TYPE,
  chargeApi,
  INSURANCE_TYPE,
  newIdempotencyKey,
  PATIENT_TYPE,
  PAY_CHANNEL,
  TENDER_METHOD,
  type ChargeOrderVO,
  type ChargePreviewVO,
  type CheckoutResultVO,
  type CheckoutTender,
  type PatientBriefVO,
  type PlatformStatus,
  type TenderMethod,
} from '@/api/charge'
import { orgApi, useCampuses, type PriceItemVO } from '@/api/org'
import { scheduleApi, SLOT_TYPES, type SlotVO } from '@/api/schedule'

/** 收费处可手工划价的项目：诊疗、检查（挂号、体检、疫苗在窗口挂号时生成收费单） */
const COUNTER_ITEM_TYPES = [3, 4]

const keyword = ref('')
const patients = ref<PatientBriefVO[]>([])
const patient = ref<PatientBriefVO>()
const priceItems = ref<PriceItemVO[]>([])
const lines = ref<{ itemCode: string; qty: number }[]>([])
const preview = ref<ChargePreviewVO>()
const order = ref<ChargeOrderVO>()
/** 收款方式：默认一行现金，可拆成多种方式组合付款（如 现金 + 银行卡） */
const tenders = ref<CheckoutTender[]>([])
const certType = ref('02')
const certNo = ref('')
/** 医保结算平台未接入时：收费员在医保结算终端结算后登记 */
const insSetlId = ref('')
const insFundPay = ref('')
// 默认按“未接入”处理：查询失败时也不会显示依赖外部平台的功能
const platforms = ref<PlatformStatus>({ insurance: false, campusCard: false, authCode: false })
const result = ref<CheckoutResultVO>()
const busy = ref(false)
/** 一次结算一个幂等键：网络超时后重试沿用，避免重复收费 */
let payKey = newIdempotencyKey()

const itemName = (code: string) => priceItems.value.find((p) => p.itemCode === code)?.itemName ?? code
const availableItems = computed(() =>
  priceItems.value.filter((p) => !lines.value.some((l) => l.itemCode === p.itemCode)),
)
const cents = (v?: string) => Math.round(Number(v || 0) * 100)
/** 尚未分配到收款方式的金额（分） */
const unallocated = computed(() =>
  order.value ? cents(order.value.payableAmount) - tenders.value.reduce((s, t) => s + cents(t.amount), 0) : 0,
)
const cashTender = computed(() => tenders.value.find((t) => t.method === 'CASH'))
const change = computed(() => {
  const t = cashTender.value
  if (!t || !t.received) return 0
  return (cents(t.received) - cents(t.amount)) / 100
})
const insured = computed(() => !!order.value && cents(order.value.insuranceAmount) > 0)
const manualInsurance = computed(() => insured.value && !platforms.value.insurance)
const tenderReady = computed(
  () =>
    unallocated.value === 0 &&
    (!manualInsurance.value || (/^[A-Za-z0-9-]{4,64}$/.test(insSetlId.value.trim()) && !!insFundPay.value)) &&
    change.value >= 0 &&
    tenders.value.every(
      (t) =>
        cents(t.amount) > 0 &&
        (t.method !== 'CAMPUS_CARD' || !!t.cardNo) &&
        (t.method !== 'BANK_CARD' || !!t.reference) &&
        (t.method !== 'AUTH_CODE' || !!t.authCode),
    ),
)

function startTenders(o: ChargeOrderVO) {
  tenders.value = [{ method: 'CASH', amount: o.payableAmount, received: o.payableAmount }]
  certType.value = '02'
  certNo.value = ''
  insSetlId.value = ''
  insFundPay.value = o.insuranceAmount
}

function addTender(method: TenderMethod) {
  const rest = Math.max(unallocated.value, 0) / 100
  tenders.value.push({ method, amount: rest.toFixed(2) })
}

function removeTender(index: number) {
  tenders.value.splice(index, 1)
}

async function search() {
  if (!keyword.value.trim()) return
  patients.value = await chargeApi.searchPatients(keyword.value.trim())
  if (patients.value.length === 1) pick(patients.value[0])
  else if (patients.value.length === 0) ElMessage.info('未找到患者，请核对就诊卡号或学号/工号')
}

/** 该患者待缴费的收费单（医生开方生成的、之前划价未缴的） */
const unpaid = ref<ChargeOrderVO[]>([])

async function pick(p: PatientBriefVO) {
  patient.value = p
  reset()
  // 收费点开放、且平台已接入的收款方式（按患者所在校区的收费点）
  methods.value = await chargeApi.tenderMethods(p.campusId).catch(() => methods.value)
  await loadUnpaid()
}

async function loadUnpaid() {
  if (!patient.value) return
  unpaid.value = (await chargeApi.page({ patientId: patient.value.id, status: 0, page: 1, size: 20 })).list
}

// ------------------------------------------------------------------ 窗口挂号

const campuses = useCampuses()
/** 本地日期；toISOString 是 UTC，早上 8 点前会变成前一天 */
function localToday() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const reg = ref({ slotType: 1, date: localToday(), campusId: '' as string })
const slots = ref<SlotVO[]>([])

async function loadSlots() {
  if (!patient.value) return
  slots.value = await scheduleApi.availableSlots({
    slotType: reg.value.slotType,
    date: reg.value.date,
    campusId: reg.value.campusId || patient.value.campusId,
  })
}

async function register(slot: SlotVO) {
  if (!patient.value) return
  const r = await scheduleApi.register(patient.value.id, slot.id)
  if (r.chargeOrder) {
    ElMessage.success(`已挂号 ${r.registration.regNo}，请收取挂号费`)
    await loadUnpaid()
    const o = unpaid.value.find((u) => u.id === r.chargeOrder!.id)
    if (o) collect(o)
  } else {
    ElMessage.success(`已挂号 ${r.registration.regNo}（免费号）`)
  }
  await loadSlots()
}

function collect(o: ChargeOrderVO) {
  lines.value = []
  order.value = o
  startTenders(o)
  result.value = undefined
  payKey = newIdempotencyKey()
}

function reset() {
  lines.value = []
  preview.value = undefined
  order.value = undefined
  result.value = undefined
  tenders.value = []
  payKey = newIdempotencyKey()
}

function addItem(code: string) {
  if (code) lines.value.push({ itemCode: code, qty: 1 })
}

function removeItem(index: number) {
  lines.value.splice(index, 1)
}

// 明细变化后实时试算医保与自付金额
let previewSeq = 0
watch(
  lines,
  async (value) => {
    preview.value = undefined
    if (!patient.value || value.length === 0) return
    const seq = ++previewSeq
    const data = await chargeApi.preview({ patientId: patient.value.id, items: value })
    if (seq === previewSeq) preview.value = data
  },
  { deep: true },
)

async function createOrder() {
  if (!patient.value || lines.value.length === 0) return
  busy.value = true
  try {
    order.value = await chargeApi.create({ patientId: patient.value.id, items: lines.value })
    startTenders(order.value)
  } finally {
    busy.value = false
  }
}

async function checkout() {
  if (!order.value || !tenderReady.value) return
  busy.value = true
  try {
    const body = tenders.value.map((t) => ({
      ...t,
      amount: Number(t.amount).toFixed(2),
      received: t.method === 'CASH' && t.received ? Number(t.received).toFixed(2) : undefined,
    }))
    result.value = await chargeApi.checkout(
      order.value.id,
      {
        tenders: body,
        certType: insured.value ? certType.value : undefined,
        certNo: insured.value && certNo.value ? certNo.value : undefined,
        insurance: manualInsurance.value
          ? { setlId: insSetlId.value.trim(), fundPay: Number(insFundPay.value).toFixed(2) }
          : undefined,
      },
      payKey,
    )
    ElMessage.success('收费成功')
    await loadUnpaid()
  } finally {
    // 失败时不换幂等键：网络超时后重试会拿到第一次的结果，不会重复扣款；
    // 失败后同一个键下没有支付记录：全部撤回的可直接重收；转为收款异常的，财务处理前后端会拒绝再次收费
    busy.value = false
  }
}

async function cancelOrder() {
  if (!order.value) return
  await chargeApi.cancel(order.value.id)
  ElMessage.success('已作废')
  order.value = undefined
  payKey = newIdempotencyKey()
}

/** 收费点开放、且平台已接入的窗口收款方式 */
const methods = ref<TenderMethod[]>(['CASH', 'BANK_CARD'])

onMounted(async () => {
  platforms.value = await chargeApi.platforms().catch(() => platforms.value)
  const all = await Promise.all(COUNTER_ITEM_TYPES.map((t) => orgApi.priceItems(t)))
  priceItems.value = all.flat()
})
</script>

<template>
  <div class="settle">
    <div class="page-card">
      <div class="page-toolbar">
        <el-input
          v-model="keyword"
          placeholder="就诊卡号 / 学号 / 工号"
          clearable
          style="width: 260px"
          @keyup.enter="search"
        />
        <el-button type="primary" @click="search">检索患者</el-button>
      </div>
      <el-table
        v-if="patients.length > 1"
        :data="patients"
        highlight-current-row
        size="small"
        @current-change="(p: PatientBriefVO | null) => p && pick(p)"
      >
        <el-table-column prop="patientNo" label="就诊卡号" />
        <el-table-column prop="name" label="姓名" />
        <el-table-column prop="schoolNo" label="学号/工号" />
      </el-table>
      <el-descriptions v-if="patient" :column="4" border class="patient">
        <el-descriptions-item label="姓名">{{ patient.name }}</el-descriptions-item>
        <el-descriptions-item label="就诊卡号">{{ patient.patientNo }}</el-descriptions-item>
        <el-descriptions-item label="身份">{{ PATIENT_TYPE[patient.patientType] }}</el-descriptions-item>
        <el-descriptions-item label="参保">
          <el-tag :type="patient.insuranceType === 0 ? 'info' : 'success'">
            {{ INSURANCE_TYPE[patient.insuranceType] }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <div v-if="patient" class="page-card">
      <div class="section-title">窗口挂号</div>
      <div class="page-toolbar">
        <el-select v-model="reg.slotType" style="width: 120px">
          <el-option v-for="t in SLOT_TYPES" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
        <el-date-picker v-model="reg.date" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
        <el-select v-model="reg.campusId" placeholder="患者所在校区" clearable style="width: 150px">
          <el-option v-for="c in campuses" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-button @click="loadSlots">查询号源</el-button>
      </div>
      <el-table v-if="slots.length" :data="slots" size="small" border>
        <el-table-column prop="deptName" label="科室" min-width="120" />
        <el-table-column prop="doctorName" label="医生" width="100" />
        <el-table-column label="时段" width="130">
          <template #default="{ row }">{{ row.startTime }}–{{ row.endTime }}</template>
        </el-table-column>
        <el-table-column label="挂号费" width="90">
          <template #default="{ row }">¥{{ row.price }}</template>
        </el-table-column>
        <el-table-column prop="remaining" label="剩余" width="70" />
        <el-table-column label="" width="80">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="row.remaining === 0" @click="register(row as SlotVO)"
              >挂号</el-button
            >
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="patient && unpaid.length" class="page-card">
      <div class="section-title">待缴费</div>
      <el-table :data="unpaid" size="small" border>
        <el-table-column prop="chargeNo" label="收费单号" min-width="180" />
        <el-table-column label="合计" width="100">
          <template #default="{ row }">¥{{ row.totalAmount }}</template>
        </el-table-column>
        <el-table-column label="医保统筹" width="100">
          <template #default="{ row }">¥{{ row.insuranceAmount }}</template>
        </el-table-column>
        <el-table-column label="个人应付" width="100">
          <template #default="{ row }">¥{{ row.payableAmount }}</template>
        </el-table-column>
        <el-table-column prop="createTime" label="开单时间" width="170" />
        <el-table-column label="" width="80">
          <template #default="{ row }">
            <el-button link type="primary" @click="collect(row as ChargeOrderVO)">收费</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="patient" class="page-card">
      <div class="section-title">划价</div>
      <div class="page-toolbar">
        <el-select
          placeholder="添加诊疗/检查项目"
          filterable
          :model-value="''"
          :disabled="!!order"
          style="width: 280px"
          @change="addItem"
        >
          <el-option
            v-for="p in availableItems"
            :key="p.itemCode"
            :label="`${p.itemName}  ¥${p.unitPrice}/${p.unit}`"
            :value="p.itemCode"
          />
        </el-select>
      </div>
      <el-table :data="preview?.items ?? lines" border empty-text="请添加收费项目">
        <el-table-column label="项目" min-width="160">
          <template #default="{ row }">{{ row.itemName ?? itemName(row.itemCode) }}</template>
        </el-table-column>
        <el-table-column label="单价" width="100">
          <template #default="{ row }">{{ row.unitPrice ? `¥${row.unitPrice}` : '' }}</template>
        </el-table-column>
        <el-table-column label="数量" width="150">
          <template #default="{ $index }">
            <el-input-number
              v-model="lines[$index].qty"
              :min="1"
              :max="999"
              size="small"
              :disabled="!!order"
            />
          </template>
        </el-table-column>
        <el-table-column label="金额" width="100">
          <template #default="{ row }">{{ row.amount ? `¥${row.amount}` : '' }}</template>
        </el-table-column>
        <el-table-column label="医保统筹" width="110">
          <template #default="{ row }">{{ row.insuranceAmount ? `¥${row.insuranceAmount}` : '' }}</template>
        </el-table-column>
        <el-table-column label="个人自付" width="110">
          <template #default="{ row }">{{ row.selfAmount ? `¥${row.selfAmount}` : '' }}</template>
        </el-table-column>
        <el-table-column label="" width="70">
          <template #default="{ $index }">
            <el-button link type="danger" :disabled="!!order" @click="removeItem($index)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="preview" class="summary">
        <span>计费策略：{{ preview.feePolicyName }}</span>
        <span
          >合计 <b>¥{{ preview.totalAmount }}</b></span
        >
        <span
          >医保统筹 <b>¥{{ preview.insuranceAmount }}</b></span
        >
        <span class="payable"
          >个人应付 <b>¥{{ preview.payableAmount }}</b></span
        >
        <el-button v-if="!order" type="primary" :loading="busy" @click="createOrder">生成收费单</el-button>
      </div>
    </div>

    <div v-if="order" class="page-card">
      <div class="section-title">收款 · {{ order.chargeNo }}</div>
      <template v-if="!result">
        <el-form label-width="96px" @submit.prevent="checkout">
          <el-form-item label="应收">
            <span class="amount">¥{{ order.payableAmount }}</span>
            <span v-if="insured" class="hint">（医保统筹 ¥{{ order.insuranceAmount }} 由医保结算）</span>
          </el-form-item>
          <el-form-item v-if="insured && !manualInsurance" label="就诊凭证">
            <el-select v-model="certType" style="width: 150px">
              <el-option v-for="(label, code) in CERT_TYPE" :key="code" :label="label" :value="code" />
            </el-select>
            <el-input
              v-model="certNo"
              :placeholder="certType === '01' ? '扫描患者医保码' : '读卡；不填按患者档案'"
              clearable
              style="width: 260px; margin-left: 8px"
            />
          </el-form-item>
          <template v-if="manualInsurance">
            <el-form-item label="医保结算">
              <el-alert
                type="warning"
                :closable="false"
                show-icon
                title="医保结算平台未接入：请先在医保结算终端为患者结算，再登记终端上的结算单号和统筹金额。系统不会自行生成医保结算结果。"
              />
            </el-form-item>
            <el-form-item label="结算单号">
              <el-input v-model="insSetlId" placeholder="医保终端显示的结算单号" style="width: 260px" />
            </el-form-item>
            <el-form-item label="统筹金额">
              <el-input v-model="insFundPay" style="width: 140px" />
              <span class="hint">应与划价的统筹 ¥{{ order.insuranceAmount }} 一致</span>
            </el-form-item>
          </template>
          <el-form-item label="收款方式">
            <div class="tenders">
              <div v-for="(t, i) in tenders" :key="i" class="tender">
                <el-tag>{{ TENDER_METHOD[t.method] }}</el-tag>
                <el-input v-model="t.amount" style="width: 110px" placeholder="金额" />
                <template v-if="t.method === 'CASH'">
                  <span class="label">实收</span>
                  <el-input v-model="t.received" style="width: 110px" placeholder="实收现金" />
                  <span :class="['change', { negative: change < 0 }]">找零 ¥{{ change.toFixed(2) }}</span>
                </template>
                <el-input
                  v-else-if="t.method === 'CAMPUS_CARD'"
                  v-model="t.cardNo"
                  style="width: 220px"
                  placeholder="刷校园卡或输入卡号"
                />
                <el-input
                  v-else-if="t.method === 'BANK_CARD'"
                  v-model="t.reference"
                  style="width: 220px"
                  placeholder="POS 小票参考号"
                />
                <el-input v-else v-model="t.authCode" style="width: 220px" placeholder="扫描患者付款码" />
                <el-button v-if="tenders.length > 1" link type="danger" @click="removeTender(i)"
                  >移除</el-button
                >
              </div>
              <div v-if="tenders.length < 4" class="tender-add">
                <el-button
                  v-for="m in methods"
                  :key="m"
                  size="small"
                  :disabled="m === 'CASH' && !!cashTender"
                  @click="addTender(m)"
                >
                  + {{ TENDER_METHOD[m] }}
                </el-button>
              </div>
              <div v-if="unallocated !== 0" class="unallocated">
                {{ unallocated > 0 ? '还差' : '多出' }} ¥{{ (Math.abs(unallocated) / 100).toFixed(2) }}
              </div>
            </div>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" native-type="submit" :loading="busy" :disabled="!tenderReady">
              确认收费
            </el-button>
            <el-button @click="cancelOrder">作废</el-button>
          </el-form-item>
        </el-form>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="收费中途失败时，系统撤销本次医保结算；已收的现金须当面退还患者，POS 交易须在 POS 机上撤销。系统无法确认这些款项已退回，会转为“收款异常”，财务逐笔核实前该单不能再收费或作废。"
        />
      </template>
      <el-result v-else icon="success" title="收费成功">
        <template #sub-title>
          <p>
            医疗费 ¥{{ result.totalAmount }} · 医保统筹 ¥{{ result.fundPay }} · 个人自付 ¥{{
              result.selfAmount
            }}
          </p>
          <p v-if="result.setlId">医保结算单号 {{ result.setlId }}</p>
        </template>
        <template #extra>
          <el-table :data="result.tenders" size="small" border class="result-tenders">
            <el-table-column label="方式" width="110">
              <template #default="{ row }">{{ PAY_CHANNEL[row.channel] }}</template>
            </el-table-column>
            <el-table-column label="金额" width="100">
              <template #default="{ row }">¥{{ row.amount }}</template>
            </el-table-column>
            <el-table-column prop="payNo" label="支付单号" min-width="200" />
            <el-table-column label="凭据" min-width="140">
              <template #default="{ row }">{{ row.tenderRef ?? row.channelTradeNo ?? '' }}</template>
            </el-table-column>
          </el-table>
          <p class="amount">找零 ¥{{ result.change }}</p>
          <el-button type="primary" @click="reset">下一位</el-button>
        </template>
      </el-result>
    </div>
  </div>
</template>

<style scoped>
.settle {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.patient {
  margin-top: 12px;
}

.section-title {
  font-weight: 600;
  margin-bottom: 12px;
}

.summary {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 24px;
  margin-top: 16px;
  color: var(--el-text-color-regular);
}

.summary .payable b,
.amount {
  font-size: 18px;
  color: var(--el-color-danger);
  font-variant-numeric: tabular-nums;
}

.negative {
  color: var(--el-color-danger);
}

.hint {
  margin-left: 12px;
  color: var(--el-text-color-secondary);
}

.tenders {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tender {
  display: flex;
  align-items: center;
  gap: 8px;
}

.change {
  font-variant-numeric: tabular-nums;
}

.label {
  color: var(--el-text-color-secondary);
}

.unallocated {
  color: var(--el-color-warning);
}

.result-tenders {
  margin-bottom: 12px;
}
</style>
