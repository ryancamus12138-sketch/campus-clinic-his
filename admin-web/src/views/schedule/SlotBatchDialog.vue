<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { scheduleApi, SLOT_TYPES, type SlotPeriod } from '@/api/schedule'
import { useCampuses, orgApi, type OptionVO, type PriceItemVO } from '@/api/org'

const campuses = useCampuses()

const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ created: [count: number] }>()

const formRef = ref<FormInstance>()
const form = reactive({
  campusId: '1',
  deptId: '',
  doctorId: '',
  slotType: 1,
  dateRange: [] as string[],
  priceItemCode: '',
  openTime: '',
})
const periods = ref<SlotPeriod[]>([])
const depts = ref<OptionVO[]>([])
const doctors = ref<OptionVO[]>([])
const priceItems = ref<PriceItemVO[]>([])
const submitting = ref(false)

const rules: FormRules = {
  deptId: [{ required: true, message: '请选择科室' }],
  dateRange: [{ required: true, message: '请选择日期范围' }],
  priceItemCode: [{ required: true, message: '请选择价目' }],
}

const dates = computed(() => {
  const [from, to] = form.dateRange
  if (!from || !to) return []
  const result: string[] = []
  for (let d = new Date(`${from}T00:00:00`); d <= new Date(`${to}T00:00:00`); d.setDate(d.getDate() + 1)) {
    result.push(
      `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`,
    )
  }
  return result
})
const slotCount = computed(() => dates.value.length * periods.value.length)

watch(
  () => form.campusId,
  async (campusId) => {
    form.deptId = ''
    depts.value = await orgApi.depts(campusId)
  },
  { immediate: true },
)

watch(
  () => form.deptId,
  async (deptId) => {
    form.doctorId = ''
    doctors.value = deptId ? await orgApi.doctors(deptId) : []
  },
)

watch(visible, async (open) => {
  if (open && priceItems.value.length === 0) {
    priceItems.value = await orgApi.priceItems()
  }
  if (open && periods.value.length === 0) {
    applyPreset('morning')
  }
})

function toTime(minutes: number) {
  return `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`
}

/** 常用时段模板：按半小时切分 */
function applyPreset(preset: 'morning' | 'fullDay' | 'vaccine') {
  const ranges: Record<typeof preset, [number, number, number][]> = {
    morning: [[8 * 60, 11 * 60 + 30, 6]],
    fullDay: [
      [8 * 60, 11 * 60 + 30, 6],
      [13 * 60, 17 * 60, 6],
    ],
    vaccine: [
      [9 * 60, 11 * 60, 50],
      [14 * 60, 16 * 60, 50],
    ],
  }
  const step = preset === 'vaccine' ? 120 : 30
  periods.value = ranges[preset].flatMap(([from, to, total]) => {
    const list: SlotPeriod[] = []
    for (let m = from; m + step <= to; m += step) {
      list.push({ startTime: toTime(m), endTime: toTime(m + step), total })
    }
    return list
  })
}

function addPeriod() {
  const last = periods.value[periods.value.length - 1]
  periods.value.push(
    last
      ? { startTime: last.endTime, endTime: last.endTime, total: last.total }
      : { startTime: '08:00', endTime: '08:30', total: 6 },
  )
}

async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  if (periods.value.length === 0) {
    ElMessage.warning('请至少设置一个时段')
    return
  }
  submitting.value = true
  try {
    const count = await scheduleApi.batchCreate({
      campusId: form.campusId,
      deptId: form.deptId,
      doctorId: form.doctorId || undefined,
      slotType: form.slotType,
      dates: dates.value,
      periods: periods.value,
      priceItemCode: form.priceItemCode,
      openTime: form.openTime || undefined,
    })
    ElMessage.success(count > 0 ? `已生成 ${count} 个号源` : '所选时段均已存在，未新增')
    emit('created', count)
    visible.value = false
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="批量排班放号" width="760px" destroy-on-close>
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="校区">
            <el-select v-model="form.campusId">
              <el-option v-for="c in campuses" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="号源类型">
            <el-radio-group v-model="form.slotType">
              <el-radio-button v-for="t in SLOT_TYPES" :key="t.value" :value="t.value">{{
                t.label
              }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="科室" prop="deptId">
            <el-select v-model="form.deptId" placeholder="选择科室">
              <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="医生">
            <el-select v-model="form.doctorId" placeholder="不指定" clearable :disabled="form.slotType !== 1">
              <el-option v-for="d in doctors" :key="d.id" :label="d.name" :value="d.id" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="日期" prop="dateRange">
            <el-date-picker v-model="form.dateRange" type="daterange" value-format="YYYY-MM-DD" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="价目" prop="priceItemCode">
            <el-select v-model="form.priceItemCode" placeholder="选择价目">
              <el-option
                v-for="p in priceItems"
                :key="p.itemCode"
                :label="`${p.itemName}（¥${p.unitPrice}）`"
                :value="p.itemCode"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="24">
          <el-form-item label="放号时间">
            <el-date-picker
              v-model="form.openTime"
              type="datetime"
              value-format="YYYY-MM-DD[T]HH:mm:ss"
              placeholder="不填则立即开放"
            />
            <span class="hint">疫苗、体检通常设定统一放号时间，系统会提前 5 分钟把余量装入缓存</span>
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="时段">
        <div class="periods">
          <div class="periods__presets">
            <el-button size="small" @click="applyPreset('morning')">上午半小时一档</el-button>
            <el-button size="small" @click="applyPreset('fullDay')">全天半小时一档</el-button>
            <el-button size="small" @click="applyPreset('vaccine')">疫苗上下午各一场</el-button>
            <el-button size="small" link type="primary" @click="addPeriod">+ 添加时段</el-button>
          </div>
          <div v-for="(p, i) in periods" :key="i" class="periods__row">
            <el-time-select
              v-model="p.startTime"
              start="06:00"
              end="22:00"
              step="00:15"
              style="width: 110px"
            />
            <span>至</span>
            <el-time-select v-model="p.endTime" start="06:15" end="22:00" step="00:15" style="width: 110px" />
            <el-input-number v-model="p.total" :min="1" :max="2000" size="small" />
            <span class="hint">个号</span>
            <el-button link type="danger" @click="periods.splice(i, 1)">删除</el-button>
          </div>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <span class="summary"
        >共 {{ dates.length }} 天 × {{ periods.length }} 个时段 = {{ slotCount }} 个号源</span
      >
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="slotCount === 0" @click="submit"
        >生成</el-button
      >
    </template>
  </el-dialog>
</template>

<style scoped>
.periods {
  width: 100%;
}

.periods__presets {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.periods__row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.hint {
  color: #6b7280;
  font-size: 12px;
  margin-left: 8px;
}

.summary {
  float: left;
  line-height: 32px;
  color: #6b7280;
}
</style>
