<template>
  <el-container class="mql-shell" style="min-height: 100vh">
    <el-aside
      v-if="!isMobile"
      class="mql-aside"
      :width="collapsed ? '64px' : '220px'"
    >
      <div class="mql-aside__brand">
        <div class="mql-brand-mark"></div>
        <span v-if="!collapsed">mql_manager</span>
      </div>

      <el-menu
        class="mql-menu"
        :default-active="active"
        :collapse="collapsed"
        background-color="var(--mql-panel-2)"
        text-color="#cbd5e1"
        active-text-color="#7aa2ff"
        router
      >
        <el-menu-item index="/dashboard">
          <el-icon><Odometer /></el-icon>
          <span>Dashboard</span>
        </el-menu-item>
        <el-menu-item index="/playlists">
          <el-icon><List /></el-icon>
          <span>Playlists</span>
        </el-menu-item>

        <el-menu-item index="/packages">
          <el-icon><Collection /></el-icon>
          <span>Packages</span>
        </el-menu-item>

        <el-menu-item index="/users">
          <el-icon><User /></el-icon>
          <span>Users</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="mql-header">
        <div class="mql-header__left">
          <el-button v-if="isMobile" text circle size="small" @click="mobileMenuOpen = true">
            <el-icon><Menu /></el-icon>
          </el-button>
          <el-button v-else text circle size="small" @click="collapsed = !collapsed">
            <el-icon><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
          </el-button>

          <el-breadcrumb separator="/" class="mql-breadcrumb">
            <el-breadcrumb-item v-for="(b, i) in breadcrumb" :key="i">{{ b }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="mql-header__right">
          <el-tag v-if="authMode" type="info" effect="plain">Auth: {{ authMode }}</el-tag>
          <el-tooltip content="Refresh" placement="bottom">
            <el-button text circle size="small" @click="reload">
              <el-icon><Refresh /></el-icon>
            </el-button>
          </el-tooltip>
          <el-dropdown>
            <span style="display:flex; gap: 8px; align-items:center; cursor:pointer;">
              <el-avatar size="small">A</el-avatar>
              <span style="font-weight:600;">admin</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="onLogout">Logout</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main :style="{ background: 'var(--mql-bg)', padding: isMobile ? '10px' : '14px' }">
        <slot />
      </el-main>
    </el-container>
  </el-container>

  <el-drawer v-model="mobileMenuOpen" class="mql-drawer" direction="ltr" size="78%" :with-header="false">
    <div class="mql-drawer__brand">
      <div class="mql-brand-mark"></div>
      <span>mql_manager</span>
    </div>

    <el-menu
      :default-active="active"
      background-color="#ffffff"
      text-color="#111827"
      active-text-color="#2563eb"
      router
      @select="() => (mobileMenuOpen = false)"
    >
      <el-menu-item index="/dashboard">
        <el-icon><Odometer /></el-icon>
        <span>Dashboard</span>
      </el-menu-item>
      <el-menu-item index="/playlists">
        <el-icon><List /></el-icon>
        <span>Playlists</span>
      </el-menu-item>
      <el-menu-item index="/packages">
        <el-icon><Collection /></el-icon>
        <span>Packages</span>
      </el-menu-item>
      <el-menu-item index="/users">
        <el-icon><User /></el-icon>
        <span>Users</span>
      </el-menu-item>
    </el-menu>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { auth } from '@/lib/auth'
import { Collection, Expand, Fold, List, Menu, Odometer, Refresh, User } from '@element-plus/icons-vue'

const props = defineProps<{ title: string; authRequired?: boolean }>()

const route = useRoute()
const router = useRouter()

const collapsed = ref(false)
const mobileMenuOpen = ref(false)
const isMobile = ref(false)

function updateIsMobile() {
  try {
    isMobile.value = window.matchMedia('(max-width: 768px)').matches
  } catch {
    isMobile.value = false
  }
  if (isMobile.value) collapsed.value = true
}

onMounted(() => {
  updateIsMobile()
  window.addEventListener('resize', updateIsMobile)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateIsMobile)
})

const active = computed(() => (typeof route.path === 'string' ? route.path : '/users'))
const breadcrumb = computed(() => {
  const bc = route.meta?.breadcrumb
  if (Array.isArray(bc) && bc.length) return bc as string[]
  const t = route.meta?.title
  return t ? [String(t)] : ['']
})
const authMode = computed(() => {
  if (props.authRequired === false) return 'disabled'
  const t = auth.getToken()
  return t ? 'token' : 'required'
})

function onLogout() {
  auth.logout()
  router.push('/login')
}

function reload() {
  router.go(0)
}
</script>

<style scoped>
.mql-shell {
  --mql-header-h: 56px;
}

.mql-aside {
  position: sticky;
  top: 0;
  height: 100vh;
  overflow: auto;
  background: var(--mql-panel-2);
  transition: width 0.15s ease;
  border-right: 1px solid rgba(255, 255, 255, 0.06);
}

.mql-aside__brand {
  height: var(--mql-header-h);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px;
  color: #e6edf3;
  font-weight: 800;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.mql-brand-mark {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: linear-gradient(135deg, #6d28d9, #2563eb);
}

.mql-menu {
  border-right: 0;
}

.mql-header {
  position: sticky;
  top: 0;
  z-index: 30;
  height: var(--mql-header-h);
  background: var(--mql-panel);
  border-bottom: 1px solid var(--mql-border);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding: 0 14px;
}

.mql-header__left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.mql-header__right {
  display: flex;
  gap: 10px;
  align-items: center;
}

.mql-breadcrumb {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mql-drawer__brand {
  height: var(--mql-header-h);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px;
  color: #111827;
  font-weight: 800;
  border-bottom: 1px solid var(--mql-border);
}

.mql-drawer :deep(.el-drawer__body) {
  padding: 0;
}
</style>
