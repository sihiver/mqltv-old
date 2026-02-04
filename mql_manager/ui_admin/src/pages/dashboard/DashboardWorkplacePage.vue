<template>
  <AdminShell title="Dashboard" :auth-required="authRequired">
    <div class="mql-page">
      <el-card class="mql-card">
        <div style="display:flex; justify-content:space-between; align-items:flex-start; gap: 12px; flex-wrap: wrap;">
          <div style="display:flex; flex-direction:column; gap: 4px;">
            <strong style="font-size: 16px;">Ringkasan</strong>
            <div style="color:#64748b; font-size:12px;">Operasional IPTV Admin: user, paket, playlist, dan presence.</div>
          </div>
          <div style="display:flex; gap: 10px; align-items:center; flex-wrap: wrap;">
            <el-button size="small" @click="go('/users')">Users</el-button>
            <el-button size="small" @click="go('/packages')">Packages</el-button>
            <el-button size="small" @click="go('/playlists')">Playlists</el-button>
            <el-button size="small" type="primary" @click="refreshAll" :loading="loadingStats || loading">Refresh semua</el-button>
          </div>
        </div>

        <el-alert v-if="statsError" :title="statsError" type="error" show-icon style="margin-top: 12px" />
        <el-alert v-if="error" :title="error" type="error" show-icon style="margin-top: 12px" />
      </el-card>

      <el-row :gutter="12">
        <el-col :span="6" :xs="12">
          <el-card class="mql-card">
            <div class="mql-metric">
              <div>
                <div class="label">Online sekarang</div>
                <div class="value">{{ onlineCount }}</div>
                <div style="margin-top: 4px; color:#94a3b8; font-size:12px;">Cutoff: {{ cutoffLabel }}</div>
              </div>
              <el-icon size="28" color="#22c55e"><Connection /></el-icon>
            </div>
          </el-card>
        </el-col>

        <el-col :span="6" :xs="12">
          <el-card class="mql-card">
            <div class="mql-metric">
              <div>
                <div class="label">Total users</div>
                <div class="value">
                  <el-skeleton v-if="loadingStats" :rows="1" animated />
                  <span v-else>{{ users.length }}</span>
                </div>
                <div style="margin-top: 4px; color:#94a3b8; font-size:12px;">Aktif: {{ activeCount }} · Expired: {{ expiredCount }} · No sub: {{ noSubCount }}</div>
              </div>
              <el-icon size="28" color="#60a5fa"><UserFilled /></el-icon>
            </div>
          </el-card>
        </el-col>

        <el-col :span="6" :xs="12">
          <el-card class="mql-card">
            <div class="mql-metric">
              <div>
                <div class="label">Packages</div>
                <div class="value">
                  <el-skeleton v-if="loadingStats" :rows="1" animated />
                  <span v-else>{{ packages.length }}</span>
                </div>
                <div style="margin-top: 4px; color:#94a3b8; font-size:12px;">Konfigurasi channel per paket</div>
              </div>
              <el-icon size="28" color="#a855f7"><Box /></el-icon>
            </div>
          </el-card>
        </el-col>

        <el-col :span="6" :xs="12">
          <el-card class="mql-card">
            <div class="mql-metric">
              <div>
                <div class="label">Playlists</div>
                <div class="value">
                  <el-skeleton v-if="loadingStats" :rows="1" animated />
                  <span v-else>{{ playlists.length }}</span>
                </div>
                <div style="margin-top: 4px; color:#94a3b8; font-size:12px;">Sumber channel untuk user</div>
              </div>
              <el-icon size="28" color="#f59e0b"><Document /></el-icon>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <el-row :gutter="12">
        <el-col :span="12" :xs="24">
          <el-card class="mql-card">
            <template #header>
              <div style="display:flex; justify-content:space-between; align-items:center; gap: 10px; flex-wrap: wrap;">
                <strong>Perlu perhatian</strong>
                <el-tag type="warning" effect="plain">{{ expiringSoon.length }} akan berakhir ≤ {{ expiringWindowDays }} hari</el-tag>
              </div>
            </template>

            <el-table
              :data="expiringSoon.slice(0, 6)"
              style="width:100%"
              v-loading="loadingStats"
              empty-text="Tidak ada user yang akan berakhir dalam waktu dekat"
            >
              <el-table-column label="User" min-width="160">
                <template #default="scope">
                  <router-link :to="`/users/${scope.row.id}`" style="color:#4f6ef7; text-decoration:none;">
                    {{ scope.row.username }}
                  </router-link>
                  <div style="color:#94a3b8; font-size:12px;">{{ scope.row.displayName || '—' }}</div>
                </template>
              </el-table-column>
              <el-table-column label="Paket" min-width="160">
                <template #default="scope">
                  <span>{{ scope.row.packages && scope.row.packages.length ? scope.row.packages.join(', ') : '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="Sisa" width="140">
                <template #default="scope">
                  <el-tag type="warning" size="small" effect="plain">{{ remainingLabel(scope.row.expiresAt) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="Berakhir" width="220">
                <template #default="scope">{{ scope.row.expiresAt ? formatDateTimeID(scope.row.expiresAt) : '—' }}</template>
              </el-table-column>
            </el-table>

            <div style="margin-top: 10px; display:flex; justify-content:space-between; gap: 10px; align-items:center; flex-wrap: wrap;">
              <div style="color:#64748b; font-size:12px;">
                Tips: untuk Disable/Enable cepat, buka halaman Users.
              </div>
              <el-button size="small" @click="go('/users')">Buka Users</el-button>
            </div>
          </el-card>
        </el-col>

        <el-col :span="12" :xs="24">
          <el-card class="mql-card">
            <template #header>
              <div style="display:flex; justify-content:space-between; align-items:center; gap: 10px; flex-wrap: wrap;">
                <strong>Status sistem</strong>
                <el-tag :type="healthOk ? 'success' : 'danger'" effect="plain">{{ healthOk ? 'API OK' : 'API ERROR' }}</el-tag>
              </div>
            </template>

            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="Server time">
                {{ healthTime ? formatDateTimeID(healthTime) : '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="Auth required">
                <el-tag :type="authRequired ? 'warning' : 'info'" size="small" effect="plain">{{ authRequired ? 'Yes' : 'No' }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="Base URL">
                <span style="color:#334155;">{{ baseUrl }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="Last refresh">
                {{ lastRefreshAt ? formatDateTimeID(lastRefreshAt) : '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="Catatan">
                Presence dipoll setiap 10 detik; statistik dipoll setiap 60 detik.
              </el-descriptions-item>
            </el-descriptions>

            <div style="margin-top: 10px; display:flex; gap: 10px; flex-wrap: wrap;">
              <el-button size="small" type="primary" plain @click="go('/packages')">Atur paket</el-button>
              <el-button size="small" type="primary" plain @click="go('/playlists')">Kelola playlist</el-button>
              <el-button size="small" type="primary" plain @click="go('/users')">Kelola user</el-button>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <el-row :gutter="12">
        <el-col :span="12" :xs="24">
          <el-card class="mql-card">
            <template #header>
              <div style="display:flex; justify-content:space-between; align-items:center; gap: 10px; flex-wrap: wrap;">
                <strong>Belum lengkap setup</strong>
                <el-tag type="info" effect="plain">{{ unconfiguredUsers.length }} users</el-tag>
              </div>
            </template>

            <el-table
              :data="unconfiguredUsers.slice(0, 6)"
              style="width:100%"
              v-loading="loadingStats"
              empty-text="Semua user sudah punya paket / playlist"
            >
              <el-table-column label="User" min-width="180">
                <template #default="scope">
                  <router-link :to="`/users/${scope.row.id}`" style="color:#4f6ef7; text-decoration:none;">
                    {{ scope.row.username }}
                  </router-link>
                </template>
              </el-table-column>
              <el-table-column label="Paket" min-width="160">
                <template #default="scope">
                  <el-tag v-if="scope.row.packages && scope.row.packages.length" type="success" size="small" effect="plain">OK</el-tag>
                  <el-tag v-else type="warning" size="small" effect="plain">Kosong</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="Playlist" width="140">
                <template #default="scope">
                  <el-tag v-if="scope.row.playlistId != null" type="success" size="small" effect="plain">OK</el-tag>
                  <el-tag v-else type="warning" size="small" effect="plain">Belum</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="Status" width="120">
                <template #default="scope">
                  <el-tag :type="userStatusTag(scope.row).type" size="small">{{ userStatusTag(scope.row).label }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>

        <el-col :span="12" :xs="24">
          <el-card class="mql-card">
            <template #header>
              <div style="display:flex; justify-content:space-between; align-items:center; gap: 10px; flex-wrap: wrap;">
                <strong>Online sekarang</strong>
                <div style="display:flex; gap: 10px; align-items:center;">
                  <el-switch v-model="showAll" active-text="All" inactive-text="Online" />
                  <el-button size="small" @click="loadPresence" :loading="loading">Refresh</el-button>
                </div>
              </div>
            </template>

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
import { api, type Package, type Playlist, type PresenceRow, type User } from '@/lib/api'
import { formatDateTimeID } from '@/lib/datetime'
import { useRouter } from 'vue-router'
import { Box, Connection, Document, UserFilled } from '@element-plus/icons-vue'

const authRequired = ref<boolean | undefined>(undefined)

const router = useRouter()

const baseUrl = ref('')
const healthOk = ref(true)
const healthTime = ref<string>('')
const lastRefreshAt = ref<string>('')

const loadingStats = ref(false)
const statsError = ref<string | null>(null)
const users = ref<User[]>([])
const packages = ref<Package[]>([])
const playlists = ref<Playlist[]>([])

const expiringWindowDays = 7
let statsTimer: number | undefined

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

const activeCount = computed(() => users.value.filter((u) => isActiveUser(u)).length)
const expiredCount = computed(() => users.value.filter((u) => u.expiresAt && !isActiveUser(u)).length)
const noSubCount = computed(() => users.value.filter((u) => !u.expiresAt).length)

const expiringSoon = computed(() => {
  const now = Date.now()
  const windowMs = expiringWindowDays * 24 * 60 * 60 * 1000
  return users.value
    .filter((u) => {
      const t = parseTime(u.expiresAt)
      if (!Number.isFinite(t)) return false
      return t > now && t <= now + windowMs
    })
    .sort((a, b) => parseTime(a.expiresAt) - parseTime(b.expiresAt))
})

const unconfiguredUsers = computed(() => {
  return users.value
    .filter((u) => {
      const hasPackages = !!(u.packages && u.packages.length)
      const hasPlaylist = u.playlistId != null
      return !hasPackages || !hasPlaylist
    })
    .sort((a, b) => {
      // Show active ones first.
      return Number(isActiveUser(b)) - Number(isActiveUser(a))
    })
})

function go(path: string) {
  router.push(path)
}

function parseTime(value: string | null | undefined): number {
  if (!value) return NaN
  const t = new Date(value).getTime()
  return Number.isFinite(t) ? t : NaN
}

function isActiveUser(u: User): boolean {
  const t = parseTime(u.expiresAt)
  return Number.isFinite(t) && t > Date.now()
}

function userStatusTag(u: User): { label: string; type: 'success' | 'warning' | 'info' | 'danger' } {
  if (!u.expiresAt) return { label: 'No subscription', type: 'info' }
  return isActiveUser(u) ? { label: 'Active', type: 'success' } : { label: 'Expired', type: 'danger' }
}

function remainingLabel(expiresAt: string | null | undefined): string {
  const t = parseTime(expiresAt)
  if (!Number.isFinite(t)) return '—'
  const diff = t - Date.now()
  if (diff <= 0) return 'Expired'
  const days = Math.floor(diff / (24 * 60 * 60 * 1000))
  const hours = Math.floor((diff % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000))
  if (days > 0) return `${days} hari` + (hours > 0 ? ` ${hours} jam` : '')
  const minutes = Math.max(1, Math.floor(diff / (60 * 1000)))
  return `${minutes} menit`
}

function statusTag(r: PresenceRow): { label: string; type: 'success' | 'warning' | 'info' | 'danger' } {
  const ls = r.lastSeenAt ? new Date(r.lastSeenAt).getTime() : NaN
  if (!Number.isFinite(ls)) return { label: r.status || 'unknown', type: 'info' }

  // Match backend cutoff default (90s).
  const fresh = Date.now() - ls <= 90 * 1000
  if (r.status === 'online' && fresh) return { label: 'Online', type: 'success' }
  if (r.status === 'online' && !fresh) return { label: 'Stale', type: 'warning' }
  return { label: 'Offline', type: 'info' }
}

async function loadPresence() {
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

async function loadStats() {
  loadingStats.value = true
  statsError.value = null
  try {
    const [u, p, pl] = await Promise.all([api.listUsers(), api.listPackages(), api.listPlaylists()])
    users.value = u || []
    packages.value = p || []
    playlists.value = pl || []
    lastRefreshAt.value = new Date().toISOString()
  } catch (e: any) {
    statsError.value = e?.message || 'Gagal load statistik'
  } finally {
    loadingStats.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadStats(), loadPresence()])
}

onMounted(async () => {
  baseUrl.value = typeof window !== 'undefined' ? window.location.origin : ''

  try {
    const h = await api.health()
    authRequired.value = h.authRequired
    healthOk.value = !!h.ok
    healthTime.value = h.time || ''
  } catch {
    healthOk.value = false
    // ignore
  }

  await refreshAll()
  timer = window.setInterval(() => {
    loadPresence()
  }, 10_000)

  statsTimer = window.setInterval(() => {
    loadStats()
  }, 60_000)
})

onUnmounted(() => {
  if (timer != null) window.clearInterval(timer)
  timer = undefined

  if (statsTimer != null) window.clearInterval(statsTimer)
  statsTimer = undefined
})
</script>
