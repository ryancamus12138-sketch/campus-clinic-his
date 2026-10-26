<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { authApi } from '@/api/auth'
import { ApiError } from '@/types/api'
import { PASSWORD_HINT, passwordRules } from '@/utils/password'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// ---------------------------------------------------------------- 修改口令
const pwdDialog = ref(false)
const pwdRef = ref<FormInstance>()
const pwd = reactive({ oldPassword: '', newPassword: '' })
const pwdRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原口令', trigger: 'blur' }],
  newPassword: passwordRules(),
}
const pwdError = ref('')

async function changePassword() {
  if (!(await pwdRef.value?.validate().catch(() => false))) return
  pwdError.value = ''
  try {
    await authApi.changePassword(pwd.oldPassword, pwd.newPassword)
    ElMessage.success('口令已修改')
    pwdDialog.value = false
  } catch (e) {
    pwdError.value = e instanceof ApiError ? e.message : '修改失败'
  }
}

// 与 sys_role.role_name 一致
const roleNames: Record<string, string> = {
  ADMIN: '系统管理员',
  CASHIER: '收费员',
  PHARMACIST: '药师',
  DOCTOR: '医生',
  FINANCE: '财务',
  AUDITOR: '审计管理员',
}

/** 页头显示登录时选择的身份；没有（如令牌恢复的会话）则显示账号的全部角色。菜单和权限不受此影响 */
const shownRoles = computed(() => (auth.loginRole ? [auth.loginRole] : (auth.user?.roles ?? [])))

async function handleCommand(command: string) {
  if (command === 'logout') {
    const role = auth.loginRole
    await auth.logout()
    // 退出后回到同一身份的登录页，方便同岗位换人登录；需要换岗位可在登录页切换身份
    router.replace({ name: 'login', query: role ? { role } : {} })
  } else if (command === 'password') {
    Object.assign(pwd, { oldPassword: '', newPassword: '' })
    pwdError.value = ''
    pwdDialog.value = true
  }
}
</script>

<template>
  <header class="header">
    <div class="header__title">{{ route.meta.title }}</div>
    <el-dropdown trigger="click" @command="handleCommand">
      <span class="header__user">
        <el-avatar :size="28">{{ auth.user?.nickname?.slice(0, 1) }}</el-avatar>
        <span>{{ auth.user?.nickname }}</span>
        <el-tag v-for="role in shownRoles" :key="role" size="small" effect="plain">
          {{ roleNames[role] ?? role }}
        </el-tag>
        <el-icon><ArrowDown /></el-icon>
      </span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="password">修改口令</el-dropdown-item>
          <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>

    <el-dialog v-model="pwdDialog" title="修改口令" width="420px">
      <el-form ref="pwdRef" :model="pwd" :rules="pwdRules" label-width="72px">
        <el-form-item label="原口令" prop="oldPassword">
          <el-input v-model="pwd.oldPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新口令" prop="newPassword">
          <el-input v-model="pwd.newPassword" type="password" show-password autocomplete="new-password" />
        </el-form-item>
      </el-form>
      <p class="tip">{{ PASSWORD_HINT }}</p>
      <el-alert v-if="pwdError" :title="pwdError" type="error" :closable="false" />
      <template #footer>
        <el-button @click="pwdDialog = false">取消</el-button>
        <el-button type="primary" @click="changePassword">确定</el-button>
      </template>
    </el-dialog>
  </header>
</template>

<style scoped>
.tip {
  font-size: 12px;
  color: #6b7280;
  margin-top: 8px;
}

.header {
  height: var(--app-header-height);
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}

.header__title {
  font-size: 16px;
  font-weight: 600;
}

.header__user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}
</style>
