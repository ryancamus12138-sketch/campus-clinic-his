<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { dictApi, type DrugDetail } from '@/api/dict'

const keyword = ref('')
const page = ref(1)
const size = ref(20)
const rows = ref<DrugDetail[]>([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const r = await dictApi.drugs(keyword.value || undefined, page.value, size.value)
    rows.value = r.list
    total.value = r.total
  } finally {
    loading.value = false
  }
}

onMounted(load)

const dialog = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const blank = (): DrugDetail => ({
  drugCode: '',
  insuranceCode: '',
  name: '',
  spec: '',
  unit: '盒',
  goodsType: 1,
  retailPrice: '',
  insuranceClass: 1,
  traceRequired: 1,
  manufacturer: '',
  approvalNo: '',
  safetyStock: 0,
  status: 1,
})
const form = reactive<DrugDetail>(blank())
const rules: FormRules = {
  drugCode: [
    { required: true, pattern: /^[A-Z0-9-]{2,32}$/, message: '大写字母、数字或短横线', trigger: 'blur' },
  ],
  insuranceCode: [{ pattern: /^[A-Z0-9]{8,32}$/, message: '8–32 位大写字母或数字', trigger: 'blur' }],
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  spec: [{ required: true, message: '请输入规格', trigger: 'blur' }],
  unit: [{ required: true, message: '请输入单位', trigger: 'blur' }],
  retailPrice: [
    { required: true, pattern: /^\d+(\.\d{1,2})?$/, message: '金额，最多两位小数', trigger: 'blur' },
  ],
}

function open(row?: DrugDetail) {
  Object.assign(form, row ? { ...row } : blank())
  dialog.value = true
  formRef.value?.clearValidate()
}

async function save() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  saving.value = true
  try {
    const body = { ...form, insuranceCode: form.insuranceCode || undefined }
    if (form.id) {
      await dictApi.updateDrug(form.id, body)
    } else {
      await dictApi.createDrug(body)
    }
    ElMessage.success('已保存；零售价调整只影响之后的划价')
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <el-input
        v-model="keyword"
        placeholder="编码、名称或医保编码"
        clearable
        style="width: 260px"
        @keyup.enter="((page = 1), load())"
      />
      <el-button type="primary" @click="((page = 1), load())">查询</el-button>
      <el-button @click="open()">新增药品</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" border size="small">
      <el-table-column prop="drugCode" label="编码" width="110" />
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="spec" label="规格" width="120" />
      <el-table-column prop="unit" label="单位" width="60" />
      <el-table-column prop="retailPrice" label="零售价" width="80" />
      <el-table-column prop="insuranceCode" label="医保编码" min-width="190" show-overflow-tooltip />
      <el-table-column label="医保" width="70">
        <template #default="{ row }">{{ row.insuranceClass === 1 ? '可报' : '自费' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="70">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="70" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row as DrugDetail)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      layout="total, prev, pager, next"
      class="pager"
      @current-change="load"
    />

    <el-dialog
      v-model="dialog"
      :title="form.id ? '编辑药品' : '新增药品'"
      width="760px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" class="grid">
        <el-form-item label="编码" prop="drugCode"><el-input v-model="form.drugCode" /></el-form-item>
        <el-form-item label="医保编码" prop="insuranceCode"
          ><el-input v-model="form.insuranceCode"
        /></el-form-item>
        <el-form-item label="名称" prop="name"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="规格" prop="spec"
          ><el-input v-model="form.spec" placeholder="如 0.25g×24粒"
        /></el-form-item>
        <el-form-item label="包装单位" prop="unit"
          ><el-input v-model="form.unit" placeholder="如 盒"
        /></el-form-item>
        <el-form-item label="零售价" prop="retailPrice"><el-input v-model="form.retailPrice" /></el-form-item>
        <el-form-item label="类别">
          <el-radio-group v-model="form.goodsType">
            <el-radio :value="1">药品</el-radio>
            <el-radio :value="2">卫生材料</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="医保">
          <el-radio-group v-model="form.insuranceClass">
            <el-radio :value="1">可报销</el-radio>
            <el-radio :value="0">自费</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="批准文号"><el-input v-model="form.approvalNo" /></el-form-item>
        <el-form-item label="生产企业"><el-input v-model="form.manufacturer" /></el-form-item>
        <el-form-item label="扫追溯码">
          <el-switch v-model="form.traceRequired" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="安全库存">
          <el-input-number v-model="form.safetyStock" :min="0" controls-position="right" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: 12px;
}

.wide {
  grid-column: span 2;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
