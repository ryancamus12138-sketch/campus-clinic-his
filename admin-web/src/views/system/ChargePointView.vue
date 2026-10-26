<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  orgApi,
  reloadCampuses,
  useCampuses,
  type CampusVO,
  type ChargePointVO,
  type CounterMethod,
} from '@/api/org'
import { TENDER_METHOD } from '@/api/charge'

const campuses = useCampuses()
const rows = ref<ChargePointVO[]>([])
const loading = ref(false)
const editing = ref<ChargePointVO>()
const form = ref<{ name: string; counterMethods: CounterMethod[]; status: number }>({
  name: '',
  counterMethods: [],
  status: 1,
})
const methodOptions = Object.entries(TENDER_METHOD) as [CounterMethod, string][]

const campusName = computed(
  () => (id: string | number) => campuses.value.find((c) => String(c.id) === String(id))?.name ?? id,
)

async function load() {
  loading.value = true
  try {
    rows.value = await orgApi.chargePoints()
  } finally {
    loading.value = false
  }
}

function edit(row: ChargePointVO) {
  editing.value = row
  form.value = { name: row.name, counterMethods: [...row.counterMethods], status: row.status }
}

async function save() {
  if (!editing.value) return
  if (form.value.counterMethods.length === 0) {
    ElMessage.warning('至少开放一种收款方式')
    return
  }
  await orgApi.updateChargePoint(editing.value.id, form.value)
  ElMessage.success('已保存')
  editing.value = undefined
  load()
}

// ------------------------------------------------------------------ 校区名称、地址
const editingCampus = ref<CampusVO>()
const campusForm = ref({ name: '', address: '' })

function editCampus(row: CampusVO) {
  editingCampus.value = row
  campusForm.value = { name: row.name, address: row.address ?? '' }
}

async function saveCampus() {
  if (!editingCampus.value) return
  if (!campusForm.value.name.trim()) {
    ElMessage.warning('请填写校区名称')
    return
  }
  await orgApi.updateCampus(editingCampus.value.id, {
    name: campusForm.value.name.trim(),
    address: campusForm.value.address.trim() || undefined,
  })
  ElMessage.success('已保存')
  editingCampus.value = undefined
  reloadCampuses()
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
      title="校区与收费窗口按本校实际维护。某种收款方式需要收费点开放、且对应平台已接入（校园卡需接入一卡通，付款码需接入统一支付平台）才会在收费界面出现。"
    />
    <h4 class="section">校区</h4>
    <el-table :data="campuses" border class="campus-table">
      <el-table-column prop="name" label="校区" width="160" />
      <el-table-column prop="address" label="地址" min-width="260" />
      <el-table-column label="" width="80">
        <template #default="{ row }">
          <el-button link type="primary" @click="editCampus(row as CampusVO)">修改</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h4 class="section">收费点</h4>
    <el-table v-loading="loading" :data="rows" border>
      <el-table-column label="校区" width="140">
        <template #default="{ row }">{{ campusName(row.campusId) }}</template>
      </el-table-column>
      <el-table-column prop="code" label="编码" width="110" />
      <el-table-column prop="name" label="收费点" min-width="160" />
      <el-table-column label="开放的收款方式" min-width="260">
        <template #default="{ row }">
          <el-tag v-for="m in row.counterMethods" :key="m" class="method">
            {{ TENDER_METHOD[m as CounterMethod] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{
            row.status === 1 ? '启用' : '停用'
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="" width="80">
        <template #default="{ row }">
          <el-button link type="primary" @click="edit(row as ChargePointVO)">修改</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      :model-value="!!editingCampus"
      title="修改校区"
      width="460px"
      @close="editingCampus = undefined"
    >
      <el-form label-width="96px">
        <el-form-item label="名称" required>
          <el-input v-model="campusForm.name" maxlength="50" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="campusForm.address" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editingCampus = undefined">取消</el-button>
        <el-button type="primary" @click="saveCampus">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog :model-value="!!editing" title="修改收费点" width="460px" @close="editing = undefined">
      <el-form label-width="96px">
        <el-form-item label="名称">
          <el-input v-model="form.name" maxlength="50" />
        </el-form-item>
        <el-form-item label="收款方式">
          <el-checkbox-group v-model="form.counterMethods">
            <el-checkbox v-for="[code, label] in methodOptions" :key="code" :value="code">{{
              label
            }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editing = undefined">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tip {
  margin-bottom: 16px;
}

.section {
  margin: 8px 0;
}

.campus-table {
  margin-bottom: 16px;
}

.method {
  margin-right: 6px;
}
</style>
