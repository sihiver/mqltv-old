<template>
  <AdminShell title="Users" :auth-required="authRequired">
    <el-card>
      <div style="display:flex; justify-content:space-between; align-items:center; gap: 12px; flex-wrap: wrap;">
        <div style="display:flex; gap: 10px; align-items:center;">
          <el-input v-model="q" placeholder="Search username" style="width: 260px" clearable />
          <el-button @click="load" :loading="loading">Refresh</el-button>
        </div>
        <el-button type="primary" @click="showCreate = true">New User</el-button>
      </div>

      <el-alert v-if="error" :title="error" type="error" show-icon style="margin: 12px 0" />

      <el-table :data="filtered" style="width:100%; margin-top: 12px" v-loading="loading">
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column prop="username" label="Username" />
        <el-table-column prop="displayName" label="Display name" />
        <el-table-column label="Playlist" width="110">
          <template #default="scope">
            <span style="color:#64748b">{{ scope.row.playlistId ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="App key" min-width="220">
          <template #default="scope">
            <span style="font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;">
              {{ scope.row.appKey ? String(scope.row.appKey).slice(0, 12) + '…' : '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="Created" width="240" />
        <el-table-column label="Actions" width="140">
          <template #default="scope">
            <el-button size="small" @click="go(scope.row.id)">Open</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="showCreate" title="Create user" width="520px">
      <el-form label-position="top">
        <el-form-item label="Username">
          <el-input v-model="newUsername" placeholder="e.g. user001" />
        </el-form-item>
        <el-form-item label="Display name">
          <el-input v-model="newDisplayName" placeholder="e.g. John" />
        </el-form-item>
        <el-form-item label="Password">
          <el-input v-model="newPassword" type="password" show-password placeholder="min 4 chars" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">Cancel</el-button>
        <el-button type="primary" @click="create" :loading="creating">Create</el-button>
      </template>
    </el-dialog>
  </AdminShell>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AdminShell from '@/components/AdminShell.vue'
import { api, type User } from '@/lib/api'

const router = useRouter()

const users = ref<User[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const q = ref('')
const authRequired = ref<boolean | undefined>(undefined)

const showCreate = ref(false)
const newUsername = ref('')
const newDisplayName = ref('')
const newPassword = ref('')
const creating = ref(false)

const filtered = computed(() => {
  const term = q.value.trim().toLowerCase()
  if (!term) return users.value
  return users.value.filter((u) => u.username.toLowerCase().includes(term))
})

function go(id: number) {
  router.push(`/users/${id}`)
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const h = await api.health()
    authRequired.value = h.authRequired
    users.value = await api.listUsers()
  } catch (e: any) {
    error.value = e?.message || 'Failed to load'
  } finally {
    loading.value = false
  }
}

async function create() {
  creating.value = true
  try {
    const u = await api.createUser({
      username: newUsername.value.trim(),
      displayName: newDisplayName.value.trim(),
      password: newPassword.value.trim()
    })
    ElMessage.success('User created')
    showCreate.value = false
    newUsername.value = ''
    newDisplayName.value = ''
    newPassword.value = ''
    await load()
    go(u.id)
  } catch (e: any) {
    ElMessage.error(e?.message || 'Create failed')
  } finally {
    creating.value = false
  }
}

onMounted(load)
</script>
