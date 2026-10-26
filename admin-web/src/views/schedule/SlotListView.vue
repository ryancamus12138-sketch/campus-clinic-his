<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { scheduleApi, SLOT_STATUS, SLOT_TYPES, type SlotQuery, type SlotVO } from '@/api/schedule'
import { useCampuses, orgApi, type OptionVO } from '@/api/org'
import { useAuthStore } from '@/stores/auth'
import SlotBatchDialog from './SlotBatchDialog.vue'

const campuses = useCampuses()

const auth = useAuthStore()

function today(offset = 0) {
  const d = new Date()
  d.setDate(d.getDate() + offset)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const query = reactive<SlotQuery & { dateRange: string[] }>({
  page: 1,
  size: 50,
  campusId: '1',
  deptId: undefined,
  slotType: undefined,
  dateRange: [today(), today(6)],
})
const rows = ref<SlotVO[]>([])
const total = ref(0)
const loading = ref(false)
const depts = ref<OptionVO[]>([])
const batchVisible = ref(false)

const slotType = (v: number) => SLOT_TYPES.find((t) => t.value === v)
const slotStatus = (v: number) => SLOT_STATUS.find((s) => s.value === v)

async function load() {
  loading.value = true
  try {
    const { dateRange, ...rest } = query
    const result = await scheduleApi.page({ ...rest, dateFrom: dateRange?.[0], dateTo: dateRange?.[1] })
    rows.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

async function toggle(row: SlotVO) {
  const next = row.status === 1 ? 2 : 1
  if (next === 2) {
    await ElMessageBox.confirm(
      `停诊后不能再挂 ${row.slotDate} ${row.startTime} 的号，已挂的号不受影响。`,
      '确认停诊',
      { type: 'warning' },
    )
  }
  await scheduleApi.changeStatus(row.id, next)
  ElMessage.success(next === 1 ? '已开放' : '已停诊')
  load()
}

function usage(row: SlotVO) {
  return row.total === 0 ? 0 : Math.round(((row.total - row.remaining) / row.total) * 100)
}

watch(
  () => query.campusId,
  async (campusId) => {
    query.deptId = undefined
    depts.value = await orgApi.depts(campusId)
  },
  { immediate: true },
)

onMounted(load)
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <el-select v-model="query.campusId" style="width: 130px">
        <el-option v-for="c in campuses" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-select v-model="query.deptId" placeholder="全部科室" clearable style="width: 140px">
        <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
      <el-select v-model="query.slotType" placeholder="全部类型" clearable style="width: 120px">
        <el-option v-for="t in SLOT_TYPES" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-date-picker
        v-model="query.dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        style="width: 260px"
      />
      <el-button type="primary" @click="search">查询</el-button>
      <div style="flex: 1" />
      <el-button
        v-if="auth.hasPermission('schedule:slot:manage')"
        type="primary"
        plain
        @click="batchVisible = true"
      >
        批量排班放号
      </el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="slotDate" label="日期" width="110" />
      <el-table-column label="时段" width="120">
        <template #default="{ row }">{{ row.startTime }}–{{ row.endTime }}</template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag :type="slotType(row.slotType)?.tag">{{ slotType(row.slotType)?.label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="deptName" label="科室" width="120" />
      <el-table-column label="医生" width="110">
        <template #default="{ row }">{{ row.doctorName ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="已挂 / 总量" min-width="180">
        <template #default="{ row }">
          <div class="usage">
            <el-progress
              :percentage="usage(row as SlotVO)"
              :stroke-width="8"
              :show-text="false"
              style="flex: 1"
            />
            <span>{{ row.total - row.remaining }} / {{ row.total }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="单价" width="90">
        <template #default="{ row }">{{ row.price ? `¥${row.price}` : '—' }}</template>
      </el-table-column>
      <el-table-column prop="openTime" label="放号时间" width="170" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="slotStatus(row.status)?.tag" effect="plain">{{
            slotStatus(row.status)?.label
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column
        v-if="auth.hasPermission('schedule:slot:manage')"
        label="操作"
        width="90"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button link :type="row.status === 1 ? 'danger' : 'primary'" @click="toggle(row as SlotVO)">
            {{ row.status === 1 ? '停诊' : '开放' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      :total="total"
      :page-sizes="[50, 100, 200]"
      layout="total, sizes, prev, pager, next"
      class="pager"
      @current-change="load"
      @size-change="search"
    />
    <SlotBatchDialog v-model="batchVisible" @created="load" />
  </div>
</template>

<style scoped>
.usage {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
