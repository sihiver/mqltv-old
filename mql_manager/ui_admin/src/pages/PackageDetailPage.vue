<template>
  <AdminShell :title="title" :auth-required="authRequired">
    <el-card>
      <template #header>
        <div style="display:flex; justify-content:space-between; align-items:center;">
          <strong>Package</strong>
          <div style="display:flex; gap: 8px; align-items:center;">
            <el-button @click="load" :loading="loading">Refresh</el-button>
            <el-button type="danger" plain size="small" @click="remove" :loading="deleting">Delete</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="error" :title="error" type="error" show-icon style="margin-bottom: 12px" />

      <div v-if="pkg">
        <el-row :gutter="12">
          <el-col :span="12" :xs="24">
            <p><b>ID:</b> {{ pkg.id }}</p>
            <p><b>Name:</b> {{ pkg.name }}</p>
            <p><b>Harga:</b> {{ formatIDR(pkg.price) }}</p>
          </el-col>
          <el-col :span="12" :xs="24">
            <p style="color:#64748b; font-size:12px">Created: {{ formatDateTimeID(pkg.createdAt) }}</p>
          </el-col>
        </el-row>
      </div>
    </el-card>

    <div style="height:12px" />

    <el-tabs v-model="activeTab" type="border-card">
      <el-tab-pane label="Channels" name="channels">
        <el-alert
          title="Pilih channel yang masuk ke paket ini. Paket bisa dipakai nanti untuk assignment (misalnya ke user)."
          type="info"
          show-icon
          style="margin-bottom: 12px"
        />

        <el-row :gutter="12" style="margin-bottom: 10px; align-items: end;">
          <el-col :span="8" :xs="24">
            <el-form label-position="top">
              <el-form-item label="Filter by playlist (optional)">
                <el-select v-model="channelsPlaylistId" placeholder="All playlists" clearable filterable style="width: 100%">
                  <el-option v-for="p in playlists" :key="p.id" :label="`#${p.id} — ${p.name}`" :value="p.id" />
                </el-select>
              </el-form-item>
            </el-form>
          </el-col>
          <el-col :span="10" :xs="24">
            <el-form label-position="top">
              <el-form-item label="Search">
                <el-input v-model="channelsQuery" placeholder="Search by name or group" clearable @keyup.enter="loadChannels" />
              </el-form-item>
            </el-form>
          </el-col>
          <el-col :span="6" :xs="24">
            <el-form label-position="top">
              <el-form-item label=" ">
                <div style="display:flex; gap: 8px; justify-content:flex-end; width: 100%;">
                  <el-button @click="loadChannels" :loading="loadingChannels">Load</el-button>
                  <el-button type="warning" plain @click="clearSelectedChannels" :disabled="selectedChannelIds.length === 0">Clear</el-button>
                  <el-button type="primary" @click="saveChannels" :loading="savingChannels" :disabled="!pkg">
                    Save ({{ selectedChannelIds.length }})
                  </el-button>
                </div>
              </el-form-item>
            </el-form>
          </el-col>
        </el-row>

        <el-table
          ref="channelsTableRef"
          :data="channels"
          v-loading="loadingChannels"
          style="width:100%"
          border
          height="520"
          :row-key="(row: any) => row.id"
          @selection-change="onChannelSelectionChange"
        >
          <el-table-column type="selection" width="55" />
          <el-table-column prop="name" label="Name" min-width="220" />
          <el-table-column prop="groupTitle" label="Group" min-width="180" />
          <el-table-column prop="streamUrl" label="Stream URL" min-width="320" show-overflow-tooltip />
        </el-table>

        <div style="margin-top: 10px; color:#64748b; font-size:12px; display:flex; justify-content:space-between;">
          <span>Showing {{ channels.length }} channels</span>
          <span>Selected {{ selectedChannelIds.length }} total</span>
        </div>
      </el-tab-pane>
    </el-tabs>
  </AdminShell>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminShell from '@/components/AdminShell.vue'
import { api, type Channel, type Package, type Playlist } from '@/lib/api'
import { formatDateTimeID } from '@/lib/datetime'
import { formatIDR } from '@/lib/money'

const route = useRoute()
const router = useRouter()

const id = Number(route.params.id)

const pkg = ref<Package | null>(null)
const playlists = ref<Playlist[]>([])

const loading = ref(false)
const deleting = ref(false)
const error = ref<string | null>(null)
const authRequired = ref<boolean | undefined>(undefined)

const activeTab = ref<'channels'>('channels')

const channelsTableRef = ref<any>(null)
const channels = ref<Channel[]>([])
const selectedChannelIds = ref<number[]>([])
const channelsPlaylistId = ref<number | null>(null)
const channelsQuery = ref('')
const loadingChannels = ref(false)
const savingChannels = ref(false)

const title = computed(() => (pkg.value ? `Package #${pkg.value.id}` : 'Package'))

async function load() {
  loading.value = true
  error.value = null
  try {
    const h = await api.health()
    authRequired.value = h.authRequired

    pkg.value = await api.getPackage(id)
    playlists.value = await api.listPlaylists()

    const selected = await api.getPackageChannels(id)
    selectedChannelIds.value = selected.map((c) => c.id)

    await loadChannels()
  } catch (e: any) {
    error.value = e?.message || 'Failed to load'
  } finally {
    loading.value = false
  }
}

async function loadChannels() {
  loadingChannels.value = true
  try {
    const items = await api.listChannels({ playlistId: channelsPlaylistId.value, q: channelsQuery.value.trim(), limit: 2000 })
    channels.value = items
    await nextTick()
    applyChannelSelectionToTable()
  } catch (e: any) {
    ElMessage.error(e?.message || 'Failed to load channels')
  } finally {
    loadingChannels.value = false
  }
}

function applyChannelSelectionToTable() {
  const table = channelsTableRef.value
  if (!table) return
  if (typeof table.clearSelection === 'function') table.clearSelection()
  const selected = new Set(selectedChannelIds.value)
  for (const row of channels.value) {
    if (selected.has(row.id) && typeof table.toggleRowSelection === 'function') {
      table.toggleRowSelection(row, true)
    }
  }
}

function onChannelSelectionChange(rows: Channel[]) {
  const visible = new Set(channels.value.map((c) => c.id))
  const selectedVisible = new Set(rows.map((r) => r.id))

  const next = selectedChannelIds.value.filter((id) => !visible.has(id))
  for (const id of selectedVisible) next.push(id)
  selectedChannelIds.value = next
}

function clearSelectedChannels() {
  selectedChannelIds.value = []
  applyChannelSelectionToTable()
}

async function saveChannels() {
  if (!pkg.value) return
  savingChannels.value = true
  try {
    await api.setPackageChannels(pkg.value.id, selectedChannelIds.value)
    ElMessage.success('Channels saved')
  } catch (e: any) {
    ElMessage.error(e?.message || 'Save failed')
  } finally {
    savingChannels.value = false
  }
}

async function remove() {
  if (!pkg.value) return
  try {
    await ElMessageBox.confirm('Delete this package?', 'Confirm', { type: 'warning' })
  } catch {
    return
  }

  deleting.value = true
  try {
    await api.deletePackage(pkg.value.id)
    ElMessage.success('Deleted')
    router.push('/packages')
  } catch (e: any) {
    ElMessage.error(e?.message || 'Delete failed')
  } finally {
    deleting.value = false
  }
}

onMounted(load)
</script>
