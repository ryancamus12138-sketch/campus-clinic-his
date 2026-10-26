<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { appRoutes } from '@/router/routes'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

/** 当前用户可用的功能入口 */
const shortcuts = computed(() =>
  appRoutes.filter((r) => r.meta?.group && auth.hasPermission(r.meta.permission)),
)
</script>

<template>
  <div class="page-card">
    <h2 class="welcome">你好，{{ auth.user?.nickname }}</h2>
    <p class="hint">以下是你当前角色可以使用的功能。</p>
    <div class="shortcuts">
      <el-card
        v-for="r in shortcuts"
        :key="String(r.name)"
        shadow="hover"
        class="shortcut"
        @click="router.push(`/${r.path}`)"
      >
        <div class="shortcut__group">{{ r.meta?.group }}</div>
        <div class="shortcut__title">{{ r.meta?.title }}</div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.welcome {
  margin: 0 0 4px;
}

.hint {
  color: #6b7280;
  margin: 0 0 20px;
}

.shortcuts {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 16px;
}

.shortcut {
  cursor: pointer;
}

.shortcut__group {
  font-size: 12px;
  color: #6b7280;
}

.shortcut__title {
  font-size: 16px;
  font-weight: 600;
  margin: 6px 0 10px;
}
</style>
