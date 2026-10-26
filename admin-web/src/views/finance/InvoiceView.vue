<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { financeApi, type InvoiceVO } from '@/api/finance'

const query = reactive({ page: 1, size: 20, chargeNo: '' })
const rows = ref<InvoiceVO[]>([])
const total = ref(0)
const STATUS: Record<number, string> = { 0: '待开具', 1: '已开', 2: '失败' }
/** 财政电子票据平台是否已接入；未接入时由财务在票据平台开具后登记票号 */
// 默认按“未接入”处理：查询失败时仍显示人工登记票号的入口
const connected = ref(false)

async function registerManual(row: InvoiceVO) {
  const { value } = await ElMessageBox.prompt(
    `${row.invoiceType === 1 ? '正票' : '红票'} ¥${row.amount}：请填写在票据平台开具的票据代码与号码`,
    '登记人工开具的票号',
    { inputPattern: /^[A-Za-z0-9-]{4,32}$/, inputErrorMessage: '票号为 4~32 位字母、数字或短横线' },
  )
  await financeApi.registerManualInvoice(row.id, value.trim())
  ElMessage.success('已登记')
  load()
}

async function load() {
  const r = await financeApi.invoices({ ...query, chargeNo: query.chargeNo || undefined })
  rows.value = r.list
  total.value = r.total
}

onMounted(async () => {
  connected.value = await financeApi.invoiceConnected().catch(() => false)
  load()
})
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <el-input
        v-model="query.chargeNo"
        placeholder="收费单号"
        clearable
        style="width: 220px"
        @keyup.enter="load"
      />
      <el-button type="primary" @click="load">查询</el-button>
      <span class="hint">付款后生成待开具记录，财务在票据平台开具后登记票号；退费后生成冲红记录</span>
    </div>
    <el-alert
      v-if="!connected"
      type="warning"
      :closable="false"
      show-icon
      class="tip"
      title="财政电子票据平台未接入：票据显示“待开具”，请在票据平台网页或原票据系统开具后登记票号。系统不会自行生成票号。"
    />
    <el-table :data="rows" border>
      <el-table-column prop="invoiceNo" label="票据号" min-width="140" />
      <el-table-column prop="chargeNo" label="收费单号" min-width="180" />
      <el-table-column label="类型" width="80">
        <template #default="{ row }">
          <el-tag :type="row.invoiceType === 1 ? 'primary' : 'danger'">{{
            row.invoiceType === 1 ? '正票' : '红票'
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="票面金额" width="110" align="right">
        <template #default="{ row }">¥{{ row.amount }}</template>
      </el-table-column>
      <el-table-column label="统筹 / 自付" width="160" align="right">
        <template #default="{ row }">{{ row.insuranceAmount }} / {{ row.selfAmount }}</template>
      </el-table-column>
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          {{ row.status === 0 && !connected ? '待人工开具' : STATUS[row.status] }}
          <el-tag v-if="row.issueMode === 2" size="small" type="info">人工登记</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="issueTime" label="开票时间" width="170" />
      <el-table-column v-if="!connected" label="" width="100">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" link type="primary" @click="registerManual(row as InvoiceVO)"
            >登记票号</el-button
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
</template>

<style scoped>
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.tip {
  margin-bottom: 12px;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
