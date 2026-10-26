<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { auditApi, type AuditLogVO, type AuditQuery } from '@/api/audit'

const query = reactive<AuditQuery & { range?: [string, string] }>({ page: 1, size: 20 })
const rows = ref<AuditLogVO[]>([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const { range, ...rest } = query
    const result = await auditApi.page({
      ...rest,
      username: rest.username || undefined,
      action: rest.action || undefined,
      from: range?.[0],
      to: range?.[1],
    })
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

onMounted(load)
</script>

<template>
  <div class="audit">
    <div class="page-card">
      <div class="page-toolbar">
        <el-input
          v-model="query.username"
          placeholder="用户名"
          clearable
          style="width: 160px"
          @keyup.enter="search"
        />
        <el-input
          v-model="query.action"
          placeholder="操作（前缀，如 LOGIN、Refund）"
          clearable
          style="width: 220px"
          @keyup.enter="search"
        />
        <el-select v-model="query.success" placeholder="结果" clearable style="width: 110px">
          <el-option label="成功" :value="true" />
          <el-option label="失败" :value="false" />
        </el-select>
        <el-date-picker
          v-model="query.range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 260px"
        />
        <el-button type="primary" @click="search">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" border size="small">
        <el-table-column prop="eventTime" label="时间" width="180" />
        <el-table-column prop="username" label="用户" width="120" />
        <el-table-column label="操作" min-width="200">
          <template #default="{ row }">
            <div>{{ row.actionName ?? row.action }}</div>
            <div class="audit__sub">{{ row.action }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="target" label="对象" min-width="140" show-overflow-tooltip />
        <el-table-column label="结果" width="110">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small">
              {{ row.success ? '成功' : `失败${row.errorCode ? ' ' + row.errorCode : ''}` }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="clientIp" label="终端 IP" width="130" />
        <el-table-column prop="detail" label="说明" min-width="180" show-overflow-tooltip />
        <el-table-column prop="traceId" label="链路 ID" width="160" show-overflow-tooltip />
      </el-table>
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[20, 50, 100, 200]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="load"
        @size-change="search"
      />
    </div>
  </div>
</template>

<style scoped>
.audit__sub {
  font-size: 12px;
  color: #9ca3af;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
