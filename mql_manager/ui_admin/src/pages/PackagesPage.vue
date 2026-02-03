<template>
  <AdminShell title="Packages" :auth-required="authRequired">
    <el-card>
      <div style="display:flex; justify-content:space-between; align-items:center; gap: 12px; flex-wrap: wrap;">
        <div style="display:flex; gap: 10px; align-items:center;">
          <el-button @click="load" :loading="loading">Refresh</el-button>
        </div>
        <el-button type="primary" @click="showCreate = true">New Package</el-button>
      </div>

      <el-alert v-if="error" :title="error" type="error" show-icon style="margin: 12px 0" />

      <el-table :data="items" style="width:100%; margin-top: 12px" v-loading="loading">
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column prop="name" label="Name" min-width="240" />
        <el-table-column prop="createdAt" label="Created" width="240" />
        <el-table-column label="Actions" width="220">
          <template #default="scope">
            <el-button size="small" @click="open(scope.row.id)">Open</el-button>
            <el-button size="small" type="danger" plain @click="remove(scope.row.id)" :loading="deletingId === scope.row.id">
              Delete
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="showCreate" title="Create package" width="520px">
      <el-form label-position="top">
        <el-form-item label="Package name">
          <el-input v-model="newName" placeholder="e.g. Paket Sport" />
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
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminShell from '@/components/AdminShell.vue'
import { api, type Package } from '@/lib/api'

const router = useRouter()

const items = ref<Package[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const authRequired = ref<boolean | undefined>(undefined)

const showCreate = ref(false)
const newName = ref('')
const creating = ref(false)

const deletingId = ref<number | null>(null)

function open(id: number) {
  router.push(`/packages/${id}`)
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const h = await api.health()
    authRequired.value = h.authRequired
    items.value = await api.listPackages()
  } catch (e: any) {
    error.value = e?.message || 'Failed to load'
  } finally {
    loading.value = false
  }
}

async function create() {
  creating.value = true
  try {
    const p = await api.createPackage({ name: newName.value.trim() })
    ElMessage.success('Package created')
    showCreate.value = false
    newName.value = ''
    items.value = [p, ...items.value]
    open(p.id)
  } catch (e: any) {
    ElMessage.error(e?.message || 'Create failed')
  } finally {
    creating.value = false
  }
}

async function remove(id: number) {
  try {
    await ElMessageBox.confirm('Delete this package?', 'Confirm', { type: 'warning' })
  } catch {
    return
  }

  deletingId.value = id
  try {
    await api.deletePackage(id)
    ElMessage.success('Deleted')
    items.value = items.value.filter((p) => p.id !== id)
  } catch (e: any) {
    ElMessage.error(e?.message || 'Delete failed')
  } finally {
    deletingId.value = null
  }
}

onMounted(load)
</script>
