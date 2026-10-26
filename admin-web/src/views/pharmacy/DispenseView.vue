<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { parseTraceCodes, pharmacyApi, type DispenseItemVO, type DispenseOrderVO } from '@/api/pharmacy'

const chargeNo = ref('')
const order = ref<DispenseOrderVO>()
/** 每条明细本次发药数量与扫到的追溯码（扫码枪每盒一行） */
const form = reactive<Record<string, { qty: number; codes: string }>>({})
const busy = ref(false)
const returning = ref<DispenseItemVO>()
const returnForm = reactive({ qty: 1, codes: '' })

async function load() {
  const no = chargeNo.value.trim()
  if (!no) return
  order.value = await pharmacyApi.dispenseView(no)
  for (const item of order.value.items) {
    form[item.chargeItemId] = { qty: item.dispensable, codes: '' }
  }
}

function scanned(item: DispenseItemVO) {
  return parseTraceCodes(form[item.chargeItemId]?.codes ?? '').length
}

async function dispense() {
  if (!order.value) return
  const items = order.value.items
    .filter((i) => i.dispensable > 0 && form[i.chargeItemId].qty > 0)
    .map((i) => ({
      chargeItemId: i.chargeItemId,
      qty: form[i.chargeItemId].qty,
      traceCodes: i.traceRequired ? parseTraceCodes(form[i.chargeItemId].codes) : [],
    }))
  if (items.length === 0) return
  busy.value = true
  try {
    await pharmacyApi.dispense(order.value.chargeNo, items)
    ElMessage.success('发药完成')
    await load()
  } finally {
    busy.value = false
  }
}

function openReturn(item: DispenseItemVO) {
  returning.value = item
  returnForm.qty = 1
  returnForm.codes = ''
}

async function confirmReturn() {
  if (!order.value || !returning.value) return
  await pharmacyApi.returnDrug({
    chargeNo: order.value.chargeNo,
    chargeItemId: returning.value.chargeItemId,
    qty: returnForm.qty,
    traceCodes: returning.value.traceRequired ? parseTraceCodes(returnForm.codes) : [],
  })
  ElMessage.success('已退药，可到收费处办理退费')
  returning.value = undefined
  await load()
}
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <el-input
        v-model="chargeNo"
        placeholder="扫描或输入收费单号"
        clearable
        style="width: 300px"
        @keyup.enter="load"
      />
      <el-button type="primary" @click="load">调出</el-button>
      <span v-if="order" class="patient">患者：{{ order.patientName }}</span>
    </div>

    <el-table v-if="order" :data="order.items" border>
      <el-table-column prop="itemName" label="药品" min-width="180" />
      <el-table-column label="开药 / 已发 / 已退费" width="160">
        <template #default="{ row }">{{ row.qty }} / {{ row.dispensedQty }} / {{ row.refundedQty }}</template>
      </el-table-column>
      <el-table-column label="批次（先到期先出）" min-width="200">
        <template #default="{ row }">
          <div v-for="l in row.locks" :key="l.batchNo" class="lock">
            {{ l.batchNo }} · 效期 {{ l.expireDate }} · 锁定 {{ l.locked }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="本次发药" width="140">
        <template #default="{ row }">
          <el-input-number
            v-if="row.dispensable > 0"
            v-model="form[row.chargeItemId].qty"
            :min="0"
            :max="row.dispensable"
            size="small"
          />
          <span v-else class="muted">已发完</span>
        </template>
      </el-table-column>
      <el-table-column label="追溯码（每盒一行）" min-width="240">
        <template #default="{ row }">
          <template v-if="row.traceRequired && row.dispensable > 0">
            <el-input
              v-model="form[row.chargeItemId].codes"
              type="textarea"
              :rows="2"
              placeholder="用扫码枪逐盒扫描"
            />
            <span :class="['count', { ok: scanned(row as DispenseItemVO) === form[row.chargeItemId].qty }]">
              已扫 {{ scanned(row as DispenseItemVO) }} / {{ form[row.chargeItemId].qty }}
            </span>
          </template>
          <span v-else-if="!row.traceRequired" class="muted">无需扫码</span>
        </template>
      </el-table-column>
      <el-table-column label="" width="80">
        <template #default="{ row }">
          <el-button
            v-if="row.dispensedQty > 0"
            link
            type="warning"
            @click="openReturn(row as DispenseItemVO)"
            >退药</el-button
          >
        </template>
      </el-table-column>
    </el-table>
    <div v-if="order" class="actions">
      <el-button type="primary" :loading="busy" @click="dispense">确认发药</el-button>
    </div>

    <el-dialog :model-value="!!returning" title="退药" width="460px" @close="returning = undefined">
      <el-form v-if="returning" label-width="90px">
        <el-form-item label="药品">{{ returning.itemName }}</el-form-item>
        <el-form-item label="退回数量">
          <el-input-number v-model="returnForm.qty" :min="1" :max="returning.dispensedQty" />
        </el-form-item>
        <el-form-item v-if="returning.traceRequired" label="追溯码">
          <el-input v-model="returnForm.codes" type="textarea" :rows="3" placeholder="扫描退回的每一盒" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="returning = undefined">取消</el-button>
        <el-button type="primary" @click="confirmReturn">确认退药</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.patient {
  margin-left: 12px;
  color: var(--el-text-color-regular);
}

.lock {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.muted {
  color: var(--el-text-color-placeholder);
}

.count {
  font-size: 12px;
  color: var(--el-color-danger);
}

.count.ok {
  color: var(--el-color-success);
}

.actions {
  margin-top: 16px;
  text-align: right;
}
</style>
