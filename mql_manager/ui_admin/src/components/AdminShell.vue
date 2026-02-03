<template>
  <el-container style="min-height: 100vh">
    <el-aside class="mql-aside" :width="collapsed ? '64px' : '220px'" style="background: var(--mql-panel-2); transition: width .15s ease;">
      <div style="display:flex; align-items:center; gap: 10px; padding: 14px 14px; color: #e6edf3; font-weight: 800;">
        <div style="width: 28px; height: 28px; border-radius: 8px; background: linear-gradient(135deg,#6d28d9,#2563eb);"></div>
        <span v-if="!collapsed">mql_manager</span>
      </div>

      <el-menu
        :default-active="active"
        :collapse="collapsed"
        background-color="var(--mql-panel-2)"
        text-color="#cbd5e1"
        active-text-color="#7aa2ff"
        router
      >
        <el-sub-menu index="dash">
          <template #title>
            <el-icon><Odometer /></el-icon>
            <span>Dashboard</span>
          </template>
          <el-menu-item index="/dashboard/analysis">Analysis</el-menu-item>
          <el-menu-item index="/dashboard/workplace">Workplace</el-menu-item>
        </el-sub-menu>
        <el-menu-item index="/users">
          <el-icon><User /></el-icon>
          <span>Users</span>
        </el-menu-item>

        <el-menu-item index="/playlists">
          <el-icon><List /></el-icon>
          <span>Playlists</span>
        </el-menu-item>

        <el-menu-item index="/packages">
          <el-icon><Collection /></el-icon>
          <span>Packages</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="mql-header" style="background: #fff; border-bottom: 1px solid #e5e7eb; display:flex; justify-content: space-between; align-items:center;">
        <div style="display:flex; align-items:center; gap: 12px;">
          <el-button text @click="collapsed = !collapsed">
            <el-icon><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
          </el-button>

          <el-breadcrumb separator="/" style="font-weight:600;">
            <el-breadcrumb-item v-for="(b, i) in breadcrumb" :key="i">{{ b }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div style="display:flex; gap: 10px; align-items:center;">
          <el-tag v-if="authMode" type="info" effect="plain">Auth: {{ authMode }}</el-tag>
          <el-tooltip content="Refresh" placement="bottom">
            <el-button text @click="reload">
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

      <el-main style="background: var(--mql-bg); padding: 14px;">
        <slot />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { auth } from '@/lib/auth'
import { Collection, Expand, Fold, List, Odometer, Refresh, User } from '@element-plus/icons-vue'

const props = defineProps<{ title: string; authRequired?: boolean }>()

const route = useRoute()
const router = useRouter()

const collapsed = ref(false)

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
.mql-aside {
  position: sticky;
  top: 0;
  height: 100vh;
  overflow: auto;
}

.mql-header {
  position: sticky;
  top: 0;
  z-index: 30;
}
</style>
