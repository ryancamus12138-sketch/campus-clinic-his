<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  ID_TYPES,
  INSURANCE_TYPES,
  PATIENT_TYPES,
  patientApi,
  type PatientBriefVO,
  type PatientCreateDTO,
  type PatientDetailVO,
  type PatientSensitiveVO,
} from '@/api/patient'
import { useCampuses } from '@/api/org'
import { useAuthStore } from '@/stores/auth'

const campuses = useCampuses()

const auth = useAuthStore()
const canReveal = computed(() => auth.hasPermission('patient:sensitive:view'))

// ---------------------------------------------------------------- 检索与详情
const keyword = ref('')
const rows = ref<PatientBriefVO[]>([])
const searching = ref(false)
const detail = ref<PatientDetailVO | null>(null)
const revealed = ref<PatientSensitiveVO | null>(null)
let hideTimer: ReturnType<typeof setTimeout> | undefined

async function search() {
  if (!keyword.value.trim()) return
  searching.value = true
  try {
    rows.value = await patientApi.search(keyword.value.trim())
    if (rows.value.length === 0) ElMessage.info('没有找到，可新建档案')
    if (rows.value.length === 1) await open(rows.value[0])
  } finally {
    searching.value = false
  }
}

async function open(row: PatientBriefVO) {
  hide()
  detail.value = await patientApi.detail(row.id)
}

async function reveal() {
  if (!detail.value) return
  const { value } = await ElMessageBox.prompt(
    '查看完整证件号和手机号会记入审计日志，请填写原因。',
    '查看完整信息',
    {
      inputPlaceholder: '如：医保结算核对身份',
      inputValidator: (v) => (v && v.trim().length >= 2 ? true : '请填写原因'),
    },
  )
  revealed.value = await patientApi.reveal(detail.value.id, value.trim())
  // 完整信息只显示一分钟
  clearTimeout(hideTimer)
  hideTimer = setTimeout(hide, 60_000)
}

function hide() {
  clearTimeout(hideTimer)
  revealed.value = null
}

onBeforeUnmount(hide)

// ---------------------------------------------------------------- 建档
const dialog = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const blank = (): PatientCreateDTO => ({
  campusId: auth.user?.campusId ?? '1',
  name: '',
  gender: 1,
  birthDate: '',
  patientType: 1,
  schoolNo: '',
  insuranceType: 1,
  idType: 1,
  idNo: '',
  phone: '',
})
const form = reactive<PatientCreateDTO>(blank())
const embeddedId = computed(() => [1, 3, 5].includes(form.idType))
const rules: FormRules = {
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  idNo: [{ required: true, message: '请输入证件号', trigger: 'blur' }],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  birthDate: [{ required: true, message: '请选择出生日期', trigger: 'change' }],
  schoolNo: [
    {
      validator: (_r, v: string, cb) =>
        form.patientType !== 3 && !v?.trim() ? cb(new Error('学生和教职工必须填写学号/工号')) : cb(),
      trigger: 'blur',
    },
  ],
}

// 18 位证件号自动带出出生日期和性别（后端还会再核对一次）
watch(
  () => [form.idNo, form.idType] as const,
  ([idNo]) => {
    const no = idNo.replace(/\s+/g, '').toUpperCase()
    if (!embeddedId.value || !/^\d{17}[\dX]$/.test(no)) return
    form.birthDate = `${no.slice(6, 10)}-${no.slice(10, 12)}-${no.slice(12, 14)}`
    form.gender = Number(no[16]) % 2 === 1 ? 1 : 2
  },
)

function openCreate() {
  Object.assign(form, blank())
  dialog.value = true
  formRef.value?.clearValidate()
}

async function create() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  saving.value = true
  try {
    const created = await patientApi.create({ ...form, schoolNo: form.schoolNo?.trim() || undefined })
    ElMessage.success(`建档成功，就诊卡号 ${created.patientNo}`)
    dialog.value = false
    hide()
    detail.value = created
    rows.value = [created]
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="archive">
    <div class="page-card">
      <div class="page-toolbar">
        <el-input
          v-model="keyword"
          placeholder="就诊卡号 / 学号工号 / 证件号 / 手机号（精确匹配）"
          clearable
          style="width: 360px"
          @keyup.enter="search"
        />
        <el-button type="primary" :loading="searching" @click="search">检索</el-button>
        <el-button v-permission="'patient:create'" @click="openCreate">新建档案</el-button>
      </div>
      <el-table :data="rows" border highlight-current-row empty-text="输入完整号码检索" @row-click="open">
        <el-table-column prop="patientNo" label="就诊卡号" min-width="200" />
        <el-table-column prop="name" label="姓名" width="120" />
        <el-table-column prop="schoolNo" label="学号/工号" width="140" />
        <el-table-column label="身份" width="90">
          <template #default="{ row }">{{ PATIENT_TYPES[row.patientType] }}</template>
        </el-table-column>
        <el-table-column label="参保" width="110">
          <template #default="{ row }">{{ INSURANCE_TYPES[row.insuranceType] }}</template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="detail" class="page-card">
      <el-descriptions :title="`${detail.name}（${detail.patientNo}）`" :column="2" border>
        <el-descriptions-item label="性别">{{ detail.gender === 1 ? '男' : '女' }}</el-descriptions-item>
        <el-descriptions-item label="出生日期">{{ detail.birthDate }}</el-descriptions-item>
        <el-descriptions-item label="身份">{{ PATIENT_TYPES[detail.patientType] }}</el-descriptions-item>
        <el-descriptions-item label="学号/工号">{{ detail.schoolNo ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="参保类型">{{
          INSURANCE_TYPES[detail.insuranceType]
        }}</el-descriptions-item>
        <el-descriptions-item label="证件类型">
          {{ ID_TYPES.find((t) => t.value === detail!.idType)?.label }}
        </el-descriptions-item>
        <el-descriptions-item label="证件号">
          <span class="mono">{{ revealed?.idNo ?? detail.idNoMasked }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="手机号">
          <span class="mono">{{ revealed?.phone ?? detail.phoneMasked }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <div class="archive__actions">
        <el-button v-if="canReveal && !revealed" link type="warning" @click="reveal">查看完整信息</el-button>
        <el-button v-if="revealed" link @click="hide">隐藏</el-button>
        <span class="archive__note">证件号、手机号加密存储，默认脱敏显示；查看完整信息会记入审计日志。</span>
      </div>
    </div>

    <el-dialog v-model="dialog" title="新建档案" width="560px" :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-form-item label="校区">
          <el-select v-model="form.campusId">
            <el-option v-for="c in campuses" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="姓名" prop="name"><el-input v-model="form.name" maxlength="50" /></el-form-item>
        <el-form-item label="证件类型">
          <el-select v-model="form.idType">
            <el-option v-for="t in ID_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="证件号" prop="idNo">
          <el-input v-model="form.idNo" maxlength="32" autocomplete="off" />
        </el-form-item>
        <el-form-item label="出生日期" prop="birthDate">
          <el-date-picker v-model="form.birthDate" value-format="YYYY-MM-DD" :disabled="embeddedId" />
        </el-form-item>
        <el-form-item label="性别">
          <el-radio-group v-model="form.gender" :disabled="embeddedId">
            <el-radio :value="1">男</el-radio>
            <el-radio :value="2">女</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" maxlength="11" autocomplete="off" />
        </el-form-item>
        <el-form-item label="身份">
          <el-radio-group v-model="form.patientType">
            <el-radio v-for="(label, v) in PATIENT_TYPES" :key="v" :value="Number(v)">{{ label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="学号/工号" prop="schoolNo">
          <el-input v-model="form.schoolNo" maxlength="20" />
        </el-form-item>
        <el-form-item label="参保类型">
          <el-select v-model="form.insuranceType">
            <el-option v-for="(label, v) in INSURANCE_TYPES" :key="v" :label="label" :value="Number(v)" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="create">建档</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.archive {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.archive__actions {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.archive__note {
  font-size: 12px;
  color: #6b7280;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
</style>
