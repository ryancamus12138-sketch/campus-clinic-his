<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { ITEM_TYPES, dictApi, type PriceItemAdmin } from '@/api/dict'

const itemType = ref<number>()
const rows = ref<PriceItemAdmin[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    rows.value = await dictApi.priceItems(itemType.value)
  } finally {
    loading.value = false
  }
}
onMounted(load)

const MONEY = /^\d+(\.\d{1,2})?$/

// ---- 新增项目 ----
const createOpen = ref(false)
const createRef = ref<FormInstance>()
const createForm = reactive({
  itemCode: '',
  itemName: '',
  insuranceCode: '',
  itemType: 3,
  unit: '次',
  unitPrice: '',
  insuranceClass: 1,
})
const createRules: FormRules = {
  itemCode: [
    { required: true, pattern: /^[A-Z0-9-]{2,32}$/, message: '大写字母、数字或短横线', trigger: 'blur' },
  ],
  itemName: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  insuranceCode: [
    { pattern: /^[A-Za-z0-9.-]{4,32}$/, message: '4–32 位字母、数字、点或短横线', trigger: 'blur' },
  ],
  unit: [{ required: true, message: '请输入单位', trigger: 'blur' }],
  unitPrice: [{ required: true, pattern: MONEY, message: '金额，最多两位小数', trigger: 'blur' }],
}

async function create() {
  if (!(await createRef.value?.validate().catch(() => false))) return
  await dictApi.createPriceItem({ ...createForm, insuranceCode: createForm.insuranceCode || undefined })
  ElMessage.success('已新增，立即生效')
  createOpen.value = false
  load()
}

// ---- 调价 ----
const priceOpen = ref(false)
const priceRef = ref<FormInstance>()
const target = ref<PriceItemAdmin>()
const priceForm = reactive({ unitPrice: '', insuranceClass: 1, effectiveFrom: '' })
const priceRules: FormRules = {
  unitPrice: [{ required: true, pattern: MONEY, message: '金额，最多两位小数', trigger: 'blur' }],
  effectiveFrom: [{ required: true, message: '请选择生效时间', trigger: 'change' }],
}

function openPrice(row: PriceItemAdmin) {
  target.value = row
  priceForm.unitPrice = row.current.unitPrice
  priceForm.insuranceClass = row.current.insuranceClass
  priceForm.effectiveFrom = ''
  priceOpen.value = true
}

async function schedule() {
  if (!target.value || !(await priceRef.value?.validate().catch(() => false))) return
  await dictApi.schedulePrice(target.value.itemCode, priceForm)
  ElMessage.success('已登记调价；生效前开出的收费单仍按原价')
  priceOpen.value = false
  load()
}

async function cancel(row: PriceItemAdmin) {
  await ElMessageBox.confirm(`撤销「${row.itemName}」尚未生效的调价？`, '撤销调价', { type: 'warning' })
  await dictApi.cancelPending(row.itemCode)
  ElMessage.success('已撤销')
  load()
}

const disabledPast = (d: Date) => d.getTime() < Date.now() - 86_400_000
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <el-select v-model="itemType" placeholder="全部类别" clearable style="width: 140px" @change="load">
        <el-option v-for="(label, v) in ITEM_TYPES" :key="v" :label="label" :value="Number(v)" />
      </el-select>
      <div style="flex: 1" />
      <el-button type="primary" @click="createOpen = true">新增项目</el-button>
    </div>
    <el-alert
      title="价格按版本管理：调价登记生效时间，到点自动切换；已开出的收费单保留开单时的价格。"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
    />
    <el-table v-loading="loading" :data="rows" border size="small">
      <el-table-column prop="itemCode" label="编码" width="130" />
      <el-table-column prop="itemName" label="名称" min-width="150" />
      <el-table-column label="类别" width="90">
        <template #default="{ row }">{{ ITEM_TYPES[(row as PriceItemAdmin).itemType] }}</template>
      </el-table-column>
      <el-table-column prop="insuranceCode" label="医保编码" min-width="150" />
      <el-table-column prop="unit" label="单位" width="60" />
      <el-table-column label="现行价格" width="150">
        <template #default="{ row }">
          ¥{{ (row as PriceItemAdmin).current.unitPrice }}
          <el-tag size="small" :type="(row as PriceItemAdmin).current.insuranceClass ? 'success' : 'info'">
            {{ (row as PriceItemAdmin).current.insuranceClass ? '可报' : '自费' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="待生效调价" min-width="200">
        <template #default="{ row }">
          <template v-if="(row as PriceItemAdmin).pending">
            ¥{{ (row as PriceItemAdmin).pending!.unitPrice }}，{{
              (row as PriceItemAdmin).pending!.effectiveFrom
            }}
            起
          </template>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="!(row as PriceItemAdmin).pending"
            link
            type="primary"
            @click="openPrice(row as PriceItemAdmin)"
          >
            调价
          </el-button>
          <el-button v-else link type="danger" @click="cancel(row as PriceItemAdmin)">撤销调价</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createOpen" title="新增收费项目" width="520px" :close-on-click-modal="false">
      <el-form ref="createRef" :model="createForm" :rules="createRules" label-width="90px">
        <el-form-item label="编码" prop="itemCode"><el-input v-model="createForm.itemCode" /></el-form-item>
        <el-form-item label="名称" prop="itemName"><el-input v-model="createForm.itemName" /></el-form-item>
        <el-form-item label="医保编码" prop="insuranceCode">
          <el-input v-model="createForm.insuranceCode" placeholder="医疗服务项目编码" />
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="createForm.itemType">
            <el-option v-for="(label, v) in ITEM_TYPES" :key="v" :label="label" :value="Number(v)" />
          </el-select>
        </el-form-item>
        <el-form-item label="单位" prop="unit"><el-input v-model="createForm.unit" /></el-form-item>
        <el-form-item label="单价" prop="unitPrice"><el-input v-model="createForm.unitPrice" /></el-form-item>
        <el-form-item label="医保">
          <el-radio-group v-model="createForm.insuranceClass">
            <el-radio :value="1">可报销</el-radio>
            <el-radio :value="0">自费</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" @click="create">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="priceOpen"
      :title="`调价：${target?.itemName ?? ''}`"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form ref="priceRef" :model="priceForm" :rules="priceRules" label-width="90px">
        <el-form-item label="现行价格">¥{{ target?.current.unitPrice }}</el-form-item>
        <el-form-item label="新价格" prop="unitPrice"
          ><el-input v-model="priceForm.unitPrice"
        /></el-form-item>
        <el-form-item label="医保">
          <el-radio-group v-model="priceForm.insuranceClass">
            <el-radio :value="1">可报销</el-radio>
            <el-radio :value="0">自费</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="生效时间" prop="effectiveFrom">
          <el-date-picker
            v-model="priceForm.effectiveFrom"
            type="datetime"
            value-format="YYYY-MM-DD[T]HH:mm:ss"
            :disabled-date="disabledPast"
            placeholder="物价部门通知的执行时间"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="priceOpen = false">取消</el-button>
        <el-button type="primary" @click="schedule">登记调价</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.muted {
  color: var(--el-text-color-placeholder);
}
</style>
