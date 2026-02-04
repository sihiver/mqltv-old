<template>
  <AdminShell title="Workplace" :auth-required="authRequired">
    <div class="mql-page">
      <el-row :gutter="12">
        <el-col :span="6" :xs="24">
          <el-card class="mql-card">
            <div class="mql-metric">
              <div>
                <div class="label">Online users</div>
                <div class="value">{{ onlineCount }}</div>
                <div style="margin-top: 4px; color:#94a3b8; font-size:12px;">Cutoff: {{ cutoffLabel }}</div>
              </div>
              <el-tag type="success" effect="plain">Live</el-tag>
            </div>
          </el-card>
        </el-col>

        <el-col :span="18" :xs="24">
          <el-card class="mql-card">
            <template #header>
              <div style="display:flex; justify-content:space-between; align-items:center; gap: 10px; flex-wrap: wrap;">
                <strong>Online sekarang</strong>
                <div style="display:flex; gap: 10px; align-items:center;">
                  <el-switch v-model="showAll" active-text="All" inactive-text="Online" />
                  <el-button size="small" @click="load" :loading="loading">Refresh</el-button>
                </div>
              </div>
            </template>

            <el-alert v-if="error" :title="error" type="error" show-icon style="margin-bottom: 12px" />

            <el-table :data="rows" style="width:100%" v-loading="loading" empty-text="Tidak ada user online">
              <el-table-column prop="username" label="Username" min-width="160" />
              <el-table-column label="Status" width="120">
                <template #default="scope">
                  <el-tag :type="statusTag(scope.row).type" size="small">{{ statusTag(scope.row).label }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="Channel terakhir" min-width="260">
                <template #default="scope">
                  <div style="display:flex; flex-direction:column; gap: 2px;">
                    <span>{{ scope.row.channelTitle || '—' }}</span>
                    <a v-if="scope.row.channelUrl" :href="scope.row.channelUrl" target="_blank" style="color:#4f6ef7; font-size:12px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width: 520px;">
                      {{ scope.row.channelUrl }}
                    </a>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="Last seen" width="240">
                <template #default="scope">
                  <span>{{ formatDateTimeID(scope.row.lastSeenAt) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </AdminShell>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import AdminShell from '@/components/AdminShell.vue'
import { api, type PresenceRow } from '@/lib/api'
import { formatDateTimeID } from '@/lib/datetime'

const authRequired = ref<boolean | undefined>(undefined)

const loading = ref(false)
const error = ref<string | null>(null)
const showAll = ref(false)
const rows = ref<PresenceRow[]>([])
const cutoff = ref<string>('')
let timer: number | undefined

const onlineCount = computed(() => {
  if (showAll.value) {
    // Count rows that look online now.
    return rows.value.filter((r) => statusTag(r).label === 'Online').length
  }
  return rows.value.length
})

const cutoffLabel = computed(() => {
  return cutoff.value ? formatDateTimeID(cutoff.value) : '—'
})

function statusTag(r: PresenceRow): { label: string; type: 'success' | 'warning' | 'info' | 'danger' } {
  const ls = r.lastSeenAt ? new Date(r.lastSeenAt).getTime() : NaN
  if (!Number.isFinite(ls)) return { label: r.status || 'unknown', type: 'info' }

  // Match backend cutoff default (90s).
  const fresh = Date.now() - ls <= 90 * 1000
  if (r.status === 'online' && fresh) return { label: 'Online', type: 'success' }
  if (r.status === 'online' && !fresh) return { label: 'Stale', type: 'warning' }
  return { label: 'Offline', type: 'info' }
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const res = await api.listPresence({ all: showAll.value, limit: 200 })
    rows.value = res.items || []
    cutoff.value = res.cutoff || ''
  } catch (e: any) {
    error.value = e?.message || 'Gagal load presence'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    const h = await api.health()
    authRequired.value = h.authRequired
  } catch {
    // ignore
  }

  await load()
  timer = window.setInterval(() => {
    load()
  }, 10_000)
})

onUnmounted(() => {
  if (timer != null) window.clearInterval(timer)
  timer = undefined
})
</script>
