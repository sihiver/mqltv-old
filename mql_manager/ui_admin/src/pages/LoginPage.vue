<template>
  <div style="min-height:100vh; display:flex; align-items:center; justify-content:center; background:#0f172a;">
    <el-card style="width: 520px;">
      <template #header>
        <div style="display:flex; justify-content:space-between; align-items:center;">
          <strong>Admin Login</strong>
          <el-tag type="info" effect="plain">API: /api</el-tag>
        </div>
      </template>

      <el-alert
        v-if="error"
        :title="error"
        type="error"
        show-icon
        style="margin-bottom: 12px"
      />

      <el-form label-position="top" @submit.prevent>
        <el-form-item label="Admin token">
          <el-input v-model="token" placeholder="boleh paste: Bearer ..." />
          <div style="margin-top:8px; color:#64748b; font-size: 12px;">
            Kosongkan token hanya jika backend auth dimatikan (localhost).
          </div>
        </el-form-item>

        <div style="display:flex; gap: 10px;">
          <el-button type="primary" @click="login">Login</el-button>
          <el-button @click="continueNoToken">Lanjut tanpa token</el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '@/lib/api'
import { auth } from '@/lib/auth'

const router = useRouter()
const route = useRoute()

const token = ref('')
const error = ref<string | null>(null)

function normalizeToken(input: string) {
  let t = (input || '').trim()
  if (t.toLowerCase().startsWith('bearer ')) t = t.slice('bearer '.length).trim()
  return t
}

async function login() {
  error.value = null
  const t = normalizeToken(token.value)
  auth.setToken(t)

  try {
    await api.listUsers()
    ElMessage.success('Login OK')
    const next = typeof route.query.next === 'string' ? route.query.next : '/users'
    router.push(next)
  } catch (e: any) {
    auth.logout()
    error.value = e?.message || 'Login failed'
  }
}

async function continueNoToken() {
  error.value = null
  auth.markNoAuthMode()

  try {
    const h = await api.health()
    if (h.authRequired) {
      auth.logout()
      error.value = 'Backend masih butuh token (authRequired=true)'
      return
    }
    ElMessage.success('Auth disabled mode')
    router.push('/users')
  } catch (e: any) {
    auth.logout()
    error.value = e?.message || 'Cannot reach backend'
  }
}
</script>
