<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  chargeApi,
  CHECKOUT_TENDER_STATUS,
  PAY_CHANNEL,
  type CheckoutExceptionTender,
  type CheckoutExceptionVO,
  type TenderVerifyResult,
} from '@/api/charge'

/**
 * 收款异常：窗口结算失败后，有款项或医保结算没能确认撤回（已收现金是否退还须人工确认、POS 刷卡须在 POS 机上撤销、
 * 平台暂时不可用、医保撤销失败），或结算途中程序中断。处理之前这张收费单不能再收费或作废。
 * 财务先逐笔核实每一笔可能已扣款的收款（已退还患者 / 实际未扣款，非现金填凭证号），全部核实后才能登记处理结果。
 * 当笔收费的收费员本人不能核实、不能处理。
 */
const resolved = ref(false)
const rows = ref<CheckoutExceptionVO[]>([])
const loading = ref(false)

const INSURANCE_STATUS: Record<number, { label: string; tag: 'warning' | 'danger' | 'success' }> = {
  0: { label: '结算中（结果未知）', tag: 'warning' },
  1: { label: '已结算，未撤销', tag: 'danger' },
  2: { label: '已撤销', tag: 'success' },
}

async function load() {
  loading.value = true
  try {
    rows.value = await chargeApi.checkoutExceptions(resolved.value)
  } finally {
    loading.value = false
  }
}

const pendingInsurance = (row: CheckoutExceptionVO) => row.insurance.some((i) => i.status !== 2)
const unverified = (row: CheckoutExceptionVO) => row.tenders.filter((t) => t.needsVerification).length

// ------------------------------------------------------------------ 逐笔核实
const verifying = ref<{ row: CheckoutExceptionVO; tender: CheckoutExceptionTender }>()
const verifyForm = reactive({ result: 'RETURNED' as TenderVerifyResult, reference: '', note: '' })

function openVerify(row: CheckoutExceptionVO, tender: CheckoutExceptionTender) {
  verifying.value = { row, tender }
  verifyForm.result = 'RETURNED'
  verifyForm.reference = ''
  verifyForm.note = ''
}

async function submitVerify() {
  if (!verifying.value) return
  const { row, tender } = verifying.value
  const isCash = tender.channel === 1
  if (!isCash && !verifyForm.reference.trim()) {
    ElMessage.warning('请填写凭证号：银行卡填 POS 撤销或退货小票上的参考号')
    return
  }
  if (isCash && !verifyForm.note.trim()) {
    ElMessage.warning('现金请写明核实说明，如“已当面退还患者”')
    return
  }
  await chargeApi.verifyCheckoutTender(row.id, tender.id, {
    result: verifyForm.result,
    reference: verifyForm.reference.trim() || undefined,
    note: verifyForm.note.trim() || undefined,
  })
  ElMessage.success('已核实')
  verifying.value = undefined
  load()
}

async function retry(row: CheckoutExceptionVO) {
  const ok = await chargeApi.retryInsuranceCancel(row.id)
  if (ok) {
    ElMessage.success('医保结算已撤销')
  } else {
    ElMessage.warning('医保结算仍未撤销，可稍后再试，或在医保端手工撤销后登记')
  }
  load()
}

const resolving = ref<CheckoutExceptionVO>()
const form = reactive({ note: '', manual: false })

function openResolve(row: CheckoutExceptionVO) {
  resolving.value = row
  form.note = ''
  form.manual = false
}

async function submitResolve() {
  if (!resolving.value || !form.note.trim()) {
    ElMessage.warning('请填写处理说明')
    return
  }
  await chargeApi.resolveCheckout(resolving.value.id, form.note.trim(), form.manual)
  ElMessage.success('已登记处理结果，收费单已解除锁定')
  resolving.value = undefined
  load()
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="tip"
      title="窗口结算中途失败、且有款项不能由系统确认已退回时（已收的现金、POS 刷卡、结果不明的收款或医保结算），记录出现在这里，处理之前该收费单不能再收费或作废。请逐笔核实每一笔收款（已退还患者或实际未收取；非现金须填凭证号，现金须写明说明），全部核实后再登记处理结果。核实人、处理人、时间和说明都会记录在案；当笔收费的收费员本人不能处理。"
    />
    <div class="page-toolbar">
      <el-radio-group v-model="resolved" @change="load">
        <el-radio-button :value="false">待处理</el-radio-button>
        <el-radio-button :value="true">已处理</el-radio-button>
      </el-radio-group>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-table
      v-loading="loading"
      :data="rows"
      border
      :empty-text="resolved ? '没有处理记录' : '没有待处理的收款异常'"
    >
      <el-table-column label="收费单号 / 经手收费员" min-width="170">
        <template #default="{ row }">
          <div>{{ row.chargeNo }}</div>
          <div class="ref">{{ row.cashierName ?? row.cashierId }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="发生时间" width="170" />
      <el-table-column prop="failReason" label="原因" min-width="160" show-overflow-tooltip />
      <el-table-column label="各收款方式（逐笔核实）" min-width="320">
        <template #default="{ row }">
          <div v-for="t in row.tenders" :key="t.payNo" class="tender">
            <div class="line">
              <span>{{ PAY_CHANNEL[t.channel] }} ¥{{ t.amount }}</span>
              <el-tag size="small" :type="CHECKOUT_TENDER_STATUS[t.status]?.tag">
                {{
                  t.channel === 6 && t.status === 3
                    ? '需在 POS 机撤销'
                    : CHECKOUT_TENDER_STATUS[t.status]?.label
                }}
              </el-tag>
              <span class="ref">{{ t.tenderRef ?? t.channelTradeNo ?? t.payNo }}</span>
              <el-button
                v-if="!resolved && t.needsVerification"
                link
                type="primary"
                size="small"
                @click="openVerify(row as CheckoutExceptionVO, t as CheckoutExceptionTender)"
              >
                核实
              </el-button>
            </div>
            <div v-if="t.verifyTime" class="ref">
              核实人 {{ t.verifyByName ?? t.verifyBy }} · {{ t.verifyTime
              }}{{ t.verifyRef ? ` · 凭证 ${t.verifyRef}` : ''
              }}{{ t.verifyNote ? ` · ${t.verifyNote}` : '' }}
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="医保结算" min-width="200">
        <template #default="{ row }">
          <div v-for="i in row.insurance" :key="i.bizNo" class="line">
            <span>{{ i.setlId ?? i.bizNo }}</span>
            <el-tag size="small" :type="INSURANCE_STATUS[i.status]?.tag">{{
              INSURANCE_STATUS[i.status]?.label
            }}</el-tag>
            <span v-if="i.cancelNote" class="ref">{{ i.cancelNote }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column v-if="resolved" label="处理" min-width="240">
        <template #default="{ row }">
          <div>{{ row.resolveNote }}</div>
          <div class="ref">处理人 {{ row.resolverName ?? row.resolverId }} · {{ row.resolveTime }}</div>
        </template>
      </el-table-column>
      <el-table-column v-else label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="pendingInsurance(row as CheckoutExceptionVO)"
            link
            @click="retry(row as CheckoutExceptionVO)"
          >
            重试撤销医保
          </el-button>
          <el-tooltip
            :disabled="unverified(row as CheckoutExceptionVO) === 0"
            :content="`还有 ${unverified(row as CheckoutExceptionVO)} 笔收款未核实`"
          >
            <span>
              <el-button
                link
                type="primary"
                :disabled="unverified(row as CheckoutExceptionVO) > 0"
                @click="openResolve(row as CheckoutExceptionVO)"
              >
                登记处理
              </el-button>
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog :model-value="!!verifying" title="逐笔核实" width="480px" @close="verifying = undefined">
      <template v-if="verifying">
        <p>
          {{ PAY_CHANNEL[verifying.tender.channel] }} ¥{{ verifying.tender.amount }}
          <span class="ref">{{ verifying.tender.tenderRef ?? verifying.tender.payNo }}</span>
        </p>
        <el-form label-width="96px">
          <el-form-item label="核实结果" required>
            <el-radio-group v-model="verifyForm.result">
              <el-radio value="RETURNED">{{
                verifying.tender.channel === 1 ? '已当面退还患者' : '已退还患者'
              }}</el-radio>
              <el-radio value="NOT_CHARGED">{{
                verifying.tender.channel === 1 ? '实际未收取现金' : '实际未扣款'
              }}</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="凭证号" :required="verifying.tender.channel !== 1">
            <el-input
              v-model="verifyForm.reference"
              maxlength="64"
              :placeholder="
                verifying.tender.channel === 6 ? 'POS 撤销或退货小票上的参考号' : '平台流水号或查询单号'
              "
            />
          </el-form-item>
          <el-form-item label="说明" :required="verifying.tender.channel === 1">
            <el-input
              v-model="verifyForm.note"
              maxlength="200"
              :placeholder="
                verifying.tender.channel === 1
                  ? '如：收费员当面退还现金，患者签字确认'
                  : '如：已在 POS 机撤销当笔交易'
              "
            />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="verifying = undefined">取消</el-button>
        <el-button type="primary" @click="submitVerify">确认核实</el-button>
      </template>
    </el-dialog>

    <el-dialog :model-value="!!resolving" title="登记处理结果" width="480px" @close="resolving = undefined">
      <el-form label-width="96px">
        <el-form-item label="处理说明" required>
          <el-input
            v-model="form.note"
            type="textarea"
            maxlength="200"
            placeholder="如：现金已当面退还；已在 POS 机撤销当笔交易"
          />
        </el-form-item>
        <el-form-item v-if="resolving && pendingInsurance(resolving)" label="医保结算">
          <el-checkbox v-model="form.manual">已在医保端（结算终端或经办机构）手工撤销</el-checkbox>
          <div class="ref">系统会先自动重试撤销一次；仍失败时，只有勾选此项才能完成处理</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolving = undefined">取消</el-button>
        <el-button type="primary" @click="submitResolve">确认处理</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tip {
  margin-bottom: 12px;
}

.tender + .tender {
  margin-top: 4px;
}

.line {
  display: flex;
  align-items: center;
  gap: 8px;
  line-height: 28px;
}

.ref {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
