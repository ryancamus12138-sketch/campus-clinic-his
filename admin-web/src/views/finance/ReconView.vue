<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { PAY_CHANNEL } from '@/api/charge'
import { DIFF_TYPE, financeApi, isoDay, type ReconDiffVO, type ReconResultVO } from '@/api/finance'

const date = ref(isoDay(-1))
const result = ref<ReconResultVO>()
const rows = ref<ReconDiffVO[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 20, status: 0 as number | undefined })

async function loadDiffs() {
  const r = await financeApi.diffs(query)
  rows.value = r.list
  total.value = r.total
}

async function run() {
  result.value = await financeApi.reconcile(date.value)
  ElMessage.success('对账完成')
  loadDiffs()
}

async function resolve(row: ReconDiffVO) {
  const { value } = await ElMessageBox.prompt('处理说明（如：已核实并补记）', '处理差异', {
    inputValidator: (v) => !!v?.trim() || '请填写处理说明',
  })
  await financeApi.resolveDiff(row.id, value)
  ElMessage.success('已处理')
  loadDiffs()
}

onMounted(loadDiffs)
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
      <el-button type="primary" @click="run">对账</el-button>
      <span class="hint"
        >与已接入的支付平台账单逐笔核对；重复对账不会重复登记差异。当前版本未接入支付平台</span
      >
      <div style="flex: 1" />
      <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px" @change="loadDiffs">
        <el-option label="待处理" :value="0" />
        <el-option label="已处理" :value="1" />
      </el-select>
    </div>
    <el-alert
      v-if="result && result.channels.length === 0"
      type="warning"
      :closable="false"
      show-icon
      class="result"
      title="没有已接入的支付平台（统一支付平台、一卡通、医保），本次没有进行渠道对账，不代表账目一致。POS 刷卡以交班时的 POS 结算单和银行对账单核对。"
    />
    <el-alert
      v-else-if="result"
      :type="result.longCount + result.shortCount + result.mismatchCount === 0 ? 'success' : 'warning'"
      :closable="false"
      :title="`${result.date}：本系统 ${result.ourCount} 笔，渠道 ${result.channelCount} 笔，一致 ${result.matched}，长款 ${result.longCount}，短款 ${result.shortCount}，金额不符 ${result.mismatchCount}`"
      class="result"
    />
    <el-table :data="rows" border>
      <el-table-column prop="reconDate" label="日期" width="110" />
      <el-table-column label="渠道" width="80">
        <template #default="{ row }">{{ PAY_CHANNEL[row.channel] }}</template>
      </el-table-column>
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag :type="row.diffType === 1 ? 'warning' : 'danger'">{{ DIFF_TYPE[row.diffType] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="payNo" label="支付单号" min-width="170" />
      <el-table-column prop="channelTradeNo" label="渠道流水号" min-width="190" />
      <el-table-column label="本系统 / 渠道" width="150" align="right">
        <template #default="{ row }">{{ row.ourAmount ?? '-' }} / {{ row.channelAmount ?? '-' }}</template>
      </el-table-column>
      <el-table-column prop="remark" label="处理说明" min-width="140" />
      <el-table-column label="" width="80">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" link type="primary" @click="resolve(row as ReconDiffVO)"
            >处理</el-button
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
      @current-change="loadDiffs"
    />
  </div>
</template>

<style scoped>
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.result {
  margin-bottom: 12px;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
