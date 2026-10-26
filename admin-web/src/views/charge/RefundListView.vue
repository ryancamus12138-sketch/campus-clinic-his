<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  chargeApi,
  PAY_CHANNEL,
  REFUND_STATUS,
  REFUND_TENDER_STATUS,
  type RefundTenderVO,
  type RefundVO,
} from '@/api/charge'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const canApprove = auth.hasPermission('charge:refund:approve')
const canConfirm = auth.hasPermission('charge:refund:apply')
// 财务默认看待审核；收费员默认看退款中（含待确认的现金、银行卡退款）
const query = reactive({ page: 1, size: 20, status: (canApprove ? 0 : 1) as number | undefined })
const rows = ref<RefundVO[]>([])
const total = ref(0)
const loading = ref(false)

const statusOf = (v: number) => REFUND_STATUS.find((s) => s.value === v)

async function load() {
  loading.value = true
  try {
    const result = await chargeApi.refunds(query)
    rows.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

// ------------------------------------------------------------------ 财务审批（通过 / 驳回）
// 申请人本人不能审批（后端同样校验）；审批人、时间、意见记录在退费单上；驳回必须写理由

type Decision = 'APPROVE' | 'REJECT'
const reviewing = ref<RefundVO>()
const reviewForm = reactive({ decision: 'APPROVE' as Decision, note: '' })
const reviewBusy = ref(false)
const isOwnRefund = (row: RefundVO) => String(row.applicantId ?? '') === String(auth.user?.userId ?? '')
const totalQty = (row: RefundVO) => row.items.reduce((n, i) => n + i.qty, 0)

function openReview(row: RefundVO, decision: Decision) {
  reviewing.value = row
  reviewForm.decision = decision
  reviewForm.note = ''
}

async function submitReview() {
  if (!reviewing.value) return
  const note = reviewForm.note.trim()
  if (reviewForm.decision === 'REJECT' && !note) {
    ElMessage.warning('驳回须填写理由')
    return
  }
  reviewBusy.value = true
  try {
    if (reviewForm.decision === 'APPROVE') {
      const result = await chargeApi.approveRefund(reviewing.value.id, note || undefined)
      ElMessage.success(result.status === 2 ? '已退款' : '已审批，等待原路退回或收费员确认')
    } else {
      await chargeApi.rejectRefund(reviewing.value.id, note)
      ElMessage.success('已驳回')
    }
    reviewing.value = undefined
    load()
  } finally {
    reviewBusy.value = false
  }
}

// ------------------------------------------------------------------ 收费员确认现金、银行卡已退还

const confirming = ref<{ refund: RefundVO; tender: RefundTenderVO }>()
const confirmForm = reactive({ reference: '', note: '' })

function openConfirm(refund: RefundVO, tender: RefundTenderVO) {
  confirming.value = { refund, tender }
  confirmForm.reference = ''
  confirmForm.note = ''
}

async function submitConfirm() {
  if (!confirming.value) return
  const { refund, tender } = confirming.value
  await chargeApi.confirmRefundTender(refund.id, tender.id, {
    reference: confirmForm.reference.trim() || undefined,
    note: confirmForm.note.trim() || undefined,
  })
  ElMessage.success('已确认')
  confirming.value = undefined
  load()
}

// ------------------------------------------------------------------ 医保平台未接入：登记终端上的撤销与重结

const registering = ref<RefundVO>()
const insForm = reactive({ fullRefund: false, setlId: '', fundPay: '' })

function openInsurance(row: RefundVO) {
  registering.value = row
  insForm.fullRefund = false
  insForm.setlId = ''
  insForm.fundPay = ''
}

async function submitInsurance() {
  if (!registering.value) return
  await chargeApi.registerRefundInsurance(
    registering.value.id,
    insForm.fullRefund
      ? undefined
      : { setlId: insForm.setlId.trim(), fundPay: Number(insForm.fundPay).toFixed(2) },
  )
  ElMessage.success('已登记')
  registering.value = undefined
  load()
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 130px" @change="load">
        <el-option v-for="s in REFUND_STATUS" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" border row-key="id">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="tenders">
            <div class="audit">
              申请人 {{ row.applicantName ?? row.applicantId }} · {{ row.createTime }}；
              <template v-if="row.approveTime">
                {{ row.status === 4 ? '驳回' : '审批' }}人 {{ row.approverName ?? row.approverId }} ·
                {{ row.approveTime }}{{ row.approveNote ? `：${row.approveNote}` : '' }}
              </template>
              <template v-else-if="row.status === 0">待审批</template>
              <template v-else>不超过审批金额，自动通过</template>
            </div>
            <el-alert
              v-if="row.awaitingInsuranceRegistration"
              type="warning"
              :closable="false"
              show-icon
              title="医保结算平台未接入：请在医保结算终端撤销原结算、按剩余项目重新结算后登记，退费才会继续。"
            />
            <el-table :data="row.tenders" size="small" border empty-text="尚未拆分到收款方式">
              <el-table-column label="原收款方式" width="110">
                <template #default="{ row: t }">{{ PAY_CHANNEL[t.channel] }}</template>
              </el-table-column>
              <el-table-column label="退还金额" width="100" align="right">
                <template #default="{ row: t }">¥{{ t.amount }}</template>
              </el-table-column>
              <el-table-column label="状态" width="130">
                <template #default="{ row: t }">
                  <el-tag :type="REFUND_TENDER_STATUS[t.status]?.tag">{{
                    REFUND_TENDER_STATUS[t.status]?.label
                  }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="凭证 / 确认" min-width="220">
                <template #default="{ row: t }">
                  <span v-if="t.channelRefundNo">{{ t.channelRefundNo }}</span>
                  <span v-if="t.confirmTime" class="muted">
                    收费员 {{ t.confirmBy }} 于 {{ t.confirmTime }} 确认{{
                      t.confirmNote ? `：${t.confirmNote}` : ''
                    }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column v-if="canConfirm" label="" width="110">
                <template #default="{ row: t }">
                  <el-button
                    v-if="t.status === 3 && row.status === 1"
                    link
                    type="primary"
                    @click="openConfirm(row as RefundVO, t as RefundTenderVO)"
                  >
                    确认已退还
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="refundNo" label="退费单号" min-width="180" />
      <el-table-column prop="chargeNo" label="收费单号" min-width="180" />
      <el-table-column label="退还患者" width="100" align="right">
        <template #default="{ row }">¥{{ row.amount }}</template>
      </el-table-column>
      <el-table-column label="医保冲销" width="100" align="right">
        <template #default="{ row }">¥{{ row.insuranceAmount }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="140" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusOf(row.status)?.tag">{{ statusOf(row.status)?.label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="申请时间" width="170" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 0 && canApprove">
            <el-button link type="primary" @click="openReview(row as RefundVO, 'APPROVE')">审批</el-button>
          </template>
          <el-button
            v-if="row.awaitingInsuranceRegistration && canConfirm"
            link
            type="warning"
            @click="openInsurance(row as RefundVO)"
          >
            登记医保重结
          </el-button>
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

    <el-dialog
      :model-value="!!reviewing"
      title="退费审批"
      width="560px"
      class="review-dialog"
      @close="reviewing = undefined"
    >
      <template v-if="reviewing">
        <div class="review-amounts">
          <div class="amount-box primary">
            <div class="label">退还患者（个人自付）</div>
            <div class="value">¥{{ reviewing.amount }}</div>
          </div>
          <div class="amount-box">
            <div class="label">医保统筹冲销</div>
            <div class="value">¥{{ reviewing.insuranceAmount }}</div>
          </div>
        </div>
        <el-descriptions :column="2" border size="small" class="review-info">
          <el-descriptions-item label="退费单号" :span="2">{{ reviewing.refundNo }}</el-descriptions-item>
          <el-descriptions-item label="收费单号" :span="2">{{ reviewing.chargeNo }}</el-descriptions-item>
          <el-descriptions-item label="申请人">{{
            reviewing.applicantName ?? reviewing.applicantId
          }}</el-descriptions-item>
          <el-descriptions-item label="申请时间">{{ reviewing.createTime }}</el-descriptions-item>
          <el-descriptions-item label="退费明细"
            >{{ reviewing.items.length }} 项，共 {{ totalQty(reviewing) }} 次/件</el-descriptions-item
          >
          <el-descriptions-item label="退还方式">按原收款方式退回</el-descriptions-item>
          <el-descriptions-item label="申请原因" :span="2">{{ reviewing.reason }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="isOwnRefund(reviewing)"
          type="error"
          :closable="false"
          show-icon
          title="这是您本人申请的退费，须由其他财务人员审批"
          class="review-tip"
        />

        <el-form label-position="top" class="review-form">
          <el-form-item label="审批结论">
            <el-radio-group v-model="reviewForm.decision">
              <el-radio-button value="APPROVE">同意退费</el-radio-button>
              <el-radio-button value="REJECT">驳回</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item
            :label="reviewForm.decision === 'REJECT' ? '驳回理由' : '审批意见（选填）'"
            :required="reviewForm.decision === 'REJECT'"
          >
            <el-input
              v-model="reviewForm.note"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              :placeholder="
                reviewForm.decision === 'REJECT'
                  ? '如：检查已执行，不符合退费条件'
                  : '如：核对检查未执行，同意退费'
              "
            />
          </el-form-item>
        </el-form>
        <div class="review-hint">
          {{
            reviewForm.decision === 'APPROVE'
              ? '同意后按原收款方式退还：现金由收费员当面退还、银行卡在 POS 机上退货，确认后才记为已退。'
              : '驳回后可退数量恢复，收费处可以重新申请。'
          }}
          审批人、时间和意见会记录在退费单上。
        </div>
      </template>
      <template #footer>
        <el-button @click="reviewing = undefined">取消</el-button>
        <el-button
          :type="reviewForm.decision === 'REJECT' ? 'danger' : 'primary'"
          :loading="reviewBusy"
          :disabled="!!reviewing && isOwnRefund(reviewing)"
          @click="submitReview"
        >
          {{ reviewForm.decision === 'REJECT' ? '确认驳回' : '同意退费' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog :model-value="!!confirming" title="确认已退还" width="440px" @close="confirming = undefined">
      <template v-if="confirming">
        <p>
          {{ PAY_CHANNEL[confirming.tender.channel] }} ¥{{ confirming.tender.amount }}
          <span class="muted">（确认人、时间、所在班次会记录在案）</span>
        </p>
        <el-form label-width="96px">
          <el-form-item v-if="confirming.tender.channel === 6" label="退货参考号" required>
            <el-input v-model="confirmForm.reference" placeholder="POS 退货小票上的参考号" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="confirmForm.note" maxlength="200" placeholder="如：患者本人签收" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="confirming = undefined">取消</el-button>
        <el-button type="primary" @click="submitConfirm">
          {{ confirming?.tender.channel === 1 ? '现金已当面退还' : '已在 POS 机上退货' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      :model-value="!!registering"
      title="登记医保撤销与重结"
      width="480px"
      @close="registering = undefined"
    >
      <el-form label-width="110px">
        <el-form-item label="退费后">
          <el-checkbox v-model="insForm.fullRefund">没有剩余医保报销项目（只需在终端撤销原结算）</el-checkbox>
        </el-form-item>
        <template v-if="!insForm.fullRefund">
          <el-form-item label="重结结算单号">
            <el-input v-model="insForm.setlId" />
          </el-form-item>
          <el-form-item label="重结统筹金额">
            <el-input v-model="insForm.fundPay" style="width: 140px" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="registering = undefined">取消</el-button>
        <el-button type="primary" @click="submitInsurance">已在医保终端办理，登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}

.tenders {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 16px;
}

.audit {
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.review-amounts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 16px;
}

.amount-box {
  padding: 12px 16px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.amount-box.primary {
  background: var(--el-color-primary-light-9);
}

.amount-box .label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.amount-box .value {
  margin-top: 4px;
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.amount-box.primary .value {
  color: var(--el-color-primary);
}

.review-tip {
  margin-top: 12px;
}

.review-form {
  margin-top: 16px;
}

.review-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.muted {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
}
</style>
