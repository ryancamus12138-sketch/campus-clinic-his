<script setup lang="ts">
import { computed, type Component } from 'vue'
import { useRoute } from 'vue-router'
import {
  Box,
  Calendar,
  Collection,
  DataLine,
  Document,
  EditPen,
  Finished,
  FirstAidKit,
  HomeFilled,
  Money,
  OfficeBuilding,
  Postcard,
  PriceTag,
  RefreshLeft,
  Tickets,
  User,
  View,
  Wallet,
  Warning,
} from '@element-plus/icons-vue'
import { appRoutes } from '@/router/routes'
import { useAuthStore } from '@/stores/auth'

const icons: Record<string, Component> = {
  Box,
  Calendar,
  Collection,
  DataLine,
  Document,
  EditPen,
  Finished,
  FirstAidKit,
  HomeFilled,
  Money,
  OfficeBuilding,
  Postcard,
  PriceTag,
  RefreshLeft,
  Tickets,
  User,
  View,
  Wallet,
  Warning,
}

interface MenuItem {
  path: string
  title: string
  icon?: Component
}

interface MenuGroup {
  title: string | null
  items: MenuItem[]
}

const route = useRoute()
const auth = useAuthStore()

/** 按权限过滤后，按 meta.group 分组；没有分组的放在最上面 */
const groups = computed<MenuGroup[]>(() => {
  const result: MenuGroup[] = []
  for (const r of appRoutes) {
    if (!auth.hasPermission(r.meta?.permission)) continue
    const group = r.meta?.group ?? null
    let target = result.find((g) => g.title === group)
    if (!target) {
      target = { title: group, items: [] }
      result.push(target)
    }
    target.items.push({
      path: `/${r.path}`,
      title: r.meta?.title ?? '',
      icon: r.meta?.icon ? icons[r.meta.icon] : undefined,
    })
  }
  return result
})
</script>

<template>
  <el-menu
    :default-active="route.path"
    router
    background-color="transparent"
    text-color="#cfe4e2"
    active-text-color="#ffffff"
    class="side-menu"
  >
    <template v-for="group in groups" :key="group.title ?? 'root'">
      <div v-if="group.title" class="side-menu__group">{{ group.title }}</div>
      <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
        <el-icon v-if="item.icon"><component :is="item.icon" /></el-icon>
        <span>{{ item.title }}</span>
      </el-menu-item>
    </template>
  </el-menu>
</template>

<style scoped>
.side-menu {
  border-right: none;
}

.side-menu__group {
  padding: 16px 20px 6px;
  font-size: 12px;
  color: #7fa8a4;
}

.side-menu :deep(.el-menu-item.is-active) {
  background: rgba(255, 255, 255, 0.08);
}
</style>
