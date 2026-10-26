<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  userApi,
  type RoleVO,
  type UserForm,
  type UserQuery,
  type UserSecretVO,
  type UserVO,
} from '@/api/user'
import { useCampuses, orgApi, type OptionVO } from '@/api/org'
import { useAuthStore } from '@/stores/auth'

const campuses = useCampuses()

const auth = useAuthStore()
const query = reactive<UserQuery>({ page: 1, size: 20, keyword: '', userType: undefined })
const rows = ref<UserVO[]>([])
const total = ref(0)
const loading = ref(false)
const roles = ref<RoleVO[]>([])
const depts = ref<OptionVO[]>([])
const roleName = computed(() => new Map(roles.value.map((r) => [r.id, r.name])))

async function load() {
  loading.value = true
  try {
    const result = await userApi.page({ ...query, keyword: query.keyword || undefined })
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

onMounted(async () => {
  load()
  if (auth.hasPermission('system:user:list')) {
    roles.value = await userApi.roles()
    depts.value = await orgApi.depts()
  }
})

// ---------------------------------------------------------------- 新增 / 修改
const dialog = ref(false)
const editingId = ref<string | null>(null)
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<UserForm>({
  username: '',
  nickname: '',
  campusId: '1',
  deptId: undefined,
  roleIds: [],
  status: 1,
})
const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      pattern: /^[A-Za-z][A-Za-z0-9_]{3,31}$/,
      message: '字母开头，4–32 位字母、数字或下划线',
      trigger: 'blur',
    },
  ],
  nickname: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  roleIds: [{ type: 'array', required: true, min: 1, message: '至少选择一个角色', trigger: 'change' }],
}

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    username: '',
    nickname: '',
    campusId: '1',
    deptId: undefined,
    roleIds: [],
    status: 1,
  })
  dialog.value = true
  formRef.value?.clearValidate()
}

function openEdit(row: UserVO) {
  editingId.value = row.id
  Object.assign(form, {
    username: row.username,
    nickname: row.nickname,
    campusId: row.campusId ?? '1',
    deptId: row.deptId,
    roleIds: [...row.roleIds],
    status: row.status,
  })
  dialog.value = true
}

async function save() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  saving.value = true
  try {
    if (editingId.value) {
      await userApi.update(editingId.value, form)
      ElMessage.success('已保存，角色变更在对方下次刷新登录状态时生效')
    } else {
      showSecret(await userApi.create(form), '账号已创建')
    }
    dialog.value = false
    load()
  } finally {
    saving.value = false
  }
}

// ---------------------------------------------------------------- 口令、锁定
const secret = ref<(UserSecretVO & { title: string }) | null>(null)

function showSecret(value: UserSecretVO, title: string) {
  secret.value = { ...value, title }
}

async function copySecret() {
  await navigator.clipboard.writeText(secret.value?.temporaryPassword ?? '')
  ElMessage.success('已复制')
}

async function resetPassword(row: UserVO) {
  await ElMessageBox.confirm(`为 ${row.nickname}（${row.username}）生成新的临时口令？`, '重置口令', {
    type: 'warning',
  })
  showSecret(await userApi.resetPassword(row.id), '口令已重置')
  load()
}

async function unlock(row: UserVO) {
  await userApi.unlock(row.id)
  ElMessage.success('已解除登录锁定')
}
</script>

<template>
  <div class="page-card">
    <div class="page-toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="用户名或姓名"
        clearable
        style="width: 220px"
        @keyup.enter="search"
      />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button v-permission="'system:user:create'" @click="openCreate">新增员工</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="username" label="用户名" min-width="130" />
      <el-table-column prop="nickname" label="姓名" min-width="110" />
      <el-table-column label="角色" min-width="160">
        <template #default="{ row }">
          <el-tag v-for="id in row.roleIds" :key="id" size="small" effect="plain" class="role-tag">
            {{ roleName.get(id) ?? id }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
          <el-tag v-if="row.mustChangePassword === 1" size="small" type="warning" class="role-tag"
            >待改口令</el-tag
          >
        </template>
      </el-table-column>
      <el-table-column prop="lastLoginTime" label="最近登录" min-width="160" />
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <template v-if="row.userType === 1">
            <el-button
              v-permission="'system:user:update'"
              link
              type="primary"
              @click="openEdit(row as UserVO)"
              >编辑</el-button
            >
            <el-button
              v-permission="'system:user:reset'"
              link
              type="primary"
              @click="resetPassword(row as UserVO)"
            >
              重置口令
            </el-button>
            <el-button v-permission="'system:user:reset'" link @click="unlock(row as UserVO)">解锁</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      :total="total"
      :page-sizes="[20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      class="pager"
      @current-change="load"
      @size-change="search"
    />

    <el-dialog v-model="dialog" :title="editingId ? '编辑员工' : '新增员工'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="!!editingId" />
        </el-form-item>
        <el-form-item label="姓名" prop="nickname"
          ><el-input v-model="form.nickname" maxlength="50"
        /></el-form-item>
        <el-form-item label="校区">
          <el-select v-model="form.campusId">
            <el-option v-for="c in campuses" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="科室">
          <el-select v-model="form.deptId" clearable placeholder="不属于临床科室可不选">
            <el-option v-for="d in depts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色" prop="roleIds">
          <el-checkbox-group v-model="form.roleIds">
            <el-checkbox v-for="r in roles" :key="r.id" :value="r.id">{{ r.name }}</el-checkbox>
          </el-checkbox-group>
          <div class="form-tip">系统管理员与审计管理员不能由同一人兼任</div>
        </el-form-item>
        <el-form-item v-if="editingId" label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog :model-value="!!secret" :title="secret?.title" width="420px" @close="secret = null">
      <p>
        账号 <b>{{ secret?.username }}</b> 的临时口令（只显示这一次，请当面或通过安全渠道交给本人）：
      </p>
      <el-input :model-value="secret?.temporaryPassword" readonly class="mono">
        <template #append><el-button @click="copySecret">复制</el-button></template>
      </el-input>
      <p class="form-tip">对方首次登录必须修改口令。</p>
      <template #footer><el-button type="primary" @click="secret = null">我已记下</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}

.role-tag {
  margin: 2px 4px 2px 0;
}

.form-tip {
  font-size: 12px;
  color: #6b7280;
}

.mono :deep(input) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
</style>
