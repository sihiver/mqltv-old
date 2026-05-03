<template>
  <AdminShell :title="title" :auth-required="authRequired">
    <el-card class="mql-card" style="border: 1px solid var(--mql-border)">
      <template #header>
        <div style="display:flex; justify-content:space-between; align-items:center;">
          <strong>User</strong>
          <div style="display:flex; gap: 8px; align-items:center;">
            <el-tag v-if="selectedChannelIds.length > 0" type="success" effect="plain" size="small">
              Custom channels: {{ selectedChannelIds.length }}
            </el-tag>
            <el-button size="small" @click="load" :loading="loading">Refresh</el-button>
            <el-button size="small" type="danger" plain @click="remove" :loading="deleting">Delete</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="error" :title="error" type="error" show-icon style="margin-bottom: 12px" />

      <div v-if="user">
        <el-row :gutter="12">
          <el-col :span="12" :xs="24">
            <el-card shadow="never" class="mql-card" style="border: 1px solid var(--mql-border)">
              <template #header><strong>Info</strong></template>

              <el-descriptions :column="1" border size="small">
                <el-descriptions-item label="ID">{{ user.id }}</el-descriptions-item>
                <el-descriptions-item label="Username">{{ user.username }}</el-descriptions-item>
                <el-descriptions-item label="Display name">{{ user.displayName || '—' }}</el-descriptions-item>
                <el-descriptions-item label="Langganan (plan)">
                  <span>{{ latestPlan || '—' }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="Paket (packages)">
                  <span>{{ assignedPackageNames }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="Status">
                  <el-tag :type="statusTag.type" size="small">{{ statusTag.label }}</el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="Sisa masa aktif">{{ remaining }}</el-descriptions-item>
                <el-descriptions-item label="Berakhir">
                  <span>{{ latestExpiresAt ? formatDateTimeID(latestExpiresAt) : '—' }}</span>
                </el-descriptions-item>
                <el-descriptions-item label="Created">{{ formatDateTimeID(user.createdAt) }}</el-descriptions-item>
              </el-descriptions>
            </el-card>
          </el-col>

          <el-col :span="12" :xs="24">
            <el-card shadow="never" class="mql-card" style="border: 1px solid var(--mql-border)">
              <template #header><strong>Access</strong></template>

              <el-form label-position="top">
                <el-form-item label="App key">
                  <el-input :model-value="user.appKey || ''" readonly class="mono">
                    <template #append>
                      <el-button :disabled="!user.appKey" @click="user.appKey && copy(user.appKey)">Copy</el-button>
                    </template>
                  </el-input>
                </el-form-item>

                <el-form-item label="Public playlist URL">
                  <el-input :model-value="publicPlaylistAbsUrl" readonly class="mono">
                    <template #append>
                      <el-button :disabled="!publicPlaylistAbsUrl" @click="publicPlaylistAbsUrl && copy(publicPlaylistAbsUrl)">
                        Copy
                      </el-button>
                    </template>
                  </el-input>
                </el-form-item>
              </el-form>
            </el-card>
          </el-col>
        </el-row>
      </div>
    </el-card>

    <div style="height:12px" />

    <el-card class="mql-card" style="border: 1px solid var(--mql-border)">
      <el-alert
        title="Prioritas playlist publik: Channels (manual) → Packages → Playlist (fallback). Jika subscription expired, akses playlist akan diblok."
        type="info"
        show-icon
        style="margin-bottom: 12px"
      />
      <el-tabs v-model="activeTab" type="card">
        <el-tab-pane label="Channels (Manual)" name="channels">
        <el-alert
          title="Pilih channel manual (custom). Jika ada custom channels, playlist publik akan di-generate dari custom channels. Jika kosong, channel efektif akan mengikuti Packages (Auto)."
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
                  <el-button type="warning" plain @click="clearSelectedChannels" :disabled="selectedChannelIds.length === 0">
                    Clear
                  </el-button>
                  <el-button type="primary" @click="saveChannels" :loading="savingChannels" :disabled="!user">
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
          <el-table-column label="Added" width="110">
            <template #default="scope">
              <el-tag v-if="isChannelAdded(scope.row.id)" type="success" size="small">Added</el-tag>
              <span v-else style="color:#94a3b8; font-size:12px;">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="Name" min-width="220" />
          <el-table-column prop="groupTitle" label="Group" min-width="180" />
          <el-table-column prop="streamUrl" label="Stream URL" min-width="320" show-overflow-tooltip />
        </el-table>

        <div style="margin-top: 10px; color:#64748b; font-size:12px; display:flex; justify-content:space-between;">
          <span>Showing {{ channels.length }} channels</span>
          <span>Custom {{ selectedChannelIds.length }} • Effective {{ effectiveChannelCount }}</span>
        </div>
        </el-tab-pane>

        <el-tab-pane label="Packages (Auto)" name="packages">
        <el-alert
          title="Assign paket ke user. Jika user belum memilih channel manual, playlist publik akan di-generate dari paket yang dipilih."
          type="info"
          show-icon
          style="margin-bottom: 12px"
        />

        <el-row :gutter="12" style="margin-bottom: 10px; align-items: end;">
          <el-col :span="18" :xs="24">
            <el-form label-position="top">
              <el-form-item label="Select packages">
                <el-select v-model="selectedPackageIds" multiple filterable clearable placeholder="Choose packages" style="width: 100%">
                  <el-option v-for="p in allPackages" :key="p.id" :label="`#${p.id} — ${p.name}`" :value="p.id" />
                </el-select>
              </el-form-item>
            </el-form>
          </el-col>
          <el-col :span="6" :xs="24" style="display:flex; gap: 8px; justify-content:flex-end;">
            <el-button type="warning" plain @click="selectedPackageIds = []" :disabled="selectedPackageIds.length === 0">Clear</el-button>
            <el-button type="primary" @click="savePackages" :loading="savingPackages" :disabled="!user">
              Save ({{ selectedPackageIds.length }})
            </el-button>
          </el-col>
        </el-row>

        <el-table :data="assignedPackages" style="width: 100%" border>
          <el-table-column prop="id" label="ID" width="90" />
          <el-table-column prop="name" label="Name" min-width="240" />
          <el-table-column label="Created" width="240">
            <template #default="scope">
              <span>{{ formatDateTimeID(scope.row.createdAt) }}</span>
            </template>
          </el-table-column>
        </el-table>
        </el-tab-pane>

        <el-tab-pane label="Playlist (Fallback)" name="playlist">
        <el-alert
          title="Digunakan hanya jika user tidak punya Channels (manual) dan tidak punya Packages."
          type="info"
          show-icon
          style="margin-bottom: 12px"
        />
        <el-alert
          v-if="!user?.appKey"
          title="This user has no appKey yet. Create a new user to generate one."
          type="warning"
          show-icon
          style="margin-bottom: 12px"
        />

        <el-form label-position="top">
          <el-form-item label="Playlist">
            <el-select v-model="selectedPlaylistId" placeholder="No playlist" clearable filterable style="width: 420px">
              <el-option v-for="p in playlists" :key="p.id" :label="`#${p.id} — ${p.name}`" :value="p.id" />
            </el-select>
            <el-button type="primary" style="margin-left: 10px" @click="savePlaylist" :loading="savingPlaylist">Save</el-button>
          </el-form-item>
        </el-form>
        </el-tab-pane>

        <el-tab-pane label="Subscriptions (Access)" name="subscriptions">
        <el-row :gutter="12">
          <el-col :span="10" :xs="24">
            <el-card shadow="never">
              <template #header>
                <strong>Perpanjang / tambah masa aktif</strong>
              </template>

              <el-alert
                title="Masa aktif bisa di-set saat membuat user. Form ini untuk perpanjang (menambah record subscription baru) dan menyimpan riwayat."
                type="info"
                show-icon
                style="margin-bottom: 12px"
              />

              <el-form label-position="top">
                <el-form-item label="Plan (dari package / manual)">
                  <el-select
                    v-model="plan"
                    filterable
                    clearable
                    allow-create
                    default-first-option
                    placeholder="Pilih package atau ketik manual"
                    style="width: 100%"
                  >
                    <el-option v-for="p in suggestedPlans" :key="p" :label="p" :value="p" />
                  </el-select>
                  <div style="margin-top: 6px; color:#64748b; font-size:12px">
                    Paket terpilih: {{ selectedPackageNames || '—' }}
                  </div>
                </el-form-item>

                <el-form-item label="Berakhir">
                  <el-date-picker
                    v-model="expiresAtDate"
                    type="datetime"
                    placeholder="Pilih tanggal & jam"
                    style="width: 100%"
                  />
                  <div style="margin-top: 6px; color:#64748b; font-size:12px">
                    Akan berakhir: {{ expiresAtLabel }}
                  </div>
                </el-form-item>

                <el-button type="primary" @click="addSub" :loading="adding">Simpan</el-button>
              </el-form>
            </el-card>
          </el-col>

          <el-col :span="14" :xs="24">
            <el-card shadow="never">
              <template #header>
                <strong>Riwayat subscription</strong>
              </template>

              <el-table :data="subs" v-loading="loading">
                <el-table-column prop="id" label="ID" width="90" />
                <el-table-column prop="plan" label="Plan" />
                <el-table-column label="Expires" width="240">
                  <template #default="scope">
                    <span>{{ formatDateTimeID(scope.row.expiresAt) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="Created" width="240">
                  <template #default="scope">
                    <span>{{ formatDateTimeID(scope.row.createdAt) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="Actions" width="140">
                  <template #default="scope">
                    <el-button
                      size="small"
                      type="danger"
                      plain
                      @click="delSub(scope.row.id)"
                      :loading="deletingSubId === scope.row.id"
                    >
                      Delete
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
          </el-col>
        </el-row>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </AdminShell>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminShell from '@/components/AdminShell.vue'
import { api, type Channel, type Package, type Playlist, type Subscription, type User } from '@/lib/api'
import { formatDateTimeID } from '@/lib/datetime'

const route = useRoute()
const router = useRouter()

const id = Number(route.params.id)

const user = ref<User | null>(null)
const subs = ref<Subscription[]>([])
const playlists = ref<Playlist[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const authRequired = ref<boolean | undefined>(undefined)

const selectedPlaylistId = ref<number | null>(null)
const savingPlaylist = ref(false)

const plan = ref('')
const expiresAtDate = ref<Date | null>(null)
const adding = ref(false)

const deleting = ref(false)
const deletingSubId = ref<number | null>(null)

const activeTab = ref<'channels' | 'packages' | 'playlist' | 'subscriptions'>('channels')

const channelsTableRef = ref<any>(null)
const channels = ref<Channel[]>([])
const selectedChannelIds = ref<number[]>([])
const channelsPlaylistId = ref<number | null>(null)
const channelsQuery = ref('')
const loadingChannels = ref(false)
const savingChannels = ref(false)

const allPackages = ref<Package[]>([])
const assignedPackages = ref<Package[]>([])
const selectedPackageIds = ref<number[]>([])
const savingPackages = ref(false)
const packageChannelIds = ref<number[]>([])
const loadingPackageChannels = ref(false)

const selectedPackageNames = computed(() => {
  const byId = new Map(allPackages.value.map((p) => [p.id, p.name]))
  const names = selectedPackageIds.value
    .map((id) => byId.get(id) || '')
    .map((s) => (s || '').trim())
    .filter(Boolean)
  return names.length ? names.join(', ') : ''
})

const suggestedPlans = computed(() => {
  const out = new Set<string>()
  const lp = (latestPlan.value || '').trim()
  if (lp) out.add(lp)
  for (const p of allPackages.value) {
    const name = (p.name || '').trim()
    if (name) out.add(name)
  }
  return Array.from(out)
})

const expiresAtISO = computed(() => {
  if (!expiresAtDate.value) return ''
  try {
    return expiresAtDate.value.toISOString()
  } catch {
    return ''
  }
})

const expiresAtLabel = computed(() => {
  const iso = expiresAtISO.value
  return iso ? formatDateTimeID(iso) : '—'
})

const selectedChannelIdSet = computed(() => new Set(selectedChannelIds.value))
function isChannelAdded(id: number): boolean {
  return effectiveChannelIdSet.value.has(id)
}

const isCustomChannels = computed(() => selectedChannelIds.value.length > 0)
const isPackageDriven = computed(() => !isCustomChannels.value && selectedPackageIds.value.length > 0)

const effectiveChannelIdSet = computed(() => {
  if (isCustomChannels.value) return new Set(selectedChannelIds.value)
  return new Set(packageChannelIds.value)
})

const effectiveChannelCount = computed(() => effectiveChannelIdSet.value.size)

const title = computed(() => (user.value ? `User #${user.value.id}` : 'User'))
const publicPlaylistUrl = computed(() => {
  if (!user.value?.appKey) return ''
  return `/public/users/${user.value.appKey}/playlist.m3u`
})

const publicPlaylistAbsUrl = computed(() => {
  const rel = publicPlaylistUrl.value
  if (!rel) return ''
  try {
    return `${window.location.origin}${rel}`
  } catch {
    return rel
  }
})

const latestSub = computed(() => {
  if (!subs.value.length) return null
  const sorted = [...subs.value].sort((a, b) => {
    const ta = new Date(a.expiresAt).getTime()
    const tb = new Date(b.expiresAt).getTime()
    return (Number.isFinite(tb) ? tb : 0) - (Number.isFinite(ta) ? ta : 0)
  })
  return sorted[0] || null
})

const latestExpiresAt = computed(() => (latestSub.value ? latestSub.value.expiresAt : ''))
const latestPlan = computed(() => (latestSub.value ? latestSub.value.plan : ''))

const assignedPackageNames = computed(() => {
  const names = assignedPackages.value.map((p) => p.name).filter(Boolean)
  return names.length ? names.join(', ') : '—'
})

const statusTag = computed((): { label: string; type: 'success' | 'warning' | 'info' | 'danger' } => {
  const exp = latestExpiresAt.value ? new Date(latestExpiresAt.value).getTime() : NaN
  if (!latestExpiresAt.value || !Number.isFinite(exp)) return { label: 'Inactive', type: 'info' }
  if (exp <= Date.now()) return { label: 'Expired', type: 'danger' }
  return { label: 'Active', type: 'success' }
})

const remaining = computed(() => {
  if (!latestExpiresAt.value) return '—'
  const exp = new Date(latestExpiresAt.value).getTime()
  if (!Number.isFinite(exp)) return '—'
  const msLeft = exp - Date.now()
  const daysLeft = Math.ceil(msLeft / (24 * 60 * 60 * 1000))
  if (daysLeft <= 0) return 'Expired'
  return `${daysLeft} hari`
})

async function load() {
  loading.value = true
  error.value = null
  try {
    const h = await api.health()
    authRequired.value = h.authRequired
    user.value = await api.getUser(id)
    subs.value = await api.listSubscriptions(id)
    playlists.value = await api.listPlaylists()
    allPackages.value = await api.listPackages()
    selectedPlaylistId.value = (user.value as any)?.playlistId ?? null

    // Load selected channels for this user (selection state is global).
    const userCh = await api.getUserChannels(id)
    selectedChannelIds.value = userCh.map((c) => c.id)

    const userPk = await api.getUserPackages(id)
    assignedPackages.value = userPk
    selectedPackageIds.value = userPk.map((p) => p.id)

    await refreshPackageChannelIds()
    await loadChannels()
  } catch (e: any) {
    error.value = e?.message || 'Failed to load'
  } finally {
    loading.value = false
  }
}

async function refreshPackageChannelIds() {
  if (!selectedPackageIds.value.length) {
    packageChannelIds.value = []
    return
  }
  loadingPackageChannels.value = true
  try {
    const results = await Promise.all(selectedPackageIds.value.map((pid) => api.getPackageChannels(pid)))
    const ids = new Set<number>()
    for (const list of results) {
      for (const ch of list) ids.add(ch.id)
    }
    packageChannelIds.value = Array.from(ids)
  } catch (e: any) {
    ElMessage.error(e?.message || 'Failed to load package channels')
    packageChannelIds.value = []
  } finally {
    loadingPackageChannels.value = false
  }
}

async function savePackages() {
  if (!user.value) return
  savingPackages.value = true
  try {
    // Packages mode should not keep custom channels.
    if (selectedChannelIds.value.length > 0) {
      await api.setUserChannels(user.value.id, [])
      selectedChannelIds.value = []
      applyChannelSelectionToTable()
    }
    const pk = await api.setUserPackages(user.value.id, selectedPackageIds.value)
    assignedPackages.value = pk
    ElMessage.success('Packages saved')
    await refreshPackageChannelIds()
  } catch (e: any) {
    ElMessage.error(e?.message || 'Save failed')
  } finally {
    savingPackages.value = false
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
  const selected = effectiveChannelIdSet.value
  for (const row of channels.value) {
    if (selected.has(row.id) && typeof table.toggleRowSelection === 'function') {
      table.toggleRowSelection(row, true)
    }
  }
}

function onChannelSelectionChange(rows: Channel[]) {
  // Keep selection across filters: only update ids that are currently visible in the table.
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
  if (!user.value) return
  savingChannels.value = true
  try {
    // Custom channels mode: packages should be cleared to avoid ambiguity.
    if (selectedChannelIds.value.length > 0 && (selectedPackageIds.value.length > 0 || assignedPackages.value.length > 0)) {
      await api.setUserPackages(user.value.id, [])
      selectedPackageIds.value = []
      assignedPackages.value = []
      packageChannelIds.value = []
      ElMessage.info('Packages cleared (custom channels active)')
    }
    await api.setUserChannels(user.value.id, selectedChannelIds.value)
    ElMessage.success('Channels saved')
  } catch (e: any) {
    ElMessage.error(e?.message || 'Save failed')
  } finally {
    savingChannels.value = false
  }
}

async function savePlaylist() {
  if (!user.value) return
  savingPlaylist.value = true
  try {
    user.value = await api.setUserPlaylist(user.value.id, selectedPlaylistId.value)
    ElMessage.success('Saved')
  } catch (e: any) {
    ElMessage.error(e?.message || 'Save failed')
  } finally {
    savingPlaylist.value = false
  }
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('Copied')
  } catch {
    ElMessage.info(text)
  }
}

async function addSub() {
  adding.value = true
  try {
    const p = plan.value.trim()
    const exp = expiresAtISO.value
    if (!p) throw new Error('Plan harus diisi')
    if (!exp) throw new Error('Tanggal berakhir harus dipilih')
    await api.createSubscription(id, { plan: p, expiresAt: exp })
    ElMessage.success('Subscription added')
    plan.value = ''
    expiresAtDate.value = null
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message || 'Add failed')
  } finally {
    adding.value = false
  }
}

watch(
  () => selectedPackageIds.value,
  () => {
    // If exactly one package is selected and plan is empty, default plan to that package name.
    if (plan.value.trim()) return
    if (selectedPackageIds.value.length !== 1) return
    const pkId = selectedPackageIds.value[0]
    const pk = allPackages.value.find((p) => p.id === pkId)
    const name = (pk?.name || '').trim()
    if (name) plan.value = name
  },
  { deep: true }
)

watch(
  () => selectedPackageIds.value,
  async () => {
    // If user starts selecting packages, switch to package-driven mode in the UI.
    // This makes the Channels tab selection follow the package channels.
    if (selectedPackageIds.value.length > 0 && selectedChannelIds.value.length > 0) {
      selectedChannelIds.value = []
    }
    // Keep effective channel markers in sync with selected packages.
    await refreshPackageChannelIds()
    await nextTick()
    applyChannelSelectionToTable()
  },
  { deep: true }
)

async function delSub(subId: number) {
  deletingSubId.value = subId
  try {
    await api.deleteSubscription(subId)
    ElMessage.success('Deleted')
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message || 'Delete failed')
  } finally {
    deletingSubId.value = null
  }
}

async function remove() {
  if (!user.value) return
  try {
    await ElMessageBox.confirm('Delete this user?', 'Confirm', { type: 'warning' })
  } catch {
    return
  }

  deleting.value = true
  try {
    await api.deleteUser(user.value.id)
    ElMessage.success('User deleted')
    router.push('/users')
  } catch (e: any) {
    ElMessage.error(e?.message || 'Delete failed')
  } finally {
    deleting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.mono :deep(input) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
}
</style>
