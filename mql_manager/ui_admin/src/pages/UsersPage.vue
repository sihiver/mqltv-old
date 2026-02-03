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
        <el-table-column prop="username" label="Username" min-width="160" />
        <el-table-column label="Paket" min-width="140">
          <template #default="scope">
            <span>{{ (scope.row.packages && scope.row.packages.length ? scope.row.packages.join(', ') : '—') }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Sisa masa aktif" width="160">
          <template #default="scope">
            <span>{{ remainingLabel(scope.row.expiresAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Expires" width="240">
          <template #default="scope">
            <span>{{ scope.row.expiresAt ? formatDateTimeID(scope.row.expiresAt) : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Created" width="240">
          <template #default="scope">
            <span>{{ formatDateTimeID(scope.row.createdAt) }}</span>
          </template>
        </el-table-column>
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

        <el-form-item label="Paket (optional)">
          <el-select v-model="selectedPackageIds" multiple filterable clearable placeholder="Pilih paket" style="width: 100%">
            <el-option
              v-for="p in allPackages"
              :key="p.id"
              :label="`#${p.id} — ${p.name} (${formatIDR(p.price)})`"
              :value="p.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="Masa aktif (hari) (optional)">
          <el-input-number v-model="activeDays" :min="0" :step="1" style="width: 100%" />
          <div v-if="expiresAt" style="margin-top: 6px; color:#64748b; font-size:12px">Akan berakhir: {{ formatDateTimeID(expiresAt) }}</div>
        </el-form-item>

        <el-form-item v-if="activeDays > 0" label="Plan">
          <el-input v-model="subPlan" placeholder="e.g. basic" />
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
import { api, type Package, type User } from '@/lib/api'
import { formatDateTimeID } from '@/lib/datetime'
import { formatIDR } from '@/lib/money'

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

const allPackages = ref<Package[]>([])
const selectedPackageIds = ref<number[]>([])
const activeDays = ref<number>(0)
const subPlan = ref<string>('basic')

const expiresAt = computed(() => {
  const days = Number(activeDays.value) || 0
  if (days <= 0) return ''
  return new Date(Date.now() + days * 24 * 60 * 60 * 1000).toISOString()
})

const filtered = computed(() => {
  const term = q.value.trim().toLowerCase()
  if (!term) return users.value
  return users.value.filter((u) => u.username.toLowerCase().includes(term))
})

function go(id: number) {
  router.push(`/users/${id}`)
}

function remainingLabel(expiresAt?: string): string {
  if (!expiresAt) return '—'
  const exp = new Date(expiresAt).getTime()
  if (!Number.isFinite(exp)) return '—'
  const now = Date.now()
  const msLeft = exp - now
  const daysLeft = Math.ceil(msLeft / (24 * 60 * 60 * 1000))
  if (daysLeft <= 0) return 'Expired'
  return `${daysLeft} hari`
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const h = await api.health()
    authRequired.value = h.authRequired
    users.value = await api.listUsers()
    allPackages.value = await api.listPackages()
  } catch (e: any) {
    error.value = e?.message || 'Failed to load'
  } finally {
    loading.value = false
  }
}

async function create() {
  creating.value = true
  try {
    if ((Number(activeDays.value) || 0) > 0 && !subPlan.value.trim()) {
      throw new Error('Plan is required when masa aktif is set')
    }
    const u = await api.createUser({
      username: newUsername.value.trim(),
      displayName: newDisplayName.value.trim(),
      password: newPassword.value.trim(),
      packageIds: selectedPackageIds.value,
      subscription:
        (Number(activeDays.value) || 0) > 0
          ? {
              plan: subPlan.value.trim(),
              expiresAt: expiresAt.value
            }
          : undefined
    })
    ElMessage.success('User created')
    showCreate.value = false
    newUsername.value = ''
    newDisplayName.value = ''
    newPassword.value = ''
    selectedPackageIds.value = []
    activeDays.value = 0
    subPlan.value = 'basic'
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
