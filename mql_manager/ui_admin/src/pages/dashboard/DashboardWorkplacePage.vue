<template>
  <AdminShell title="Workplace" :auth-required="authRequired">
    <el-card class="mql-card">
      <h3 style="margin-top:0;">Workplace</h3>
      <p style="color:#94a3b8; margin:0;">Placeholder page. Add operational widgets here (logs, server status, recent users).</p>
    </el-card>
  </AdminShell>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AdminShell from '@/components/AdminShell.vue'
import { api } from '@/lib/api'

const authRequired = ref<boolean | undefined>(undefined)

onMounted(async () => {
  try {
    const h = await api.health()
    authRequired.value = h.authRequired
  } catch {
    // ignore
  }
})
</script>
