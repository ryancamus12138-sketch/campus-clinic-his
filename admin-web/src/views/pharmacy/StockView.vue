<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useCampuses } from '@/api/org'
import { pharmacyApi, type StockVO } from '@/api/pharmacy'
import { useAuthStore } from '@/stores/auth'

const campuses = useCampuses()

const auth = useAuthStore()
const campusId = ref('1')
const keyword = ref('')
const rows = ref<StockVO[]>([])
const loading = ref(false)
const inboundVisible = ref(false)
const inbound = reactive({ drugId: '', batchNo: '', expireDate: '', purchasePrice: '', qty: 100 })

async function load() {
  loading.value = true
  try {
    rows.value = await pharmacyApi.stock(campusId.value, keyword.value || undefined)
  } finally {
    loading.value = false
  }
}

function openInbound(row?: StockVO) {
  Object.assign(inbound, {
    drugId: row?.drugId ?? '',
    batchNo: '',
    expireDate: '',
    purchasePrice: '',
    qty: 100,
  })
  inboundVisible.value = true
}

async function submitInbound() {
  await pharmacyApi.inbound({ campusId: campusId.value, ...inbound })
  ElMessage.success('已入库')
  inboundVisible.value = false
  load()
}

onMounted(load)
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <el-select v-model="campusId" style="width: 130px" @change="load">
        <el-option v-for="c in campuses" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="药品名称或编码"
        clearable
        style="width: 220px"
        @keyup.enter="load"
      />
      <el-button type="primary" @click="load">查询</el-button>
      <div style="flex: 1" />
      <el-button
        v-if="auth.hasPermission('pharmacy:stock:manage')"
        type="primary"
        plain
        @click="openInbound()"
      >
        采购入库
      </el-button>
    </div>
    <el-table v-loading="loading" :data="rows" border row-key="drugId">
      <el-table-column type="expand">
        <template #default="{ row }">
          <el-table :data="row.batches" size="small" class="batches">
            <el-table-column prop="batchNo" label="批号" />
            <el-table-column prop="expireDate" label="效期" />
            <el-table-column prop="onHand" label="在库" />
            <el-table-column prop="locked" label="已开单待发" />
            <el-table-column label="">
              <template #default="{ row: b }">
                <el-tag v-if="b.nearExpiry" type="danger" size="small">近效期，停止发放</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </el-table-column>
      <el-table-column prop="drugCode" label="编码" width="110" />
      <el-table-column label="药品" min-width="200">
        <template #default="{ row }">{{ row.name }} {{ row.spec }}</template>
      </el-table-column>
      <el-table-column label="零售价" width="90">
        <template #default="{ row }">¥{{ row.retailPrice }}</template>
      </el-table-column>
      <el-table-column prop="onHand" label="在库" width="80" />
      <el-table-column prop="locked" label="已开单待发" width="100" />
      <el-table-column label="可用" width="110">
        <template #default="{ row }">
          <span :class="{ warn: row.belowSafety }">{{ row.available }}</span>
          <el-tag v-if="row.belowSafety" type="warning" size="small">低于安全库存</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="inboundVisible" title="采购入库" width="460px">
      <el-form label-width="90px">
        <el-form-item label="药品">
          <el-select v-model="inbound.drugId" filterable style="width: 100%">
            <el-option v-for="r in rows" :key="r.drugId" :value="r.drugId" :label="`${r.name} ${r.spec}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="批号"><el-input v-model="inbound.batchNo" /></el-form-item>
        <el-form-item label="效期">
          <el-date-picker v-model="inbound.expireDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="进价"><el-input v-model="inbound.purchasePrice" /></el-form-item>
        <el-form-item label="数量"
          ><el-input-number v-model="inbound.qty" :min="1" :max="100000"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inboundVisible = false">取消</el-button>
        <el-button type="primary" @click="submitInbound">入库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.batches {
  margin: 0 48px;
  width: auto;
}

.warn {
  color: var(--el-color-warning);
  margin-right: 6px;
}
</style>
