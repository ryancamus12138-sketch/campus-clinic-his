<script setup lang="ts">
import SideMenu from './SideMenu.vue'
import AppHeader from './AppHeader.vue'
import { useOrgName } from '@/api/auth'

const orgName = useOrgName()
</script>

<template>
  <div class="layout">
    <aside class="layout__sidebar">
      <div class="layout__brand">
        <span class="layout__logo">医</span>
        <span>{{ orgName }}</span>
      </div>
      <SideMenu />
    </aside>
    <div class="layout__main">
      <AppHeader />
      <main class="layout__content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100%;
}

.layout__sidebar {
  width: var(--app-sidebar-width);
  flex-shrink: 0;
  background: #0b2b29;
  color: #e5f2f1;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

.layout__brand {
  height: var(--app-header-height);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.layout__logo {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: var(--el-color-primary);
  display: grid;
  place-items: center;
  font-size: 15px;
}

.layout__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.layout__content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
